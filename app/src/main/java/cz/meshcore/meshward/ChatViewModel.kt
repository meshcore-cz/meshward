package cz.meshcore.meshward

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.meshcore.meshward.data.Channel
import cz.meshcore.meshward.data.ChannelKind
import cz.meshcore.meshward.data.ChatDatabase
import cz.meshcore.meshward.data.Contact
import cz.meshcore.meshward.data.DiscoveredContact
import cz.meshcore.meshward.data.DiscoverySource
import cz.meshcore.meshward.data.MeshNetwork
import cz.meshcore.meshward.data.Message
import cz.meshcore.meshward.data.MsgStatus
import cz.meshcore.meshward.data.Reaction
import cz.meshcore.meshward.data.channelPeerId
import cz.meshcore.meshward.data.channelPskHexOf
import cz.meshcore.meshward.data.isChannelPeer
import cz.meshcore.sidepath.chat.ChatChannel
import cz.meshcore.sidepath.chat.ChatChannelReaction
import cz.meshcore.sidepath.chat.ChatKind
import cz.meshcore.sidepath.protocol.Sidepath
import cz.meshcore.sidepath.protocol.Identity
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.protocol.PayloadProtocol
import cz.meshcore.sidepath.protocol.TraceResponseBody
import cz.meshcore.sidepath.transport.PHYMode
import cz.meshcore.sidepath.meshcore.MeshCoreAdvert
import cz.meshcore.sidepath.meshcore.MeshCoreCodec
import cz.meshcore.sidepath.service.SidepathService
import cz.meshcore.sidepath.service.LogEntry
import cz.meshcore.sidepath.service.MeshStats
import cz.meshcore.sidepath.service.PeerInfo
import cz.meshcore.sidepath.service.ReceivedMessage
import cz.meshcore.sidepath.service.TopologyEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshward_prefs")
private val SEED_KEY = stringPreferencesKey("seed")
private val DESC_KEY = stringPreferencesKey("description")
private val NAME_KEY = stringPreferencesKey("name")
private val DM_RETRY_DELAY_KEY = intPreferencesKey("dm_retry_delay_ms")
private val DM_MAX_TRIES_KEY = intPreferencesKey("dm_max_tries")
private const val DEFAULT_DM_RETRY_DELAY_MS = 3000
private const val DEFAULT_DM_MAX_TRIES = 3
private val FLOOD_TTL_KEY = intPreferencesKey("flood_ttl")
private val PHY_MODE_KEY = stringPreferencesKey("phy_mode")
private val AVATAR_STYLE_KEY = stringPreferencesKey("avatar_style")
private val THEME_KEY = stringPreferencesKey("theme_mode")
private val ANALYZER_URLS_KEY = stringPreferencesKey("analyzer_urls") // newline-separated base URLs
// Location is opt-in and only used locally to compute distances to nodes that advertise GPS.
private val LOCATION_ENABLED_KEY = booleanPreferencesKey("location_enabled")
private val LOCATION_PROMPT_DISMISSED_KEY = booleanPreferencesKey("location_prompt_dismissed")
private val LOCATION_LAT_KEY = doublePreferencesKey("location_lat")
private val LOCATION_LON_KEY = doublePreferencesKey("location_lon")
private val LOCATION_PRECISION_KEY = stringPreferencesKey("location_precision")
// The manually pinned Meshcore Network code (its short code, e.g. "CZ"), used only when auto is off.
private val ACTIVE_NETWORK_CODE_KEY = stringPreferencesKey("active_network_code")
// Whether the active network is auto-detected from heard bridges (default) vs. manually pinned.
private val NETWORK_AUTO_KEY = booleanPreferencesKey("network_auto")

/** Default MeshCore packet analyzer base URL; the content hash is appended. */
const val DEFAULT_ANALYZER_URL = "https://analyzer.meshcore.cz/#/packets/"

/** The Meshcore Network pinned by default (used only as the manual fallback before auto-detection). */
const val DEFAULT_NETWORK_CODE = "CZ"

/**
 * Default URL the "Refresh definitions" action pulls the network-definitions dataset from. Placeholder
 * until the canonical sidepath-protocol-hosted URL is provided; the refresh plumbing is otherwise live.
 */
const val DEFAULT_NETWORK_DEFS_URL = "https://raw.githubusercontent.com/meshcore-cz/sidepath-protocol/main/networks.json"

/** How long a discovered contact survives after it was last heard advertising. */
private const val DISCOVERED_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days

/** Title of the conversation with one's own node — a local notepad, à la Signal. */
const val NOTE_TO_SELF = "Note to Self"

/** How contact/channel avatars are drawn. */
enum class AvatarStyle(val value: String) {
    IDENTICON("identicon"), // default: a deterministic identicon from the public key
    INITIALS("initials");   // colored circle with initials

    companion object {
        fun fromValue(v: String?) = entries.firstOrNull { it.value == v } ?: IDENTICON
    }
}

/** App theme preference. */
enum class ThemeMode(val value: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromValue(v: String?) = entries.firstOrNull { it.value == v } ?: AUTO
    }
}

/**
 * How precisely we read this device's own location for distance estimates. [decimals] is how many
 * fractional digits we keep (and the runtime permission it needs): COARSE rounds to ~110 m
 * (ACCESS_COARSE_LOCATION), FINE keeps full precision (ACCESS_FINE_LOCATION).
 */
enum class LocationPrecision(val value: String, val decimals: Int) {
    COARSE("coarse", 3), FINE("fine", 6);

    companion object {
        fun fromValue(v: String?) = entries.firstOrNull { it.value == v } ?: COARSE
    }
}

/** Live state of an in-progress / completed trace, shown on the trace page. */
data class TraceUi(
    val peerHex: String,
    val running: Boolean,
    val tag: Int? = null,
    val startedAtMs: Long = 0L,
    val rttMs: Long? = null, // round-trip time once the response arrives
    val result: TraceResponseBody? = null,
    val error: String? = null,
)

/** A node matching a MeshCore path-hash prefix (see [ChatViewModel.meshCoreHopCandidates]). */
data class MeshCoreHopMatch(val name: String, val nodeHex: String, val pubKeyHex: String)

/** Row count and on-disk size (bytes, -1 if unavailable) of one database table. */
data class TableStat(val name: String, val rows: Long, val bytes: Long)

/** A snapshot of the Room database's on-disk footprint, for the debug screen. */
data class DatabaseStats(
    val path: String,
    val fileBytes: Long,
    val walBytes: Long,
    val shmBytes: Long,
    val pageSize: Long,
    val pageCount: Long,
    val dbStatAvailable: Boolean,
    val tables: List<TableStat>,
) {
    val totalBytes: Long get() = fileBytes + walBytes + shmBytes
}

/** A row in the Chats list. */
data class ConversationSummary(
    val peerHex: String,
    val title: String,
    val pubKeyHex: String, // 32-byte Ed25519 key (hex), or "" if not known yet
    val lastText: String,
    val lastTimestampMs: Long,
    val unread: Int,
)

/**
 * A contact/channel profile, shown on the full-screen profile page opened by tapping an avatar.
 * For a user, [pubKeyHex]/[nodeHex]/[isContact] are set; for a channel, [pskHex]/[channelKind]/
 * [channelHash] are.
 */
data class ProfileInfo(
    val peerHex: String,
    val isChannel: Boolean,
    val name: String,
    val nodeHex: String = "",
    val pubKeyHex: String = "",
    val isContact: Boolean = false,
    val online: Boolean = false, // present in the live topology (signed ANNOUNCE)
    val description: String = "", // node's own free-form description, shown only on the profile
    val platform: String = "",    // node's OS/device string, shown in profile node information
    val channelKind: String = "",
    val channelHash: Int = 0,
    val pskHex: String = "",
    val neighborHexes: List<String> = emptyList(), // this node's directly-connected neighbors (from its ANNOUNCE)
    // MeshCore / discovery info. [isMeshCore] = the node is a bridged MeshCore node (not directly
    // DM-reachable). [isDiscovered] = heard advertising but not yet saved as a contact. The rest
    // mirror the bridged ADVERT and are shown in the profile's MeshCore section.
    val isMeshCore: Boolean = false,
    val isDiscovered: Boolean = false,
    val isSelf: Boolean = false, // this is our own identity (profile opened from our avatar)
    val nodeType: Int = 0,
    val hasGps: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val sigVerified: Boolean = false,
    val nodeAdvertisedMs: Long = 0L,
    // Meshcore Network this node was tiered to at discovery (the bridge network it came through).
    // Set for MeshCore nodes heard via a gateway; blank otherwise.
    val networkCode: String = "",
)

/** Our own approximate location, used only on-device to estimate distances to GPS-bearing nodes. */
data class UserLocation(val lat: Double, val lon: Double)

/**
 * A unified row in the merged Chats list — a direct conversation or a channel. [isChannel]
 * selects the renderer; channel-only fields ([lastSender], [channelKind]) and DM-only [pubKeyHex]
 * are filled accordingly.
 */
data class ChatListItem(
    val peerHex: String,        // node id hex (DM) or "ch:"+pskHex (channel)
    val isChannel: Boolean,
    val title: String,
    val lastText: String,
    val lastSender: String,     // channel author label, or ""
    val lastTimestampMs: Long,
    val unread: Int,
    val pubKeyHex: String = "", // DM identicon key
    val channelKind: String = "",
)

/** A row in the Channels list. */
data class ChannelSummary(
    val pskHex: String,
    val name: String,
    val kind: String, // ChannelKind.{PUBLIC,NAMED,SECRET}
    val lastSender: String, // author of the last message (channel plaintext name), or ""
    val lastText: String,
    val lastTimestampMs: Long,
    val unread: Int,
)

/** Connection health shown as a colored status dot. */
enum class ConnState { CONNECTED, NO_PEERS, OFFLINE, ERROR }

