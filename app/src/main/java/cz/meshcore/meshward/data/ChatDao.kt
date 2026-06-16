package cz.meshcore.meshward.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // Messages live in MessageDao; discovered contacts in ExploreDao.

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

    /**
     * Flags an existing contact as a MeshCore node. Used to repair contacts that were saved before
     * their MeshCore origin was known (e.g. added via a path that didn't set the flag), so outgoing
     * messages route over the bridge as a MeshCore TXT_MSG and incoming DMs get decrypted.
     */
    @Query("UPDATE contacts SET isMeshCore = 1 WHERE nodeHex = :nodeHex AND isMeshCore = 0")
    suspend fun markContactMeshCore(nodeHex: String)

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

    // ---- conversation notification prefs ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPref(pref: ConversationPref)

    @Query("SELECT * FROM conversation_prefs")
    fun prefs(): Flow<List<ConversationPref>>

    @Query("SELECT * FROM conversation_prefs WHERE peerHex = :peerHex LIMIT 1")
    suspend fun prefFor(peerHex: String): ConversationPref?

    @Query("DELETE FROM conversation_prefs WHERE peerHex = :peerHex")
    suspend fun deletePref(peerHex: String)

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

    // ---- MeshCore PATHs (latest returned path per contact) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeshCorePath(path: MeshCorePath)

    @Query("SELECT * FROM meshcore_paths WHERE nodeHex = :nodeHex LIMIT 1")
    fun meshCorePathForNode(nodeHex: String): Flow<MeshCorePath?>

    @Query("DELETE FROM meshcore_paths WHERE nodeHex = :nodeHex")
    suspend fun deleteMeshCorePathForNode(nodeHex: String)

    // ---- meshcore networks (user custom overrides; built-in defaults come from sidepath-protocol) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetwork(network: MeshNetwork)

    @Query("SELECT * FROM mesh_networks ORDER BY code")
    fun networks(): Flow<List<MeshNetwork>>

    @Query("DELETE FROM mesh_networks WHERE code = :code")
    suspend fun deleteNetwork(code: String)

    // ---- node announcements (latest ANNOUNCE/ADVERT per node, persisted for the profile) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnnouncement(announcement: NodeAnnouncement)

    @Query("SELECT * FROM node_announcements WHERE nodeHex = :nodeHex")
    fun announcementsForNode(nodeHex: String): Flow<List<NodeAnnouncement>>

    /** The current receipt time we have stored for this node/source, or null (to only upsert newer). */
    @Query("SELECT timestampMs FROM node_announcements WHERE nodeHex = :nodeHex AND source = :source LIMIT 1")
    suspend fun announcementTimestamp(nodeHex: String, source: String): Long?
}
