package cz.meshcore.meshward.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.ConnState
import cz.meshcore.meshward.companion.CompanionLinkState
import cz.meshcore.meshward.companion.CompanionState

/**
 * App-wide mesh navigation actions (open the Trace, Rx Log, companion and network screens). Provided
 * once at the navigation root via [LocalMeshNav], so universal components like [ConnectionStatusButton]
 * can navigate from anywhere without each screen having to thread callbacks through.
 */
data class MeshNav(
    val openTrace: (String) -> Unit = {},
    val openRxLog: () -> Unit = {},
    val openMeshCoreLog: () -> Unit = {},
    val openTopology: () -> Unit = {},
    val openProfile: (String) -> Unit = {},
    val openCompanions: () -> Unit = {},
    val openNetworkDetail: (String) -> Unit = {},
)

val LocalMeshNav = staticCompositionLocalOf { MeshNav() }

/**
 * Universal connection-status chip, kept as minimal as possible: a health dot for Sidepath, plus —
 * only when present — the active MeshCore network code (teal chip) and a companion link indicator.
 * Tapping it opens the status sheet. Reusable in any TopAppBar; navigation comes from [LocalMeshNav].
 */
@Composable
fun ConnectionStatusButton(
    vm: ChatViewModel,
    modifier: Modifier = Modifier,
) {
    val status by vm.connectionStatus.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    val activeNetwork by vm.activeNetwork.collectAsState()
    val companions by vm.companions.collectAsState()
    var open by remember { mutableStateOf(false) }

    val readyCompanion = companions.firstOrNull { it.link == CompanionLinkState.READY }

    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .clickable { open = true }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ConnectionDot(status)
        Text(
            "${peers.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Active MeshCore network: a small teal code chip ("CZ") — only when one is active.
        activeNetwork?.let { NetworkCodeChipMini(it.code) }
        // Local companion: a Bluetooth glyph tinted by its bridge/link state — only when one is connected.
        readyCompanion?.let { CompanionMiniIcon(it) }
    }

    if (open) ConnectionStatusSheet(vm) { open = false }
}

/** Compact teal network-code pill for the status chip (a small sibling of [NetworkCodeChip]). */
@Composable
private fun NetworkCodeChipMini(code: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(NetworkChipColor.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            code,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = NetworkChipColor,
        )
    }
}

/** Bluetooth glyph for a connected companion, tinted green when bridging / red when blocked. */
@Composable
private fun CompanionMiniIcon(c: CompanionState) {
    val tint = when {
        c.radioBlocked -> MaterialTheme.colorScheme.error
        c.config.bridgeEnabled -> Color(0xFF2E7D32)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(Icons.Default.Bluetooth, contentDescription = "Companion", tint = tint, modifier = Modifier.size(16.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionStatusSheet(
    vm: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val nav = LocalMeshNav.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SidepathSection(vm, nav, onDismiss)
            HorizontalDivider()
            MeshCoreSection(vm, nav, onDismiss)

            val companions by vm.companions.collectAsState()
            if (companions.isNotEmpty()) {
                HorizontalDivider()
                CompanionsSection(vm, companions, nav, onDismiss)
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}

// ---- Sidepath -------------------------------------------------------------

@Composable
private fun SidepathSection(vm: ChatViewModel, nav: MeshNav, onDismiss: () -> Unit) {
    val status by vm.connectionStatus.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    val topology by vm.topology.collectAsState()
    val running by vm.isRunning.collectAsState()
    val lastPacketAt by vm.lastPacketAtMs.collectAsState()
    val myHex = vm.nodeId.collectAsState().value.toHex()

    val others = remember(topology, myHex) { topology.filter { it.nodeId.toHex() != myHex } }
    val links = remember(others) { others.sumOf { it.neighborIds.size } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ConnectionDot(status, size = 12)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(healthLabel(status), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${peers.size} peers · ${others.size} nodes · $links links",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = running, onCheckedChange = { on -> if (on) vm.startMesh() else vm.stopMesh() })
        }
        Text(
            lastPacketLabel(lastPacketAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val traceTarget = peers.firstOrNull { !it.degraded }?.nodeId?.toHex().orEmpty()
            FilledTonalButton(
                onClick = { onDismiss(); nav.openTrace(traceTarget) },
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
        }
    }
}

// ---- MeshCore network -----------------------------------------------------

@Composable
private fun MeshCoreSection(vm: ChatViewModel, nav: MeshNav, onDismiss: () -> Unit) {
    val enabled by vm.meshCoreEnabled.collectAsState()
    val net by vm.activeNetwork.collectAsState()
    val isLocal by vm.activeNetworkIsLocal.collectAsState()
    val lastPacketAt by vm.activeNetworkLastPacketMs.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Hub,
                contentDescription = null,
                tint = if (enabled && net != null) NetworkChipColor else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("MeshCore", style = MaterialTheme.typography.titleMedium)
                val sub = when {
                    !enabled -> "Off"
                    net == null -> "On · no network detected"
                    isLocal -> "Active · from local radio"
                    else -> "Active · detected nearby"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = { vm.setMeshCoreEnabled(it) })
        }

        // Details only when a network is actually active (per the "show only when active" rule).
        net?.takeIf { enabled }?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkCodeChip(n.code)
                Column(Modifier.weight(1f)) {
                    Text(n.name.ifBlank { n.code }, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        networkRadioSummary(n),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Text(
                lastPacketLabel(lastPacketAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onDismiss(); nav.openMeshCoreLog() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rx Log")
                }
                OutlinedButton(
                    onClick = { onDismiss(); nav.openNetworkDetail(n.code) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Network")
                }
            }
        }
    }
}

// ---- Local companions -----------------------------------------------------

@Composable
private fun CompanionsSection(
    vm: ChatViewModel,
    companions: List<CompanionState>,
    nav: MeshNav,
    onDismiss: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (companions.size == 1) "Local companion" else "Local companions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { onDismiss(); nav.openCompanions() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Settings")
            }
        }
        companions.forEach { c ->
            Row(
                Modifier.fillMaxWidth().clickable { onDismiss(); nav.openCompanions() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompanionMiniIcon(c)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.displayName, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        companionSubtitle(c),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = c.config.enabled,
                    onCheckedChange = { on -> if (on) vm.companionConnect(c.address) else vm.companionDisconnect(c.address) },
                )
            }
        }
    }
}

/** "Connected · bridging · 4.05 V"-style one-liner condensing a companion's link/bridge/battery. */
private fun companionSubtitle(c: CompanionState): String = listOfNotNull(
    when (c.link) {
        CompanionLinkState.READY -> "Connected"
        CompanionLinkState.CONNECTING -> "Connecting…"
        CompanionLinkState.FAILED -> "Failed"
        CompanionLinkState.DISCONNECTED -> "Disconnected"
    },
    when {
        c.radioBlocked -> "bridge blocked"
        c.config.bridgeEnabled -> "bridging"
        else -> "bridge off"
    },
    companionBatteryLabel(c),
).joinToString(" · ")

private fun companionBatteryLabel(c: CompanionState): String? {
    val mv = if (c.batteryMv > 0) c.batteryMv else c.core?.batteryMv ?: 0
    return if (mv > 0) "%.2f V".format(mv / 1000.0) else null
}

private fun healthLabel(state: ConnState): String = when (state) {
    ConnState.CONNECTED -> "Sidepath Connected"
    ConnState.NO_PEERS -> "No peers in range"
    ConnState.OFFLINE -> "Offline"
    ConnState.ERROR -> "Permissions needed"
}