/** A node we've heard advertise, offered in the "New chat" picker. */
data class AdvertisedNode(
    val nodeHex: String,
    val description: String,
    val pubKeyHex: String,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = ChatDatabase.get(app).chatDao()

    private val _service = MutableStateFlow<SidepathService?>(null)
    private var serviceBound = false

    // packet keys we've already folded into the DB, to dedup the cumulative receivedMessages list.
    private val processed = mutableSetOf<String>()

    private val _permissionsGranted = MutableStateFlow(false)
    val permissionsGranted: StateFlow<Boolean> = _permissionsGranted.asStateFlow()

    private val _seedHex = MutableStateFlow("")
    val seedHex: StateFlow<String> = _seedHex.asStateFlow()

    /** This node's own 32-byte public key (hex), derived from the seed — for the self identicon. */
    val myPubKeyHex: StateFlow<String> = _seedHex.map { hex ->
        runCatching { Identity.fromSeed(hex.hexToBytes()).publicKey.toHex() }.getOrDefault("")
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    /** The user's chosen name override (may be blank = use the deterministic default). */
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name.asStateFlow()

    /** This node's effective display name: the override if set, else the deterministic default. */
    val myName: StateFlow<String> = combine(_name, myPubKeyHex) { n, pub ->
        n.ifBlank { nameFromPubKey(pub) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Direct-message delivery retry: wait this long for an ACK before re-sending. */
    private val _dmRetryDelayMs = MutableStateFlow(DEFAULT_DM_RETRY_DELAY_MS)
    val dmRetryDelayMs: StateFlow<Int> = _dmRetryDelayMs.asStateFlow()

    /** Total DM send attempts (including the first) before giving up. */
    private val _dmMaxTries = MutableStateFlow(DEFAULT_DM_MAX_TRIES)
    val dmMaxTries: StateFlow<Int> = _dmMaxTries.asStateFlow()

    /** Hop limit (TTL) applied to messages we originate — how far they flood across the mesh. */
    private val _floodTtl = MutableStateFlow(Sidepath.DEFAULT_FLOOD_TTL)
    val floodTtl: StateFlow<Int> = _floodTtl.asStateFlow()

    // Configured MeshCore packet-analyzer base URLs (content hash appended). Defaults to one entry.
    private val _analyzerUrls = MutableStateFlow(listOf(DEFAULT_ANALYZER_URL))
    val analyzerUrls: StateFlow<List<String>> = _analyzerUrls.asStateFlow()

    // ---- Explore filter state -------------------------------------------------
    // Held in the VM (not screen-local) so the selected filters survive navigating to another page
    // and back — the Explore subtree leaves composition on navigation, which would drop local state.
    private val _exploreTypeFilter = MutableStateFlow<String?>(null) // DiscoverySource.* or null = all
    val exploreTypeFilter: StateFlow<String?> = _exploreTypeFilter.asStateFlow()
    fun setExploreTypeFilter(v: String?) { _exploreTypeFilter.value = v }

    private val _exploreRoleFilter = MutableStateFlow<Int?>(null) // MeshCore node type, or null = all
    val exploreRoleFilter: StateFlow<Int?> = _exploreRoleFilter.asStateFlow()
    fun setExploreRoleFilter(v: Int?) { _exploreRoleFilter.value = v }

    private val _exploreNetworkFilter = MutableStateFlow<String?>(null) // network code, or null = all
    val exploreNetworkFilter: StateFlow<String?> = _exploreNetworkFilter.asStateFlow()
    fun setExploreNetworkFilter(v: String?) { _exploreNetworkFilter.value = v }

    private val _exploreSortByDistance = MutableStateFlow(false)
    val exploreSortByDistance: StateFlow<Boolean> = _exploreSortByDistance.asStateFlow()
    fun setExploreSortByDistance(v: Boolean) { _exploreSortByDistance.value = v }

    // ---- Meshcore Networks (auto-detected from bridge announces) --------------
    // Built-in definitions come from sidepath-protocol (NetworkDefs), refreshable from a URL and
    // cached on disk; user-added ones live in the DB as custom overrides. The effective list is the
    // union, deduped by code. The active network is auto-detected from nearby gateway bridges by
    // default ([networkAuto]); turning auto off lets the user pin one manually ([activeNetworkCode]).
    private val _builtinNetworks = MutableStateFlow(loadNetworkDefs(app))

    val networks: StateFlow<List<MeshNetwork>> =
        combine(dao.networks(), _builtinNetworks) { custom, builtin ->
            val byCode = LinkedHashMap<String, MeshNetwork>()
            builtin.forEach { byCode[it.code] = it }
            custom.forEach { byCode[it.code] = it } // a custom network overrides a built-in of the same code
            byCode.values.sortedBy { it.code }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, _builtinNetworks.value.sortedBy { it.code })

    private val _activeNetworkCode = MutableStateFlow(DEFAULT_NETWORK_CODE)
    val activeNetworkCode: StateFlow<String> = _activeNetworkCode.asStateFlow()

    private val _networkAuto = MutableStateFlow(true)
    /** When true, the active network is auto-detected from heard bridges; when false it's pinned manually. */
    val networkAuto: StateFlow<Boolean> = _networkAuto.asStateFlow()

    private val _avatarStyle = MutableStateFlow(AvatarStyle.IDENTICON)
    val avatarStyle: StateFlow<AvatarStyle> = _avatarStyle.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.AUTO)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // ---- approximate location (opt-in, on-device only) -----------------------
    // [locationEnabled] = the user opted into distance estimates. [locationPromptDismissed] = the
    // Explore opt-in banner has been answered (allowed or denied) so it stops showing. [userLocation]
    // = our last cached approximate position, or null until we have one.
    private val _locationEnabled = MutableStateFlow(false)
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()

    private val _locationPromptDismissed = MutableStateFlow(false)
    val locationPromptDismissed: StateFlow<Boolean> = _locationPromptDismissed.asStateFlow()

    private val _userLocation = MutableStateFlow<UserLocation?>(null)
    val userLocation: StateFlow<UserLocation?> = _userLocation.asStateFlow()

    private val _locationPrecision = MutableStateFlow(LocationPrecision.COARSE)
    val locationPrecision: StateFlow<LocationPrecision> = _locationPrecision.asStateFlow()

    private val _trace = MutableStateFlow<TraceUi?>(null)
    val trace: StateFlow<TraceUi?> = _trace.asStateFlow()

    // Peers (hex) currently shown as "typing…". Each entry has a removal job that expires it
    // if no fresh typing hint arrives; a real incoming message clears it immediately.
    private val _typingPeers = MutableStateFlow<Set<String>>(emptySet())
    val typingPeers: StateFlow<Set<String>> = _typingPeers.asStateFlow()
    private val typingExpiry = mutableMapOf<String, Job>()
    private val lastRealMessageSentAtMs = mutableMapOf<String, Long>()

    // Outgoing typing: a single loop re-sends a hint every 10s for the peer we're typing to.
    private var outgoingTypingJob: Job? = null
    private var outgoingTypingPeer: String? = null

    val nodeId: StateFlow<NodeId> = _service.flatMapLatest {
        it?.nodeId ?: flowOf(NodeId.BROADCAST)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NodeId.BROADCAST)

    val phyMode: StateFlow<PHYMode> = _service.flatMapLatest {
        it?.phyMode ?: flowOf(PHYMode.ONE_M)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PHYMode.ONE_M)

    val topology: StateFlow<List<TopologyEntry>> = _service.flatMapLatest {
        it?.knownTopology ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Networks currently detected from verified gateway announces: every distinct bridge `code` heard
     * in the live topology, resolved against the definitions dataset (custom announce radio params
     * override the canonical ones), most-recently-announced first. Empty when no bridge is nearby.
     */
    val detectedNetworks: StateFlow<List<MeshNetwork>> =
        combine(topology, networks) { topo, defs ->
            val byCode = defs.associateBy { it.code }
            topo.filter { it.bridges.isNotEmpty() }
                .sortedByDescending { it.lastAnnounceMs }
                .flatMap { entry -> entry.bridges.map { resolveBridge(it, byCode) } }
                .distinctBy { it.code }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The network this device is operating in: auto-detected (the strongest/most-recent detected
     * network) when [networkAuto] is on, else the manually pinned [activeNetworkCode]. Null when auto
     * is on and nothing is detected — callers (Explore) hide the network card in that case.
     */
    val activeNetwork: StateFlow<MeshNetwork?> =
        combine(networks, detectedNetworks, _activeNetworkCode, _networkAuto) { all, detected, code, auto ->
            if (auto) detected.firstOrNull()
            else all.firstOrNull { it.code == code }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val connectedPeers: StateFlow<List<PeerInfo>> = _service.flatMapLatest {
        it?.connectedPeers ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isRunning: StateFlow<Boolean> = _service.flatMapLatest {
        it?.isRunning ?: flowOf(false)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The mesh as a force-graph: one node per known node, one link per (deduped) neighbor
     * relationship. Rebuilt whenever the topology or our direct connections change; the
     * Topology view throttles how often it pushes this to the WebView.
     */
    val topologyGraph: StateFlow<cz.meshcore.sidepath.topology.TopologyGraph> =
        combine(topology, connectedPeers, nodeId) { topo, peers, self ->
            cz.meshcore.sidepath.topology.buildTopologyGraph(
                selfHex = self.toHex(),
                peers = peers,
                topology = topo,
                nowMs = System.currentTimeMillis(),
            ) { hex -> topologyLabelFor(hex) }
        // Lazily started (not Eagerly): the resolver touches `contacts`, which is declared later in
        // this class — collecting during <init> would NPE on the not-yet-initialized StateFlow.
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), cz.meshcore.sidepath.topology.TopologyGraph(emptyList(), emptyList()))

    /** Best display label for a node in the topology graph: alias → wire name → derived → short hex. */
    private fun topologyLabelFor(hex: String): String =
        nameForHex(hex).ifBlank { nameFromPubKey(pubKeyForHex(hex)) }.ifBlank { hex.take(12) }

    val stats: StateFlow<MeshStats> = _service.flatMapLatest {
        it?.stats ?: flowOf(MeshStats())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MeshStats())

    /** Routing/SYS log entries, newest first — raw diagnostic text log. */
    val routingLog: StateFlow<List<LogEntry>> = _service.flatMapLatest {
        it?.routingLog ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Decoded Sidepath packets seen on the radio, newest first — backs the Rx Log screen. */
    val rxPackets: StateFlow<List<cz.meshcore.sidepath.service.RxPacket>> = _service.flatMapLatest {
        it?.rxPackets ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Cumulative count of all packets received (rxPackets is trimmed); for the "(N total)" header. */
    val rxTotal: StateFlow<Int> = _service.flatMapLatest {
        it?.rxTotal ?: flowOf(0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Decoded MeshCore packets seen on the mesh, for the dedicated MeshCore Rx Log. */
    val meshCorePackets: StateFlow<List<cz.meshcore.sidepath.meshcore.MeshCorePacket>> = _service.flatMapLatest {
        it?.meshCorePackets ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val meshCoreTotal: StateFlow<Int> = _service.flatMapLatest {
        it?.meshCoreTotal ?: flowOf(0)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Wall-clock time of the most recent received packet, or null if none yet. */
    val lastPacketAtMs: StateFlow<Long?> = rxPackets
        .map { it.firstOrNull()?.timestampMs }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Repeats of our own flooded (broadcast/channel) messages heard back on the radio, keyed by the
     * message's packet-id hex (= [cz.meshcore.meshward.data.Message.id]). Each sample carries the
     * receiving link's RSSI. Surfaced as an icon+count on the bubble and a list in message details.
     */
    val floodRepeats: StateFlow<Map<String, List<cz.meshcore.sidepath.service.RepeatSample>>> =
        _service.flatMapLatest { it?.floodRepeats ?: flowOf(emptyMap()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Per-DM delivery progress (attempts, acked, failed), keyed by the message's packet-id hex. */
    val dmDeliveries: StateFlow<Map<String, cz.meshcore.sidepath.service.DmDelivery>> =
        _service.flatMapLatest { it?.dmDeliveries ?: flowOf(emptyMap()) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Starts/stops the mesh radio (advertise + scan + GATT). Surfaced on the Network page. */
    fun startMesh() { _service.value?.startBLE() }
    fun stopMesh() { _service.value?.stopBLE() }

    /** Overall connection health, surfaced as the status dot in the Chats header. */
    val connectionStatus: StateFlow<ConnState> =
        combine(permissionsGranted, isRunning, connectedPeers) { granted, running, peers ->
            when {
                !granted -> ConnState.ERROR
                !running -> ConnState.OFFLINE
                peers.isEmpty() -> ConnState.NO_PEERS
                else -> ConnState.CONNECTED
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnState.OFFLINE)

    val contacts: StateFlow<List<Contact>> =
        dao.contacts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val channels: StateFlow<List<Channel>> =
        dao.channels().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Emoji reactions grouped by the message id they target, for the conversation UI. */
    val reactions: StateFlow<Map<String, List<Reaction>>> =
        dao.allReactions().map { it.groupBy { r -> r.messageId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Persisted echoes of our own messages, grouped by message id — the durable replacement for the
     * service's in-memory floodRepeats, so the echo count / delivery proof survives a restart.
     */
    val echoes: StateFlow<Map<String, List<cz.meshcore.meshward.data.Echo>>> =
        dao.allEchoes().map { it.groupBy { e -> e.messageId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Persisted distinct-path MeshCore receptions ("heards"), grouped by message id. */
    val meshCoreHeards: StateFlow<Map<String, List<cz.meshcore.meshward.data.MeshCoreHeard>>> =
        dao.allMeshCoreHeards().map { it.groupBy { h -> h.messageId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Direct conversations for the Chats tab: one row per peer we've messaged, plus every saved
     * contact (even with no messages yet, so they're reachable from Chats). Channels are merged in
     * separately by [chatItems].
     */
    val conversations: StateFlow<List<ConversationSummary>> =
        combine(dao.allMessages(), contacts, topology, nodeId) { msgs, contacts, topo, me ->
            val byNode = contacts.associateBy { it.nodeHex }
            val meHex = me.toHex()
            // The conversation with our own node is "Note to Self".
            fun titleFor(peer: String) = if (peer == meHex) NOTE_TO_SELF else displayName(peer, byNode, topo)
            val byPeer = msgs.filter { !isChannelPeer(it.peerHex) }.groupBy { it.peerHex }
            val messaged = byPeer.map { (peer, list) ->
                val last = list.maxByOrNull { it.timestampMs }!!
                ConversationSummary(
                    peerHex = peer,
                    title = titleFor(peer),
                    pubKeyHex = publicKeyHex(peer, byNode, topo),
                    lastText = last.text,
                    lastTimestampMs = last.timestampMs,
                    unread = list.count { it.incoming && !it.read },
                )
            }
            // Saved contacts we haven't exchanged messages with yet — shown as empty conversations.
            val contactOnly = contacts.filter { it.nodeHex !in byPeer.keys }.map { c ->
                ConversationSummary(
                    peerHex = c.nodeHex,
                    title = titleFor(c.nodeHex),
                    pubKeyHex = publicKeyHex(c.nodeHex, byNode, topo),
                    lastText = "",
                    lastTimestampMs = 0L,
                    unread = 0,
                )
            }
            (messaged + contactOnly).sortedByDescending { it.lastTimestampMs }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Channels list (joined channels with their latest message + unread count). */
    val channelConversations: StateFlow<List<ChannelSummary>> =
        combine(channels, dao.allMessages()) { chans, msgs ->
            val byPeer = msgs.groupBy { it.peerHex }
            chans.map { ch ->
                val list = byPeer[channelPeerId(ch.pskHex)].orEmpty()
                val last = list.maxByOrNull { it.timestampMs }
                ChannelSummary(
                    pskHex = ch.pskHex,
                    name = ch.name,
                    kind = ch.kind,
                    lastSender = last?.senderName ?: "",
                    lastText = last?.text ?: "",
                    lastTimestampMs = last?.timestampMs ?: 0L,
                    unread = list.count { it.incoming && !it.read },
                )
            }.sortedByDescending { it.lastTimestampMs }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Merged Chats list: direct conversations and channels, intermixed by recency. */
    val chatItems: StateFlow<List<ChatListItem>> =
        combine(conversations, channelConversations) { dms, chans ->
            val dmItems = dms.map {
                ChatListItem(
                    peerHex = it.peerHex, isChannel = false, title = it.title,
                    lastText = it.lastText, lastSender = "", lastTimestampMs = it.lastTimestampMs,
                    unread = it.unread, pubKeyHex = it.pubKeyHex,
                )
            }
            val chanItems = chans.map {
                ChatListItem(
                    peerHex = channelPeerId(it.pskHex), isChannel = true, title = it.name,
                    lastText = it.lastText, lastSender = it.lastSender,
                    lastTimestampMs = it.lastTimestampMs, unread = it.unread, channelKind = it.kind,
                )
            }
            (dmItems + chanItems).sortedByDescending { it.lastTimestampMs }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Index of saved contacts by display name (lowercased) → node id. Used to resolve a bridged
     * channel's *declared* sender (or an `@mention`) — which carries no public key — back to a
     * saved identity when the names match. Built from contacts only ("our database").
     */
    val contactNameIndex: StateFlow<Map<String, String>> =
        combine(contacts, topology) { c, t ->
            val byNode = c.associateBy { it.nodeHex }
            val out = HashMap<String, String>(c.size)
            c.forEach { out[displayName(it.nodeHex, byNode, t).lowercase()] = it.nodeHex }
            out
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Resolves a declared name to a saved contact's node id (hex), or null if none matches. */
    fun nodeHexForName(name: String): String? = contactNameIndex.value[name.trim().lowercase()]

    /**
     * Every node we can start an encrypted chat with — i.e. whose public key we know,
     * whether from the live topology (signed ANNOUNCE) or a saved contact (learned from a
     * received DM or a previous chat).
     */
    val advertisedNodes: StateFlow<List<AdvertisedNode>> =
        combine(topology, contacts, nodeId) { topo, contacts, me ->
            val meHex = me.toHex()
            val byHex = LinkedHashMap<String, AdvertisedNode>()
            contacts.forEach { c ->
                if (c.nodeHex != meHex && c.pubKeyHex.length == 64) {
                    byHex[c.nodeHex] = AdvertisedNode(c.nodeHex, c.description, c.pubKeyHex)
                }
            }
            topo.forEach { t ->
                val hex = t.nodeId.toHex()
                if (hex != meHex && t.publicKey.size == 32) {
                    val desc = t.description.ifBlank { byHex[hex]?.description ?: "" }
                    byHex[hex] = AdvertisedNode(hex, desc, t.publicKey.toHex())
                }
            }
            byHex.values.sortedBy { it.description.ifBlank { it.nodeHex } }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Nodes heard advertising that we haven't added — shown on the Explore tab. Persisted across
     * restarts; entries expire [DISCOVERED_TTL_MS] after they were last heard. Excludes self and
     * any node already in our contacts (matched by node id or public key).
     */
    val discoveredContacts: StateFlow<List<DiscoveredContact>> =
        combine(dao.discoveredContacts(), contacts, nodeId) { discovered, contacts, me ->
            val meHex = me.toHex()
            val addedNodes = contacts.mapTo(HashSet()) { it.nodeHex }
            val addedKeys = contacts.mapTo(HashSet()) { it.pubKeyHex }
            val cutoff = System.currentTimeMillis() - DISCOVERED_TTL_MS
            discovered.filter {
                it.lastAdvertisedMs >= cutoff &&
                    it.nodeHex != meHex && it.nodeHex !in addedNodes && it.pubKeyHex !in addedKeys
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun messagesFor(peerHex: String): StateFlow<List<Message>> =
        dao.messagesFor(peerHex).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun displayNameFor(peerHex: String): StateFlow<String> =
        combine(contacts, topology) { c, t ->
            displayName(peerHex, c.associateBy { it.nodeHex }, t)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, shortHex(peerHex))

    // ---- service lifecycle ---------------------------------------------------

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = (binder as SidepathService.LocalBinder).getService()
            _service.value = service
            viewModelScope.launch { initializeService(service) }
            observeIncoming(service)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            _service.value = null
            serviceBound = false
        }
    }

    fun onPermissionsGranted() {
        _permissionsGranted.value = true
        bindService()
    }

    private fun bindService() {
        if (serviceBound) return
        serviceBound = true
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, SidepathService::class.java)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private suspend fun initializeService(service: SidepathService) {
        val ctx = getApplication<Application>()
        val pref = ctx.dataStore.data.first()
        val seedHex = pref[SEED_KEY] ?: generateSeedHex().also { gen ->
            ctx.dataStore.edit { it[SEED_KEY] = gen }
        }
        val desc = pref[DESC_KEY] ?: ""
        val nodeName = pref[NAME_KEY] ?: ""
        val retryDelay = pref[DM_RETRY_DELAY_KEY] ?: DEFAULT_DM_RETRY_DELAY_MS
        val maxTries = pref[DM_MAX_TRIES_KEY] ?: DEFAULT_DM_MAX_TRIES
        val phy = PHYMode.fromString(pref[PHY_MODE_KEY] ?: PHYMode.ONE_M.value)
        _seedHex.value = seedHex
        _description.value = desc
        _name.value = nodeName
        _dmRetryDelayMs.value = retryDelay
        _dmMaxTries.value = maxTries
        _floodTtl.value = (pref[FLOOD_TTL_KEY] ?: Sidepath.DEFAULT_FLOOD_TTL).coerceIn(1, Sidepath.MAX_TTL)
        _avatarStyle.value = AvatarStyle.fromValue(pref[AVATAR_STYLE_KEY])
        _themeMode.value = ThemeMode.fromValue(pref[THEME_KEY])
        _analyzerUrls.value = parseAnalyzerUrls(pref[ANALYZER_URLS_KEY])

        val identity = Identity.fromSeed(seedHex.hexToBytes())
        service.initialize(identity, phy, emptySet(), desc, nodeName, retryDelay.toLong(), maxTries)
        ensurePublicChannel()
    }

    /**
     * Ensures MeshCore's default Public channel is joined on every start, so it's always present
     * after launch (and is restored after a destructive Room migration wipes the channels table).
     */
    private suspend fun ensurePublicChannel() {
        val pskHex = ChatChannel.PUBLIC_SECRET.toHex()
        if (dao.channelByPsk(pskHex) == null) {
            dao.upsertChannel(
                Channel(
                    pskHex = pskHex,
                    name = "Public",
                    hashByte = ChatChannel.channelHash(ChatChannel.PUBLIC_SECRET).toInt() and 0xFF,
                    kind = ChannelKind.PUBLIC,
                )
            )
        }
    }

    init {
        // Load display preferences early so the theme/avatars are right before the service binds.
        viewModelScope.launch {
            val pref = getApplication<Application>().dataStore.data.first()
            _avatarStyle.value = AvatarStyle.fromValue(pref[AVATAR_STYLE_KEY])
            _themeMode.value = ThemeMode.fromValue(pref[THEME_KEY])
            _locationEnabled.value = pref[LOCATION_ENABLED_KEY] ?: false
            _locationPromptDismissed.value = pref[LOCATION_PROMPT_DISMISSED_KEY] ?: false
            _locationPrecision.value = LocationPrecision.fromValue(pref[LOCATION_PRECISION_KEY])
            _activeNetworkCode.value = pref[ACTIVE_NETWORK_CODE_KEY] ?: DEFAULT_NETWORK_CODE
            _networkAuto.value = pref[NETWORK_AUTO_KEY] ?: true
            val lat = pref[LOCATION_LAT_KEY]
            val lon = pref[LOCATION_LON_KEY]
            if (lat != null && lon != null) _userLocation.value = UserLocation(lat, lon)
            // Refresh in the background if the user has location on and the permission is still held.
            if (_locationEnabled.value) refreshLocation()
        }
        // Drop discovered contacts whose TTL has elapsed so the table doesn't grow unbounded.
        viewModelScope.launch {
            dao.pruneDiscovered(System.currentTimeMillis() - DISCOVERED_TTL_MS)
        }
    }

    fun setAvatarStyle(style: AvatarStyle) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[AVATAR_STYLE_KEY] = style.value }
            _avatarStyle.value = style
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[THEME_KEY] = mode.value }
            _themeMode.value = mode
        }
    }

    // ---- Meshcore Networks ----------------------------------------------------

    /**
     * Pins the device to the network with [code] and turns auto-detection off (a manual pick is an
     * explicit override). Persisted across restarts.
     */
    fun setActiveNetwork(code: String) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[ACTIVE_NETWORK_CODE_KEY] = code
                it[NETWORK_AUTO_KEY] = false
            }
            _activeNetworkCode.value = code
            _networkAuto.value = false
        }
    }

    /** Toggles auto-detection of the active network from heard bridges (persisted across restarts). */
    fun setNetworkAuto(auto: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[NETWORK_AUTO_KEY] = auto }
            _networkAuto.value = auto
        }
    }

    /**
     * Refreshes the bundled network definitions from [url] (download + validate + cache). On success
     * the new built-ins take effect immediately and survive restart; on failure the existing
     * definitions are kept. Returns the outcome so the UI can surface success/failure.
     */
    suspend fun refreshNetworkDefs(url: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            refreshNetworkDefs(getApplication(), url).map { fresh -> _builtinNetworks.value = fresh }
        }

    /** Adds or updates a user network. The stored row is always a custom (non-built-in) network. */
    fun upsertNetwork(network: MeshNetwork) {
        viewModelScope.launch { dao.upsertNetwork(network.copy(isBuiltin = false)) }
    }

    /**
     * Deletes a custom network. A built-in of the same code (if any) re-surfaces; if the deleted
     * network was active, selection falls through to the first remaining network.
     */
    fun deleteNetwork(code: String) {
        viewModelScope.launch { dao.deleteNetwork(code) }
    }

    /** True if [code] is shipped as a built-in preset (so it can't be deleted, only customized). */
    fun isBuiltinNetwork(code: String): Boolean = _builtinNetworks.value.any { it.code == code }

    // ---- approximate / precise location --------------------------------------

    /** The runtime permission a given precision needs (FINE for precise, COARSE for approximate). */
    private fun permissionFor(precision: LocationPrecision): String =
        if (precision == LocationPrecision.FINE) Manifest.permission.ACCESS_FINE_LOCATION
        else Manifest.permission.ACCESS_COARSE_LOCATION

    /** True once the location permission for [precision] has been granted to the app. */
    fun hasLocationPermission(precision: LocationPrecision = _locationPrecision.value): Boolean =
        ContextCompat.checkSelfPermission(getApplication(), permissionFor(precision)) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Turns distance estimates on at [precision] (after its permission was granted) and grabs an
     * initial fix. Also marks the Explore opt-in banner as answered so it won't show again.
     */
    fun enableLocation(precision: LocationPrecision = _locationPrecision.value) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[LOCATION_ENABLED_KEY] = true
                it[LOCATION_PROMPT_DISMISSED_KEY] = true
                it[LOCATION_PRECISION_KEY] = precision.value
            }
            _locationEnabled.value = true
            _locationPromptDismissed.value = true
            _locationPrecision.value = precision
        }
        refreshLocation()
    }

    /** Dismisses the Explore opt-in banner without enabling location (the user declined). */
    fun dismissLocationPrompt() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[LOCATION_PROMPT_DISMISSED_KEY] = true }
            _locationPromptDismissed.value = true
        }
    }

    /** Settings toggle: turn distance estimates on (grabs a fix) or off (forgets the cached location). */
    fun setLocationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[LOCATION_ENABLED_KEY] = enabled
                if (enabled) it[LOCATION_PROMPT_DISMISSED_KEY] = true
                if (!enabled) {
                    it.remove(LOCATION_LAT_KEY)
                    it.remove(LOCATION_LON_KEY)
                }
            }
            _locationEnabled.value = enabled
            if (enabled) {
                _locationPromptDismissed.value = true
            } else {
                _userLocation.value = null
            }
        }
        if (enabled) refreshLocation()
    }

    /**
     * Refreshes our position: surfaces any last-known fix immediately (so distances show right away)
     * and also asks for a fresh single update, since on many devices last-known is null. The fix is
     * rounded per the current precision. No-op if location is off or the permission is missing.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun refreshLocation() {
        if (!_locationEnabled.value || !hasLocationPermission()) return
        val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return
        // Instant: most recent last-known fix across providers.
        runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        }.getOrNull()?.let { storeLocation(it) }
        // Fresh: a single current-location update (last-known is often null on real devices/emulators).
        // Precise mode prefers GPS; approximate prefers the (faster, coarser) network provider.
        val order = if (_locationPrecision.value == LocationPrecision.FINE)
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        else
            listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val provider = order.firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) } ?: return
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(getApplication())) { loc ->
                    loc?.let { storeLocation(it) }
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(
                    provider,
                    object : android.location.LocationListener {
                        override fun onLocationChanged(loc: Location) { storeLocation(loc) }
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, status: Int, extras: android.os.Bundle?) {}
                    },
                    android.os.Looper.getMainLooper(),
                )
            }
        }
    }

    /** Rounds a fresh fix to the current precision's decimals and caches it in state + DataStore. */
    private fun storeLocation(loc: Location) {
        val decimals = _locationPrecision.value.decimals
        val rounded = UserLocation(loc.latitude.roundCoord(decimals), loc.longitude.roundCoord(decimals))
        if (rounded == _userLocation.value) return
        _userLocation.value = rounded
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[LOCATION_LAT_KEY] = rounded.lat
                it[LOCATION_LON_KEY] = rounded.lon
            }
        }
    }

    private fun Double.roundCoord(decimals: Int): Double {
        val f = Math.pow(10.0, decimals.toDouble())
        return Math.round(this * f) / f
    }

    /**
     * Starts a source-routed trace to a node; results arrive via [trace]. With an empty [route] the
     * router picks the path automatically; pass an explicit hop list (NOT including this node) to
     * trace a route the user specified by hand or by picking nodes.
     */
    fun startTrace(peerHex: String, route: List<NodeId> = emptyList()) {
        val service = _service.value
        if (service == null) {
            _trace.value = TraceUi(peerHex, running = false, error = "Mesh service not ready yet.")
            return
        }
        val tag = service.sendTrace(NodeId.fromHex(peerHex), route)
        _trace.value = if (tag == null) {
            TraceUi(peerHex, running = false, error = if (route.isEmpty())
                "No route to this node yet — try again once it's in the network."
            else
                "Couldn't start that route — its first hop must be a directly connected peer.")
        } else {
            TraceUi(peerHex, running = true, tag = tag, startedAtMs = System.currentTimeMillis())
        }
    }

    /**
     * Parses a manual route string like "d503fdbcb61c654f,be0d40fda9b839b5,d503fdbcb61c654f" into
     * NodeIDs. Accepts comma/space/newline separators; each token must be 16 hex chars (an 8-byte
     * NodeId). Returns null if any token is malformed so the UI can show an error.
     */
    fun parseManualRoute(text: String): List<NodeId>? {
        val tokens = text.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.map { tok ->
            if (tok.length != Sidepath.NODE_ID_BYTES * 2 || tok.any { it.digitToIntOrNull(16) == null }) return null
            NodeId.fromHex(tok.lowercase())
        }
    }

    fun clearTrace() { _trace.value = null }

    fun clearDiscoveredContacts() {
        viewModelScope.launch { dao.clearDiscovered() }
    }

    private fun handleTraceResponse(msg: ReceivedMessage) {
        val result = msg.traceResponse ?: return
        val cur = _trace.value ?: return
        if (cur.tag != null && cur.tag.toLong() == result.tag) {
            val rtt = (System.currentTimeMillis() - cur.startedAtMs).coerceAtLeast(0)
            _trace.value = cur.copy(running = false, rttMs = rtt, result = result)
        }
    }

    private fun observeIncoming(service: SidepathService) {
        viewModelScope.launch {
            service.receivedMessages.collect { list ->
                list.forEach { handleIncoming(it) }
            }
        }
        // Keep the service's MeshCore channel secrets in sync with our joined channels so the
        // MeshCore Rx Log can render decrypted GRP_TXT plaintext.
        viewModelScope.launch {
            channels.collect { chans ->
                service.setMeshCoreChannelSecrets(chans.map { it.pskHex.hexToBytes() })
            }
        }
        // Push known MeshCore contacts' public keys so the service can resolve the full sender key
        // (from a DM's 1-byte src hash) and decrypt incoming MeshCore direct messages.
        viewModelScope.launch {
            contacts.collect { list ->
                service.setMeshCoreContactKeys(
                    list.filter { it.isMeshCore && it.pubKeyHex.length == 64 }
                        .mapNotNull { runCatching { it.pubKeyHex.hexToBytes() }.getOrNull() },
                )
            }
        }
        // Ingest discovered Sidepath nodes from the live topology into the discovered-contacts table.
        viewModelScope.launch {
            combine(topology, nodeId) { topo, me -> topo to me.toHex() }.collect { (topo, meHex) ->
                for (t in topo) {
                    val hex = t.nodeId.toHex()
                    if (hex == meHex || t.publicKey.size != 32) continue
                    val advertisedMs = if (t.lastAnnounceMs > 0) t.lastAnnounceMs else System.currentTimeMillis()
                    // Dedup on the announce timestamp so we only write when the node re-announces.
                    if (!processed.add("disc:ble:$hex:$advertisedMs")) continue
                    val pubHex = t.publicKey.toHex()
                    upsertDiscovered(
                        pubKeyHex = pubHex,
                        nodeHex = hex,
                        name = t.name.ifBlank { nameFromPubKey(pubHex) },
                        source = DiscoverySource.SIDEPATH,
                        lastAdvertisedMs = advertisedMs,
                    )
                }
            }
        }
        // Ingest MeshCore nodes from bridged ADVERT packets.
        viewModelScope.launch {
            service.meshCoreAdverts.collect { adverts ->
                for (a in adverts) {
                    if (!processed.add("disc:mc:${a.publicKeyHex}:${a.timestampSec}")) continue
                    val heardMs = System.currentTimeMillis()
                    val nodeAdvertisedMs = if (a.timestampSec > 0) a.timestampSec * 1000 else 0L
                    val nodeHex = a.publicKeyHex.take(20) // Sidepath-style NodeId = pubkey[:10]
                    val advertName = a.name.ifBlank { nameFromPubKey(a.publicKeyHex) }
                    // If this node is already a saved contact, refresh its name from the advert
                    // (unless the user has manually renamed it) so a name change propagates.
                    dao.refreshMeshCoreName(nodeHex, advertName)
                    upsertDiscovered(
                        pubKeyHex = a.publicKeyHex,
                        nodeHex = nodeHex,
                        name = advertName,
                        source = DiscoverySource.MESHCORE,
                        nodeType = a.nodeType,
                        hasGps = a.hasGps,
                        lat = a.lat,
                        lon = a.lon,
                        sigVerified = a.sigVerified,
                        lastAdvertisedMs = heardMs,
                        nodeAdvertisedMs = nodeAdvertisedMs,
                        // Tier MeshCore contacts to the bridge network they came through (auto-detected).
                        networkCode = tierNetworkCode(),
                    )
                }
            }
        }
        // When a DM exhausts all retries with no ACK, mark it failed (errored) in the chat.
        viewModelScope.launch {
            service.dmDeliveries.collect { map ->
                map.forEach { (idHex, d) ->
                    if (d.failed && !d.acked && processed.add("fail:$idHex")) {
                        dao.updateDelivery(idHex, MsgStatus.FAILED, "")
                    }
                }
            }
        }
        // Persist the service's in-memory echoes (flood repeats / MeshCore round-trips) into Room so
        // the echo list + delivery proof survive an app restart. The first echo of one of our own
        // messages also confirms it propagated → mark it delivered.
        viewModelScope.launch {
            service.floodRepeats.collect { map ->
                map.forEach { (idHex, samples) ->
                    samples.forEach { s ->
                        if (!processed.add("echo:$idHex:${s.timestampMs}")) return@forEach
                        dao.insertEcho(
                            cz.meshcore.meshward.data.Echo(
                                messageId = idHex,
                                timestampMs = s.timestampMs,
                                rssi = s.rssi,
                                forwarderHex = s.forwarderId?.toHex().orEmpty(),
                                viaMeshCore = s.viaMeshCore,
                                packetHex = s.raw?.toHex().orEmpty(),
                            ),
                        )
                        // A heard echo confirms a broadcast channel message propagated; flip it
                        // SENT → DELIVERED (DMs are confirmed by their ACK, not by echoes).
                        val msg = dao.messageById(idHex)
                        if (msg != null && !msg.incoming && msg.status == MsgStatus.SENT &&
                            cz.meshcore.meshward.data.isChannelPeer(msg.peerHex)) {
                            dao.updateDelivery(idHex, MsgStatus.DELIVERED, msg.routeHex)
                        }
                    }
                }
            }
        }
        // Persist every distinct-path MeshCore reception ("heard") of a bridged channel message, so
        // the full set of routes the message reached us by survives an app restart. Keyed by the
        // channel payload hex, which is the chat message's "mc:" primary key.
        viewModelScope.launch {
            service.meshCoreHeards.collect { map ->
                map.forEach { (payloadHex, samples) ->
                    val msgId = "mc:$payloadHex"
                    samples.forEach { s ->
                        if (!processed.add("mcheard:$msgId:${s.contentId}")) return@forEach
                        dao.insertMeshCoreHeard(
                            cz.meshcore.meshward.data.MeshCoreHeard(
                                messageId = msgId,
                                contentId = s.contentId,
                                timestampMs = s.timestampMs,
                                rssi = s.rssi,
                                forwarderHex = s.forwarderHex,
                                hopCount = s.hopCount,
                                pathHashSize = s.pathHashSize,
                                routeLabel = s.routeLabel,
                                hopsHex = s.hopsHex,
                                packetHex = s.packetHex,
                                carrierHex = s.carrierHex,
                            ),
                        )
                    }
                }
            }
        }
        // Persist the latest Sidepath ANNOUNCE per node so the profile's "Last announcement" (and its
        // openable packet) survive a restart, after the in-memory Rx Log has aged out.
        viewModelScope.launch {
            service.rxPackets.collect { list ->
                list.filter { it.controlKind == cz.meshcore.sidepath.protocol.ControlKind.ANNOUNCE }
                    .groupBy { it.source.toHex() }
                    .forEach { (hex, pkts) ->
                        val p = pkts.maxByOrNull { it.timestampMs } ?: return@forEach
                        if (!processed.add("ann:ble:$hex:${p.timestampMs}")) return@forEach
                        val pub = topology.value.firstOrNull { it.nodeId.toHex() == hex }?.publicKey?.toHex().orEmpty()
                        dao.upsertAnnouncement(
                            cz.meshcore.meshward.data.NodeAnnouncement(
                                nodeHex = hex,
                                source = DiscoverySource.SIDEPATH,
                                pubKeyHex = pub,
                                timestampMs = p.timestampMs,
                                rawHex = p.raw.toHex(),
                            ),
                        )
                    }
            }
        }
        // Persist the latest MeshCore ADVERT per node, likewise.
        viewModelScope.launch {
            service.meshCorePackets.collect { list ->
                list.filter { it.envelope?.type == cz.meshcore.sidepath.meshcore.MeshCoreType.ADVERT }
                    .forEach { p ->
                        // Dedup per distinct packet (avoid re-decoding the advert on every emission).
                        if (!processed.add("ann:mc:${p.contentId.ifBlank { p.timestampMs.toString() }}")) return@forEach
                        val pub = meshAdvertPubKeyHex(p) ?: return@forEach
                        dao.upsertAnnouncement(
                            cz.meshcore.meshward.data.NodeAnnouncement(
                                nodeHex = pub.take(20), // Sidepath-style NodeId = pubkey[:10]
                                source = DiscoverySource.MESHCORE,
                                pubKeyHex = pub,
                                timestampMs = p.timestampMs,
                                rawHex = p.raw.toHex(),
                            ),
                        )
                    }
            }
        }
    }

    private suspend fun handleIncoming(msg: ReceivedMessage) {
        if (msg.isAck) {
            val acked = msg.ackedId ?: return
            val key = "ack:" + acked.toHex()
            if (!processed.add(key)) return
            dao.updateDeliveryWithAck(
                acked.toHex(), MsgStatus.DELIVERED, msg.path.toRouteHex(),
                ackPacketHex = msg.raw?.toHex().orEmpty(),
                ackTimestampMs = msg.timestampMs,
            )
            return
        }
        // ACK_BRIDGED: a gateway relayed one of our channel messages onto MeshCore. The bridged
        // datagram id is the channel Message's primary key, so mark it directly.
        msg.bridgedDatagramId?.let { bridgedId ->
            val idHex = bridgedId.toHex()
            if (!processed.add("bridged:$idHex")) return
            dao.markBridgedToMeshCore(idHex, msg.bridgedByNodeId?.toHex() ?: "")
            return
        }
        if (msg.protocol == PayloadProtocol.SIDEPATH_CONTROL && msg.traceResponse != null) {
            handleTraceResponse(msg); return
        }
        when (msg.chatKind) {
            ChatKind.DIRECT_TEXT -> handleIncomingDm(msg)
            ChatKind.CHANNEL_TEXT -> handleIncomingChannel(msg)
            ChatKind.TYPING -> showTyping(msg.fromNodeId.toHex(), msg.sentAtMs)
            ChatKind.DIRECT_REACTION -> handleIncomingDirectReaction(msg)
            ChatKind.CHANNEL_REACTION -> handleIncomingChannelReaction(msg)
            else -> return // PUBLIC_TEXT / other: not part of the direct/channel chat model
        }
    }

    private suspend fun handleIncomingDirectReaction(msg: ReceivedMessage) {
        val target = msg.reactionTargetRef ?: return
        if (!processed.add(msg.datagramId.toHex())) return
        applyReaction(target, msg.fromNodeId.toHex(), msg.reactionEmoji.orEmpty(), msg.reactionRemove, msg.timestampMs)
    }

    private suspend fun handleIncomingChannelReaction(msg: ReceivedMessage) {
        val cp = msg.channelReactionPayload ?: return
        if (!processed.add(msg.datagramId.toHex())) return
        val hashByte = ChatChannel.payloadChannelHash(cp) ?: return
        for (ch in dao.channelsByHash(hashByte)) {
            val d = ChatChannelReaction.decodePayload(ch.pskHex.hexToBytes(), cp) ?: continue
            applyReaction(d.targetRef, msg.fromNodeId.toHex(), d.emoji, d.remove, msg.timestampMs)
            return
        }
    }

    /** Adds or removes one author's emoji reaction on a target message (empty emoji = ignore). */
    private suspend fun applyReaction(messageId: String, author: String, emoji: String, remove: Boolean, ts: Long) {
        if (author.isBlank()) return
        if (remove || emoji.isBlank()) dao.deleteReaction(messageId, author)
        else dao.upsertReaction(Reaction(messageId, author, emoji, ts))
    }

    /** Marks [peerHex] as typing and (re)arms a timer to clear it if no fresh hint arrives. */
    private fun showTyping(peerHex: String, sentAtMs: Long) {
        val lastReal = lastRealMessageSentAtMs[peerHex] ?: 0L
        if (sentAtMs > 0L && lastReal > 0L && sentAtMs <= lastReal) return
        _typingPeers.value = _typingPeers.value + peerHex
        typingExpiry.remove(peerHex)?.cancel()
        // A bit longer than the sender's 10s resend so a steady typist stays "typing".
        typingExpiry[peerHex] = viewModelScope.launch {
            delay(13_000)
            clearTyping(peerHex)
        }
    }

    private fun clearTyping(peerHex: String) {
        typingExpiry.remove(peerHex)?.cancel()
        if (peerHex in _typingPeers.value) _typingPeers.value = _typingPeers.value - peerHex
    }

    private suspend fun handleIncomingDm(msg: ReceivedMessage) {
        val peer = msg.fromNodeId.toHex()
        // A bridged MeshCore DM arrives over several LoRa/Sidepath paths as distinct carrier datagrams
        // but with an identical encrypted payload, so key its id on that payload (path-independent) to
        // collapse duplicates. Native Sidepath DMs keep their unique datagram id.
        val dedupPayload = msg.channelPayload
        val id = if (msg.fromMeshCore && dedupPayload != null)
            "mcdm:" + sha256Hex(dedupPayload)
        else msg.datagramId.toHex().ifEmpty { newId() }
        if (!processed.add(id)) return
        if (msg.sentAtMs > 0L) {
            lastRealMessageSentAtMs[peer] = maxOf(lastRealMessageSentAtMs[peer] ?: 0L, msg.sentAtMs)
        }
        // A real message means they're no longer typing — drop the indicator at once.
        clearTyping(peer)
        dao.insertMessage(
            Message(
                id = id,
                peerHex = peer,
                senderHex = peer,
                incoming = true,
                text = msg.text ?: "[unable to decrypt]",
                timestampMs = msg.timestampMs,
                status = MsgStatus.DELIVERED,
                routeHex = msg.path.toRouteHex(),
                read = false,
                viaMeshCore = msg.fromMeshCore,
                meshCoreType = msg.meshCoreType.orEmpty(),
                meshCoreRoute = msg.meshCoreRoute.orEmpty(),
                meshCoreHops = msg.meshCoreHops,
                meshCorePacketId = msg.meshCorePacketId.orEmpty(),
                packetHex = msg.raw?.toHex().orEmpty(),
                meshCorePacketHex = msg.meshCorePacketRaw?.toHex().orEmpty(),
                rssi = msg.rssi,
            )
        )
        // The service verified the sender (MeshCore MAC, or outer.source == sender_public_key[:10] for
        // native DMs); save the sender's key so we can reply even if it's not (yet) in our topology.
        learnContact(peer, msg.senderPublicKey, isMeshCore = msg.fromMeshCore)
    }

    private suspend fun handleIncomingChannel(msg: ReceivedMessage) {
        val channelPayload = msg.channelPayload ?: return
        val hashByte = ChatChannel.payloadChannelHash(channelPayload) ?: return
        // Identity = the encrypted channel_payload (identical for the same message across LoRa/Sidepath
        // paths, since AES-ECB is deterministic), so a re-routed duplicate collapses to one row. Native
        // Sidepath channel messages keep their unique datagram id.
        val id = if (msg.fromMeshCore) "mc:" + channelPayload.toHex()
        else msg.datagramId.toHex().ifEmpty { newId() }
        if (!processed.add(id)) return
        // Try every joined channel whose hash matches; the MAC tells us which one decrypts.
        for (ch in dao.channelsByHash(hashByte)) {
            val decoded = ChatChannel.decodePayload(ch.pskHex.hexToBytes(), channelPayload) ?: continue
            dao.insertMessage(
                Message(
                    id = id,
                    peerHex = channelPeerId(ch.pskHex),
                    // A bridged author is unverifiable (no public key) — keep only its declared
                    // name and record which Sidepath node bridged it. Native channel messages keep
                    // the real originating node as the sender.
                    senderHex = if (msg.fromMeshCore) "" else msg.fromNodeId.toHex(),
                    bridgeHex = if (msg.fromMeshCore) msg.fromNodeId.toHex() else "",
                    senderName = decoded.senderLabel,
                    incoming = true,
                    text = decoded.text,
                    timestampMs = msg.timestampMs,
                    status = MsgStatus.DELIVERED,
                    routeHex = msg.path.toRouteHex(),
                    read = false,
                    viaMeshCore = msg.fromMeshCore,
                    meshCoreType = msg.meshCoreType.orEmpty(),
                    meshCoreRoute = msg.meshCoreRoute.orEmpty(),
                    meshCoreHops = msg.meshCoreHops,
                    meshCorePacketId = msg.meshCorePacketId.orEmpty(),
                    // Native Sidepath channel messages carry their raw datagram for "Packet details";
                    // bridged MeshCore ones carry the inner MeshCore packet (examined separately).
                    packetHex = msg.raw?.toHex().orEmpty(),
                    meshCorePacketHex = msg.meshCorePacketRaw?.toHex().orEmpty(),
                    rssi = msg.rssi,
                )
            )
            return
        }
    }

    /** Saves/updates a contact's public key. Prefers an explicit key, else the topology key. */
    private suspend fun learnContact(nodeHex: String, pub: ByteArray?, isMeshCore: Boolean = false) {
        val topo = topology.value.firstOrNull { it.nodeId.toHex() == nodeHex }
        val key = pub?.takeIf { it.size == 32 } ?: topo?.publicKey?.takeIf { it.size == 32 } ?: return
        val existing = dao.contactByNode(nodeHex)
        val desc = topo?.description ?: existing?.description ?: ""
        dao.upsertContact(
            Contact(nodeHex, key.toHex(), desc, localName = existing?.localName.orEmpty(),
                isMeshCore = (existing?.isMeshCore ?: false) || isMeshCore,
                nameIsCustom = existing?.nameIsCustom ?: false),
        )
    }

    private fun sha256Hex(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).toHex()

    // ---- actions -------------------------------------------------------------

    /** Ensures a contact row exists for [node] before opening its conversation. */
    fun startChat(node: AdvertisedNode) {
        viewModelScope.launch {
            val existing = dao.contactByNode(node.nodeHex)
            dao.upsertContact(
                Contact(node.nodeHex, node.pubKeyHex, node.description,
                    localName = existing?.localName.orEmpty(), isMeshCore = existing?.isMeshCore ?: false,
                    nameIsCustom = existing?.nameIsCustom ?: false),
            )
        }
    }

    /**
     * Adds [peerHex] to contacts (from a discovered node or the topology) so it appears in Chats,
     * resolving its public key and carrying over MeshCore origin / advertised name. Used when
     * starting a chat from a discovered node's profile. No-op if it's already a contact.
     */
    fun startChat(peerHex: String) {
        viewModelScope.launch {
            val existing = dao.contactByNode(peerHex)
            val topo = topology.value.firstOrNull { it.nodeId.toHex() == peerHex }
            val disc = discoveredContacts.value.firstOrNull { it.nodeHex == peerHex }
                ?: dao.discoveredByPubKey(existing?.pubKeyHex.orEmpty())
            val pub = existing?.pubKeyHex?.takeIf { it.length == 64 }
                ?: topo?.publicKey?.takeIf { it.size == 32 }?.toHex()
                ?: disc?.pubKeyHex?.takeIf { it.length == 64 }
                ?: ""
            val isMeshCore = existing?.isMeshCore ?: (disc?.source == DiscoverySource.MESHCORE)
            // Keep a chosen alias; else seed a MeshCore node's advertised name as the local name.
            // A seeded advert name is not "custom", so later adverts can refresh it.
            val localName = existing?.localName?.takeIf { it.isNotBlank() }
                ?: disc?.name?.takeIf { isMeshCore && it.isNotBlank() }
                ?: ""
            dao.upsertContact(
                Contact(peerHex, pub, existing?.description.orEmpty(), localName = localName,
                    isMeshCore = isMeshCore, nameIsCustom = existing?.nameIsCustom ?: false),
            )
        }
    }

    /** Live profile (name, public key, contact state) for the profile page. */
    fun profileFor(peerHex: String): StateFlow<ProfileInfo> =
        combine(contacts, topology, channels, dao.discoveredContacts()) { c, t, chans, discovered ->
            if (isChannelPeer(peerHex)) {
                val psk = channelPskHexOf(peerHex)
                val ch = chans.firstOrNull { it.pskHex == psk }
                ProfileInfo(
                    peerHex = peerHex,
                    isChannel = true,
                    name = ch?.name ?: "Channel",
                    channelKind = ch?.kind ?: "",
                    channelHash = ch?.hashByte ?: 0,
                    pskHex = psk,
                )
            } else if (peerHex == nodeId.value.toHex()) {
                // Our own profile (opened from the avatar): use our local identity/settings.
                ProfileInfo(
                    peerHex = peerHex,
                    isChannel = false,
                    name = myName.value.ifBlank { shortHex(peerHex) },
                    nodeHex = peerHex,
                    pubKeyHex = myPubKeyHex.value,
                    isContact = false,
                    isSelf = true,
                    online = true,
                    description = _description.value,
                    platform = _service.value?.defaultPlatform() ?: "",
                )
            } else {
                val byNode = c.associateBy { it.nodeHex }
                val contact = byNode[peerHex]
                val inTopo = t.any { it.nodeId.toHex() == peerHex }
                // Discovered (heard advertising) row — by node id, then public key. Kept even after
                // the node becomes a contact so the profile's MeshCore section still resolves.
                val pubFromKnown = publicKeyHex(peerHex, byNode, t)
                val disc = discovered.firstOrNull { it.nodeHex == peerHex }
                    ?: pubFromKnown.takeIf { it.isNotBlank() }?.let { pk -> discovered.firstOrNull { it.pubKeyHex == pk } }
                val isContact = contact != null
                val pub = pubFromKnown.ifBlank { disc?.pubKeyHex.orEmpty() }
                val name = when {
                    isContact || inTopo -> displayName(peerHex, byNode, t)
                    !disc?.name.isNullOrBlank() -> disc!!.name
                    pub.isNotBlank() -> nameFromPubKey(pub).ifBlank { shortHex(peerHex) }
                    else -> shortHex(peerHex)
                }
                val isMeshCore = contact?.isMeshCore ?: (disc?.source == DiscoverySource.MESHCORE)
                ProfileInfo(
                    peerHex = peerHex,
                    isChannel = false,
                    name = name,
                    nodeHex = peerHex,
                    pubKeyHex = pub,
                    isContact = isContact,
                    online = inTopo,
                    description = nodeDescription(peerHex, byNode, t),
                    platform = nodePlatform(peerHex, t),
                    neighborHexes = t.firstOrNull { it.nodeId.toHex() == peerHex }
                        ?.neighborIds?.map { it.toHex() } ?: emptyList(),
                    isMeshCore = isMeshCore,
                    isDiscovered = !isContact && disc != null,
                    nodeType = disc?.nodeType ?: 0,
                    hasGps = disc?.hasGps ?: false,
                    lat = disc?.lat ?: 0.0,
                    lon = disc?.lon ?: 0.0,
                    sigVerified = disc?.sigVerified ?: false,
                    nodeAdvertisedMs = disc?.nodeAdvertisedMs ?: 0L,
                    networkCode = disc?.networkCode.orEmpty(),
                )
            }
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly,
            ProfileInfo(peerHex, isChannelPeer(peerHex), name = shortHex(peerHex)),
        )

    /**
     * Sets a contact's local alias (Rename), preserving its public key and description (creating
     * the row if needed). The alias overrides the node's wire name in every view via [displayName].
     */
    fun renameContact(nodeHex: String, newName: String) {
        viewModelScope.launch {
            val existing = dao.contactByNode(nodeHex)
            val pub = existing?.pubKeyHex
                ?: topology.value.firstOrNull { it.nodeId.toHex() == nodeHex }
                    ?.publicKey?.takeIf { it.size == 32 }?.toHex()
                ?: ""
            dao.upsertContact(
                Contact(nodeHex, pub, existing?.description.orEmpty(), localName = newName.trim(),
                    isMeshCore = existing?.isMeshCore ?: false, nameIsCustom = true),
            )
        }
    }

    /**
     * Removes a contact along with its conversation history — a contact and its chat are one and
     * the same in Chats, so deleting one deletes the other (the node can be re-discovered in
     * Explore). See also [deleteChat].
     */
    fun deleteContact(nodeHex: String) {
        viewModelScope.launch {
            dao.deleteContact(nodeHex)
            dao.deleteReactionsForPeer(nodeHex)
            dao.deleteEchoesForPeer(nodeHex)
            dao.deleteMeshCoreHeardsForPeer(nodeHex)
            dao.deleteMessagesFor(nodeHex)
        }
    }

    /** Renames a joined channel (keeps its PSK / hash). */
    fun renameChannel(pskHex: String, newName: String) {
        viewModelScope.launch {
            dao.channelByPsk(pskHex)?.let { dao.upsertChannel(it.copy(name = newName.trim())) }
        }
    }

    /**
     * Signals that the local user is actively typing in the DM with [peerHex]. Starts a loop
     * that emits a typing hint immediately and re-sends every 10s until [stopTyping]. No-op for
     * channels (broadcast typing would just spam the mesh).
     */
    fun onUserTyping(peerHex: String) {
        if (isChannelPeer(peerHex)) return
        if (outgoingTypingPeer == peerHex && outgoingTypingJob?.isActive == true) return
        outgoingTypingJob?.cancel()
        outgoingTypingPeer = peerHex
        outgoingTypingJob = viewModelScope.launch {
            val dst = NodeId.fromHex(peerHex)
            while (isActive) {
                _service.value?.sendTyping(dst)
                delay(10_000)
            }
        }
    }

    /** Stops re-sending typing hints for [peerHex] (user cleared the field, sent, or left). */
    fun stopTyping(peerHex: String) {
        if (outgoingTypingPeer == peerHex) {
            outgoingTypingJob?.cancel()
            outgoingTypingJob = null
            outgoingTypingPeer = null
        }
    }

    /** Sends an encrypted direct message to a node conversation. */
    fun sendChat(peerHex: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        // Note to Self: a local notepad — store the note, never transmit it over the mesh.
        if (peerHex == nodeId.value.toHex()) {
            viewModelScope.launch {
                dao.insertMessage(
                    Message(
                        id = newId(), peerHex = peerHex, senderHex = peerHex, incoming = false,
                        text = body, timestampMs = System.currentTimeMillis(), status = MsgStatus.DELIVERED,
                    )
                )
            }
            return
        }
        val service = _service.value ?: return
        stopTyping(peerHex)
        viewModelScope.launch {
            val contact = dao.contactByNode(peerHex)
            val pub = contact?.pubKeyHex?.takeIf { it.length == 64 }?.hexToBytes()
            // A MeshCore contact is reached over the bridge as a MeshCore TXT_MSG (not a native
            // encrypted Sidepath DM). Delivery (✓✓) comes from the radio ACK via the same isAck path.
            if (contact?.isMeshCore == true && pub != null) {
                val id = service.sendMeshCoreDirect(pub, body)
                dao.insertMessage(
                    Message(
                        id = id?.toHex() ?: newId(),
                        peerHex = peerHex,
                        senderHex = nodeId.value.toHex(),
                        incoming = false,
                        text = body,
                        timestampMs = System.currentTimeMillis(),
                        status = if (id == null) MsgStatus.FAILED else MsgStatus.SENT,
                        viaMeshCore = true,
                        packetHex = id?.let { service.originatedPacketHex(it.toHex()) }.orEmpty(),
                    )
                )
                return@launch
            }
            val dst = NodeId.fromHex(peerHex)
            // Resolve the recipient's key from the saved contact (learned from a received DM
            // or the picker), falling back to topology inside the service.
            val id = service.sendChat(body, dst, pub, floodTtl = _floodTtl.value)
            dao.insertMessage(
                Message(
                    id = id?.toHex() ?: newId(),
                    peerHex = peerHex,
                    senderHex = nodeId.value.toHex(),
                    incoming = false,
                    text = body,
                    timestampMs = System.currentTimeMillis(),
                    status = if (id == null) MsgStatus.FAILED else MsgStatus.SENT,
                    packetHex = id?.let { service.originatedPacketHex(it.toHex()) }.orEmpty(),
                )
            )
        }
    }

    /** Sends a message to a channel (MeshCore GRP_TXT, broadcast to the mesh). */
    fun sendChannelMessage(pskHex: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val service = _service.value ?: return
        viewModelScope.launch {
            // The channel sender label is our display name (deterministic name or override),
            // matching what everyone shows for us elsewhere — not the free-form description.
            val myLabel = myName.value.ifBlank { shortHex(nodeId.value.toHex()) }
            val id = service.sendChannel(pskHex.hexToBytes(), myLabel, body, _floodTtl.value)
            dao.insertMessage(
                Message(
                    id = id.toHex(),
                    peerHex = channelPeerId(pskHex),
                    senderHex = nodeId.value.toHex(),
                    senderName = myLabel,
                    incoming = false,
                    text = body,
                    timestampMs = System.currentTimeMillis(),
                    status = MsgStatus.SENT, // broadcast — no per-message ACK
                    packetHex = service.originatedPacketHex(id.toHex()).orEmpty(),
                )
            )
        }
    }

    /**
     * Toggles my emoji reaction on [msg]: tapping the same emoji removes it, a different emoji
     * replaces it. Applied locally at once and broadcast/sent over the mesh (DIRECT_REACTION for a
     * DM, CHANNEL_REACTION for a channel) so other members converge.
     */
    fun toggleReaction(msg: Message, emoji: String) {
        val me = nodeId.value.toHex()
        viewModelScope.launch {
            val remove = dao.reactionBy(msg.id, me)?.emoji == emoji
            applyReaction(msg.id, me, emoji, remove, System.currentTimeMillis())
            val service = _service.value ?: return@launch
            if (isChannelPeer(msg.peerHex)) {
                val secret = channelPskHexOf(msg.peerHex).hexToBytes()
                val label = myName.value.ifBlank { shortHex(me) }
                service.sendChannelReaction(secret, label, msg.id, emoji, remove)
            } else {
                val dst = NodeId.fromHex(msg.peerHex)
                val pub = dao.contactByNode(msg.peerHex)?.pubKeyHex?.takeIf { it.length == 64 }?.hexToBytes()
                service.sendDirectReaction(dst, pub, msg.id, emoji, remove)
            }
        }
    }

    // ---- channel management --------------------------------------------------

    /** Joins a channel and returns its conversation peer id, so the UI can jump straight there. */
    fun joinPublic(): String = joinChannelPsk(ChatChannel.PUBLIC_SECRET, "Public", ChannelKind.PUBLIC)

    /** Joins a named channel; returns its peer id, or null if the name is blank. */
    fun joinNamedChannel(name: String): String? {
        val n = name.trim().removePrefix("#").trim()
        if (n.isEmpty()) return null
        // MeshCore derives a hashtag channel's key from the name INCLUDING the leading '#'
        // (e.g. SHA-256("#xxxyyy")[:16]). Match that exactly so named channels interoperate;
        // we store the bare name ("xxxyyy") and render the '#' via channelLabel.
        return joinChannelPsk(ChatChannel.namedSecret("#$n"), n, ChannelKind.NAMED)
    }

    /** Joins a secret channel; returns its peer id, or null if the secret is blank. */
    fun joinSecretChannel(name: String, secret: String): String? {
        val n = name.trim().ifBlank { "Secret" }
        val s = secret.trim()
        if (s.isEmpty()) return null
        // A 32-hex-char secret is taken as the raw 16-byte PSK; anything else is hashed.
        val psk = if (s.length == 32 && s.all { it.lowercaseChar() in "0123456789abcdef" }) s.hexToBytes()
        else ChatChannel.namedSecret(s)
        return joinChannelPsk(psk, n, ChannelKind.SECRET)
    }

    fun leaveChannel(pskHex: String) {
        viewModelScope.launch { dao.deleteChannel(pskHex) }
    }

    // ---- MeshCore deep links (meshcore://… opened from a QR / link) ----------

    /** A conversation/channel the UI should open in response to a deep link, or null. */
    private val _pendingOpenPeer = MutableStateFlow<String?>(null)
    val pendingOpenPeer: StateFlow<String?> = _pendingOpenPeer.asStateFlow()

    fun consumePendingOpen() { _pendingOpenPeer.value = null }

    /**
     * Handles a scanned/clicked `meshcore://` link: joins the channel or adds the contact, then
     * asks the UI to open it. Returns true if the URI was a recognised MeshCore link.
     */
    fun handleSharedUri(uri: String): Boolean {
        MeshCoreUri.parseChannel(uri)?.let { ch ->
            joinSecretChannel(ch.name, ch.secretHex)
            _pendingOpenPeer.value = channelPeerId(ch.secretHex.lowercase())
            return true
        }
        MeshCoreUri.parseContact(uri)?.let { c ->
            val nodeHex = c.publicKeyHex.take(Sidepath.NODE_ID_BYTES * 2)
            // The shared contact's name is a user-facing label → store it as the local alias.
            viewModelScope.launch {
                val existing = dao.contactByNode(nodeHex)
                dao.upsertContact(
                    Contact(nodeHex, c.publicKeyHex, existing?.description.orEmpty(), localName = c.name,
                        isMeshCore = existing?.isMeshCore ?: false, nameIsCustom = true),
                )
            }
            _pendingOpenPeer.value = nodeHex
            return true
        }
        return false
    }

    private fun joinChannelPsk(psk: ByteArray, name: String, kind: String): String {
        val pskHex = psk.toHex()
        viewModelScope.launch {
            dao.upsertChannel(
                Channel(
                    pskHex = pskHex,
                    name = name,
                    hashByte = ChatChannel.channelHash(psk).toInt() and 0xFF,
                    kind = kind,
                )
            )
        }
        return channelPeerId(pskHex)
    }

    fun markRead(peerHex: String) {
        viewModelScope.launch { dao.markRead(peerHex) }
    }

    /**
     * Deletes a conversation. For a direct chat this also removes the contact, so it disappears
     * from Chats entirely (it can be re-discovered in Explore); channel history is just cleared.
     */
    fun deleteChat(peerHex: String) {
        viewModelScope.launch {
            dao.deleteReactionsForPeer(peerHex)
            dao.deleteEchoesForPeer(peerHex)
            dao.deleteMeshCoreHeardsForPeer(peerHex)
            dao.deleteMessagesFor(peerHex)
            if (!isChannelPeer(peerHex)) dao.deleteContact(peerHex)
        }
    }

    fun setDescription(text: String) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[DESC_KEY] = text }
            _description.value = text
            _service.value?.setDescription(text)
        }
    }

    /** Updates DM delivery retry settings (wait time per try and total tries). */
    fun setDmRetry(retryDelayMs: Int, maxTries: Int) {
        val delay = retryDelayMs.coerceAtLeast(500)
        val tries = maxTries.coerceIn(1, 10)
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[DM_RETRY_DELAY_KEY] = delay
                it[DM_MAX_TRIES_KEY] = tries
            }
            _dmRetryDelayMs.value = delay
            _dmMaxTries.value = tries
            _service.value?.setDmRetry(delay.toLong(), tries)
        }
    }

    /** Sets the hop limit (TTL) for messages we originate; clamped to 1..MAX_TTL. */
    fun setFloodTtl(ttl: Int) {
        val clamped = ttl.coerceIn(1, Sidepath.MAX_TTL)
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[FLOOD_TTL_KEY] = clamped }
            _floodTtl.value = clamped
        }
    }

    /** The CoreScope-compatible MeshCore content hash of a raw inner packet, or null if undecodable. */
    fun meshContentHash(raw: ByteArray): String? = MeshCoreCodec.computeContentHash(raw)

    /**
     * The advertised public key (hex) of a MeshCore ADVERT packet, or null when the packet isn't an
     * advert (or can't be decoded). Lets the profile match a bridged node to the advert it heard.
     */
    fun meshAdvertPubKeyHex(p: cz.meshcore.sidepath.meshcore.MeshCorePacket): String? {
        val env = p.envelope ?: return null
        if (env.type != cz.meshcore.sidepath.meshcore.MeshCoreType.ADVERT) return null
        return MeshCoreCodec.decodeAdvert(env.payload)?.publicKeyHex
    }

    /**
     * Saves the configured packet-analyzer base URLs (one per line in [text]). Blank input restores
     * the single default. The MeshCore content hash is appended to a chosen base to form the link.
     */
    fun setAnalyzerUrls(text: String) {
        val list = parseAnalyzerUrls(text)
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[ANALYZER_URLS_KEY] = list.joinToString("\n") }
            _analyzerUrls.value = list
        }
    }

    /** Sets the user's display-name override (blank resets to the deterministic default). */
    fun setName(text: String) {
        val clean = text.trim()
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[NAME_KEY] = clean }
            _name.value = clean
            _service.value?.setName(clean)
        }
    }

    fun setPhyMode(mode: PHYMode) {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[PHY_MODE_KEY] = mode.value }
            _service.value?.setPhyMode(mode)
        }
    }

    /** Replaces the identity seed (64 hex chars) and restarts the mesh node. */
    fun applySeed(hex: String): Boolean {
        val clean = hex.trim().lowercase()
        if (clean.length != Sidepath.SEED_BYTES * 2 || !clean.all { it in "0123456789abcdef" }) return false
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[SEED_KEY] = clean }
            _seedHex.value = clean
            processed.clear()
            restartService()
        }
        return true
    }

    fun regenerateSeed() {
        applySeed(generateSeedHex())
    }

    private fun restartService() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, SidepathService::class.java)
        if (serviceBound) {
            runCatching { ctx.unbindService(serviceConnection) }
            serviceBound = false
        }
        ctx.stopService(intent)
        _service.value = null
        bindService()
    }

    override fun onCleared() {
        super.onCleared()
        if (serviceBound) {
            runCatching { getApplication<Application>().unbindService(serviceConnection) }
            serviceBound = false
        }
    }

    /**
     * Decodes a stored raw datagram (hex) back into an [RxPacket] for the packet-detail dialog —
     * used to show our own outgoing packet and each persisted echo, both of which keep their raw
     * bytes in Room. [rssi]/[timestampMs] override the reconstructed defaults for an echo reception.
     */
    fun decodePacket(
        hex: String,
        rssi: Int = cz.meshcore.sidepath.service.RSSI_UNKNOWN,
        timestampMs: Long = System.currentTimeMillis(),
    ): cz.meshcore.sidepath.service.RxPacket? = runCatching {
        val data = hex.hexToBytes()
        val dg = cz.meshcore.sidepath.protocol.Datagram.decode(data)
        val chatKind = if (dg.protocol == PayloadProtocol.SIDEPATH_CHAT)
            cz.meshcore.sidepath.chat.Chat.peekKind(dg.payload) else null
        val controlKind = if (dg.protocol == PayloadProtocol.SIDEPATH_CONTROL)
            runCatching { cz.meshcore.sidepath.protocol.ControlMessage.decode(dg.payload).kind }.getOrNull() else null
        cz.meshcore.sidepath.service.RxPacket(
            timestampMs = timestampMs,
            protocol = dg.protocol, chatKind = chatKind, controlKind = controlKind,
            id = dg.id, source = dg.source, destination = dg.destination,
            sourceRouted = dg.isSourceRouted, routeCursor = dg.routeCursor, ttl = dg.ttl,
            flags = dg.flags, ackRequested = dg.ackRequested(),
            path = dg.path, route = dg.route, payloadSize = dg.payload.size, raw = data,
            forUs = dg.isBroadcast || dg.destination.toHex() == nodeId.value.toHex(),
            rssi = rssi, droppedReason = null,
        )
    }.getOrNull()

    /**
     * Rebuilds a [MeshCorePacket] from the raw inner MeshCore bytes persisted on a bridged message
     * ([Message.meshCorePacketHex]), so its packet details / "Examine" work after the live Rx Log
     * entry has aged out or the app restarted. The envelope is re-decoded; the Sidepath carrier
     * fields come from the message (the carrier datagram id isn't stored, so it stays blank).
     */
    fun decodeMeshCorePacket(hex: String, msg: cz.meshcore.meshward.data.Message): cz.meshcore.sidepath.meshcore.MeshCorePacket? = runCatching {
        val raw = hex.hexToBytes()
        val path = msg.routeHex.split(",").filter { it.isNotBlank() }.map { NodeId(it.hexToBytes()) }
        cz.meshcore.sidepath.meshcore.MeshCorePacket(
            timestampMs = msg.timestampMs,
            source = if (msg.bridgeHex.isNotBlank()) NodeId(msg.bridgeHex.hexToBytes()) else NodeId.BROADCAST,
            datagramId = ByteArray(0),
            path = path,
            directRssi = cz.meshcore.sidepath.service.RSSI_UNKNOWN,
            raw = raw,
            envelope = cz.meshcore.sidepath.meshcore.MeshCoreCodec.decodeEnvelope(raw),
            contentId = msg.meshCorePacketId,
            channelSender = msg.senderName.ifBlank { null },
            channelText = msg.text,
        )
    }.getOrNull()

    /**
     * Rebuilds a [MeshCorePacket] from just the raw inner MeshCore bytes (no carrying [Message]),
     * for the profile's persisted MeshCore ADVERT. Only the decoded envelope + receipt time are known.
     */
    fun decodeMeshCorePacketRaw(hex: String, timestampMs: Long): cz.meshcore.sidepath.meshcore.MeshCorePacket? = runCatching {
        val raw = hex.hexToBytes()
        cz.meshcore.sidepath.meshcore.MeshCorePacket(
            timestampMs = timestampMs,
            source = NodeId.BROADCAST,
            datagramId = ByteArray(0),
            path = emptyList(),
            directRssi = cz.meshcore.sidepath.service.RSSI_UNKNOWN,
            raw = raw,
            envelope = cz.meshcore.sidepath.meshcore.MeshCoreCodec.decodeEnvelope(raw),
        )
    }.getOrNull()

    /** The persisted last-heard announcements (Sidepath + MeshCore) for a node, for its profile. */
    fun announcementsForNode(nodeHex: String) = dao.announcementsForNode(nodeHex)

    /** Resolves a node id (hex) to its best-known display name, for route detail rendering. */
    fun nameForHex(hex: String): String =
        displayName(hex, contacts.value.associateBy { it.nodeHex }, topology.value)

    /**
     * Every node whose public key begins with a MeshCore path-hash prefix (hex, the first
     * [pathHashSize] bytes of a repeater's key — usually 1 byte). A 1-byte prefix can match several
     * nodes, so this returns all candidates (deduped by public key); the UI surfaces the ambiguity.
     */
    fun meshCoreHopCandidates(prefixHex: String): List<MeshCoreHopMatch> {
        if (prefixHex.isBlank()) return emptyList()
        val p = prefixHex.lowercase()
        val out = LinkedHashMap<String, MeshCoreHopMatch>()
        discoveredContacts.value.forEach {
            if (it.pubKeyHex.startsWith(p)) out[it.pubKeyHex] =
                MeshCoreHopMatch(it.name.ifBlank { it.nodeHex.take(8) }, it.nodeHex, it.pubKeyHex)
        }
        contacts.value.forEach {
            if (it.pubKeyHex.startsWith(p)) out.putIfAbsent(it.pubKeyHex, MeshCoreHopMatch(nameForHex(it.nodeHex), it.nodeHex, it.pubKeyHex))
        }
        return out.values.toList()
    }

    /** Best-known single display name for a MeshCore hop prefix, or "" if unknown ([meshCoreHopCandidates]). */
    fun meshCoreHopName(prefixHex: String): String = meshCoreHopCandidates(prefixHex).firstOrNull()?.name.orEmpty()

    /**
     * Snapshot of the Room database on disk: file/WAL/SHM sizes, page geometry, and per-table row
     * counts (plus per-table bytes when the `dbstat` virtual table is available). For the debug screen.
     */
    suspend fun databaseStats(): DatabaseStats = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val file = ctx.getDatabasePath("meshward.db")
        val wal = java.io.File(file.path + "-wal")
        val shm = java.io.File(file.path + "-shm")
        val db = ChatDatabase.get(ctx).openHelper.readableDatabase

        fun queryLong(sql: String): Long = db.query(sql).use { if (it.moveToFirst()) it.getLong(0) else 0L }

        val tableNames = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT LIKE 'room_master_table' AND name != 'android_metadata' ORDER BY name",
        ).use { c -> while (c.moveToNext()) tableNames += c.getString(0) }

        // dbstat is an optional SQLite virtual table; absent on some builds, so degrade gracefully.
        val byTableBytes = runCatching {
            val m = HashMap<String, Long>()
            db.query("SELECT name, SUM(pgsize) FROM dbstat GROUP BY name").use { c ->
                while (c.moveToNext()) m[c.getString(0)] = c.getLong(1)
            }
            m
        }.getOrNull()

        val tables = tableNames.map { t ->
            TableStat(t, queryLong("SELECT COUNT(*) FROM `$t`"), byTableBytes?.get(t) ?: -1L)
        }.sortedByDescending { it.rows }

        DatabaseStats(
            path = file.path,
            fileBytes = file.length(),
            walBytes = wal.length(),
            shmBytes = shm.length(),
            pageSize = queryLong("PRAGMA page_size"),
            pageCount = queryLong("PRAGMA page_count"),
            dbStatAvailable = byTableBytes != null,
            tables = tables,
        )
    }

    /** Resolves a node id (hex) to its OS/device platform string (from its ANNOUNCE), or "". */
    fun platformForHex(hex: String): String = nodePlatform(hex, topology.value)

    /** Resolves a node id (hex) to its 32-byte public key (hex), for identicon avatars, or "". */
    fun pubKeyForHex(hex: String): String =
        if (hex == nodeId.value.toHex()) myPubKeyHex.value
        else publicKeyHex(hex, contacts.value.associateBy { it.nodeHex }, topology.value)

    /** This node's own NodeId as hex — used to exclude self from route pickers. */
    fun myNodeHex(): String = nodeId.value.toHex()

    // ---- helpers -------------------------------------------------------------

    /**
     * The primary label for a node: a deterministic name derived from its public key (e.g.
     * "barrel-two-return"), so the same identity reads the same everywhere. Falls back to a short
     * id until the public key is known. Channels keep their joined name. The node's own free-form
     * description is NOT used here — it's shown only on the profile page (see [nodeDescription]).
     */
    private fun displayName(peerHex: String, contacts: Map<String, Contact>, topo: List<TopologyEntry>): String {
        if (isChannelPeer(peerHex)) {
            return channels.value.firstOrNull { it.pskHex == channelPskHexOf(peerHex) }?.name ?: "Channel"
        }
        // A user-chosen local alias (Rename) always wins.
        contacts[peerHex]?.localName?.takeIf { it.isNotBlank() }?.let { return it }
        // Else the node's real name carried on the wire (ANNOUNCE/NODE_INFO); if it hasn't sent
        // one (e.g. ESP32, or not yet learned), derive the deterministic default from its pubkey.
        topo.firstOrNull { it.nodeId.toHex() == peerHex }?.name?.takeIf { it.isNotBlank() }?.let { return it }
        val derived = nameFromPubKey(publicKeyHex(peerHex, contacts, topo))
        return derived.ifBlank { shortHex(peerHex) }
    }

    /** The node's OS/device string (from its ANNOUNCE / NODE_INFO), or "". */
    private fun nodePlatform(peerHex: String, topo: List<TopologyEntry>): String =
        topo.firstOrNull { it.nodeId.toHex() == peerHex }?.platform?.takeIf { it.isNotBlank() } ?: ""

    /** The node's own free-form description (from its ANNOUNCE / NODE_INFO), or "" — profile only. */
    private fun nodeDescription(peerHex: String, contacts: Map<String, Contact>, topo: List<TopologyEntry>): String {
        contacts[peerHex]?.description?.takeIf { it.isNotBlank() }?.let { return it }
        return topo.firstOrNull { it.nodeId.toHex() == peerHex }?.description?.takeIf { it.isNotBlank() } ?: ""
    }

    /** The node's 32-byte Ed25519 key (hex), from a saved contact or the live topology, or "". */
    private fun publicKeyHex(peerHex: String, contacts: Map<String, Contact>, topo: List<TopologyEntry>): String {
        contacts[peerHex]?.pubKeyHex?.takeIf { it.length == 64 }?.let { return it }
        topo.firstOrNull { it.nodeId.toHex() == peerHex }?.publicKey
            ?.takeIf { it.size == 32 }?.let { return it.toHex() }
        return ""
    }

    /** Inserts/updates a discovered contact, preserving its original first-seen time. */
    private suspend fun upsertDiscovered(
        pubKeyHex: String,
        nodeHex: String,
        name: String,
        source: String,
        nodeType: Int = 0,
        hasGps: Boolean = false,
        lat: Double = 0.0,
        lon: Double = 0.0,
        sigVerified: Boolean = false,
        lastAdvertisedMs: Long,
        nodeAdvertisedMs: Long = 0L,
        networkCode: String = "",
    ) {
        val firstSeen = dao.discoveredByPubKey(pubKeyHex)?.firstSeenMs?.takeIf { it > 0 } ?: lastAdvertisedMs
        dao.upsertDiscovered(
            DiscoveredContact(
                pubKeyHex = pubKeyHex,
                nodeHex = nodeHex,
                name = name,
                source = source,
                nodeType = nodeType,
                hasGps = hasGps,
                lat = lat,
                lon = lon,
                sigVerified = sigVerified,
                lastAdvertisedMs = lastAdvertisedMs,
                nodeAdvertisedMs = nodeAdvertisedMs,
                firstSeenMs = firstSeen,
                networkCode = networkCode,
            )
        )
    }

    /**
     * The network code to tier a MeshCore discovery to: the most-recently-announced detected network
     * (the bridge it most plausibly came through). Blank when no bridge is currently detected, so the
     * contact stays untiered rather than mis-attributed. Sidepath-native discoveries are never tiered.
     */
    private fun tierNetworkCode(): String = detectedNetworks.value.firstOrNull()?.code ?: ""

    private fun generateSeedHex(): String =
        ByteArray(Sidepath.SEED_BYTES).also { SecureRandom().nextBytes(it) }.toHex()

    private fun newId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
}

// ---- byte/hex utilities -----------------------------------------------------

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
fun shortHex(hex: String): String = if (hex.length >= 8) "node ${hex.take(8)}" else hex
private fun List<NodeId>.toRouteHex(): String = joinToString(",") { it.toHex() }

/** Parses analyzer base URLs (one per line), trimming blanks; falls back to the single default. */
private fun parseAnalyzerUrls(stored: String?): List<String> =
    stored.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf(DEFAULT_ANALYZER_URL) }
