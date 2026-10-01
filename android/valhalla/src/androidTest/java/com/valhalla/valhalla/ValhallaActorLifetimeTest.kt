package com.valhalla.valhalla

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
 * One [Valhalla] keeps its native actor, and so the package set built from its config, across
 * requests: the package tars stay mapped between routes and are released by `close()`.
 */
@RunWith(AndroidJUnit4::class)
class ValhallaActorLifetimeTest {

  private lateinit var golden: GoldenPackages

  @Before
  fun setUp() {
    golden = GoldenPackages(InstrumentationRegistry.getInstrumentation().targetContext)
    Valhalla.joinPackages(golden.joinConfig.absolutePath, golden.overlays.absolutePath)
  }

  @Test
  fun packagesStayMappedBetweenRoutesAndAreReleasedByClose() {
    val valhalla =
        Valhalla(InstrumentationRegistry.getInstrumentation().targetContext, golden.routeConfig.absolutePath)
    val request = GoldenPackages.routeRequest(GoldenPackages.WEST_POINT, GoldenPackages.EAST_POINT)

    repeat(2) { route ->
      val trip = JSONObject(valhalla.routeJson(request)).getJSONObject("trip")
      assertEquals(0, trip.getInt("status"))
      assertEquals(GoldenPackages.ROUTE_KM, trip.getJSONObject("summary").getDouble("length"), 1e-3)
      assertTrue("west.tar after route ${route + 1}", mappings("west.tar") > 0)
      assertTrue("east.tar after route ${route + 1}", mappings("east.tar") > 0)
    }

    valhalla.close()
    assertEquals("west.tar after close", 0, mappings("west.tar"))
    assertEquals("east.tar after close", 0, mappings("east.tar"))
  }

  @Test
  fun aClosedActorRefusesRequestsAndClosingTwiceIsHarmless() {
    val valhalla =
        Valhalla(InstrumentationRegistry.getInstrumentation().targetContext, golden.routeConfig.absolutePath)
    valhalla.close()
    valhalla.close()
    try {
      valhalla.routeJson(GoldenPackages.routeRequest(GoldenPackages.WEST_POINT, GoldenPackages.EAST_POINT))
      throw AssertionError("a closed actor routed")
    } catch (expected: IllegalStateException) {}
  }

  @Test
  fun aConfigThatCannotBeLoadedFailsAtCreation() {
    try {
      Valhalla(InstrumentationRegistry.getInstrumentation().targetContext, File(golden.dir, "missing.json").absolutePath)
      throw AssertionError("a missing config was accepted")
    } catch (expected: RuntimeException) {
      assertTrue(expected.message, expected.message!!.contains("missing.json"))
    }
  }

  /** How many lines of this process's memory map name the file. */
  private fun mappings(fileName: String) =
      File("/proc/self/maps").readLines().count { it.endsWith("/$fileName") }
}
