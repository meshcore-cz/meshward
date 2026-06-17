package cz.meshcore.meshward.outpost.protocol

import java.io.ByteArrayOutputStream

/**
 * Outpost protocol version 1 — the wire definition, kept deliberately free of Android and storage
 * concerns so the SPECIFICATION can be the single source of truth and the encoding can be unit
 * tested on the JVM.
 *
 * A persistent object (CREATE / UPDATE / CLOSE) is a single self-contained, author-signed binary
 * record that fits inside one MeshCore group datagram. Its layout (SPECIFICATION §9):
 *
 * ```
 * 0      1   version<<4 | object_type
 * 1      1   profile<<4 | flags
 * 2      8   board_id
 * 10     10  author_ref  (= author MeshCore public key[0:10])
 * 20     4   listing_id  (LE u32, author-chosen)
 * 24     1   revision    (0 for CREATE)
 * 25     4   created_at  (LE u32 unix seconds)
 * 29     1   ttl_code
 * 30     N   profile payload (≤ 66 bytes)
 * tail   64  Ed25519 signature over  signature_domain || bytes[0 .. 30+N)
 * ```
 *
 * Signatures, board ids and object ids live in [OutpostCrypto]; this file only knows how to lay out
 * and parse bytes plus validate structural limits (§12).
 */
object OutpostProtocol {
    const val VERSION = 0x1

    // §5.2 — every v1 frame (object included) is at most 160 bytes; an object is at least 94.
    const val MAX_OBJECT_SIZE = 160
    const val HEADER_SIZE = 30
    const val SIGNATURE_SIZE = 64
    const val MIN_OBJECT_SIZE = HEADER_SIZE + SIGNATURE_SIZE // 94
    const val MAX_PAYLOAD_SIZE = MAX_OBJECT_SIZE - MIN_OBJECT_SIZE // 66

    const val BOARD_ID_SIZE = 8
    const val AUTHOR_REF_SIZE = 10
    const val OBJECT_ID_SIZE = 8

    // §6.2 signature domain: "OUTPOST" || 0x00 || 0x01
    val SIGNATURE_DOMAIN = byteArrayOf(0x4F, 0x55, 0x54, 0x50, 0x4F, 0x53, 0x54, 0x00, 0x01)

    // §5.4 two-byte transport magic "OP" prefixed in front of a frame on generic raw transports.
    val TRANSPORT_MAGIC = byteArrayOf(0x4F, 0x50)
}

/** Persistent object kinds (§4 / §8 frame low nibble). */
enum class OutpostObjectType(val code: Int) {
    CREATE(0x1), UPDATE(0x2), CLOSE(0x3);

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Application profile (§9.1 byte 1 high nibble). Only [EXCHANGE] is fully modelled in v1. */
enum class OutpostProfile(val code: Int, val label: String) {
    GENERIC(0x0, "Generic"),
    EXCHANGE(0x1, "Exchange"),
    EVENTS(0x2, "Events"),
    HELP(0x3, "Help"),
    NETWORK_REPORT(0x4, "Network report");

    /** Bit position of this profile in a sync profile mask (§16.1). */
    val maskBit: Int get() = 1 shl code

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Signed expiry policy relative to created_at (§9.5). */
enum class OutpostTtl(val code: Int, val seconds: Long, val label: String) {
    H1(0x00, 3_600, "1 hour"),
    H6(0x01, 21_600, "6 hours"),
    D1(0x02, 86_400, "1 day"),
    D3(0x03, 259_200, "3 days"),
    D7(0x04, 604_800, "7 days"),
    D14(0x05, 1_209_600, "14 days"),
    D30(0x06, 2_592_000, "30 days"),
    D90(0x07, 7_776_000, "90 days");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Exchange categories (§11.2). */
enum class ExchangeCategory(val code: Int, val label: String) {
    OFFER(0x00, "Offer"),
    WANTED(0x01, "Wanted"),
    TRADE(0x02, "Trade"),
    FREE(0x03, "Free"),
    SERVICE(0x04, "Service"),
    BORROW(0x05, "Borrow / lend"),
    HELP(0x06, "Help"),
    EVENT(0x07, "Event"),
    REPORT(0x08, "Report");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/** Close reasons (§10.3). A CLOSE is terminal. */
enum class CloseReason(val code: Int, val label: String) {
    CLOSED(0x00, "Closed"),
    SOLD(0x01, "Sold"),
    FULFILLED(0x02, "Fulfilled"),
    WITHDRAWN(0x03, "Withdrawn"),
    INVALIDATED(0x04, "Invalidated");

