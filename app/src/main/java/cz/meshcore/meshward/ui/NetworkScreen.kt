package cz.meshcore.meshward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.ConnState
import cz.meshcore.meshward.nameFromPubKey
import cz.meshcore.meshward.toHex
import cz.meshcore.sidepath.protocol.Capabilities
import cz.meshcore.sidepath.service.MeshStats
import cz.meshcore.sidepath.service.PeerInfo
import cz.meshcore.sidepath.service.TopologyEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    vm: ChatViewModel,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val peers by vm.connectedPeers.collectAsState()
    val topology by vm.topology.collectAsState()
    val myNode by vm.nodeId.collectAsState()
    val status by vm.connectionStatus.collectAsState()
    val stats by vm.stats.collectAsState()
    val running by vm.isRunning.collectAsState()
    val lastPacketAt by vm.lastPacketAtMs.collectAsState()
    val nav = LocalMeshNav.current
    val myHex = myNode.toHex()

    // Standalone Trace tool; seed it with a healthy direct peer when we have one, else open empty.
    val traceTarget = peers.firstOrNull { !it.degraded }?.nodeId?.toHex().orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sidepath") },
                actions = {
                    // Start/stop the mesh radio right from the page header.
                    Text(
                        if (running) "On" else "Off",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = running,
                        onCheckedChange = { on -> if (on) vm.startMesh() else vm.stopMesh() },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    OverflowMenu(onOpenSettings = onOpenSettings)
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        // One unified node list (connected peers + nodes learned from announces), mirroring `sp peers`.
        val nodes = remember(peers, topology, myHex) { mergePeerNodes(peers, topology, myHex) }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { ConnectionBanner(status, peers.size, nodes.size) }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = { nav.openTrace(traceTarget) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Trace")
                    }
                    OutlinedButton(onClick = nav.openRxLog, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Rx Log")
                    }
                }
            }

            item {
                Text(
                    lastPacketLabel(lastPacketAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Topology graph entry point. The graph view isn't wired up yet; the button stays so the
            // entry point is visible and ready once it works.
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    FilledTonalButton(onClick = nav.openTopology, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Topology View")
                    }
                }
            }

            item { SectionHeader("Peers (${nodes.size})") }
            if (nodes.isEmpty()) {
                item { EmptyLine("No nodes known yet.") }
            } else {
                items(nodes, key = { "node:${it.hex}" }) { node ->
                    PeerRow(vm, node) { onOpenProfile(node.hex) }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { StatsCard(stats) }
        }
    }
}

@Composable
private fun ConnectionBanner(status: ConnState, peers: Int, knownNodes: Int) {
    val label = when (status) {
        ConnState.CONNECTED -> "Sidepath Connected"
        ConnState.NO_PEERS -> "No peers in range"
        ConnState.OFFLINE -> "Offline"
        ConnState.ERROR -> "Permissions needed"
    }
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionDot(status, size = 16)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$peers connected · $knownNodes known node${if (knownNodes == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(s: MeshStats) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Counters", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            StatLine("Packets received", s.packetsReceived)
            StatLine("Packets sent", s.packetsSent)
            StatLine("Flood relays", s.floodRelays)
            StatLine("ACKs sent", s.acksSent)
            StatLine("Traces sent", s.tracesSent)
            StatLine("Duplicates dropped", s.duplicatesDropped)
        }
    }
}

