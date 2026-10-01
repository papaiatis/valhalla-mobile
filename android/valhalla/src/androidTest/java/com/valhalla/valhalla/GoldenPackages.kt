package com.valhalla.valhalla

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The two packages built independently, `west` and `east` (the assets in `golden/`, exported from
 * the fork's GoldenJoin test), copied to the app's storage together with the Valhalla configs that
 * join them.
 */
class GoldenPackages(private val context: Context) {

  val dir: File =
      File(context.filesDir, "golden").apply {
        deleteRecursively()
        mkdirs()
      }
  val overlays = File(dir, "overlays")
  val joinConfig: File
  val routeConfig: File

  init {
    for (name in context.assets.list("golden")!!) {
      context.assets.open("golden/$name").use { input ->
        File(dir, name).outputStream().use { input.copyTo(it) }
      }
    }
    joinConfig = File(dir, "join.json").apply { writeText(config(withOverlays = false)) }
    routeConfig = File(dir, "route.json").apply { writeText(config(withOverlays = true)) }
  }

  fun tar(name: String) = File(dir, "$name.tar")

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
              .put("tile_extract", tar(name).absolutePath)
              .put("polygon", File(dir, "$name.poly").absolutePath)
              .put("build_time", 0)
              .put("tile_refs", File(dir, "${name}_tile_refs.bin").absolutePath))
    }
    mjolnir.put("packages", packages)
    mjolnir.put("package_join_tolerance", 10)
    if (withOverlays) mjolnir.put("package_joined", overlays.absolutePath)
    return base.toString()
  }

  companion object {
    // Nodes A and h of the fixture, 1.836 km apart by road; the same route the Linux build takes.
    val WEST_POINT = 52.1 to 5.2
    val EAST_POINT = 52.0973051 to 5.2224578
    const val ROUTE_KM = 1.836
    const val ROUTE_SECONDS = 112.209

    fun routeRequest(from: Pair<Double, Double>, to: Pair<Double, Double>): String =
        """{"locations":[{"lat":${from.first},"lon":${from.second}},""" +
            """{"lat":${to.first},"lon":${to.second}}],"costing":"auto"}"""
  }
}
