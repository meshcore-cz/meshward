package cz.meshcore.meshward

import android.content.Context
import cz.meshcore.meshward.data.MeshNetwork
import cz.meshcore.sidepath.protocol.BridgeAd
import cz.meshcore.sidepath.protocol.NetworkDef
import cz.meshcore.sidepath.protocol.NetworkDefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The refreshed network-definitions cache, preferred over the bundled `sidepath-protocol` resource. */
private const val DEFS_CACHE_FILE = "network_defs.json"

/** A geographic point. GeoJSON stores coordinates as [lon, lat]; this keeps them named. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * Maps a protocol [NetworkDef] (canonical, integer-Hz radio params owned by `sidepath-protocol`) to
 * the app's display model. Hz → MHz/kHz for presentation; the link lists become newline-separated
 * strings (the format the editor and `parseAnalyzerUrls` expect). Marked [MeshNetwork.isBuiltin].
 */
fun NetworkDef.toMeshNetwork(): MeshNetwork = MeshNetwork(
    code = code,
    name = name,
    freqMhz = if (freqHz > 0) freqHz / 1_000_000.0 else 0.0,
    bandwidthKhz = if (bandwidthHz > 0) bandwidthHz / 1_000.0 else 0.0,
    spreadingFactor = sf,
    codingRate = cr,
    analyzerUrls = analyzerUrls.joinToString("\n"),
    mqttEndpoints = mqtt.joinToString("\n"),
    geoJson = geoJson,
    description = description,
    isBuiltin = true,
)

/**
 * The built-in network definitions: the refreshed on-disk cache when present and parseable, else the
 * dataset bundled into `sidepath-protocol` at build time. Returns an empty list (never throws) if
 * both are unavailable, so a packaging/refresh slip can't crash startup.
 */
fun loadNetworkDefs(context: Context): List<MeshNetwork> {
    val cached = runCatching {
        val f = File(context.filesDir, DEFS_CACHE_FILE)
        if (f.exists()) NetworkDefs.parse(f.readText()) else null
    }.getOrNull()
    val defs = cached?.takeIf { it.isNotEmpty() } ?: NetworkDefs.builtins()
    return defs.map { it.toMeshNetwork() }
}

/**
 * Downloads a definitions JSON document from [url], validates it ([NetworkDefs.parse] must yield at
 * least one network), and caches it to the app files dir so [loadNetworkDefs] prefers it (and it
 * survives restarts). Returns the freshly parsed networks on success. Blocking — call off the main
 * thread. On any failure the existing cache is left untouched.
 */
fun refreshNetworkDefs(context: Context, url: String): Result<List<MeshNetwork>> = runCatching {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        requestMethod = "GET"
    }
    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val parsed = NetworkDefs.parse(text)
    require(parsed.isNotEmpty()) { "no valid networks in document" }
    File(context.filesDir, DEFS_CACHE_FILE).writeText(text)
    parsed.map { it.toMeshNetwork() }
}

/**
 * Resolves a detected [BridgeAd] (heard on a verified gateway's v2 ANNOUNCE) to a display network.
 * The canonical definition for the code (from [defsByCode]) supplies the name/territory/links; when
 * the announce carries custom radio params they override the canonical ones. Falls back to a bare
 * code-only network when the definition is unknown.
 */
fun resolveBridge(ad: BridgeAd, defsByCode: Map<String, MeshNetwork>): MeshNetwork {
    val base = defsByCode[ad.code] ?: MeshNetwork(code = ad.code, name = ad.code, isBuiltin = true)
    return if (ad.isCustom) {
        base.copy(
            freqMhz = if (ad.freqHz > 0) ad.freqHz / 1_000_000.0 else base.freqMhz,
            bandwidthKhz = if (ad.bandwidthHz > 0) ad.bandwidthHz / 1_000.0 else base.bandwidthKhz,
            spreadingFactor = if (ad.sf > 0) ad.sf else base.spreadingFactor,
            codingRate = if (ad.cr > 0) ad.cr else base.codingRate,
        )
    } else {
        base
    }
}

/**
 * Parses a GeoJSON `Polygon` or `MultiPolygon` geometry string into its rings (each a list of
 * [LatLon]). Returns an empty list for blank/unsupported input. Used to draw a network's territory
 * offline on a Compose Canvas.
 */
fun parseGeoRings(geoJson: String): List<List<LatLon>> = runCatching {
    if (geoJson.isBlank()) return emptyList()
    val obj = JSONObject(geoJson)
    val coords = obj.optJSONArray("coordinates") ?: return emptyList()
    when (obj.optString("type")) {
        // coordinates: [ ring[ [lon,lat], ... ], ... ]
        "Polygon" -> (0 until coords.length()).mapNotNull { ringAt(coords, it) }
        // coordinates: [ polygon[ ring[ [lon,lat], ... ], ... ], ... ]
        "MultiPolygon" -> (0 until coords.length()).flatMap { p ->
            val poly = coords.optJSONArray(p) ?: return@flatMap emptyList()
            (0 until poly.length()).mapNotNull { ringAt(poly, it) }
        }
        else -> emptyList()
    }
}.getOrDefault(emptyList())

private fun ringAt(rings: JSONArray, index: Int): List<LatLon>? {
    val ring = rings.optJSONArray(index) ?: return null
    val pts = (0 until ring.length()).mapNotNull { j ->
        val pt = ring.optJSONArray(j) ?: return@mapNotNull null
        if (pt.length() < 2) null else LatLon(lat = pt.optDouble(1), lon = pt.optDouble(0))
    }
    return pts.ifEmpty { null }
}
