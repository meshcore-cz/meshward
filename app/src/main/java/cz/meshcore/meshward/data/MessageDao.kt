package cz.meshcore.meshward.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A distinct (name, node-id) pair seen in a channel — powers @-mention autocomplete/resolution. */
data class SenderRef(
    val senderName: String,
    val senderHex: String,
)

/**
 * One row per conversation for the Chats list: the latest message's preview fields plus the
 * unread count, computed in SQL so the UI never loads the whole message table.
 */
data class ChatSummaryRow(
    val peerHex: String,
    val lastText: String,
    val lastSender: String,
    val lastTimestampMs: Long,
    val unread: Int,
)

/** Messages: paged history, search, mention sources, the Chats-list aggregate, and message CRUD. */
@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(msg: Message): Long

    /** Newest-first page of a conversation, for a reverse-laid-out paged list. */
    @Query("SELECT * FROM messages WHERE peerHex = :peer ORDER BY timestampMs DESC")
    fun pagingSource(peer: String): PagingSource<Int, Message>

    /** Newest-first page of a conversation filtered to messages containing [q] (in-chat search). */
    @Query(
        "SELECT * FROM messages WHERE peerHex = :peer AND text LIKE '%' || :q || '%' " +
            "ORDER BY timestampMs DESC"
    )
    fun searchPagingSource(peer: String, q: String): PagingSource<Int, Message>

    /** Distinct named senders seen in a channel, for @-mention autocomplete/resolution. */
    @Query(
        "SELECT DISTINCT senderName, senderHex FROM messages " +
            "WHERE peerHex = :peer AND senderName != '' AND senderHex != ''"
    )
    fun distinctSenders(peer: String): Flow<List<SenderRef>>

    /**
     * One summary row per conversation (DM or channel): the latest message's preview + unread count.
     * The inner MAX(timestampMs) join picks each conversation's newest row; the outer GROUP BY guards
     * against two rows sharing that timestamp yielding duplicate conversation rows.
     */
    @Query(
        "SELECT m.peerHex AS peerHex, m.text AS lastText, m.senderName AS lastSender, " +
            "m.timestampMs AS lastTimestampMs, " +
            "(SELECT COUNT(*) FROM messages u " +
            "   WHERE u.peerHex = m.peerHex AND u.incoming = 1 AND u.read = 0) AS unread " +
            "FROM messages m " +
            "JOIN (SELECT peerHex, MAX(timestampMs) AS mx FROM messages GROUP BY peerHex) t " +
            "  ON m.peerHex = t.peerHex AND m.timestampMs = t.mx " +
            "GROUP BY m.peerHex"
    )
    fun chatSummaries(): Flow<List<ChatSummaryRow>>

    /** Live count of unread incoming messages in a conversation (for the jump-to-newest badge). */
    @Query("SELECT COUNT(*) FROM messages WHERE peerHex = :peer AND incoming = 1 AND read = 0")
    fun unreadCount(peer: String): Flow<Int>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun messageById(id: String): Message?

    @Query("UPDATE messages SET status = :status, routeHex = :route WHERE id = :id")
    suspend fun updateDelivery(id: String, status: Int, route: String)

    /** Mark a DM delivered and persist the ACK packet + receipt time for the round-trip detail. */
    @Query(
        "UPDATE messages SET status = :status, routeHex = :route, " +
            "ackPacketHex = :ackPacketHex, ackTimestampMs = :ackTimestampMs WHERE id = :id"
    )
    suspend fun updateDeliveryWithAck(
        id: String,
        status: Int,
        route: String,
        ackPacketHex: String,
        ackTimestampMs: Long,
    )

    @Query("UPDATE messages SET bridgedToMeshCore = 1, bridgedByHex = :bridgeHex WHERE id = :id")
    suspend fun markBridgedToMeshCore(id: String, bridgeHex: String)

    @Query("UPDATE messages SET read = 1 WHERE peerHex = :peer AND incoming = 1 AND read = 0")
    suspend fun markRead(peer: String)

    @Query("DELETE FROM messages WHERE peerHex = :peer")
    suspend fun deleteMessagesFor(peer: String)
}
