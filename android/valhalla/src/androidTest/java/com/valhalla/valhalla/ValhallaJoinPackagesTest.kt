package com.valhalla.valhalla

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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

  private lateinit var golden: GoldenPackages

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    golden = GoldenPackages(context)
  }

  @Test
  fun joinWritesTheGoldenOverlaysAndRoutesAcrossTheJoin() {
    assertEquals(WEST_TAR_HASH, fnv1a(golden.tar("west")))
    assertEquals(EAST_TAR_HASH, fnv1a(golden.tar("east")))

    val report = JSONObject(Valhalla.joinPackages(golden.joinConfig.absolutePath, golden.overlays.absolutePath))

    assertEquals(0, report.getInt("lost"))
    assertTrue("nothing was joined: $report", report.getInt("joined") > 0)
    assertEquals(WEST_OVERLAY_HASH, fnv1a(File(golden.overlays, "west.joined")))
    assertEquals(EAST_OVERLAY_HASH, fnv1a(File(golden.overlays, "east.joined")))

    val valhalla = Valhalla(context, golden.routeConfig.absolutePath)
    // A is in the west package, h in the east one: the route crosses the join both ways.
    for ((from, to) in listOf(GoldenPackages.WEST_POINT to GoldenPackages.EAST_POINT, GoldenPackages.EAST_POINT to GoldenPackages.WEST_POINT)) {
      val trip = JSONObject(valhalla.routeJson(GoldenPackages.routeRequest(from, to))).getJSONObject("trip")
      assertEquals(0, trip.getInt("status"))
      val summary = trip.getJSONObject("summary")
      assertEquals(GoldenPackages.ROUTE_KM, summary.getDouble("length"), 1e-3)
      assertEquals(GoldenPackages.ROUTE_SECONDS, summary.getDouble("time"), 1e-2)
    }
  }

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
  }
}
