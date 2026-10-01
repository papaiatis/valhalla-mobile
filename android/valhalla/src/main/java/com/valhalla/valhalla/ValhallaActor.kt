package com.valhalla.valhalla

internal interface ValhallaActorProviding : AutoCloseable {
  fun route(request: String): String
  fun traceRoute(request: String): String
  fun traceAttributes(request: String): String
}

/**
 * Access with raw unchecked strings to the Valhalla routing engine. This class is available, but
 * not recommended for general use.
 *
 * It owns one native actor, created from the config and kept until [close], so the tiles and the
 * package set built from the config are reused by every request. Requests aren't thread-safe:
 * callers serialize them.
 *
 * @param configPath the Valhalla JSON config file
 * @throws RuntimeException if the config can't be loaded
 */
internal class ValhallaActor(configPath: String) : ValhallaActorProviding {
  private val valhallaKotlin = ValhallaKotlin()
  private var handle = valhallaKotlin.createActor(configPath)

  /**
   * Run a route request to the Valhalla routing engine. This assumes your request string is valid.
   *
   * @param request
   * @return
   */
  override fun route(request: String): String = valhallaKotlin.route(request, handle())

  override fun traceRoute(request: String): String = valhallaKotlin.traceRoute(request, handle())

  override fun traceAttributes(request: String): String =
      valhallaKotlin.traceAttributes(request, handle())

  /** Frees the native actor and everything it holds. Closing twice is harmless. */
  @Synchronized
  override fun close() {
    if (handle != 0L) {
      valhallaKotlin.destroyActor(handle)
      handle = 0L
    }
  }

  private fun handle(): Long {
    check(handle != 0L) { "The Valhalla actor is closed" }
    return handle
  }
}
