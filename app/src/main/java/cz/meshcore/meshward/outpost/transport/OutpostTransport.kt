package cz.meshcore.meshward.outpost.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * A way Outpost frames can travel between devices. The protocol is transport-independent (README
 * §18): the same canonical signed bytes may arrive over MeshCore, Sidepath, or any future carrier,
 * and are verified identically. Each transport only moves bytes and reports whether a peer is
 * currently reachable; it never inspects or trusts payloads.
 *
 * Concrete adapters ([SidepathOutpostTransport], [MeshCoreOutpostTransport]) bind these calls to the
 * real radios. Keeping them behind this interface is what lets the repository and UI stay free of
 * transport detail and lets the same board run over one carrier, the other, or both.
 */
interface OutpostTransport {
    /** Stable id recorded on stored objects as their source transport. */
    val id: String

    /** Human label for sync/status UI ("Sidepath", "MeshCore"). */
    val displayName: String

    /** Whether at least one peer is currently reachable on this transport (drives sync availability). */
    val reachable: StateFlow<Boolean>

    /** Broadcasts a freshly published object on its board channel (§14). Best-effort, fire-and-forget. */
    fun publish(boardId: ByteArray, signed: ByteArray)

    /**
     * Asks reachable peers for recent history of a board (§16 over MeshCore / §19 over Sidepath).
     * The transport drives the bounded request/inventory/object exchange and feeds recovered bytes
     * back through [OutpostInbound]; the repository verifies and stores them.
     */
    fun requestSync(boardId: ByteArray, sinceSeconds: Long, maxObjects: Int)

    /** Wires the sink that receives raw object bytes this transport pulls off the air. */
    fun setInbound(inbound: OutpostInbound)
}

/** Sink for raw object bytes arriving from any transport. Implemented by the repository. */
fun interface OutpostInbound {
    fun onObjectBytes(signed: ByteArray, sourceTransportId: String)
}
