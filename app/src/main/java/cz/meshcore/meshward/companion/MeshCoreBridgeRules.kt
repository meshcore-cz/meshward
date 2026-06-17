package cz.meshcore.meshward.companion

import cz.meshcore.sidepath.meshcore.MeshCoreCodec
import cz.meshcore.sidepath.meshcore.MeshCoreEnvelope
import cz.meshcore.sidepath.meshcore.MeshCoreType
import java.security.MessageDigest

/**
 * Bridge forwarding rules — a Kotlin port of the packet-classification and de-duplication logic in
 * the Go reference bridge (`../sidepath-protocol/bridge/meshcore/bridge.go`). Kept in one file, with
 * matching function names and semantics, so the two implementations can be reviewed/synced together.
 *
 * These are the "extra rules" beyond raw forwarding: which over-the-air MeshCore packets propagate
 * onto the Sidepath mesh (and how — flood vs direct-by-target-hash), and the de-dup that stops the
 * mesh being spammed by MeshCore's repeated re-floods. Network tagging (the SPMC frame that records
 * which MeshCore network a packet crossed) is applied separately by `SidepathService` via
 * `MeshCoreCarrier`, exactly as the Go Sidepath side wraps the bridge's forwarded packets.
 *
 * Not yet ported: `BridgeChannelOut` (Sidepath channel message → MeshCore GRP_TXT) needs a generic
 * MeshCore packet encoder the gomobile `meshpkt` binding doesn't currently expose; the app also does
 * not yet bridge Sidepath channel traffic outward. [BridgeDedup.shouldBridgeOut] is provided for when
 * it lands.
 */
enum class ForwardMode { SKIP, FLOOD, DIRECT }

/** Result of [MeshCoreBridgeRules.classify]: how (if at all) a packet should be forwarded. */
class BridgeClassification(
    val mode: ForwardMode,
    /** 1-byte routing target hash for [ForwardMode.DIRECT], else null. */
    val targetHash: ByteArray?,
    /** Human-readable reason when [mode] is [ForwardMode.SKIP]. */
    val reason: String,
    /** True for ADVERTs, which bypass content de-dup (repeated adverts must keep nodes discoverable). */
    val isAdvert: Boolean,
)

object MeshCoreBridgeRules {
    /** Payload types whose first payload byte is a 1-byte destination hash (mirrors Go payloadCarriesDestHash). */
    private val DEST_HASH_TYPES = setOf(
        MeshCoreType.REQ, MeshCoreType.RESPONSE, MeshCoreType.TXT_MSG, MeshCoreType.ANON_REQ, MeshCoreType.PATH,
    )

    /** Decode [raw] and classify it. Undecodable packets are skipped. */
    fun classify(raw: ByteArray): BridgeClassification = classifyEnvelope(MeshCoreCodec.decodeEnvelope(raw))

    /**
     * Pure classification over an already-decoded envelope (so it is unit-testable without the native
     * meshpkt `.so`). Mirrors Go `classify`:
     *  - ADVERT → always flood (node discovery), even when sent DIRECT.
     *  - data-bearing dest-hash types → direct, target = payload[0], independent of route mode.
     *  - FLOOD / TRANSPORT_FLOOD → flood.
     *  - DIRECT / TRANSPORT_DIRECT → direct by target hash; ACK-like packets with no target flood.
     *  - anything else → skip.
     */
    fun classifyEnvelope(env: MeshCoreEnvelope?): BridgeClassification {
        if (env == null) return skip("undecodable packet")
        if (env.type == MeshCoreType.ADVERT) {
            return BridgeClassification(ForwardMode.FLOOD, null, "", isAdvert = true)
        }
        if (env.type in DEST_HASH_TYPES && env.payload.isNotEmpty()) {
            return direct(byteArrayOf(env.payload[0]))
        }
        val isFlood = env.route.contains("FLOOD")
        val isDirect = env.route.contains("DIRECT")
        return when {
            isFlood -> flood()
            isDirect -> {
                val target = directTargetHash(env)
                if (target == null) {
                    if (ackLike(env)) flood() else skip("direct packet has no routable target hash")
                } else {
                    direct(target)
                }
            }
            else -> skip("unsupported route type")
        }
    }

