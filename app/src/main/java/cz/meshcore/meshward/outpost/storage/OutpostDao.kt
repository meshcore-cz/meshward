package cz.meshcore.meshward.outpost.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Room access for the Outpost object store, board subscriptions, and cached author identities. */
@Dao
interface OutpostDao {

    // ---- objects -------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertObject(obj: OutpostObjectEntity): Long

    @Upsert
    suspend fun upsertObject(obj: OutpostObjectEntity)

    @Query("SELECT * FROM outpost_objects WHERE objectIdHex = :objectIdHex")
    suspend fun objectById(objectIdHex: String): OutpostObjectEntity?

    @Query("SELECT objectIdHex FROM outpost_objects WHERE boardId = :boardId")
    suspend fun objectIdsForBoard(boardId: String): List<String>

    /** Every stored object for a board, newest first — the repository derives active listings from it. */
    @Query("SELECT * FROM outpost_objects WHERE boardId = :boardId ORDER BY createdAt DESC")
    fun objectsForBoard(boardId: String): Flow<List<OutpostObjectEntity>>

    @Query("SELECT * FROM outpost_objects WHERE listingKey = :listingKey ORDER BY revision DESC")
    suspend fun revisionsForListing(listingKey: String): List<OutpostObjectEntity>

    @Query("SELECT DISTINCT listingKey FROM outpost_objects")
    suspend fun allListingKeys(): List<String>

    /** Pending objects whose author wasn't known yet — retried whenever a new identity is learned. */
    @Query("SELECT * FROM outpost_objects WHERE verification = ${0 /* PENDING */}")
    suspend fun pendingObjects(): List<OutpostObjectEntity>

    @Query("SELECT * FROM outpost_objects WHERE verification = ${0 /* PENDING */} AND authorRef = :authorRef")
    suspend fun pendingObjectsForAuthor(authorRef: String): List<OutpostObjectEntity>

    @Query("UPDATE outpost_objects SET verification = :state WHERE objectIdHex = :objectIdHex")
    suspend fun setVerification(objectIdHex: String, state: Int)

    @Query("UPDATE outpost_objects SET conflicted = :conflicted WHERE objectIdHex = :objectIdHex")
    suspend fun setConflicted(objectIdHex: String, conflicted: Boolean)

    /** Mark verified-but-expired objects so the board hides them; tombstones are retained longer (§13.3). */
    @Query(
        "UPDATE outpost_objects SET verification = ${3 /* EXPIRED */} " +
            "WHERE verification = ${1 /* VERIFIED */} AND expiresAt <= :nowSeconds AND objectType != ${0x3 /* CLOSE */}",
    )
    suspend fun expireOldObjects(nowSeconds: Long)

    /** Drop expired non-CLOSE objects and CLOSE tombstones older than the retention cutoff (§13.3). */
    @Query(
        "DELETE FROM outpost_objects WHERE (objectType = ${0x3} AND expiresAt <= :tombstoneCutoff) " +
            "OR (objectType != ${0x3} AND verification = ${3 /* EXPIRED */} AND expiresAt <= :expiredCutoff)",
    )
    suspend fun pruneExpired(tombstoneCutoff: Long, expiredCutoff: Long)

    @Query("SELECT COUNT(*) FROM outpost_objects WHERE boardId = :boardId AND verification = ${1 /* VERIFIED */}")
    suspend fun verifiedCountForBoard(boardId: String): Int

    /** Newest verified objects for a board — the payload of a sync response (§16.4 / §20). */
    @Query(
        "SELECT * FROM outpost_objects WHERE boardId = :boardId AND verification = ${1 /* VERIFIED */} " +
            "ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun newestVerifiedForBoard(boardId: String, limit: Int): List<OutpostObjectEntity>

    // ---- listings (denormalized, one row per listing) ------------------------

    @Upsert
    suspend fun upsertListing(listing: OutpostListingEntity)

    @Query("DELETE FROM outpost_listings WHERE listingKey = :listingKey")
    suspend fun deleteListing(listingKey: String)

    /** The board's listings, freshest first — the UI renders these directly, no per-read derivation. */
    @Query("SELECT * FROM outpost_listings WHERE boardId = :boardId ORDER BY createdAt DESC")
    fun listings(boardId: String): Flow<List<OutpostListingEntity>>

    /** Drop listing rows whose every object has been pruned (so an emptied listing disappears). */
    @Query("DELETE FROM outpost_listings WHERE listingKey NOT IN (SELECT DISTINCT listingKey FROM outpost_objects)")
    suspend fun deleteOrphanListings()

    // ---- boards --------------------------------------------------------------

    @Upsert
    suspend fun upsertBoard(board: OutpostBoardEntity)

    @Query("SELECT * FROM outpost_boards ORDER BY subscribedAtMs ASC")
    fun boards(): Flow<List<OutpostBoardEntity>>

    @Query("SELECT * FROM outpost_boards WHERE boardId = :boardId")
    suspend fun board(boardId: String): OutpostBoardEntity?

    @Query("UPDATE outpost_boards SET lastSyncMs = :nowMs WHERE boardId = :boardId")
    suspend fun markSynced(boardId: String, nowMs: Long)

    @Query("DELETE FROM outpost_boards WHERE boardId = :boardId")
    suspend fun deleteBoard(boardId: String)

    // ---- identities ----------------------------------------------------------

    @Upsert
    suspend fun upsertIdentity(identity: OutpostIdentityEntity)

    @Query("SELECT * FROM outpost_identities WHERE authorRef = :authorRef")
    suspend fun identitiesForRef(authorRef: String): List<OutpostIdentityEntity>

    @Query("SELECT * FROM outpost_identities")
    fun identities(): Flow<List<OutpostIdentityEntity>>
}
