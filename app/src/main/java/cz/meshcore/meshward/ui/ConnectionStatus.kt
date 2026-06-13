package cz.meshcore.meshward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.ConnState
import cz.meshcore.sidepath.service.RSSI_UNKNOWN

/**
 * App-wide mesh navigation actions (open the Trace and Rx Log screens). Provided once at the
 * navigation root via [LocalMeshNav], so universal components like [ConnectionStatusButton]
 * can navigate from anywhere without each screen having to thread callbacks through.
 */
data class MeshNav(
    val openTrace: (String) -> Unit = {},
    val openRxLog: () -> Unit = {},
    val openMeshCoreLog: () -> Unit = {},
    val openTopology: () -> Unit = {},
    val openProfile: (String) -> Unit = {},
)

val LocalMeshNav = staticCompositionLocalOf { MeshNav() }

/**
 * Universal connection-status chip: a colored health dot + the connected-peer count. Tapping
 * it opens a sheet with health, peers, topology stats, and Trace / Rx Log shortcuts. Reusable
 * in any TopAppBar — navigation comes from [LocalMeshNav], so no per-screen wiring is needed.
 */
@Composable
fun ConnectionStatusButton(
    vm: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val status by vm.connectionStatus.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    var open by remember { mutableStateOf(false) }

    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .clickable { open = true }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConnectionDot(status)
        Spacer(Modifier.width(4.dp))
        Text(
            "${peers.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (open) ConnectionStatusSheet(vm) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionStatusSheet(
    vm: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val nav = LocalMeshNav.current
    val status by vm.connectionStatus.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    val topology by vm.topology.collectAsState()
    val running by vm.isRunning.collectAsState()
    val lastPacketAt by vm.lastPacketAtMs.collectAsState()
    val myHex = vm.nodeId.collectAsState().value.toHex()

    val others = remember(topology, myHex) { topology.filter { it.nodeId.toHex() != myHex } }
    val links = remember(others) { others.sumOf { it.neighborIds.size } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(status, size = 14)
                Spacer(Modifier.width(10.dp))
                Text(healthLabel(status), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    if (running) "Mesh on" else "Mesh off",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = running,
                    onCheckedChange = { on -> if (on) vm.startMesh() else vm.stopMesh() },
                )
            }

            Text(
                lastPacketLabel(lastPacketAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow("Connected peers", "${peers.size}")
                StatRow("Known nodes", "${others.size}")
                StatRow("Topology links", "$links")
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val traceTarget = peers.firstOrNull { !it.degraded }?.nodeId?.toHex()
                FilledTonalButton(
                    onClick = { traceTarget?.let { onDismiss(); nav.openTrace(it) } },
                    enabled = traceTarget != null,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Trace")
                }
                OutlinedButton(
                    onClick = { onDismiss(); nav.openRxLog() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rx Log")
                }
                OutlinedButton(
                    onClick = { onDismiss(); nav.openMeshCoreLog() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("MeshCore")
                }
            }

            HorizontalDivider()

            Text("Connected peers", style = MaterialTheme.typography.labelLarge)
            if (peers.isEmpty()) {
                Text("None right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                peers.forEach { p ->
                    val hex = p.nodeId.toHex()
                    val name = vm.nameForHex(hex)
                    val platform = vm.platformForHex(hex)
                    val link = if (p.degraded) "degraded" else if (p.incoming) "inbound" else "outbound"
                    val sub = listOfNotNull(
                        link,
                        p.txPhy.toString(),
                        platform.takeIf { it.isNotBlank() },
                        p.connectedSinceMs.takeIf { it > 0 }?.let { "for ${shortDuration(System.currentTimeMillis() - it)}" },
                    ).joinToString(" · ")
                    Row(
                        Modifier.fillMaxWidth().clickable { onDismiss(); nav.openProfile(hex) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(seed = hex, label = name, size = 32, identiconKey = vm.pubKeyForHex(hex).ifBlank { null })
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(
                                sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        SignalLabel(p.rssi, "rssi", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private fun healthLabel(state: ConnState): String = when (state) {
    ConnState.CONNECTED -> "Sidepath Connected"
    ConnState.NO_PEERS -> "No peers in range"
    ConnState.OFFLINE -> "Offline"
    ConnState.ERROR -> "Permissions needed"
}
