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
import cz.meshcore.sidepath.service.MeshStats
import cz.meshcore.sidepath.service.PeerInfo
import cz.meshcore.sidepath.service.RSSI_UNKNOWN
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

    // Identicons are drawn from a node's 32-byte public key; learn it from the live topology.
    val pubKeys = remember(topology) {
        topology.filter { it.publicKey.size == 32 }
            .associate { it.nodeId.toHex() to it.publicKey.toHex() }
    }
    // A direct peer is always reachable, so the Trace button targets the first one.
    val traceTarget = peers.firstOrNull { !it.degraded }?.nodeId?.toHex()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
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
        val others = topology.filter { it.nodeId.toHex() != myHex }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { ConnectionBanner(status, peers.size, others.size) }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = { traceTarget?.let(nav.openTrace) },
                        enabled = traceTarget != null,
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = nav.openMeshCoreLog, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("MeshCore")
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

            item { SectionHeader("Connected peers (${peers.size})") }
            if (peers.isEmpty()) {
                item { EmptyLine("No peers connected.") }
            } else {
                // Keys must be unique across the whole list; a peer is usually also in the
                // topology below, so namespace the keys to avoid a collision crash.
                items(peers, key = { "peer:${it.nodeId.toHex()}" }) { peer ->
                    PeerRow(vm, peer, pubKeys[peer.nodeId.toHex()]) { onOpenProfile(peer.nodeId.toHex()) }
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item { SectionHeader("Topology") }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    FilledTonalButton(onClick = nav.openTopology, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Topology View")
                    }
                }
            }

            item { SectionHeader("Known topology (${others.size})") }
            if (others.isEmpty()) {
                item { EmptyLine("No nodes learned yet.") }
            } else {
                items(others, key = { "topo:${it.nodeId.toHex()}" }) { node ->
                    TopologyRow(node, node.publicKey.takeIf { it.size == 32 }?.toHex()) { onOpenProfile(node.nodeId.toHex()) }
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

@Composable
private fun PeerRow(vm: ChatViewModel, peer: PeerInfo, pubKeyHex: String?, onClick: () -> Unit) {
    val hex = peer.nodeId.toHex()
    // Resolve to the same display name used everywhere (contact alias → wire name → derived).
    val label = vm.nameForHex(hex).ifBlank { nameFromPubKey(pubKeyHex ?: "") }.ifBlank { hex.take(16) }
    val platform = vm.platformForHex(hex)
    val linkLabel = if (peer.degraded) "degraded" else if (peer.incoming) "inbound" else "outbound"
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = hex, label = label, identiconKey = pubKeyHex, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                listOfNotNull(hex.take(16), platform.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SignalLabel(peer.rssi, "rssi", style = MaterialTheme.typography.bodySmall)
                Text(
                    listOfNotNull(
                        linkLabel,
                        peer.txPhy.toString(),
                        peer.connectedSinceMs.takeIf { it > 0 }?.let { "for ${shortDuration(System.currentTimeMillis() - it)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TopologyRow(node: TopologyEntry, pubKeyHex: String?, onClick: () -> Unit) {
    val label = node.name.ifBlank { nameFromPubKey(pubKeyHex ?: "") }
        .ifBlank { node.nodeId.toHex().take(16) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = node.nodeId.toHex(), label = label, size = 36, identiconKey = pubKeyHex, onClick = onClick)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
            val nb = node.neighborIds.size
            val platform = node.platform.ifBlank { "unknown platform" }
            Text(
                "${node.nodeId.toHex().take(16)} · $platform · $nb neighbor${if (nb == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