    companion object {
        fun fromCode(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/**
 * A decoded persistent Outpost object. [signedBytes] are the exact canonical bytes (header + payload
 * + signature) — the only thing that is ever stored or transmitted; every other field is a parsed
 * view over those bytes. Construct via [OutpostObject.decode] or [OutpostObjectBuilder].
 */
class OutpostObject internal constructor(
    val signedBytes: ByteArray,
    val type: OutpostObjectType,
    val profile: OutpostProfile,
    val boardId: ByteArray,        // 8 bytes
    val authorRef: ByteArray,      // 10 bytes
    val listingId: Long,           // u32
    val revision: Int,             // 0..255
    val createdAt: Long,           // unix seconds
    val ttl: OutpostTtl,
    val payload: ByteArray,
) {
    /** Bytes covered by the signature: everything before the trailing 64-byte signature. */
    val unsignedBytes: ByteArray
        get() = signedBytes.copyOfRange(0, signedBytes.size - OutpostProtocol.SIGNATURE_SIZE)

    val signature: ByteArray
        get() = signedBytes.copyOfRange(signedBytes.size - OutpostProtocol.SIGNATURE_SIZE, signedBytes.size)

    /** Expiry instant in unix seconds (§9.5). */
    val expiresAt: Long get() = createdAt + ttl.seconds

    /** The (board, author, listing) tuple that identifies one listing across its revisions (§4). */
    val listingKey: String
        get() = boardId.hex() + ":" + authorRef.hex() + ":" + listingId.toString(16)

    fun isExpiredAt(nowSeconds: Long): Boolean = nowSeconds >= expiresAt

    /** Decodes the Exchange payload, or null when this object isn't an Exchange CREATE/UPDATE. */
    fun exchange(): ExchangePayload? =
        if (profile == OutpostProfile.EXCHANGE && type != OutpostObjectType.CLOSE)
            ExchangePayload.decode(payload) else null

    /** Decodes the CLOSE payload, or null when this isn't a CLOSE. */
    fun close(): ClosePayload? = if (type == OutpostObjectType.CLOSE) ClosePayload.decode(payload) else null

    companion object {
        /**
         * Parses canonical [bytes] into an object, validating structure and limits (§12 steps 1–7).
         * Returns null on any structural problem. Signature and identity checks happen later in the
         * repository once the author's full key is resolved.
         */
        fun decode(bytes: ByteArray): OutpostObject? {
            if (bytes.size < OutpostProtocol.MIN_OBJECT_SIZE || bytes.size > OutpostProtocol.MAX_OBJECT_SIZE) return null
            val first = bytes[0].toInt() and 0xFF
            if ((first ushr 4) != OutpostProtocol.VERSION) return null
            val type = OutpostObjectType.fromCode(first and 0x0F) ?: return null

            val pf = bytes[1].toInt() and 0xFF
            val profile = OutpostProfile.fromCode(pf ushr 4) ?: return null
            if ((pf and 0x0F) != 0) return null // §9.1 all flags must be zero in v1

            val boardId = bytes.copyOfRange(2, 10)
            val authorRef = bytes.copyOfRange(10, 20)
            val listingId = readLe32(bytes, 20)
            val revision = bytes[24].toInt() and 0xFF
            val createdAt = readLe32(bytes, 25)
            val ttl = OutpostTtl.fromCode(bytes[29].toInt() and 0xFF) ?: return null

            // CREATE must be revision 0; UPDATE/CLOSE must be > 0 (§10).
            if (type == OutpostObjectType.CREATE && revision != 0) return null
            if (type != OutpostObjectType.CREATE && revision == 0) return null

            val payload = bytes.copyOfRange(OutpostProtocol.HEADER_SIZE, bytes.size - OutpostProtocol.SIGNATURE_SIZE)
            if (payload.size > OutpostProtocol.MAX_PAYLOAD_SIZE) return null

            // Per-profile payload sanity (§12 step 7).
            when (type) {
                OutpostObjectType.CLOSE -> if (ClosePayload.decode(payload) == null) return null
                else -> if (profile == OutpostProfile.EXCHANGE && ExchangePayload.decode(payload) == null) return null
            }

            return OutpostObject(
                signedBytes = bytes.copyOf(),
                type = type, profile = profile, boardId = boardId, authorRef = authorRef,
                listingId = listingId, revision = revision, createdAt = createdAt, ttl = ttl, payload = payload,
            )
        }
    }
}

/** Decoded Exchange CREATE/UPDATE payload (§11.1). */
data class ExchangePayload(
    val category: ExchangeCategory,
    val currency: String,   // "" when no monetary price
    val price: Long,        // whole currency units; 0 = free / negotiable / n/a
    val description: String,
) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(category.code)
        val cur = if (currency.isBlank()) byteArrayOf(0, 0, 0) else currency.uppercase().toByteArray(Charsets.US_ASCII)
        require(cur.size == 3) { "currency must be 3 ASCII bytes or blank" }
        out.write(cur)
        writeLe32(out, price)
        out.write(description.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    companion object {
        fun decode(payload: ByteArray): ExchangePayload? {
            if (payload.size < 9) return null // 1 + 3 + 4 + at least 1 description byte (§11.5)
            val category = ExchangeCategory.fromCode(payload[0].toInt() and 0xFF) ?: return null
            val curBytes = payload.copyOfRange(1, 4)
            val currency = if (curBytes.all { it.toInt() == 0 }) "" else
                String(curBytes, Charsets.US_ASCII).takeIf { it.all { c -> c in 'A'..'Z' } } ?: return null
            val price = readLe32(payload, 4)
            val descBytes = payload.copyOfRange(8, payload.size)
            val description = runCatching { decodeUtf8Strict(descBytes) }.getOrNull() ?: return null
            return ExchangePayload(category, currency, price, description)
        }
    }
}

/** Decoded CLOSE payload (§10.3): a reason byte + optional UTF-8 note. */
data class ClosePayload(val reason: CloseReason, val note: String) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(reason.code)
        if (note.isNotEmpty()) out.write(note.toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    companion object {
        fun decode(payload: ByteArray): ClosePayload? {
            if (payload.isEmpty()) return null
            val reason = CloseReason.fromCode(payload[0].toInt() and 0xFF) ?: return null
            val note = if (payload.size > 1)
                runCatching { String(payload, 1, payload.size - 1, Charsets.UTF_8) }.getOrNull() ?: return null
            else ""
            return ClosePayload(reason, note)
        }
    }
}

/**
 * Assembles the unsigned header + payload of a persistent object. The repository signs the result
 * (via [OutpostCrypto.sign]) to produce a finished [OutpostObject]; this keeps all key material out
 * of the wire layer.
 */
class OutpostObjectBuilder(
    val type: OutpostObjectType,
    val profile: OutpostProfile,
    val boardId: ByteArray,
    val authorRef: ByteArray,
    val listingId: Long,
    val revision: Int,
    val createdAt: Long,
    val ttl: OutpostTtl,
    val payload: ByteArray,
) {
    init {
        require(boardId.size == OutpostProtocol.BOARD_ID_SIZE) { "board_id must be 8 bytes" }
        require(authorRef.size == OutpostProtocol.AUTHOR_REF_SIZE) { "author_ref must be 10 bytes" }
        require(revision in 0..255) { "revision out of range" }
        require(payload.size <= OutpostProtocol.MAX_PAYLOAD_SIZE) { "payload exceeds ${OutpostProtocol.MAX_PAYLOAD_SIZE} bytes" }
    }

    /** The bytes the signature covers (header + payload, no signature). */
    fun unsignedBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((OutpostProtocol.VERSION shl 4) or type.code)
        out.write((profile.code shl 4) or 0x0)
        out.write(boardId)
        out.write(authorRef)
        writeLe32(out, listingId)
        out.write(revision)
        writeLe32(out, createdAt)
        out.write(ttl.code)
        out.write(payload)
        return out.toByteArray()
    }
}

// ---- little-endian + utf-8 helpers (kept local so the protocol layer has no module deps) --------

internal fun readLe32(b: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 0 until 4) v = v or ((b[off + i].toLong() and 0xFF) shl (8 * i))
    return v
}

internal fun writeLe32(out: ByteArrayOutputStream, v: Long) {
    for (i in 0 until 4) out.write(((v ushr (8 * i)) and 0xFF).toInt())
}

/** Strict UTF-8 decode rejecting control characters other than ordinary space (§11.5). */
internal fun decodeUtf8Strict(bytes: ByteArray): String {
    val s = String(bytes, Charsets.UTF_8)
    if (s.toByteArray(Charsets.UTF_8).size != bytes.size) error("not valid utf-8")
    if (s.any { it.isISOControl() && it != ' ' }) error("control characters not allowed")
    return s
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
