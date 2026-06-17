package cz.meshcore.meshward.outpost.transport

import cz.meshcore.meshward.outpost.OutpostRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Coordinates the Outpost repository with whatever transports are available, so the rest of the app
 * has one place to publish a post or trigger a sync and one place to learn whether any peer is
 * reachable. It hides which carrier actually moved the bytes (README §18, hybrid operation).
 */
class OutpostManager(
    private val scope: CoroutineScope,
    val repository: OutpostRepository,
    transports: List<OutpostTransport>,
    inbound: OutpostInbound,
) {
    private val transports = transports.toList()

    init {
        // Every transport feeds raw frames into the same dispatcher (object → verify-and-store,
        // sync frame → responder), tagged with the transport they arrived on.
        this.transports.forEach { it.setInbound(inbound) }
    }

    /** True when at least one transport currently has a reachable peer (drives sync/empty states). */
    val anyReachable: StateFlow<Boolean> =
        if (transports.isEmpty()) MutableStateFlow(false)
        else combine(transports.map { it.reachable }) { states -> states.any { it } }
            .stateIn(scope, SharingStarted.Eagerly, false)

    /** Per-transport reachability, for the (advanced) sync status detail. */
    val reachability: StateFlow<List<TransportReachability>> =
        if (transports.isEmpty()) MutableStateFlow(emptyList())
        else combine(transports.map { it.reachable }) { states ->
            transports.mapIndexed { i, t -> TransportReachability(t.id, t.displayName, states[i]) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Fan a freshly published object out across every transport (§14). */
    fun broadcast(boardId: ByteArray, signed: ByteArray) {
        transports.forEach { it.publish(boardId, signed) }
    }

    /** Ask peers on every transport for recent board history (§16 / §19). */
    fun requestSync(boardId: ByteArray, sinceSeconds: Long, maxObjects: Int) {
        transports.forEach { it.requestSync(boardId, sinceSeconds, maxObjects) }
        scope.launch { repository.markSynced(boardId.hex()) }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

data class TransportReachability(val id: String, val displayName: String, val reachable: Boolean)
