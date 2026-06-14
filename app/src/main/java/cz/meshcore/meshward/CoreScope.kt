package cz.meshcore.meshward

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * A CoreScope packet-analyzer endpoint, identified by its bare host (e.g. `analyzer.meshcore.cz`).
 *
 * Users configure analyzers by typing just a domain; the rest of every analyzer URL is derived here.
 * Keeping the endpoint as a host (rather than a full URL) is deliberate: as CoreScope grows we'll
 * fetch the concrete link templates from the endpoint itself, so callers should always go through the
 * builder methods rather than hand-concatenating URLs.
 */
@JvmInline
value class AnalyzerEndpoint(val host: String) {
    /** The analyzer's packets browser, with no specific packet selected. */
    fun packetsBaseUrl(): String = "https://$host/#/packets/"

    /** Deep link to a specific packet by its route-independent CoreScope content hash. */
    fun packetUrl(contentHash: String): String = packetsBaseUrl() + contentHash

    /** The analyzer's node roster API (CoreScope `/api/nodes`), capped at [limit] entries. */
    fun nodesApiUrl(limit: Int): String = "https://$host/api/nodes?limit=$limit"

    /** The canonical stored/display form (the bare host). */
    override fun toString(): String = host
}

/**
 * One node from a CoreScope analyzer's `/api/nodes` roster. The fields we map into a discovered
 * contact are surfaced directly; the full original object is kept in [rawJson] for the cached
 * `analyzerData` blob (scores, relay counts, battery, etc.).
 */
data class AnalyzerNode(
    val publicKeyHex: String,
    val name: String,
    val role: String,
    val hasGps: Boolean,
    val lat: Double,
    val lon: Double,
    val lastSeenMs: Long,
    val rawJson: String,
)

/** CoreScope role string → the app's MeshCore node-type code (1 chat, 2 repeater, 3 room, 4 sensor). */
fun analyzerRoleToNodeType(role: String): Int = when (role.lowercase()) {
    "chat", "companion" -> 1
    "repeater" -> 2
    "room", "roomserver", "room_server" -> 3
    "sensor" -> 4
    else -> 0
}

/**
 * Fetches and parses an analyzer's node roster (`/api/nodes?limit=…`). Blocking — call off the main
 * thread. Nodes without a usable public key are skipped. Returns a failure (rather than throwing) on
 * any network/parse error so callers can surface it.
 */
fun fetchAnalyzerNodes(endpoint: AnalyzerEndpoint, limit: Int = 2000): Result<List<AnalyzerNode>> = runCatching {
    val conn = (URL(endpoint.nodesApiUrl(limit)).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 30_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/json")
    }
    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    val arr = JSONObject(text).optJSONArray("nodes") ?: return@runCatching emptyList()
    (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val pub = o.optString("public_key").trim().lowercase()
        if (pub.length < 16) return@mapNotNull null
        AnalyzerNode(
            publicKeyHex = pub,
            name = o.optString("name").trim(),
            role = o.optString("role").trim(),
            hasGps = o.has("lat") && !o.isNull("lat") && o.has("lon") && !o.isNull("lon"),
            lat = o.optDouble("lat", 0.0),
            lon = o.optDouble("lon", 0.0),
            lastSeenMs = parseIsoMs(o.optString("last_seen").ifBlank { o.optString("last_heard") }),
            rawJson = o.toString(),
        )
    }
}

/** Parses an ISO-8601 instant (e.g. `2026-06-14T00:51:45Z`) to epoch millis, or 0 when unparseable. */
private fun parseIsoMs(s: String): Long =
    if (s.isBlank()) 0L else runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)

/**
 * Normalizes user input — a bare domain (`analyzer.meshcore.cz`), a host with a path, or a full URL
 * (`https://analyzer.meshcore.cz/#/packets/`) — to an [AnalyzerEndpoint] host, or null when no host
 * can be extracted. Strips any scheme, `#`/`?`/path tail, and surrounding whitespace.
 */
fun parseAnalyzerEndpoint(input: String): AnalyzerEndpoint? {
    var s = input.trim()
    if (s.isEmpty()) return null
    s = s.substringAfter("://", s)              // drop scheme if present
    s = s.substringBefore('/').substringBefore('#').substringBefore('?') // keep host only
    s = s.trim().trimEnd('.')
    // A bare host has at least one dot and no spaces; reject obvious junk.
    if (s.isEmpty() || s.contains(' ')) return null
    return AnalyzerEndpoint(s.lowercase())
}

/** Parses a newline-separated list of analyzer endpoints (one host per line), skipping invalid lines. */
fun parseAnalyzerEndpoints(text: String): List<AnalyzerEndpoint> =
    text.lines().mapNotNull { parseAnalyzerEndpoint(it) }.distinctBy { it.host }
