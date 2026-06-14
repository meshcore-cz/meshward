package cz.meshcore.meshward

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

    /** The canonical stored/display form (the bare host). */
    override fun toString(): String = host
}

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
