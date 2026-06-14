package cz.meshcore.meshward.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A conversation key for a channel is "ch:" + the channel's PSK hex. */
fun channelPeerId(pskHex: String): String = "ch:$pskHex"
fun isChannelPeer(peerHex: String): Boolean = peerHex.startsWith("ch:")
fun channelPskHexOf(peerHex: String): String = peerHex.removePrefix("ch:")

/** Channel kinds offered in the Join dialog. */
object ChannelKind {
    const val PUBLIC = "public"
    const val NAMED = "named"  // PSK derived from the name (SHA-256(name)[:16])
    const val SECRET = "secret"
}

/**
 * A joined MeshCore-compatible channel, identified by its 16-byte PSK ([pskHex], 32 hex
 * chars). [hashByte] is the 1-byte channel hash (0..255) used to match inbound packets.
 */
@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey val pskHex: String,
    val name: String,
    val hashByte: Int,
    val kind: String,
)

/**
 * A "Meshcore Network" — a named region/profile the device can operate in (e.g. "CZ"). Bundles the
 * LoRa tech parameters (informational/display only for now — these do NOT reconfigure the radio),
 * useful links, and an optional geographic territory as a GeoJSON geometry. Keyed by its short
 * [code] (≤5 chars). Built-in defaults come from the sidepath-protocol definitions dataset (bundled
 * + refreshable) and are loaded in-memory with [isBuiltin] = true; user-added/override networks are
 * stored in this table (always [isBuiltin] = false).
 * [analyzerUrls] and [mqttEndpoints] are newline-separated lists (parsed with parseAnalyzerUrls).
 */
@Entity(tableName = "mesh_networks")
data class MeshNetwork(
    @PrimaryKey val code: String,
    val name: String,
    val freqMhz: Double = 0.0,
    val bandwidthKhz: Double = 0.0,
    val spreadingFactor: Int = 0,
    val codingRate: Int = 0,      // the N in coding rate 4/N
    val analyzerUrls: String = "",
    val mqttEndpoints: String = "",
    val geoJson: String = "",     // raw GeoJSON geometry (Polygon / MultiPolygon), may be blank
    val description: String = "",
    @androidx.room.Ignore val isBuiltin: Boolean = false,
) {
    // Room needs a constructor that sets every persisted column; isBuiltin is @Ignore'd so DB rows
    // always materialize as custom (isBuiltin = false).
    constructor(
        code: String, name: String, freqMhz: Double, bandwidthKhz: Double, spreadingFactor: Int,
        codingRate: Int, analyzerUrls: String, mqttEndpoints: String, geoJson: String,
        description: String,
    ) : this(code, name, freqMhz, bandwidthKhz, spreadingFactor, codingRate, analyzerUrls,
        mqttEndpoints, geoJson, description, isBuiltin = false)
}

/** Where a discovered contact was heard. */
object DiscoverySource {
    const val SIDEPATH = "sidepath" // a Sidepath node's signed ANNOUNCE (topology)
    const val MESHCORE = "meshcore" // a MeshCore ADVERT bridged onto the mesh
}

/**
 * A node we've heard advertise but haven't added to our contacts. Surfaced on the Explore tab.
 * Keyed by [pubKeyHex] (32-byte Ed25519 key) which both Sidepath nodes and MeshCore nodes carry.
 * [nodeType] is the MeshCore node type (0=unknown 1=chat 2=repeater 3=room 4=sensor; 0 for
 * Sidepath). [lastAdvertisedMs] is local device receipt time and is updated each time we
 * hear the node advertise. [nodeAdvertisedMs] preserves a MeshCore advert's own timestamp,
 * which can be wrong on repeaters without accurate clocks.
 */
@Entity(tableName = "discovered_contacts")
data class DiscoveredContact(
    @PrimaryKey val pubKeyHex: String,
    val nodeHex: String,        // Sidepath NodeId hex (pubkey[:10]); derived for MeshCore too
    val name: String,
    val source: String,         // DiscoverySource.*
    val nodeType: Int = 0,
    val hasGps: Boolean = false,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val sigVerified: Boolean = false,
    val lastAdvertisedMs: Long = 0L,
    val nodeAdvertisedMs: Long = 0L,
    val firstSeenMs: Long = 0L,
    // Meshcore Network this contact was tiered to at discovery (the network of the bridge it came
    // through). Set for MeshCore contacts heard via a gateway; blank for Sidepath-native nodes.
    val networkCode: String = "",
)

/**
 * The most recent announcement we've heard from a node, persisted so the profile's "Last
 * announcement" survives a restart (the live Rx buffers don't). Keyed by ([nodeHex], [source]) so a
 * node can have one Sidepath ANNOUNCE and one MeshCore ADVERT entry. [rawHex] is the raw packet —
 * the full Sidepath datagram for SIDEPATH, the inner MeshCore OTA bytes for MESHCORE — so its packet
 * detail can be rebuilt offline. [timestampMs] is local receipt time.
 */
@Entity(tableName = "node_announcements", primaryKeys = ["nodeHex", "source"])
data class NodeAnnouncement(
    val nodeHex: String,
    val source: String,      // DiscoverySource.*
    val pubKeyHex: String,
    val timestampMs: Long,
    val rawHex: String,
)

/**
 * One emoji reaction by one author on one message. Keyed by ([messageId], [authorHex]) so a person
 * has at most one reaction per message (reacting again replaces it; the same emoji again removes
 * it). [messageId] is the target [Message.id]; [authorHex] is the reactor's node id hex.
 */
@Entity(tableName = "reactions", primaryKeys = ["messageId", "authorHex"])
data class Reaction(
    val messageId: String,
    val authorHex: String,
    val emoji: String,
    val timestampMs: Long,
)

