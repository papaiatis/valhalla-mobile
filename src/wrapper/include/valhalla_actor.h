#ifndef VALHALLAACTOR_H
#define VALHALLAACTOR_H

#include <string>
#include <valhalla/tyr/actor.h>
#include <valhalla/baldr/tilegetter.h>
#include <valhalla/baldr/packageset.h>

class ValhallaMobileHttpClient {
public:
    virtual ~ValhallaMobileHttpClient() = default;
    
    /**
     * Makes a synchronous GET request to fetch tile data
     * @param url the URL to fetch
     * @param range_offset optional offset for range requests
     * @param range_size optional size for range requests
     * @return GET_response_t with the response data and status
     */
    virtual valhalla::baldr::tile_getter_t::GET_response_t 
    get(const std::string& url, uint64_t range_offset = 0, uint64_t range_size = 0) = 0;
    
    /**
     * Makes a synchronous HEAD request to fetch response headers
     * @param url the URL to query
     * @param header_mask mask for which headers to retrieve
     * @return HEAD_response_t with the response headers and status
     */
    virtual valhalla::baldr::tile_getter_t::HEAD_response_t 
    head(const std::string& url, valhalla::baldr::tile_getter_t::header_mask_t header_mask) = 0;
};

class ValhallaActor {
private:
    std::unique_ptr<valhalla::tyr::actor_t> actor;
    std::unique_ptr<valhalla::baldr::GraphReader> graph_reader;
public:
    ValhallaActor(const std::string& config_path, ValhallaMobileHttpClient* http_client = nullptr);
    
    std::string route(const std::string& request);
    std::string traceRoute(const std::string& request);
    std::string traceAttributes(const std::string& request);
};

/**
 * Joins independently built packages (mjolnir.packages) ahead of time and writes per-package
 * overlays to output_dir. Returns a JSON summary: seconds, join_key, joined, lost, full_rewrites,
 * id_rewrites, clean_tiles, and per-package overlay sizes.
 *
 * @param config_path path to the Valhalla JSON config containing mjolnir.packages
 * @param output_dir directory where the overlays are written
 * @return JSON summary string
 */
std::string joinPackages(const std::string& config_path, const std::string& output_dir);

#endif // VALHALLAACTOR_H
