package com.valhalla.valhalla

internal class ValhallaKotlin {
  companion object {
    init {
      System.loadLibrary("valhalla-wrapper")
    }
  }

  /**
   * Creates the native actor for a config and returns its handle. The package set built from the
   * config lives as long as the actor. Throws [RuntimeException] when the config can't be loaded.
   */
  external fun createActor(configPath: String): Long

  /** Frees the native actor behind a handle from [createActor]. */
  external fun destroyActor(handle: Long)

  external fun route(request: String, handle: Long): String
  external fun traceRoute(request: String, handle: Long): String
  external fun traceAttributes(request: String, handle: Long): String
  external fun joinPackages(configPath: String, outputDir: String): String
}
