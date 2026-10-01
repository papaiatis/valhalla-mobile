package com.valhalla.valhalla

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device measurement of routing over a package set or a merged reference graph.
 *
 * Runs only when the instrumentation argument `optc_config` names a config under
 * /data/local/tmp/optc. Each route gets its own [Valhalla]: the first route's time includes
 * creating it, and with it the package set; the warm runs reuse it, the way the app routes. Run
 * one target per process so peak memory belongs to that target alone:
 *
 *   adb shell am instrument -w -e class com.valhalla.valhalla.OptionCDeviceBenchmark \
 *     -e optc_config reference com.valhalla.valhalla.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OptionCDeviceBenchmark {

  @Test
  fun measure() {
    val target = InstrumentationRegistry.getArguments().getString("optc_config")
    assumeTrue("optc_config not given", target != null)
    val root = File("/data/local/tmp/optc")
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val config = File(root, "$target.json").absolutePath
    val routes = JSONObject(File(root, "routes.json").readText())
    // optional: a single route id, so each route's peak memory is measured in its own process
    val only = InstrumentationRegistry.getArguments().getString("optc_route")
    val warmRuns = 5
    // optional: bind every thread of this process to the given CPUs (e.g. "0-3", the little cores),
    // to approximate a slower device; threads started later inherit the binding
    val cpus = InstrumentationRegistry.getArguments().getString("optc_cpus")

    val report = JSONObject().put("target", target)
    if (cpus != null) {
      bindToCpus(cpus)
      report.put("cpus", cpus)
    }
    report.put("before", memory())
    // PSS drops once an actor is destroyed and its tile mappings go away, so sample the peak
    // while routes run.
    var peakPss = 0.0 // read only after join(), which publishes the sampler's writes
    val sampling = AtomicBoolean(true)
    val sampler = Thread {
      while (sampling.get()) {
        peakPss = maxOf(peakPss, pss())
        Thread.sleep(10)
      }
    }.apply { start() }
    for (id in routes.keys()) {
      if (only != null && id != only) continue
      val request = routes.getJSONObject(id).toString()
      val times = mutableListOf<Double>()
      var summary: JSONObject? = null
      var valhalla: Valhalla? = null
      repeat(1 + warmRuns) { run ->
        val start = SystemClock.elapsedRealtimeNanos()
        if (run == 0) valhalla = Valhalla(context, config)
        val response = JSONObject(valhalla!!.routeJson(request))
        times += (SystemClock.elapsedRealtimeNanos() - start) / 1e9
        check(response.has("trip")) { "$target $id: $response" }
        summary = response.getJSONObject("trip").getJSONObject("summary")
      }
      valhalla!!.close()
      report.put(
          id,
          JSONObject()
              .put("cold_s", times.first())
              .put("warm_s", times.drop(1).sorted()[warmRuns / 2])
              .put("length_km", summary!!.getDouble("length"))
              .put("time_s", summary!!.getDouble("time")))
    }
    sampling.set(false)
    sampler.join()
    report.put("after", memory())
    report.put("peak_pss_mb", peakPss)
    report.put("mappings", mappings())
    Log.i(TAG, "RESULT $report")
    // optional: keep the process alive so a heap profiler can dump it
    InstrumentationRegistry.getArguments().getString("optc_hold_s")?.let {
      Thread.sleep(it.toLong() * 1000)
    }
    InstrumentationRegistry.getInstrumentation()
        .sendStatus(0, android.os.Bundle().apply { putString("optc_result", report.toString()) })
  }

  /**
   * Binds every thread of this process to the given CPUs (e.g. "0-3", the little cores); threads
   * started later inherit the binding.
   */
  private fun bindToCpus(cpus: String) {
    val pid = android.os.Process.myPid()
    // toybox taskset (Android 10) takes a hex mask, not a CPU list
    val mask =
        cpus
            .split(',')
            .flatMap { part ->
              val (first, last) = part.split('-').map(String::toInt).let { it.first() to it.last() }
              (first..last).toList()
            }
            .fold(0L) { acc, cpu -> acc or (1L shl cpu) }
    val exit =
        ProcessBuilder("taskset", "-a", "-p", mask.toString(16), pid.toString())
            .redirectErrorStream(true)
            .start()
            .let { it.waitFor() to it.inputStream.bufferedReader().readText() }
    val allowed = File("/proc/self/status").readLines().first { it.startsWith("Cpus_allowed_list") }
    check(exit.first == 0 && allowed.substringAfter(':').trim() == cpus) {
      "taskset $cpus failed: ${exit.second} / $allowed"
    }
  }

  /**
   * Joins the packages of the config named by `optc_join` (a file under /data/local/tmp/optc) and
   * reports how long the join took, writing the overlays to the app's cache directory.
   */
  @Test
  fun join() {
    val target = InstrumentationRegistry.getArguments().getString("optc_join")
    assumeTrue("optc_join not given", target != null)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val config = File("/data/local/tmp/optc", "$target.json").absolutePath
    val cpus = InstrumentationRegistry.getArguments().getString("optc_cpus")
    // optional: a directory name in the cache to keep the overlays in, for routing with them
    val keep = InstrumentationRegistry.getArguments().getString("optc_keep")
    val overlays = File(context.cacheDir, keep ?: "optc-overlays").apply {
      deleteRecursively()
      mkdirs()
    }

    val report = JSONObject().put("target", target)
    if (cpus != null) {
      bindToCpus(cpus)
      report.put("cpus", cpus)
    }
    val start = SystemClock.elapsedRealtimeNanos()
    val join = JSONObject(Valhalla.joinPackages(config, overlays.absolutePath))
    report.put("wall_s", (SystemClock.elapsedRealtimeNanos() - start) / 1e9)
    report.put("join", join)
    report.put("overlay_bytes", overlays.listFiles()!!.sumOf { it.length() })
    report.put("peak_rss_mb", memory().getDouble("VmHWM"))
    if (keep == null) overlays.deleteRecursively()
    Log.i(TAG, "RESULT $report")
    InstrumentationRegistry.getInstrumentation()
        .sendStatus(0, android.os.Bundle().apply { putString("optc_result", report.toString()) })
  }

  /** Process memory in MB: peak RSS (VmHWM), current RSS split, and PSS. */
  private fun memory(): JSONObject {
    val result = JSONObject()
    val wanted = setOf("VmHWM", "RssAnon", "RssFile")
    File("/proc/self/status").readLines().forEach { line ->
      val key = line.substringBefore(':')
      if (key in wanted) result.put(key, kb(line))
    }
    File("/proc/self/smaps_rollup").readLines().firstOrNull { it.startsWith("Pss:") }?.let {
      result.put("Pss", kb(it))
    }
    // live native allocations, as opposed to heap pages the allocator keeps after frees
    result.put("native_alloc", android.os.Debug.getNativeHeapAllocatedSize() / 1048576.0)
    return result
  }

  /** Pss, Private_Dirty, and Anonymous in MB per mapping name, for mappings over 1 MB Pss. */
  private fun mappings(): JSONObject {
    val totals = mutableMapOf<String, DoubleArray>()
    var name = "[anon]"
    File("/proc/self/smaps").forEachLine { line ->
      val first = line.substringBefore(' ')
      if (first.contains('-') && first.all { it.isLetterOrDigit() || it == '-' }) {
        name = line.trim().split(Regex("\\s+")).getOrNull(5)?.substringAfterLast('/') ?: "[anon]"
      } else {
        val index = when (line.substringBefore(':')) {
          "Pss" -> 0
          "Private_Dirty" -> 1
          "Anonymous" -> 2
          else -> -1
        }
        if (index >= 0) totals.getOrPut(name) { DoubleArray(3) }[index] += kb(line)
      }
    }
    val result = JSONObject()
    totals.filter { it.value[0] > 1 }.forEach { (key, v) ->
      result.put(key, JSONObject().put("pss", v[0]).put("private_dirty", v[1]).put("anon", v[2]))
    }
    return result
  }

  private fun pss(): Double =
      File("/proc/self/smaps_rollup").readLines().first { it.startsWith("Pss:") }.let(::kb)

  private fun kb(line: String) = line.substringAfter(':').trim().split(Regex("\\s+"))[0].toDouble() / 1024

  companion object {
    private const val TAG = "OptionCDeviceBenchmark"
  }
}
