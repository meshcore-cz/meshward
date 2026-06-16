package cz.meshcore.meshward.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Discovered contacts (Explore tab): reads plus the ingestion/prune CRUD. */
@Dao
interface ExploreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiscovered(contact: DiscoveredContact)

    /** Bulk upsert — used by the analyzer roster sync to write the whole batch in one transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDiscoveredAll(contacts: List<DiscoveredContact>)

    /** Full discovered list (recency-ordered). A plain LazyColumn renders only the visible rows. */
    @Query("SELECT * FROM discovered_contacts ORDER BY lastAdvertisedMs DESC")
    fun discoveredContacts(): Flow<List<DiscoveredContact>>

    @Query("SELECT * FROM discovered_contacts WHERE pubKeyHex = :pubKeyHex LIMIT 1")
    suspend fun discoveredByPubKey(pubKeyHex: String): DiscoveredContact?

    /** Batch lookup of existing rows by public key, so a bulk sync can resolve them in one query. */
    @Query("SELECT * FROM discovered_contacts WHERE pubKeyHex IN (:pubKeyHexes)")
    suspend fun discoveredByPubKeys(pubKeyHexes: List<String>): List<DiscoveredContact>

    @Query("DELETE FROM discovered_contacts WHERE pubKeyHex = :pubKeyHex")
    suspend fun deleteDiscovered(pubKeyHex: String)

    @Query("DELETE FROM discovered_contacts")
    suspend fun clearDiscovered()

    /** Drops discovered contacts not heard from since [cutoffMs] (their TTL has elapsed). */
    @Query("DELETE FROM discovered_contacts WHERE lastAdvertisedMs < :cutoffMs")
    suspend fun pruneDiscovered(cutoffMs: Long)
}