/**
 * A persisted "echo" of one of our own flooded messages heard back across the mesh (a relay
 * rebroadcast, or a MeshCore round-trip). Stored per-message so the echo count / delivery proof
 * survives an app restart, unlike the in-memory routing logs. [packetHex] is the raw received
 * datagram (hex) so the echo is clickable through to its packet detail.
 */
@Entity(tableName = "echoes", primaryKeys = ["messageId", "timestampMs"])
data class Echo(
    val messageId: String,
    val timestampMs: Long,
    val rssi: Int,
    val forwarderHex: String = "",
    val viaMeshCore: Boolean = false,
    val packetHex: String = "",
)

/**
 * One distinct-path reception ("heard") of a bridged MeshCore channel message. The same MeshCore
 * packet floods to us along several routes; each arrives wrapped in its own Sidepath carrier and
 * carries a different accumulated path, so it dedups to a distinct [contentId]. Stored per-message
 * so the full set of paths survives a restart (the chat [Message] itself collapses them to one row).
 *
 * [hopsHex] is the comma-separated list of per-hop path-hash prefixes (each [pathHashSize] bytes);
 * [packetHex] is the inner MeshCore OTA packet and [carrierHex] the Sidepath carrier datagram, both
 * kept so the heard's path / packet detail can be rebuilt offline.
 */
@Entity(tableName = "meshcore_heards", primaryKeys = ["messageId", "contentId"])
data class MeshCoreHeard(
    val messageId: String,
    val contentId: String,
    val timestampMs: Long,
    val rssi: Int,
    val forwarderHex: String = "",
    val hopCount: Int = 0,
    val pathHashSize: Int = 0,
    val routeLabel: String = "",
    val hopsHex: String = "",
    val packetHex: String = "",
    val carrierHex: String = "",
)

/** Outgoing-message delivery state. */
object MsgStatus {
    const val SENDING = 0
    const val SENT = 1       // transmitted to the mesh
    const val DELIVERED = 2  // recipient ACKed (direct messages only)
    const val FAILED = 3     // could not be sent (e.g. unknown public key)
}

/**
 * A peer we have learned about (from its ANNOUNCE) or chatted with.
 * [pubKeyHex] is the 32-byte Ed25519 key used to encrypt direct messages.
 */
@Entity(tableName = "contacts")
data class Contact(
    @PrimaryKey val nodeHex: String,
    val pubKeyHex: String,
    val description: String,
    // A user-chosen local alias (from Rename) OR a MeshCore node's last advertised name. Overrides
    // the node's wire/derived name in all views; empty means "use the node's advertised name".
    // Distinct from [description].
    val localName: String = "",
    // True if [localName] was set by the user (Rename / shared QR) rather than seeded from a
    // MeshCore advert. A manual name is pinned; an advert-seeded name is refreshed by later adverts.
    val nameIsCustom: Boolean = false,
    // True if this contact was added from a bridged MeshCore node. Such nodes aren't directly
    // reachable over Sidepath (DMs to them will fail), but they're kept as full contacts so their
    // name resolves and they appear in Chats. Surfaced as a "MeshCore" label on the profile.
    val isMeshCore: Boolean = false,
)

/**
 * One chat message. [id] is the mesh packet id (hex) so an inbound delivery ACK can be
 * matched back to the outgoing message it confirms. [peerHex] is the conversation:
 * the other node's id for a direct chat, or "ch:"+pskHex for a channel (see [channelPeerId]).
 * [routeHex] is a comma-separated hop path (the trace the packet took, or for a
 * delivered DM the route the ACK returned along).
 */
@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    val peerHex: String,
    val senderHex: String = "",  // originating node id (hex)
    val senderName: String = "", // display name of the sender (from a channel message's plaintext)
    val incoming: Boolean,
    val text: String,
    val timestampMs: Long,
    val status: Int = MsgStatus.SENT,
    val routeHex: String = "",
    val read: Boolean = false,
    val viaMeshCore: Boolean = false, // true if this message arrived over the MeshCore bridge
    // For a bridged channel message, the Sidepath node that injected it onto the mesh (the
    // "bridge"). The real author is unverifiable — only its declared name is known, in
    // [senderName] — so [senderHex] is left blank for bridged messages.
    val bridgeHex: String = "",
    // MeshCore carrier details (only set when viaMeshCore), shown in message details.
    val meshCoreType: String = "",
    val meshCoreRoute: String = "",
    val meshCoreHops: Int = 0,
    val meshCorePacketId: String = "",
    // Set when a gateway relayed this (outgoing channel) message onto MeshCore and sent back an
    // ACK_BRIDGED. [bridgedByHex] is the gateway NodeID hex.
    val bridgedToMeshCore: Boolean = false,
    val bridgedByHex: String = "",
    // Raw outgoing datagram (hex) for messages we sent, so "Packet details" can show our own
    // packet (persisted, unlike the trimmed Rx Log). Empty for incoming messages.
    val packetHex: String = "",
    // Raw inner MeshCore OTA packet (hex) for a bridged incoming message, so its "Examine" /
    // MeshCore packet details survive the packet ageing out of the Rx Log or an app restart.
    val meshCorePacketHex: String = "",
    // RSSI (dBm) of the link this message was received on, for incoming messages. Int.MIN_VALUE
    // (RSSI_UNKNOWN) when unknown. Shown as the reception signal in message details.
    val rssi: Int = Int.MIN_VALUE,
    // For a delivered direct message: the raw ACK datagram (hex) and the local time we received it,
    // so the round-trip delay and the ACK packet detail survive an app restart.
    val ackPacketHex: String = "",
    val ackTimestampMs: Long = 0L,
)
