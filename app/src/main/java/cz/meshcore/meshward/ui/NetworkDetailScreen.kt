package cz.meshcore.meshward.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.parseAnalyzerEndpoints

/** Extended overview of a single Meshcore Network: identity, tech parameters, links, and territory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    vm: ChatViewModel,
    code: String,
    onBack: () -> Unit,
    onOpenNetworks: () -> Unit = {},
    onOpenProfile: (String) -> Unit = {},
    onOpenRxLog: () -> Unit = {},
) {
    val networks by vm.networks.collectAsState()
    val auto by vm.networkAuto.collectAsState()
    val detected by vm.detectedNetworks.collectAsState()
    val activeNetwork by vm.activeNetwork.collectAsState()
    val topology by vm.topology.collectAsState()
    val discovered by vm.discoveredContacts.collectAsState()
    val stats by vm.stats.collectAsState()
    val isUnknown = code == cz.meshcore.meshward.UNKNOWN_NETWORK_CODE
    val net = networks.firstOrNull { it.code == code }
    val isActive = activeNetwork?.code == code
    // This network is the one in effect because a nearby bridge announced it (vs. a manual pin).
    val isAutoDetected = auto && detected.any { it.code == code }
    // Sidepath gateways currently advertising a bridge into this network (§8.3).
    val bridges = remember(topology, code) { topology.filter { e -> e.bridges.any { it.code == code } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meshcore Network Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (isUnknown) {
            UnknownNetworkDetail(Modifier.padding(padding))
            return@Scaffold
        }
        if (net == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Network not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        val networkNodes = remember(discovered, code) { discovered.filter { it.networkCode == code } }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header — an active network reads "live" (accent-tinted, pulse dot); an inactive one is
            // a plain informational definition.
            Surface(
                color = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetworkCodeChip(net.code)
                        Text(net.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    if (isActive) {
                        LiveStatusLine(if (isAutoDetected) "Live · active network · auto-detected" else "Live · active network")
                    } else {
                        Text(
                            if (isAutoDetected) "Meshcore network · detected nearby" else "Meshcore network",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (net.description.isNotBlank()) {
                        Text(net.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isActive) {
                            Button(onClick = { vm.setActiveNetwork(net.code) }) { Text("Set as active") }
                        }
                        if (isActive) {
                            OutlinedButton(onClick = onOpenRxLog) {
                                Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Rx Log")
                            }
                        }
                        // Manage detection / pinning lives in Settings → Meshcore Networks.
                        OutlinedButton(onClick = onOpenNetworks) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Change network")
                        }
                    }
                }
            }

            if (isActive) {
                // Live view: bridges first (with RX/TX near), then the definition, statistics last.
                BridgesCard(bridges, stats, vm, onOpenProfile)
                RadioParamsCard(net)
                LinksCard(net)
                StatisticsCard(bridges.size, networkNodes)
            } else {
                // Informational view: just the network definition.
                RadioParamsCard(net)
                LinksCard(net)
            }

            // Territory (GeoJSON) is intentionally hidden for now; the renderer stays for later.
        }
    }
}

/** A green "live" indicator: a filled dot + label, used to make the active-network header feel live. */
@Composable
private fun LiveStatusLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF2E7D32)))
        Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
    }
}

/** Active bridges card (live view): each gateway bridging this network, with overall RX/TX nearby. */
@Composable
private fun BridgesCard(
    bridges: List<cz.meshcore.sidepath.service.TopologyEntry>,
    stats: cz.meshcore.sidepath.service.MeshStats,
    vm: ChatViewModel,
    onOpenProfile: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Active bridges (${bridges.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // Device mesh RX/TX — the active network is the only one this device operates in.
            Text(
                "RX ${stats.packetsReceived} · TX ${stats.packetsSent} · relays ${stats.floodRelays} · ACKs ${stats.acksSent}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (bridges.isEmpty()) {
                Text(
                    "No bridge is advertising this network right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                bridges.forEach { b ->
                    BridgeRow(b, name = vm.nameForHex(b.nodeId.toHex()), onClick = { onOpenProfile(b.nodeId.toHex()) })
                }
            }
        }
    }
}

