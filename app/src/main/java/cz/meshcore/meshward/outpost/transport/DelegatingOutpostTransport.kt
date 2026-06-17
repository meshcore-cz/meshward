package cz.meshcore.meshward.outpost.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * A transport whose reachability and send actions are supplied by the host (the ViewModel, where the
 * Sidepath service and MeshCore companion live). This keeps the radio plumbing at the integration
 * edge while the Outpost layer talks only to the [OutpostTransport] contract.
 *
 * [onPublish] should put the canonical object bytes on the board channel (§15.1 over MeshCore, §19
 * over Sidepath); [onSync] should drive the bounded history request for the board (§16 / §19).
 * Inbound bytes are pushed back in by the host calling [deliver] from its receive path.
 */
class DelegatingOutpostTransport(
    override val id: String,
    override val displayName: String,
    override val reachable: StateFlow<Boolean>,
    private val onPublish: (boardId: ByteArray, signed: ByteArray) -> Unit,
    private val onSync: (boardId: ByteArray, sinceSeconds: Long, maxObjects: Int) -> Unit,
) : OutpostTransport {

    private var inbound: OutpostInbound? = null

    override fun publish(boardId: ByteArray, signed: ByteArray) = onPublish(boardId, signed)

    override fun requestSync(boardId: ByteArray, sinceSeconds: Long, maxObjects: Int) =
        onSync(boardId, sinceSeconds, maxObjects)

    override fun setInbound(inbound: OutpostInbound) { this.inbound = inbound }

    /** Host receive path calls this when an Outpost frame arrives on this transport. */
    fun deliver(signed: ByteArray) { inbound?.onObjectBytes(signed, id) }
}
