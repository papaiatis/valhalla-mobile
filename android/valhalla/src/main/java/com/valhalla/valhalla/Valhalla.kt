package com.valhalla.valhalla

import android.content.Context
import com.osrm.api.models.RouteResponse as OsrmRouteResponse
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.valhalla.api.models.DirectionsOptions
import com.valhalla.api.models.MapMatchRequest
import com.valhalla.api.models.RouteRequest
import com.valhalla.api.models.RouteResponse
import com.valhalla.api.models.TraceAttributesRequest
import com.valhalla.api.models.TraceAttributesResponse
import com.valhalla.config.models.ValhallaConfig
import com.valhalla.valhalla.config.ValhallaConfigManager

/**
 * Main entry point for the Valhalla routing engine on Android.
 *
 * This class provides a Kotlin interface to the native Valhalla C++ routing engine. It handles
 * configuration management, JSON serialization, and routing requests.
 *
 * @param context The Android context used for file system operations and configuration management.
 * @param config The Valhalla configuration specifying tile locations and routing options.
 * @param valhallaConfigManager Manages the Valhalla configuration file on the device. Defaults to a
 *   new instance.
 * @param moshi JSON serialization adapter. Defaults to a Moshi instance with Kotlin reflection
 *   support.
 * @see ValhallaConfig
 * @see ValhallaConfigManager
 * @see RouteRequest
 * @see ValhallaResponse
 */