@Composable
private fun StatisticsCard(bridgeCount: Int, networkNodes: List<cz.meshcore.meshward.data.DiscoveredContact>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Statistics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ParamLine("Active bridges", bridgeCount.toString())
            ParamLine("Known nodes", networkNodes.size.toString())
            ParamLine("Repeaters", networkNodes.count { it.nodeType == 2 }.toString())
            ParamLine("Chat nodes", networkNodes.count { it.nodeType == 1 }.toString())
            ParamLine("Rooms", networkNodes.count { it.nodeType == 3 }.toString())
            ParamLine("Sensors", networkNodes.count { it.nodeType == 4 }.toString())
            ParamLine("With location", networkNodes.count { it.hasGps }.toString())
            Text(
                "Counts reflect nodes this device has discovered (heard or synced from an analyzer).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RadioParamsCard(net: cz.meshcore.meshward.data.MeshNetwork) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Radio parameters", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            ParamLine("Frequency", net.freqMhz.takeIf { it != 0.0 }?.let { "%.3f MHz".format(it) })
            ParamLine("Bandwidth", net.bandwidthKhz.takeIf { it != 0.0 }?.let { "${it.toInt()} kHz" })
            ParamLine("Spreading factor", net.spreadingFactor.takeIf { it != 0 }?.let { "SF$it" })
            ParamLine("Coding rate", net.codingRate.takeIf { it != 0 }?.let { "4/$it" })
            Text(
                "Shown for reference — these don't reconfigure the connected radio.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LinksCard(net: cz.meshcore.meshward.data.MeshNetwork) {
    val analyzers = remember(net.analyzerUrls) { parseAnalyzerEndpoints(net.analyzerUrls) }
    val mqttLinks = remember(net.mqttEndpoints) { net.mqttEndpoints.toLines() }
    if (analyzers.isEmpty() && mqttLinks.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (analyzers.isNotEmpty()) {
                Text("Packet analyzers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                analyzers.forEach { AnalyzerRow(it) }
            }
            if (mqttLinks.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                Text("MQTT endpoints", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                mqttLinks.forEach { ep ->
                    Text(ep, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/** A bridge node row: avatar, resolved name, short pubkey; taps through to its profile. */
@Composable
private fun BridgeRow(entry: cz.meshcore.sidepath.service.TopologyEntry, name: String, onClick: () -> Unit) {
    val hex = entry.nodeId.toHex()
    val pubHex = entry.publicKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(seed = hex, label = name, size = 36, identiconKey = pubHex.ifBlank { null })
        Column(Modifier.weight(1f)) {
            Text(name.ifBlank { hex.take(12) }, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                formatPubKey(pubHex.ifBlank { hex }),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Informational detail for "Unknown network" — MeshCore traffic with no advertised network. No stats. */
@Composable
private fun UnknownNetworkDetail(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NetworkCodeChip(cz.meshcore.meshward.UNKNOWN_NETWORK_CODE)
                    Text("Unknown network", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Meshcore network",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "MeshCore packets are arriving without an identified network — no nearby bridge is " +
                        "advertising which Meshcore network it bridges. Once a bridge announces its network, " +
                        "these contacts will be tiered to it automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ParamLine(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value ?: "—", fontWeight = FontWeight.SemiBold)
    }
}

/** A CoreScope analyzer endpoint row: shows the bare host, opens its packets browser. */
@Composable
private fun AnalyzerRow(endpoint: cz.meshcore.meshward.AnalyzerEndpoint) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endpoint.packetsBaseUrl()))) }
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            endpoint.host,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Icon(Icons.Default.OpenInNew, contentDescription = "Open", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/** Split a newline-separated stored field into trimmed, non-empty entries. */
private fun String.toLines(): List<String> = lines().map { it.trim() }.filter { it.isNotEmpty() }
