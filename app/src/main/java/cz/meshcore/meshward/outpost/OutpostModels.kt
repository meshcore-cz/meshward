package cz.meshcore.meshward.outpost

import cz.meshcore.meshward.outpost.protocol.CloseReason
import cz.meshcore.meshward.outpost.protocol.ExchangeCategory

/**
 * How a post presents to the user. Deliberately a community-service vocabulary, not a protocol one —
 * the four states the task calls for, plus a rare conflict flag carried alongside.
 *
 *  - [VERIFIED]    — signed by a known author and currently active.
 *  - [UNRESOLVED]  — author identity not yet known, so the signature can't be checked (§6); shown as
 *                    "awaiting verification" and never trusted as valid content.
 *  - [EXPIRED]     — past its author-set expiry (§9.5); kept visible but dimmed.
 *  - [UNAVAILABLE] — the author closed it (sold / fulfilled / withdrawn, §10.3).
 */
enum class OutpostPostStatus { VERIFIED, UNRESOLVED, EXPIRED, UNAVAILABLE }

/** A board's display state, derived from its subscription + stored objects. */
data class OutpostBoard(
    val boardId: String,
    val channelPskHex: String,
    val name: String,
    val activeCount: Int,
    val unresolvedCount: Int,
    val lastSyncMs: Long,
)

/** One active listing as shown on a board — the protocol/transport detail already resolved away. */
data class OutpostPost(
    val objectIdHex: String,
    val listingKey: String,
    val boardId: String,
    val category: ExchangeCategory?,
    val description: String,
    val priceLabel: String,
    val createdAt: Long,
    val expiresAt: Long,
    val status: OutpostPostStatus,
    val closeReason: CloseReason?,
    val conflicted: Boolean,
    val authorRefHex: String,
    val authorPubKeyHex: String?,   // resolved full 32-byte key, when known
    val authorName: String,
    val authorIsContact: Boolean,   // true when we can open a direct conversation
    val isMine: Boolean,
    val sourceTransport: String,
) {
    /** A short, single-line title for the card (first line of the description, trimmed). */
    val title: String
        get() = description.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { "(no description)" }
}

/** A single resolved author identity for display (§18). Null result means unknown or ambiguous. */
data class ResolvedAuthor(
    val pubKeyHex: String,
    val name: String,
    val isContact: Boolean,
    val isSelf: Boolean,
)

/**
 * Supplies author identities to the Outpost layer without coupling it to the chat database. The
 * ViewModel implements this over its existing contacts / topology / discovered-node tables plus the
 * Outpost identity cache, so any node Meshward already knows is immediately verifiable (§18).
 */
interface OutpostIdentityResolver {
    /** Our own 32-byte public key, or null if no identity is loaded. */
    fun selfPublicKey(): ByteArray?

    /** Our own 32-byte signing seed, or null if this client can't sign (read-only, §6.1). */
    fun selfSeed(): ByteArray?

    /** All distinct full 32-byte keys currently known whose first 10 bytes equal [authorRef] (§6.3). */
    suspend fun candidateKeys(authorRef: ByteArray): List<ByteArray>

    /** The single best identity for display, or null when unknown/ambiguous. */
    suspend fun resolved(authorRef: ByteArray): ResolvedAuthor?
}
