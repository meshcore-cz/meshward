package cz.meshcore.meshward

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * MeshCore-compatible share URIs (see https://docs.meshcore.io/qr_codes/), rendered as QR
 * codes so other MeshCore apps can scan them:
 *
 *   meshcore://contact/add?name=<name>&public_key=<64 hex>&type=<n>
 *   meshcore://channel/add?name=<name>&secret=<32 hex>
 */
object MeshCoreUri {
    // Contact role identifiers; a phone/companion node is type 1.
    const val TYPE_COMPANION = 1

    fun contact(name: String, publicKeyHex: String, type: Int = TYPE_COMPANION): String =
        "meshcore://contact/add?name=${enc(name)}&public_key=${publicKeyHex.lowercase()}&type=$type"

    fun channel(name: String, secretHex: String): String =
        "meshcore://channel/add?name=${enc(name)}&secret=${secretHex.lowercase()}"

    data class Channel(val name: String, val secretHex: String)
    data class Contact(val name: String, val publicKeyHex: String, val type: Int)

    /** Parses a `meshcore://channel/add` URI, or null if it isn't one / the secret is malformed. */
    fun parseChannel(uri: String): Channel? {
        if (!uri.startsWith("meshcore://channel/add")) return null
        val q = params(uri)
        val secret = q["secret"]?.lowercase() ?: return null
        if (!secret.matches(HEX32)) return null
        return Channel(q["name"].orEmpty(), secret)
    }

    /** Parses a `meshcore://contact/add` URI, or null if it isn't one / the key is malformed. */
    fun parseContact(uri: String): Contact? {
        if (!uri.startsWith("meshcore://contact/add")) return null
        val q = params(uri)
        val key = q["public_key"]?.lowercase() ?: return null
        if (!key.matches(HEX64)) return null
        return Contact(q["name"].orEmpty(), key, q["type"]?.toIntOrNull() ?: TYPE_COMPANION)
    }

    private val HEX32 = Regex("[0-9a-f]{32}")
    private val HEX64 = Regex("[0-9a-f]{64}")

    private fun params(uri: String): Map<String, String> {
        val query = uri.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        return query.split("&").mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i < 0) null
            else URLDecoder.decode(kv.substring(0, i), "UTF-8") to URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }.toMap()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** True for a 32-hex-char string (a raw 16-byte MeshCore channel PSK). */
fun isHex32(s: String): Boolean = s.length == 32 && s.all { it.lowercaseChar() in "0123456789abcdef" }