class Valhalla
private constructor(
    private val valhallaActor: ValhallaActorProviding,
    private val moshi: Moshi,
) : AutoCloseable {

  /**
   * Routes over the typed config, which is written to the device first.
   *
   * @param context The Android context used for file system operations and configuration
   *   management.
   * @param config The Valhalla configuration specifying tile locations and routing options.
   * @param valhallaConfigManager Manages the Valhalla configuration file on the device. Defaults to
   *   a new instance.
   * @param moshi JSON serialization adapter. Defaults to a Moshi instance with Kotlin reflection
   *   support.
   */
  constructor(
      context: Context,
      config: ValhallaConfig,
      valhallaConfigManager: ValhallaConfigManager = ValhallaConfigManager(context),
      moshi: Moshi = defaultMoshi(),
  ) : this(createActor(valhallaConfigManager, config), moshi)

  /**
   * Routes over a config file the caller wrote. Use this for settings the typed [ValhallaConfig]
   * doesn't have, such as `mjolnir.packages`.
   *
   * @param context The Android context.
   * @param configPath The Valhalla JSON config file.
   * @param moshi JSON serialization adapter. Defaults to a Moshi instance with Kotlin reflection
   *   support.
   * @throws RuntimeException if the config can't be loaded.
   */
  @Suppress("UNUSED_PARAMETER")
  constructor(
      context: Context,
      configPath: String,
      moshi: Moshi = defaultMoshi(),
  ) : this(ValhallaActor(configPath), moshi)

  /**
   * Frees the native actor, and with it the tiles and package set it holds. Don't use this
   * instance afterwards.
   */
  override fun close() = valhallaActor.close()

  /** Runs a route request given as Valhalla JSON and returns Valhalla's raw JSON, errors included. */
  fun routeJson(request: String): String = valhallaActor.route(request)

  /** Runs a trace_route request given as Valhalla JSON and returns the raw JSON, errors included. */
  fun traceRouteJson(request: String): String = valhallaActor.traceRoute(request)

  /**
   * Runs a trace_attributes request given as Valhalla JSON and returns the raw JSON, errors
   * included.
   */
  fun traceAttributesJson(request: String): String = valhallaActor.traceAttributes(request)

  /**
   * Fetch a route from Valhalla.
   *
   * This function returns a sealed class with the format you designated. Currently this only
   * supports [ValhallaResponse.Json] and [ValhallaResponse.Osrm] formats.
   *
   * @param request The Valhalla routing request containing locations, costing model, and options.
   * @return The route response wrapped in a [ValhallaResponse] sealed class based on the requested
   *   format.
   * @throws ValhallaException.Internal if the Valhalla engine returns an error response.
   * @throws ValhallaException.InvalidError if an error response cannot be parsed.
   * @throws ValhallaException.InvalidResponse if the response JSON cannot be parsed.
   * @throws ValhallaException.NotSupported if an unsupported format (GPX or PBF) is requested.
   * @see RouteRequest
   * @see ValhallaResponse
   * @see RouteRequest.Format
   */
  fun route(request: RouteRequest): ValhallaResponse {
    val encodedRequest = moshi.adapter(RouteRequest::class.java).toJson(request)
    val rawResponse = valhallaActor.route(encodedRequest)
    val format = request.format?.let { reqFormat ->
        DirectionsOptions.Format.entries.firstOrNull { it.name == reqFormat.name }
    }
    return parseRouteResponse(rawResponse, format)
  }

  fun traceRoute(request: MapMatchRequest): ValhallaResponse {
    val encodedRequest = moshi.adapter(MapMatchRequest::class.java).toJson(request)
    val rawResponse = valhallaActor.traceRoute(encodedRequest)
    return parseRouteResponse(rawResponse, request.directionsOptions?.format)
  }

  fun traceAttributes(request: TraceAttributesRequest): TraceAttributesResponse {
    val encodedRequest = moshi.adapter(TraceAttributesRequest::class.java).toJson(request)
    var rawResponse = valhallaActor.traceAttributes(encodedRequest)

    if (rawResponse.contains("code") && !rawResponse.contains("edges")) {
      val error = moshi.adapter(ErrorResponse::class.java).fromJson(rawResponse)
      error?.let { throw ValhallaException.Internal(it) }
      throw ValhallaException.InvalidError()
    }

    // Workaround: osm_changeset is defined as an Int? in the external models dependency,
    // but OSM changesets can exceed Int.MAX_VALUE. We replace the value with null to avoid crashing.
    rawResponse = rawResponse.replace(Regex("\"osm_changeset\"\\s*:\\s*\\d+"), "\"osm_changeset\":null")

    return moshi.adapter(TraceAttributesResponse::class.java).fromJson(rawResponse)
        ?: throw ValhallaException.InvalidResponse()
  }

  private fun parseRouteResponse(
      rawResponse: String,
      format: DirectionsOptions.Format?
  ): ValhallaResponse {
    // Check for error response in Valhalla format.
    // OSRM has a code and message like the valhalla error, but it's not the same format.
    // If the response contains routes, it's a valid OSRM response.
    if (rawResponse.contains("code") and !rawResponse.contains("routes")) {
      val error = moshi.adapter(ErrorResponse::class.java).fromJson(rawResponse)
      error?.let { throw ValhallaException.Internal(it) }
      throw ValhallaException.InvalidError()
    }

    return when (format) {
      DirectionsOptions.Format.gpx -> throw ValhallaException.NotSupported()
      DirectionsOptions.Format.osrm -> {
        val osrmResponse =
            moshi.adapter(OsrmRouteResponse::class.java).fromJson(rawResponse)
                ?: throw ValhallaException.InvalidResponse()
        ValhallaResponse.Osrm(osrmResponse)
      }
      DirectionsOptions.Format.pbf -> throw ValhallaException.NotSupported()
      else -> {
        val valhallaResponse =
            moshi.adapter(RouteResponse::class.java).fromJson(rawResponse)
                ?: throw ValhallaException.InvalidResponse()
        ValhallaResponse.Json(valhallaResponse)
      }
    }
  }

  companion object {
    private fun defaultMoshi(): Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private fun createActor(
        valhallaConfigManager: ValhallaConfigManager,
        config: ValhallaConfig
    ): ValhallaActorProviding {
      valhallaConfigManager.writeConfig(config)
      return ValhallaActor(valhallaConfigManager.getAbsolutePath())
    }

    /**
     * Joins independently built packages ahead of time and writes per-package overlays to outputDir.
     *
     * The config at configPath must contain a `mjolnir.packages` array describing the packages to
     * join. Overlays are written to outputDir as `<name>.joined` files. Once the overlays are in
     * place, point `mjolnir.package_joined` at outputDir so the packages route without rewriting
     * tiles at runtime.
     *
     * @param configPath path to the Valhalla JSON config containing mjolnir.packages
     * @param outputDir directory where the overlays are written
     * @return JSON summary: seconds, join_key, joined, lost, full_rewrites, id_rewrites, clean_tiles,
     *   and per-package overlay sizes
     * @throws ValhallaException.Internal if the join fails with a Valhalla error
     * @throws ValhallaException.InvalidError if the error response cannot be parsed
     */
    fun joinPackages(configPath: String, outputDir: String): String {
        val result = ValhallaKotlin().joinPackages(configPath, outputDir)
      if (result.contains("code") && !result.contains("seconds")) {
        val error = defaultMoshi().adapter(ErrorResponse::class.java).fromJson(result)
        error?.let { throw ValhallaException.Internal(it) }
        throw ValhallaException.InvalidError()
      }
      return result
    }
  }
}