    /**
     * The 1-byte routing target for a direct packet. Dest-hash payload types are addressed by their
     * payload[0]; otherwise the first traversed hop is used. (TRACE route-hash extraction in the Go
     * version isn't ported — the meshpkt binding exposes no trace-route op — so TRACE falls through to
     * its first hop, which is the common case.)
     */
    private fun directTargetHash(env: MeshCoreEnvelope): ByteArray? {
        if (env.type in DEST_HASH_TYPES && env.payload.isNotEmpty()) return byteArrayOf(env.payload[0])
        return env.hops.firstOrNull()?.copyOf()
    }

    /** Mirrors Go ackLikePacket: a bare ACK, or a MULTIPART wrapping an ACK. */
    private fun ackLike(env: MeshCoreEnvelope): Boolean {
        if (env.type == MeshCoreType.ACK) return true
        if (env.type != MeshCoreType.MULTIPART) return false
        // decodeMultipartAckCrc returns non-null only when the inner type is ACK with a 4-byte CRC.
        return MeshCoreCodec.decodeMultipartAckCrc(env.payload) != null
    }

    private fun skip(reason: String) = BridgeClassification(ForwardMode.SKIP, null, reason, isAdvert = false)
    private fun flood() = BridgeClassification(ForwardMode.FLOOD, null, "", isAdvert = false)
    private fun direct(target: ByteArray) = BridgeClassification(ForwardMode.DIRECT, target, "", isAdvert = false)
}

/**
 * Time-windowed de-duplication for bridging, mirroring the three `seen*` maps in the Go bridge.
 * MeshCore re-floods packets repeatedly; without this the Sidepath mesh (and the radio) would be
 * spammed. All methods are safe for concurrent use.
 *
 *  - [shouldForwardPacket] — inbound (radio → mesh). Keys on the route-independent content digest so
 *    the same logical packet over different routes/paths forwards once. ADVERTs bypass.
 *  - [shouldInjectRaw] — outbound (mesh → radio). Keys on the exact wire bytes, so two logically-equal
 *    packets with different routes are still both injectable, but an identical re-flood isn't.
 *  - [shouldBridgeOut] — outbound channel datagrams, keyed by Sidepath datagram id (for future
 *    channel-out parity with Go `shouldBridgeOut`).
 */
class BridgeDedup(private val ttlMs: Long = DEFAULT_TTL_MS) {
    private val seen = HashMap<String, Long>()        // inbound content digest -> last forward
    private val seenRawOut = HashMap<String, Long>()  // outbound raw-bytes hash -> last inject
    private val seenOut = HashMap<String, Long>()     // outbound datagram id -> last bridge

    /** Inbound forward gate. ADVERTs always pass; others dedup on the route-independent content hash. */
    @Synchronized
    fun shouldForwardPacket(raw: ByteArray, isAdvert: Boolean): Boolean {
        if (isAdvert) return true
        val digest = MeshCoreCodec.computeContentHash(raw) ?: sha256Hex(raw)
        return firstWithin(seen, digest)
    }

    /** Outbound inject gate, keyed on exact wire bytes. */
    @Synchronized
    fun shouldInjectRaw(raw: ByteArray): Boolean = firstWithin(seenRawOut, sha256Hex(raw))

    /** Outbound channel-datagram gate, keyed on the Sidepath datagram id hex. */
    @Synchronized
    fun shouldBridgeOut(datagramIdHex: String): Boolean = firstWithin(seenOut, datagramIdHex)

    private fun firstWithin(map: HashMap<String, Long>, key: String): Boolean {
        val now = System.currentTimeMillis()
        val it = map.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > ttlMs) it.remove()
        val last = map[key]
        if (last != null && now - last <= ttlMs) return false
        map[key] = now
        return true
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L
    }
}

private fun sha256Hex(b: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
