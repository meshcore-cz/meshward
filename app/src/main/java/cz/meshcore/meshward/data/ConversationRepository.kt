package cz.meshcore.meshward.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

/**
 * Paged access to a single conversation's messages, plus its mention sources and message CRUD.
 * Backed by [MessageDao] so the UI loads only the recent page and lazily fetches older history.
 * Callers apply `cachedIn(scope)` on the returned flows (the repository has no coroutine scope).
 */
class ConversationRepository(private val dao: MessageDao) {

    /** Newest-first paged history for [peerHex]. */
    fun messages(peerHex: String): Flow<PagingData<Message>> =
        Pager(PAGING_CONFIG) { dao.pagingSource(peerHex) }.flow

    /** Newest-first paged history for [peerHex], filtered to messages containing [query]. */
    fun search(peerHex: String, query: String): Flow<PagingData<Message>> =
        Pager(PAGING_CONFIG) { dao.searchPagingSource(peerHex, query) }.flow

    /** Distinct named senders seen in this conversation, for @-mention autocomplete/resolution. */
    fun distinctSenders(peerHex: String): Flow<List<SenderRef>> = dao.distinctSenders(peerHex)

    /** Live unread-incoming count for a conversation. */
    fun unreadCount(peerHex: String): Flow<Int> = dao.unreadCount(peerHex)

    suspend fun insert(msg: Message): Long = dao.insertMessage(msg)
    suspend fun messageById(id: String): Message? = dao.messageById(id)
    suspend fun updateDelivery(id: String, status: Int, route: String) =
        dao.updateDelivery(id, status, route)
    suspend fun updateDeliveryWithAck(id: String, status: Int, route: String, ackPacketHex: String, ackTimestampMs: Long) =
        dao.updateDeliveryWithAck(id, status, route, ackPacketHex, ackTimestampMs)
    suspend fun markBridgedToMeshCore(id: String, bridgeHex: String) =
        dao.markBridgedToMeshCore(id, bridgeHex)
    suspend fun markRead(peerHex: String) = dao.markRead(peerHex)
    suspend fun deleteMessagesFor(peerHex: String) = dao.deleteMessagesFor(peerHex)

    private companion object {
        // Placeholders off: the conversation UI peeks neighbors for grouping and can't show gaps.
        val PAGING_CONFIG = PagingConfig(pageSize = 40, enablePlaceholders = false)
    }
}
