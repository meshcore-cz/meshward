package cz.meshcore.meshward.outpost

import cz.meshcore.meshward.outpost.protocol.CloseReason
import cz.meshcore.meshward.outpost.protocol.ExchangeCategory
import cz.meshcore.meshward.outpost.protocol.ExchangePayload
import cz.meshcore.meshward.outpost.protocol.OutpostCrypto
import cz.meshcore.meshward.outpost.protocol.OutpostObject
import cz.meshcore.meshward.outpost.protocol.OutpostObjectBuilder
import cz.meshcore.meshward.outpost.protocol.OutpostObjectType
import cz.meshcore.meshward.outpost.protocol.OutpostProfile
import cz.meshcore.meshward.outpost.protocol.OutpostTtl
import cz.meshcore.meshward.outpost.storage.OutpostBoardEntity
import cz.meshcore.meshward.outpost.storage.OutpostDao
import cz.meshcore.meshward.outpost.storage.OutpostIdentityEntity
import cz.meshcore.meshward.outpost.storage.OutpostListingEntity
import cz.meshcore.meshward.outpost.storage.OutpostObjectEntity
import cz.meshcore.meshward.outpost.storage.OutpostVerification
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

/**
 * The Outpost domain layer: it ingests, verifies, stores and selects signed objects, and exposes
 * board / post state to the UI. It owns no transport and no UI — transports hand it raw bytes
 * through [ingest]; the UI reads its flows and calls [publishExchange] / [closeListing].
 *
 * The verification pipeline follows SPECIFICATION §12: decode and limit-check (in
 * [OutpostObject.decode]), resolve the author reference to exactly one full key (§6.3 / §18), verify
 * the signature, then store with a local state. Active-listing selection follows §13.1.
 */
