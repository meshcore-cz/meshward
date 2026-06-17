package cz.meshcore.meshward.outpost

import cz.meshcore.meshward.data.Channel
import cz.meshcore.meshward.data.Contact
import cz.meshcore.meshward.data.DiscoveredContact
import cz.meshcore.meshward.hexToBytes
import cz.meshcore.meshward.outpost.protocol.ExchangeCategory
import cz.meshcore.meshward.outpost.protocol.OutpostCrypto
import cz.meshcore.meshward.outpost.protocol.OutpostSync
import cz.meshcore.meshward.outpost.protocol.OutpostTtl
import cz.meshcore.meshward.outpost.storage.OutpostDao
import cz.meshcore.meshward.outpost.transport.DelegatingOutpostTransport
import cz.meshcore.meshward.outpost.transport.OutpostInbound
import cz.meshcore.meshward.outpost.transport.OutpostManager
import cz.meshcore.meshward.toHex
import cz.meshcore.sidepath.chat.ChatChannel
import cz.meshcore.sidepath.protocol.Identity
import cz.meshcore.sidepath.service.TopologyEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Integrates Outpost into Meshward: it builds the repository and transports, implements author
 * identity resolution over the app's existing contact / topology / discovered-node tables, and
 * exposes the flows the Outpost UI binds to. Construct one per active identity inside the ViewModel.
 *
 * Radio sending is delegated to [sidepathSend] (a flooded Sidepath OUTPOST datagram) and
 * [meshCoreSend] (a channel-encrypted MeshCore GRP_DATA group datagram), both supplied by the
 * ViewModel where the service lives. Inbound frames are routed here by the ViewModel via
 * [deliverFromSidepath] / [deliverFromMeshCore], then dispatched (object → store, sync → respond).
 */
