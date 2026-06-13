package cz.meshcore.meshward.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // ---- messages ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(msg: Message): Long

    @Query("SELECT * FROM messages ORDER BY timestampMs ASC")
    fun allMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE peerHex = :peer ORDER BY timestampMs ASC")
    fun messagesFor(peer: String): Flow<List<Message>>

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

    // ---- contacts ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(contact: Contact)

    @Query("SELECT * FROM contacts")
    fun contacts(): Flow<List<Contact>>

    @Query("SELECT * FROM contacts WHERE nodeHex = :nodeHex LIMIT 1")
    suspend fun contactByNode(nodeHex: String): Contact?

    /**
     * Refreshes a MeshCore contact's display name from its latest advert. No-op unless the contact
     * exists, came from MeshCore, and hasn't been manually renamed (nameIsCustom = 0), so a user's
     * Rename is never overwritten.
     */
    @Query(
        "UPDATE contacts SET localName = :name " +
            "WHERE nodeHex = :nodeHex AND isMeshCore = 1 AND nameIsCustom = 0 AND localName != :name"
    )
    suspend fun refreshMeshCoreName(nodeHex: String, name: String)

    @Query("DELETE FROM contacts WHERE nodeHex = :nodeHex")
    suspend fun deleteContact(nodeHex: String)

    // ---- channels ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: Channel)

    @Query("SELECT * FROM channels ORDER BY name")
    fun channels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE pskHex = :pskHex LIMIT 1")
    suspend fun channelByPsk(pskHex: String): Channel?

    @Query("SELECT * FROM channels WHERE hashByte = :hashByte")
    suspend fun channelsByHash(hashByte: Int): List<Channel>

    @Query("DELETE FROM channels WHERE pskHex = :pskHex")
    suspend fun deleteChannel(pskHex: String)

    // ---- reactions ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReaction(reaction: Reaction)

    @Query("SELECT * FROM reactions WHERE messageId = :messageId AND authorHex = :authorHex LIMIT 1")
    suspend fun reactionBy(messageId: String, authorHex: String): Reaction?

    @Query("DELETE FROM reactions WHERE messageId = :messageId AND authorHex = :authorHex")
    suspend fun deleteReaction(messageId: String, authorHex: String)

    @Query("SELECT * FROM reactions")
    fun allReactions(): Flow<List<Reaction>>

    /** Drops reactions on every message of a conversation (called when the chat is deleted). */
    @Query("DELETE FROM reactions WHERE messageId IN (SELECT id FROM messages WHERE peerHex = :peer)")
    suspend fun deleteReactionsForPeer(peer: String)

    // ---- echoes (persisted flood/MeshCore echoes of our own messages) ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEcho(echo: Echo)

    @Query("SELECT * FROM echoes ORDER BY timestampMs ASC")
    fun allEchoes(): Flow<List<Echo>>

    @Query("DELETE FROM echoes WHERE messageId IN (SELECT id FROM messages WHERE peerHex = :peer)")
    suspend fun deleteEchoesForPeer(peer: String)

    // ---- MeshCore heards (persisted distinct-path receptions of a bridged channel message) ----
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMeshCoreHeard(heard: MeshCoreHeard)

    @Query("SELECT * FROM meshcore_heards ORDER BY timestampMs ASC")
    fun allMeshCoreHeards(): Flow<List<MeshCoreHeard>>

    @Query("DELETE FROM meshcore_heards WHERE messageId IN (SELECT id FROM messages WHERE peerHex = :peer)")
    suspend fun deleteMeshCoreHeardsForPeer(peer: String)

    // ---- discovered contacts ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiscovered(contact: DiscoveredContact)

    @Query("SELECT * FROM discovered_contacts ORDER BY lastAdvertisedMs DESC")
    fun discoveredContacts(): Flow<List<DiscoveredContact>>

    @Query("SELECT * FROM discovered_contacts WHERE pubKeyHex = :pubKeyHex LIMIT 1")
    suspend fun discoveredByPubKey(pubKeyHex: String): DiscoveredContact?

    @Query("DELETE FROM discovered_contacts WHERE pubKeyHex = :pubKeyHex")
    suspend fun deleteDiscovered(pubKeyHex: String)

    @Query("DELETE FROM discovered_contacts")
    suspend fun clearDiscovered()

    /** Drops discovered contacts not heard from since [cutoffMs] (their TTL has elapsed). */
    @Query("DELETE FROM discovered_contacts WHERE lastAdvertisedMs < :cutoffMs")
    suspend fun pruneDiscovered(cutoffMs: Long)
}