class OutpostRepository(
    private val dao: OutpostDao,
    private val resolver: OutpostIdentityResolver,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    // ---- boards --------------------------------------------------------------

    /** Subscribed boards with live active / unresolved counts. */
    fun boards(): Flow<List<OutpostBoard>> =
        combine(dao.boards(), dao.identities()) { boards, _ -> boards }
            .map { list -> list.map { toBoard(it) } }

    private suspend fun toBoard(b: OutpostBoardEntity): OutpostBoard {
        val objects = dao.objectIdsForBoard(b.boardId) // cheap count proxy
        val verified = dao.verifiedCountForBoard(b.boardId)
        // Unresolved = how many stored objects are still pending an author key.
        val pending = dao.pendingObjects().count { it.boardId == b.boardId }
        return OutpostBoard(
            boardId = b.boardId, channelPskHex = b.channelPskHex, name = b.name,
            activeCount = verified.coerceAtMost(objects.size), unresolvedCount = pending, lastSyncMs = b.lastSyncMs,
        )
    }

    /** Subscribe to (follow) a board derived from a channel key. Idempotent. */
    suspend fun subscribeBoard(channelKey: ByteArray, name: String): String {
        val boardId = OutpostCrypto.boardId(channelKey).hex()
        if (dao.board(boardId) == null) {
            dao.upsertBoard(
                OutpostBoardEntity(
                    boardId = boardId, channelPskHex = channelKey.hex(), name = name,
                    subscribedAtMs = nowMs(),
                ),
            )
        }
        return boardId
    }

    suspend fun unsubscribeBoard(boardId: String) = dao.deleteBoard(boardId)

    suspend fun board(boardId: String): OutpostBoardEntity? = dao.board(boardId)

    suspend fun markSynced(boardId: String) = dao.markSynced(boardId, nowMs())

    // ---- listings (the denormalized, ready-to-render store) ------------------

    /**
     * A board's listing rows, freshest first, straight from [outpost_listings] — no per-read
     * decoding or identity resolution (that happened once at ingest). The UI maps each row to an
     * [OutpostPost], resolving only the author's display name from its warm contact index and the
     * EXPIRED state from the current clock (both cheap), via [postFrom].
     */
    fun listings(boardId: String): Flow<List<OutpostListingEntity>> = dao.listings(boardId)

    /**
     * Maps a stored listing row to the UI post model. [authorName] / [authorIsContact] come from the
     * caller's live identity index (so renames reflect without rewriting rows); the EXPIRED state is
     * derived from [now] against the signed expiry.
     */
    fun postFrom(
        row: OutpostListingEntity,
        authorName: String,
        authorIsContact: Boolean,
        now: Long = nowSeconds(),
    ): OutpostPost {
        val status = when {
            !row.verified -> OutpostPostStatus.UNRESOLVED
            row.closed -> OutpostPostStatus.UNAVAILABLE
            now >= row.expiresAt -> OutpostPostStatus.EXPIRED
            else -> OutpostPostStatus.VERIFIED
        }
        return OutpostPost(
            objectIdHex = row.headObjectIdHex,
            listingKey = row.listingKey,
            boardId = row.boardId,
            category = ExchangeCategory.fromCode(row.categoryCode),
            description = row.description,
            priceLabel = row.priceLabel,
            createdAt = row.createdAt,
            expiresAt = row.expiresAt,
            status = status,
            closeReason = CloseReason.fromCode(row.closeReasonCode),
            conflicted = row.conflicted,
            authorRefHex = row.authorRefHex,
            authorPubKeyHex = row.authorPubKeyHex.ifBlank { null },
            authorName = authorName,
            authorIsContact = authorIsContact,
            isMine = row.isMine,
            sourceTransport = row.sourceTransport,
        )
    }

    /**
     * Rebuilds the denormalized row for one listing key from its stored objects (§13.1): the greatest
     * revision whose signature has ever verified wins; a CLOSE makes it unavailable; a listing with
     * only pending objects surfaces as unresolved (§18); an INVALID-only listing is dropped (§20).
     * Called whenever an object for the listing is stored or its author becomes known.
     */
    private suspend fun recomputeListing(listingKey: String) {
        val revs = dao.revisionsForListing(listingKey)
        val signedHead = revs.filter {
            val v = OutpostVerification.fromValue(it.verification)
            v == OutpostVerification.VERIFIED || v == OutpostVerification.EXPIRED
        }.maxByOrNull { it.revision }
        val entity = signedHead ?: revs.filter {
            OutpostVerification.fromValue(it.verification) == OutpostVerification.PENDING
        }.maxByOrNull { it.revision }
        if (entity == null) { dao.deleteListing(listingKey); return }

        val obj = OutpostObject.decode(entity.signedHex.hexToBytesLocal()) ?: run { dao.deleteListing(listingKey); return }
        val author = resolver.resolved(obj.authorRef)
        val selfPub = resolver.selfPublicKey()?.hex()
        val closed = obj.type == OutpostObjectType.CLOSE
        val closeReason = if (closed) obj.close()?.reason ?: CloseReason.CLOSED else null
        val ex: ExchangePayload? = obj.exchange()

        dao.upsertListing(
            OutpostListingEntity(
                listingKey = listingKey,
                boardId = obj.boardId.hex(),
                headObjectIdHex = entity.objectIdHex,
                revision = obj.revision,
                authorRefHex = obj.authorRef.hex(),
                authorPubKeyHex = author?.pubKeyHex.orEmpty(),
                isMine = author?.isSelf == true || (selfPub != null && author?.pubKeyHex == selfPub),
                verified = signedHead != null,
                closed = closed,
                closeReasonCode = closeReason?.code ?: -1,
                conflicted = revs.any { it.conflicted },
                categoryCode = ex?.category?.code ?: -1,
                currency = ex?.currency.orEmpty(),
                price = ex?.price ?: 0L,
                description = ex?.description ?: closeReason?.let { "Listing ${it.label.lowercase()}" } ?: "",
                priceLabel = ex?.let { priceLabel(it) } ?: "",
                createdAt = obj.createdAt,
                expiresAt = obj.expiresAt,
                sourceTransport = entity.sourceTransport,
            ),
        )
    }

    /** Backfills/repairs every listing row from the stored objects (e.g. after a schema upgrade). */
    suspend fun rebuildAllListings() {
        for (key in dao.allListingKeys()) recomputeListing(key)
    }

    // ---- ingest --------------------------------------------------------------

    /** Canonical bytes of the newest verified objects for a board — used to answer a sync request. */
    suspend fun objectsForSync(boardId: String, max: Int): List<ByteArray> =
        dao.newestVerifiedForBoard(boardId, max).map { it.signedHex.hexToBytesLocal() }

    /** Transport entry point: validate, verify and store raw object bytes from a peer (§12). */
    suspend fun ingest(signed: ByteArray, sourceTransportId: String): Boolean {
        val obj = OutpostObject.decode(signed) ?: return false
        return store(obj, sourceTransportId)
    }

    private suspend fun store(obj: OutpostObject, sourceTransportId: String): Boolean {
        val objectId = OutpostCrypto.objectId(obj.signedBytes).hex()
        if (dao.objectById(objectId) != null) return true // dedup (§14)

        val candidates = candidateKeysFor(obj.authorRef)
        val verification = when {
            candidates.isEmpty() -> OutpostVerification.PENDING            // §18 unknown author
            candidates.size > 1 -> OutpostVerification.INVALID             // §6.3 ambiguous reference
            !OutpostCrypto.verify(obj, candidates.first()) -> OutpostVerification.INVALID
            obj.isExpiredAt(nowSeconds()) && obj.type != OutpostObjectType.CLOSE -> OutpostVerification.EXPIRED
            else -> OutpostVerification.VERIFIED
        }

        // Cache the key that verified this object so the author stays resolvable across restarts (§18).
        if (verification == OutpostVerification.VERIFIED) cacheIdentity(candidates.first())

        val entity = OutpostObjectEntity(
            objectIdHex = objectId,
            fullHashHex = OutpostCrypto.fullDigest(obj.signedBytes).hex(),
            boardId = obj.boardId.hex(),
            authorRef = obj.authorRef.hex(),
            listingKey = obj.listingKey,
            listingId = obj.listingId,
            revision = obj.revision,
            objectType = obj.type.code,
            profile = obj.profile.code,
            createdAt = obj.createdAt,
            expiresAt = obj.expiresAt,
            verification = verification.value,
            receivedAtMs = nowMs(),
            sourceTransport = sourceTransportId,
            signedHex = obj.signedBytes.hex(),
        )
        dao.insertObject(entity)
        markConflicts(obj.listingKey)
        recomputeListing(obj.listingKey) // refresh the denormalized row this listing renders from
        return true
    }

    /** Same-revision conflict detection (§13.2): flag every object sharing a listing key + revision. */
    private suspend fun markConflicts(listingKey: String) {
        val revs = dao.revisionsForListing(listingKey)
            .filter { OutpostVerification.fromValue(it.verification) != OutpostVerification.INVALID }
        val dupRevisions = revs.groupBy { it.revision }.filterValues { it.size > 1 }.keys
        revs.forEach { dao.setConflicted(it.objectIdHex, it.revision in dupRevisions) }
    }

    /** Re-checks pending objects after a new identity is learned; promotes those that now verify (§18). */
    suspend fun retryPending() {
        val touched = HashSet<String>()
        for (e in dao.pendingObjects()) {
            val obj = OutpostObject.decode(e.signedHex.hexToBytesLocal()) ?: continue
            val candidates = candidateKeysFor(obj.authorRef)
            val newState = when {
                candidates.isEmpty() -> continue
                candidates.size > 1 -> OutpostVerification.INVALID
                !OutpostCrypto.verify(obj, candidates.first()) -> OutpostVerification.INVALID
                obj.isExpiredAt(nowSeconds()) && obj.type != OutpostObjectType.CLOSE -> OutpostVerification.EXPIRED
                else -> { cacheIdentity(candidates.first()); OutpostVerification.VERIFIED }
            }
            dao.setVerification(e.objectIdHex, newState.value)
            touched += e.listingKey
        }
        // A newly-resolved author flips its listings from unresolved to verified — rebuild their rows.
        for (key in touched) recomputeListing(key)
    }

    /** Periodic maintenance: hide newly-expired objects and prune past the retention window (§13.3). */
    suspend fun runExpiryMaintenance() {
        val now = nowSeconds()
        dao.expireOldObjects(now)
        // Keep tombstones 30 days (§13.3); drop other expired objects a day after expiry.
        dao.pruneExpired(tombstoneCutoff = now - OutpostTtl.D30.seconds, expiredCutoff = now - OutpostTtl.D1.seconds)
        // A pruned listing (all its objects gone) must drop its denormalized row too.
        dao.deleteOrphanListings()
    }

    private suspend fun candidateKeysFor(authorRef: ByteArray): List<ByteArray> {
        val fromResolver = resolver.candidateKeys(authorRef)
        val fromCache = dao.identitiesForRef(authorRef.hex()).map { it.pubKeyHex.hexToBytesLocal() }
        // Distinct full keys whose prefix actually matches the reference.
        return (fromResolver + fromCache)
            .filter { it.size == 32 && OutpostCrypto.authorRef(it).contentEquals(authorRef) }
            .distinctBy { it.hex() }
    }

    private suspend fun cacheIdentity(pubKey: ByteArray) {
        val name = resolver.resolved(OutpostCrypto.authorRef(pubKey))?.name ?: ""
        dao.upsertIdentity(
            OutpostIdentityEntity(
                pubKeyHex = pubKey.hex(), authorRef = OutpostCrypto.authorRef(pubKey).hex(),
                displayName = name, updatedAtMs = nowMs(),
            ),
        )
    }

    // ---- publishing ----------------------------------------------------------

    /**
     * Signs and stores a new Exchange listing on a board, returning the canonical bytes for transport
     * (or null if this client can't sign, §6.1). Storing locally before transmit is mandatory (§14).
     */
    suspend fun publishExchange(
        boardId: String,
        category: ExchangeCategory,
        description: String,
        price: Long,
        currency: String,
        ttl: OutpostTtl,
    ): ByteArray? {
        val seed = resolver.selfSeed() ?: return null
        val pub = resolver.selfPublicKey() ?: return null
        val board = dao.board(boardId) ?: return null
        val payload = ExchangePayload(category, currency, price, description).encode()
        val builder = OutpostObjectBuilder(
            type = OutpostObjectType.CREATE,
            profile = OutpostProfile.EXCHANGE,
            boardId = board.boardId.hexToBytesLocal(),
            authorRef = OutpostCrypto.authorRef(pub),
            listingId = newListingId(),
            revision = 0,
            createdAt = nowSeconds(),
            ttl = ttl,
            payload = payload,
        )
        val obj = OutpostCrypto.sign(builder, seed)
        store(obj, sourceTransportId = "local")
        return obj.signedBytes
    }

    /** Signs and stores a CLOSE for one of our own listings, returning bytes for transport (§10.3). */
    suspend fun closeListing(listingKey: String, reason: CloseReason, note: String): ByteArray? {
        val seed = resolver.selfSeed() ?: return null
        val pub = resolver.selfPublicKey() ?: return null
        val revs = dao.revisionsForListing(listingKey)
        val head = revs.maxByOrNull { it.revision } ?: return null
        val headObj = OutpostObject.decode(head.signedHex.hexToBytesLocal()) ?: return null
        if (!OutpostCrypto.authorRef(pub).contentEquals(headObj.authorRef)) return null // only the author (§10.2)
        val builder = OutpostObjectBuilder(
            type = OutpostObjectType.CLOSE,
            profile = headObj.profile,
            boardId = headObj.boardId,
            authorRef = headObj.authorRef,
            listingId = headObj.listingId,
            revision = (headObj.revision + 1).coerceAtMost(255),
            createdAt = nowSeconds(),
            ttl = OutpostTtl.D30,
            payload = cz.meshcore.meshward.outpost.protocol.ClosePayload(reason, note).encode(),
        )
        val obj = OutpostCrypto.sign(builder, seed)
        store(obj, sourceTransportId = "local")
        return obj.signedBytes
    }

    private fun newListingId(): Long {
        val b = ByteArray(4).also { SecureRandom().nextBytes(it) }
        var v = 0L
        for (i in 0 until 4) v = v or ((b[i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun priceLabel(ex: ExchangePayload): String = when {
        ex.category == ExchangeCategory.FREE -> "Free"
        ex.price <= 0L -> ""
        ex.currency.isBlank() -> ex.price.toString()
        else -> "${ex.price} ${ex.currency}"
    }

    private fun shortRef(authorRef: ByteArray): String = authorRef.hex().take(8)
}

// Local hex helpers (the app's public ones live in ChatViewModel.kt; duplicated tiny private copies
// here keep this domain file importable from unit tests without dragging in Android types).
private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
private fun String.hexToBytesLocal(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