@Composable
private fun StatLine(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text("$value", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/**
 * "Last packet received Ns ago" — ticks every second so the relative time stays live. Returns
 * a friendly string; shared by the Network page and the connection-status popup.
 */
@Composable
fun lastPacketLabel(atMs: Long?): String {
    if (atMs == null) return "No packets received yet"
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(atMs) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val secs = ((now - atMs) / 1000).coerceAtLeast(0)
    val ago = when {
        secs < 60 -> "${secs}s ago"
        secs < 3600 -> "${secs / 60}m ${secs % 60}s ago"
        else -> "${secs / 3600}h ago"
    }
    return "Last packet received $ago"
}

/**
 * One node as shown in the unified Peers list: a live BLE [peer] (when connected) and/or the [topo]
 * entry learned from its signed announce. At least one of the two is always present.
 */
private data class PeerNode(
    val hex: String,
    val peer: PeerInfo?,
    val topo: TopologyEntry?,
)

/**
 * Merges connected peers and learned topology into one CLI-style node list (mirrors `sp peers`): one
 * row per node, connected nodes first, the rest by id. The local node is excluded.
 */
private fun mergePeerNodes(
    peers: List<PeerInfo>,
    topology: List<TopologyEntry>,
    myHex: String,
): List<PeerNode> {
    val peerByHex = peers.associateBy { it.nodeId.toHex() }
    val topoByHex = topology.filter { it.nodeId.toHex() != myHex }.associateBy { it.nodeId.toHex() }
    val hexes = LinkedHashSet<String>().apply { addAll(peerByHex.keys); addAll(topoByHex.keys) }
    return hexes.map { PeerNode(it, peerByHex[it], topoByHex[it]) }
        .sortedWith(compareByDescending<PeerNode> { it.peer != null }.thenBy { it.hex })
}

/** Compact relay/gateway capability flags, CLI-style ("relay,gw"); null when nothing notable is set. */
private fun capsLabel(caps: Capabilities?): String? = when {
    caps == null -> null
    caps.isRelay() && caps.isGateway() -> "relay,gw"
    caps.isRelay() -> "relay"
    caps.isGateway() -> "gw"
    else -> null
}

/** Short "Ns/Nm/Nh" age for a wall-clock timestamp (ms); blank for never. */
private fun shortAgo(atMs: Long): String {
    if (atMs <= 0L) return ""
    val s = ((System.currentTimeMillis() - atMs) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}

@Composable
private fun PeerRow(vm: ChatViewModel, node: PeerNode, onClick: () -> Unit) {
    val hex = node.hex
    val peer = node.peer
    val topo = node.topo
    // Identicons are drawn from a node's 32-byte public key; prefer the announce, fall back to the link.
    val pubKeyHex = (topo?.publicKey?.takeIf { it.size == 32 } ?: peer?.publicKey?.takeIf { it.size == 32 })?.toHex()
    // Resolve to the same display name used everywhere (contact alias → wire name → derived).
    val label = vm.nameForHex(hex)
        .ifBlank { topo?.name.orEmpty() }
        .ifBlank { nameFromPubKey(pubKeyHex ?: "") }
        .ifBlank { hex.take(16) }
    val platform = vm.platformForHex(hex).ifBlank { topo?.platform.orEmpty() }
    val caps = peer?.caps ?: topo?.caps

    // CONN column: §4.4 multi-link — a peer may be held over both an inbound and an outbound link at
    // once. Show each direction (in+out when both) and the physical link count for a redundant pair.
    val connLabel = when {
        peer == null -> null
        peer.degraded -> "degraded"
        peer.inbound && peer.outbound -> "in+out"
        peer.inbound -> "inbound"
        else -> "outbound"
    }
    val linkLabel = connLabel?.let { if (peer != null && peer.linkCount > 1) "$it · ${peer.linkCount} links" else it }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = hex, label = label, identiconKey = pubKeyHex, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (linkLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        linkLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (peer?.degraded == true) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
            Text(
                listOfNotNull(hex.take(16), platform.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            // Detail line: live link facts (PHY, uptime) when connected, then announce-derived facts
            // (caps, neighbor count, last announce) — the same data the CLI peers table carries.
            val nbrs = topo?.neighborIds?.size ?: 0
            val detail = buildList {
                if (peer != null) {
                    add(peer.txPhy.toString())
                    peer.connectedSinceMs.takeIf { it > 0 }?.let { add("up ${shortDuration(System.currentTimeMillis() - it)}") }
                    peer.lastRecvMs.takeIf { it > 0 }?.let { add("rx ${shortAgo(it)} ago") }
                }
                capsLabel(caps)?.let { add(it) }
                if (nbrs > 0) add("$nbrs nbr${if (nbrs == 1) "" else "s"}")
                topo?.lastAnnounceMs?.takeIf { it > 0 }?.let { add("ann ${shortAgo(it)} ago") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (peer != null) SignalLabel(peer.rssi, "rssi", style = MaterialTheme.typography.bodySmall)
                if (detail.isNotEmpty()) {
                    Text(
                        detail.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
