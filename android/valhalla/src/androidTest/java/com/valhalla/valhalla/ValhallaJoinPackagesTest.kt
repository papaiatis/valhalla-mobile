package com.valhalla.valhalla

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Joins two packages built independently (the assets in `golden/`, exported from the fork's
 * GoldenJoin test), then routes across the join.
 *
 * The hashes are those of the Linux build, so equal hashes mean the join writes byte-identical
 * overlays on this device.
 */
@RunWith(AndroidJUnit4::class)
class ValhallaJoinPackagesTest {

  private lateinit var context: Context
  private lateinit var dir: File
  private lateinit var overlays: File
  private lateinit var joinConfig: File
  private lateinit var routeConfig: File

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    dir = File(context.filesDir, "golden").apply {
      deleteRecursively()
      mkdirs()
    }
    overlays = File(dir, "overlays")
    for (name in context.assets.list("golden")!!) {
      context.assets.open("golden/$name").use { input ->
        File(dir, name).outputStream().use { input.copyTo(it) }
      }
    }
    joinConfig = File(dir, "join.json").apply { writeText(config(withOverlays = false)) }
    routeConfig = File(dir, "route.json").apply { writeText(config(withOverlays = true)) }
  }

  @Test
  fun joinWritesTheGoldenOverlaysAndRoutesAcrossTheJoin() {
    assertEquals(WEST_TAR_HASH, fnv1a(File(dir, "west.tar")))
    assertEquals(EAST_TAR_HASH, fnv1a(File(dir, "east.tar")))

    val actor = ValhallaActor(routeConfig.absolutePath)
    val report = JSONObject(actor.joinPackages(joinConfig.absolutePath, overlays.absolutePath))

    assertEquals(0, report.getInt("lost"))
    assertTrue("nothing was joined: $report", report.getInt("joined") > 0)
    assertEquals(WEST_OVERLAY_HASH, fnv1a(File(overlays, "west.joined")))
    assertEquals(EAST_OVERLAY_HASH, fnv1a(File(overlays, "east.joined")))

    // A is in the west package, h in the east one: the route crosses the join both ways.
    for ((from, to) in listOf(WEST_POINT to EAST_POINT, EAST_POINT to WEST_POINT)) {
      val trip = JSONObject(actor.route(routeRequest(from, to))).getJSONObject("trip")
      assertEquals(0, trip.getInt("status"))
      val summary = trip.getJSONObject("summary")
      assertEquals(ROUTE_KM, summary.getDouble("length"), 1e-3)
      assertEquals(ROUTE_SECONDS, summary.getDouble("time"), 1e-2)
    }
  }

  private fun config(withOverlays: Boolean): String {
    val base = context.assets.open("config.json").bufferedReader().use { JSONObject(it.readText()) }
    val mjolnir = base.getJSONObject("mjolnir")
    mjolnir.remove("tile_extract")
    mjolnir.remove("traffic_extract")
    mjolnir.put("tile_dir", File(dir, "no_tiles").absolutePath)
    val packages = JSONArray()
    for (name in listOf("west", "east")) {
      packages.put(
          JSONObject()
              .put("name", name)
              .put("tile_extract", File(dir, "$name.tar").absolutePath)
              .put("polygon", File(dir, "$name.poly").absolutePath)
              .put("build_time", 0)
              .put("tile_refs", File(dir, "${name}_tile_refs.bin").absolutePath))
    }
    mjolnir.put("packages", packages)
    mjolnir.put("package_join_tolerance", 10)
    if (withOverlays) mjolnir.put("package_joined", overlays.absolutePath)
    return base.toString()
  }

  private fun routeRequest(from: Pair<Double, Double>, to: Pair<Double, Double>): String =
      """{"locations":[{"lat":${from.first},"lon":${from.second}},""" +
          """{"lat":${to.first},"lon":${to.second}}],"costing":"auto"}"""

  private fun fnv1a(file: File): ULong {
    var hash = 0xcbf29ce484222325uL
    for (byte in file.readBytes()) {
      hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
    }
    return hash
  }

  private companion object {
    val WEST_TAR_HASH = 535886572131682744uL
    val EAST_TAR_HASH = 9302745409726607298uL
    val WEST_OVERLAY_HASH = 16156776531689387855uL
    val EAST_OVERLAY_HASH = 15970251218106846668uL

    // Nodes A and h of the fixture, 1.836 km apart by road; the same route the Linux build takes.
    val WEST_POINT = 52.1 to 5.2
    val EAST_POINT = 52.0973051 to 5.2224578
    const val ROUTE_KM = 1.836
    const val ROUTE_SECONDS = 112.209
  }
}
