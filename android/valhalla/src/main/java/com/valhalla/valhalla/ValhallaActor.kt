package com.valhalla.valhalla

internal interface ValhallaActorProviding {
  fun route(request: String): String
  fun traceRoute(request: String): String
  fun traceAttributes(request: String): String
}

/**
 * Access with raw unchecked strings to the Valhalla routing engine. This class is available, but
 * not recommended for general use.
 *
 * @property configPath
 */
internal class ValhallaActor(private val configPath: String) : ValhallaActorProviding {
  private val valhallaKotlin = ValhallaKotlin()

  /**
   * Run a route request to the Valhalla routing engine. This assumes your config path is valid,
   * tiles exist and your request string is valid.
   *
   * @param request
   * @return
   */
  override fun route(request: String): String {
    return valhallaKotlin.route(request, configPath)
  }

  override fun traceRoute(request: String): String {
    return valhallaKotlin.traceRoute(request, configPath)
  }

  override fun traceAttributes(request: String): String {
    return valhallaKotlin.traceAttributes(request, configPath)
  }
}