class OutpostController(
    private val scope: CoroutineScope,
    private val dao: OutpostDao,
    private val contacts: StateFlow<List<Contact>>,
    private val topology: StateFlow<List<TopologyEntry>>,
    private val discovered: StateFlow<List<DiscoveredContact>>,
    private val channels: StateFlow<List<Channel>>,
    private val seedHex: StateFlow<String>,
    sidepathReachable: StateFlow<Boolean>,
    meshCoreReachable: StateFlow<Boolean>,
    /** Floods an Outpost frame over Sidepath as a native OUTPOST datagram. */
    private val sidepathSend: (frame: ByteArray) -> Unit,
    /** Sends an Outpost frame as a MeshCore GRP_DATA group datagram on the board's channel [psk]. */
    private val meshCoreSend: (psk: ByteArray, frame: ByteArray) -> Unit,
) {
    private val resolver = AppIdentityResolver()
    private val meshCoreReachableFlow = meshCoreReachable
    val repository = OutpostRepository(dao, resolver)

    private val sidepath = DelegatingOutpostTransport(
        id = "sidepath", displayName = "Sidepath", reachable = sidepathReachable,
        onPublish = { _, signed -> sidepathSend(signed) },
        onSync = { board, since, max -> sidepathSend(OutpostSync.encodeRequest(board, since, max)) },
    )
    // The MeshCore GRP_DATA send also floods over the local Sidepath mesh, so it only adds value when
    // MeshCore is actually active — otherwise the native Sidepath OUTPOST datagram already covers it.
    private val meshCore = DelegatingOutpostTransport(
        id = "meshcore", displayName = "MeshCore", reachable = meshCoreReachable,
        onPublish = { board, signed -> if (meshCoreReachableFlow.value) pskForBoard(board.toHex())?.let { meshCoreSend(it, signed) } },
        onSync = { board, since, max -> if (meshCoreReachableFlow.value) pskForBoard(board.toHex())?.let { meshCoreSend(it, OutpostSync.encodeRequest(board, since, max)) } },
    )

    // Object frames (0x11/0x12/0x13) verify and store; a sync-request frame (0x14) triggers a
    // bounded re-broadcast of our newest verified objects for the board (§16.4).
    private val inbound = OutpostInbound { frame, src -> scope.launch { dispatchInbound(frame, src) } }
    private val manager = OutpostManager(scope, repository, listOf(sidepath, meshCore), inbound)

    // Per-board cooldown so a sync request can't make us answer in a tight loop / storm (§16.2).
    private val lastSyncResponseMs = HashMap<String, Long>()

    val anyReachable: StateFlow<Boolean> get() = manager.anyReachable
    val reachability get() = manager.reachability

    /** Subscribed boards with live counts. */
    val boards: StateFlow<List<OutpostBoard>> =
        repository.boards().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The single MVP board: MeshCore's Public channel. Every user is auto-subscribed to it (see
     * init), so the Outpost tab always has exactly one board to show without any discovery step.
     */
    val publicBoard: StateFlow<OutpostBoard?> =
        boards.map { list -> list.firstOrNull { it.boardId == PUBLIC_BOARD_ID } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Author display index: full-key hex → (name, isContact), built once from the live contacts /
     * topology / discovered tables and kept warm. Resolving a listing's author name is then an O(1)
     * map lookup at render — and reacts to renames — instead of a table scan per post.
     */
    private val nameIndex: StateFlow<Map<String, AuthorInfo>> =
        combine(contacts, topology, discovered) { c, t, d -> buildNameIndex(c, t, d) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /**
     * The Public board's posts as a warm, eagerly-started StateFlow. Each post is now just a stored
     * listing row plus a cheap name lookup — no decoding or identity resolution at read time (that
     * happened once at ingest). Kept warm so the tab opens instantly on every visit. `null` means the
     * first emission hasn't arrived yet, so the screen can avoid flashing an empty state.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val publicPosts: StateFlow<List<OutpostPost>?> =
        combine(
            publicBoard.filterNotNull().flatMapLatest { repository.listings(it.boardId) },
            nameIndex,
        ) { rows, idx -> rows.map { postFrom(it, idx) }.sortedForDisplay() }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Joined channels not yet followed as a board — the "discover boards" surface. */
    val followableChannels: StateFlow<List<Channel>> =
        combine(channels, boards) { chans, brds ->
            val followed = brds.map { it.channelPskHex }.toSet()
            chans.filter { it.pskHex !in followed }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        // MVP: everyone is subscribed to the Public board by default, so the tab is never empty of
        // boards and there is nothing to "discover" or follow.
        scope.launch { runCatching { repository.subscribeBoard(ChatChannel.PUBLIC_SECRET, "Public") } }
        // When the set of known identities changes (a new contact, a fresh advert), re-check pending
        // posts so they verify as soon as their author becomes known (§18).
        scope.launch {
            combine(contacts, topology) { c, t -> c.size to t.size }
                .collect { repository.retryPending() }
        }
        // Backfill the denormalized listing rows from stored objects (e.g. after a schema upgrade),
        // then hide newly-expired objects and prune past the retention window (§13.3 / §14).
        scope.launch { repository.rebuildAllListings(); repository.runExpiryMaintenance() }
    }

    private data class AuthorInfo(val name: String, val isContact: Boolean)

    /** Builds the full-key → (name, isContact) index using the same priority as identity resolution. */
    private fun buildNameIndex(
        c: List<Contact>,
        t: List<TopologyEntry>,
        d: List<DiscoveredContact>,
    ): Map<String, AuthorInfo> {
        val m = HashMap<String, AuthorInfo>()
        d.forEach { if (it.pubKeyHex.length == 64 && it.name.isNotBlank()) m[it.pubKeyHex] = AuthorInfo(it.name, false) }
        t.forEach {
            if (it.publicKey.size == 32 && it.name.isNotBlank()) {
                val h = it.publicKey.toHex()
                m[h] = AuthorInfo(it.name, m[h]?.isContact == true)
            }
        }
        c.forEach {
            if (it.pubKeyHex.length == 64) {
                val name = it.localName.ifBlank { it.description }.ifBlank { m[it.pubKeyHex]?.name.orEmpty() }
                m[it.pubKeyHex] = AuthorInfo(name, true)
            }
        }
        return m
    }

    private fun postFrom(row: cz.meshcore.meshward.outpost.storage.OutpostListingEntity, idx: Map<String, AuthorInfo>): OutpostPost {
        val info = when {
            row.isMine -> AuthorInfo("You", false)
            row.authorPubKeyHex.isNotBlank() -> idx[row.authorPubKeyHex] ?: AuthorInfo(row.authorRefHex.take(8), false)
            else -> AuthorInfo(row.authorRefHex.take(8), false)
        }
        return repository.postFrom(row, info.name.ifBlank { row.authorRefHex.take(8) }, info.isContact)
    }

    /** Newest first, with closed/expired sinking below active posts of the same recency. */
    private fun List<OutpostPost>.sortedForDisplay(): List<OutpostPost> =
        sortedWith(compareByDescending<OutpostPost> { it.status == OutpostPostStatus.VERIFIED }
            .thenByDescending { it.createdAt })

    /** Follow a joined channel as an Outpost board. */
    fun followChannel(channel: Channel) = scope.launch {
        runCatching { repository.subscribeBoard(channel.pskHex.hexToBytes(), channel.name) }
    }

    fun unfollow(boardId: String) = scope.launch { repository.unsubscribeBoard(boardId) }

    /** Publish a new Exchange post and broadcast it on every reachable transport (§14). */
    fun publish(
        boardId: String,
        category: ExchangeCategory,
        description: String,
        price: Long,
        currency: String,
        ttl: OutpostTtl,
    ) = scope.launch {
        val signed = repository.publishExchange(boardId, category, description, price, currency, ttl) ?: return@launch
        manager.broadcast(boardId.hexToBytes(), signed)
    }

    fun close(listingKey: String, reason: cz.meshcore.meshward.outpost.protocol.CloseReason, note: String) = scope.launch {
        val signed = repository.closeListing(listingKey, reason, note) ?: return@launch
        val board = listingKey.substringBefore(":")
        manager.broadcast(board.hexToBytes(), signed)
    }

    /** Trigger a bounded history sync for a board (§16 / §19). */
    fun sync(boardId: String) = scope.launch {
        manager.requestSync(boardId.hexToBytes(), sinceSeconds = 0, maxObjects = 20)
    }

    /**
     * No-op whose only purpose is to force construction of this (otherwise lazily-created) controller
     * at app startup. Touching it starts the eager [publicPosts] / [nameIndex] flows deriving the
     * board in the background, so the tab opens instantly on first visit instead of warming cold then.
     */
    fun warmUp() = Unit

    fun runMaintenance() = scope.launch { repository.runExpiryMaintenance() }

    /** Inbound Outpost frame from the Sidepath receive path (native OUTPOST datagram). */
    fun deliverFromSidepath(frame: ByteArray) = sidepath.deliver(frame)

    /** Inbound Outpost frame from a MeshCore GRP_DATA group datagram. */
    fun deliverFromMeshCore(frame: ByteArray) = meshCore.deliver(frame)

    /** Can this identity sign (and therefore publish)? False for a read-only/keyless identity (§6.1). */
    fun canPublish(): Boolean = resolver.selfSeed() != null

    private fun pskForBoard(boardIdHex: String): ByteArray? =
        boards.value.firstOrNull { it.boardId == boardIdHex }?.channelPskHex
            ?.takeIf { it.length == 32 }?.runCatching { hexToBytes() }?.getOrNull()

    /** Routes an inbound frame: a persistent object is stored; a sync request is answered. */
    private suspend fun dispatchInbound(frame: ByteArray, src: String) {
        if (OutpostSync.isControlFrame(frame)) {
            respondToSync(frame, src)
        } else {
            repository.ingest(frame, src)
        }
    }

    /** Answers a peer's SYNC_REQUEST with our newest verified objects for that board (§16.4 / §20). */
    private suspend fun respondToSync(frame: ByteArray, src: String) {
        val req = OutpostSync.decodeRequest(frame) ?: return
        val boardIdHex = req.boardId.toHex()
        if (boards.value.none { it.boardId == boardIdHex }) return // not a board we follow → nothing to serve
        val now = System.currentTimeMillis()
        if (now - (lastSyncResponseMs[boardIdHex] ?: 0L) < SYNC_RESPONSE_COOLDOWN_MS) return
        lastSyncResponseMs[boardIdHex] = now

        val objects = repository.objectsForSync(boardIdHex, req.maxObjects)
        if (objects.isEmpty()) return
        // Re-broadcast on the transport the request arrived on; receivers verify + dedup (§20).
        val psk = pskForBoard(boardIdHex)
        objects.forEach { signed ->
            when (src) {
                "meshcore" -> psk?.let { meshCoreSend(it, signed) }
                else -> sidepathSend(signed)
            }
        }
    }

    // ---- identity resolution over the app's existing tables (§18) ------------

    private inner class AppIdentityResolver : OutpostIdentityResolver {
        override fun selfSeed(): ByteArray? = seedHex.value.takeIf { it.length == 64 }?.runCatching { hexToBytes() }?.getOrNull()

        override fun selfPublicKey(): ByteArray? =
            selfSeed()?.let { runCatching { Identity.fromSeed(it).publicKey }.getOrNull() }

        override suspend fun candidateKeys(authorRef: ByteArray): List<ByteArray> {
            val refHex = authorRef.toHex()
            val keys = ArrayList<ByteArray>()
            selfPublicKey()?.let { keys += it }
            contacts.value.forEach { c -> c.pubKeyHex.toKeyOrNull()?.let { keys += it } }
            topology.value.forEach { t -> if (t.publicKey.size == 32) keys += t.publicKey }
            // Nodes we've heard advertise but not saved as contacts are still valid resolution sources (§18).
            discovered.value.forEach { d -> d.pubKeyHex.toKeyOrNull()?.let { keys += it } }
            return keys
                .filter { it.size == 32 && OutpostCrypto.authorRef(it).toHex() == refHex }
                .distinctBy { it.toHex() }
        }

        override suspend fun resolved(authorRef: ByteArray): ResolvedAuthor? {
            val candidates = candidateKeys(authorRef)
            if (candidates.size != 1) return null // unknown or ambiguous (§6.3)
            val key = candidates.first()
            val keyHex = key.toHex()
            val nodeHex = keyHex.take(20) // pubKey[:10]
            val self = selfPublicKey()?.toHex() == keyHex
            val contact = contacts.value.firstOrNull { it.nodeHex == nodeHex }
            val topo = topology.value.firstOrNull { it.publicKey.size == 32 && it.publicKey.toHex() == keyHex }
            val disc = discovered.value.firstOrNull { it.pubKeyHex == keyHex }
            val name = when {
                self -> "You"
                !contact?.localName.isNullOrBlank() -> contact!!.localName
                !contact?.description.isNullOrBlank() -> contact!!.description
                !topo?.name.isNullOrBlank() -> topo!!.name
                !disc?.name.isNullOrBlank() -> disc!!.name
                else -> nodeHex.take(8)
            }
            return ResolvedAuthor(pubKeyHex = keyHex, name = name, isContact = contact != null, isSelf = self)
        }

        private fun String.toKeyOrNull(): ByteArray? =
            takeIf { it.length == 64 }?.runCatching { hexToBytes() }?.getOrNull()
    }

    companion object {
        // Don't answer the same board's sync request more than once per this window (§16.2 cooldown).
        private const val SYNC_RESPONSE_COOLDOWN_MS = 8_000L

        /** Board id of MeshCore's Public channel — the one MVP board. */
        private val PUBLIC_BOARD_ID = OutpostCrypto.boardId(ChatChannel.PUBLIC_SECRET).toHex()
    }
}
