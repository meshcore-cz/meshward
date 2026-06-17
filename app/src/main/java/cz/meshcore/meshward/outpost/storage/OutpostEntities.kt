package cz.meshcore.meshward.outpost.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local verification state of a stored object (§12). Persisted as [value].
 *
 *  - [PENDING]  — structurally valid but the author's full key isn't known yet, so the signature
 *                 can't be checked. Held back from normal display and never served (§20).
 *  - [VERIFIED] — author resolved and signature valid; eligible for display and replication.
 *  - [INVALID]  — signature failed or the reference was ambiguous; kept only to suppress re-ingest.
 *  - [EXPIRED]  — verified but past its signed expiry; hidden from the active board but retained for
 *                 a while (tombstones especially, §13.3) so it doesn't reappear through sync.
 */
enum class OutpostVerification(val value: Int) {
    PENDING(0), VERIFIED(1), INVALID(2), EXPIRED(3);

    companion object {
        fun fromValue(v: Int?): OutpostVerification = entries.firstOrNull { it.value == v } ?: PENDING
    }
}

/**
 * One stored Outpost persistent object. [signedHex] is the canonical signed object bytes, stored
 * unchanged (§13) — every other column is an index over those bytes for search and the UI. Keyed by
 * [objectIdHex] (`object_id` = SHA-256(signed)[0:8]) so duplicate receptions collapse.
 */
@Entity(
    tableName = "outpost_objects",
    indices = [Index("boardId"), Index("listingKey"), Index("authorRef"), Index("verification")],
)
data class OutpostObjectEntity(
    @PrimaryKey val objectIdHex: String,
    val fullHashHex: String,
    val boardId: String,        // board_id hex
    val authorRef: String,      // author_ref hex (10 bytes)
    val listingKey: String,     // board:author:listing — groups a listing's revisions (§13.1)
    val listingId: Long,
    val revision: Int,
    val objectType: Int,        // OutpostObjectType.code
    val profile: Int,           // OutpostProfile.code
    val createdAt: Long,        // unix seconds
    val expiresAt: Long,        // unix seconds
    val verification: Int,      // OutpostVerification.value
    val conflicted: Boolean = false,
    val receivedAtMs: Long,
    val sourceTransport: String = "",
    val signedHex: String,
)

/**
 * One *listing* — the denormalized, ready-to-render head of a listing key (§13.1). This is the
 * Outpost analogue of the `messages` table: the expensive work (group a listing's revisions, decode
 * the head's payload, resolve the author) is done once at ingest and stored here, so the UI reads a
 * single ordered query per board instead of re-deriving every object on every read.
 *
 * One row per [listingKey]; rebuilt whenever an object for that listing is stored or its author
 * becomes known. The author's *display name* is intentionally NOT stored — it is resolved live from
 * the contacts/topology index at render (so renames reflect immediately), exactly like chat senders.
 */
@Entity(tableName = "outpost_listings", indices = [Index("boardId"), Index("createdAt")])
data class OutpostListingEntity(
    @PrimaryKey val listingKey: String,
    val boardId: String,
    val headObjectIdHex: String,
    val revision: Int,
    val authorRefHex: String,
    val authorPubKeyHex: String,    // "" until the author is resolved (then the post is verified)
    val isMine: Boolean,
    val verified: Boolean,          // head signature has verified (false ⇒ still unresolved/pending)
    val closed: Boolean,            // head is a CLOSE ⇒ listing unavailable
    val closeReasonCode: Int,       // CloseReason.code, or -1
    val conflicted: Boolean,
    val categoryCode: Int,          // ExchangeCategory.code, or -1
    val currency: String,
    val price: Long,
    val description: String,
    val priceLabel: String,
    val createdAt: Long,            // unix seconds (head revision's time) — the sort key
    val expiresAt: Long,            // unix seconds; EXPIRED is computed from this at render
    val sourceTransport: String,
)

/**
 * A board this device follows. A board is bound to a MeshCore-compatible channel: [channelPskHex] is
 * the raw channel key the board id is derived from (§7), so the same key can later interoperate over
 * MeshCore even when the board is used Sidepath-only. [boardId] is the cached derivation.
 */
@Entity(tableName = "outpost_boards")
data class OutpostBoardEntity(
    @PrimaryKey val boardId: String,    // board_id hex
    val channelPskHex: String,
    val name: String,
    val subscribedAtMs: Long,
    val lastSyncMs: Long = 0L,
)

/**
 * A cached author identity used to resolve a 10-byte `author_ref` to a full 32-byte key (§18). We
 * also resolve against the live contact / topology tables, but caching the keys that have actually
 * verified an object keeps pending posts resolvable across restarts. Keyed by the full key hex so a
 * reference that (improbably) collides to two keys yields two rows and is treated as ambiguous.
 */
@Entity(tableName = "outpost_identities", indices = [Index("authorRef")])
data class OutpostIdentityEntity(
    @PrimaryKey val pubKeyHex: String,
    val authorRef: String,      // pubKey[0:10] hex
    val displayName: String = "",
    val updatedAtMs: Long,
)
