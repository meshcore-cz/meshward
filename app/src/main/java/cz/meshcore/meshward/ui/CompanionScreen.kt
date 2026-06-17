package cz.meshcore.meshward.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.companion.CompanionLinkState
import cz.meshcore.meshward.companion.CompanionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val companions by vm.companions.collectAsState()
    val scanResults by vm.companionScanResults.collectAsState()
    val scanning by vm.companionScanning.collectAsState()

    // Which companion's detail is open (relevant only with 2+); defaults to the single one.
    var selected by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) vm.companionStartScan()
    }

    fun beginBleScan() = permLauncher.launch(permissions)
    fun openAdd() { showAdd = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local MeshCore Companion") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Connect a MeshCore radio over Bluetooth to bridge its LoRa traffic into the nearby " +
                    "Meshward mesh. Packets it hears are shared with nearby phones, and theirs are " +
                    "transmitted on its radio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                companions.isEmpty() -> EmptyState(onAdd = ::openAdd)
                companions.size == 1 -> {
                    CompanionDetail(vm, companions.first())
                    OutlinedButton(onClick = ::openAdd, modifier = Modifier.fillMaxWidth()) {
                        Text("Add another companion")
                    }
                }
                else -> {
                    val sel = selected ?: companions.first().address
                    Text("Companions", style = MaterialTheme.typography.titleSmall)
                    companions.forEach { c ->
                        CompanionSummaryRow(c, selected = c.address == sel) { selected = c.address }
                    }
                    OutlinedButton(onClick = ::openAdd, modifier = Modifier.fillMaxWidth()) {
                        Text("Add companion")
                    }
                    companions.firstOrNull { it.address == sel }?.let { CompanionDetail(vm, it) }
                }
            }
        }
    }

    if (showAdd) {
        AddCompanionDialog(
            vm = vm,
            scanning = scanning,
            scanResults = scanResults,
            onBleScan = ::beginBleScan,
            onDone = {
                vm.companionStopScan()
                showAdd = false
            },
        )
    }
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Text("No companion connected", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add a MeshCore radio over Bluetooth, USB or the network to get started.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(16.dp))
            Button(onClick = onAdd) { Text("Add a companion") }
        }
    }
}

@Composable
private fun CompanionSummaryRow(c: CompanionState, selected: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else CardDefaults.cardColors(),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    c.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
            LinkChip(c.link)
        }
    }
}

@Composable
private fun LinkChip(link: CompanionLinkState) {
    val (label, color) = when (link) {
        CompanionLinkState.READY -> "Connected" to MaterialTheme.colorScheme.primary
        CompanionLinkState.CONNECTING -> "Connecting…" to MaterialTheme.colorScheme.tertiary
        CompanionLinkState.FAILED -> "Failed" to MaterialTheme.colorScheme.error
        CompanionLinkState.DISCONNECTED -> "Disconnected" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(onClick = {}, enabled = false, label = { Text(label) }, colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(labelColor = color))
}

@Composable
private fun CompanionDetail(vm: ChatViewModel, c: CompanionState) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ---- Connection ----
        SectionCard("Connection") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Enabled", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Auto-connect to this radio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = c.config.enabled,
                    onCheckedChange = { on -> if (on) vm.companionConnect(c.address) else vm.companionDisconnect(c.address) },
                )
            }
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinkChip(c.link)
                Spacer(Modifier.weight(1f))
                AssistChip(onClick = {}, enabled = false, label = { Text(transportLabel(c.config.transport)) })
            }
            Field(if (c.config.transport == cz.meshcore.meshward.companion.CompanionTransportKind.BLE) "Address" else "Endpoint", c.config.effectiveEndpoint)
            if (c.lastError.isNotBlank()) {
                Text(c.lastError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.link == CompanionLinkState.READY || c.link == CompanionLinkState.CONNECTING) {
                    OutlinedButton(onClick = { vm.companionDisconnect(c.address) }) { Text("Disconnect") }
                } else {
                    Button(onClick = { vm.companionConnect(c.address) }) { Text("Connect") }
                }
                TextButton(onClick = { vm.companionForget(c.address) }) { Text("Forget") }
            }
            // Editable label
            var label by remember(c.config.label) { mutableStateOf(c.config.label) }
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { vm.companionSetLabel(c.address, label) }) { Text("Save label") }
        }

        // ---- Status ----
        SectionCard("Status") {
            Field("Name", c.selfInfo?.name ?: "")
            Field("Public key", c.selfInfo?.publicKey ?: "")
            Field("Model", c.deviceInfo?.model ?: "")
            Field("Firmware", listOfNotNull(c.deviceInfo?.version?.ifBlank { null }, c.deviceInfo?.buildDate?.ifBlank { null }).joinToString(" · "))
            Field("Battery", formatBattery(c))
            Field("Uptime", c.core?.let { formatUptime(it.uptimeSecs) } ?: "")
            Field("Last RSSI / SNR", c.radio?.let { "${it.lastRssi} dBm / ${"%+.1f".format(it.lastSnr)} dB" } ?: "")
            Field("TX queue", c.core?.queueLen?.toString() ?: "")
        }

        // ---- Radio (read-only this pass) ----
        SectionCard("Radio") {
            val si = c.selfInfo
            Field("Frequency", si?.let { if (it.radioFreqKHz > 0) "%.3f MHz".format(it.radioFreqKHz / 1000.0) else "" } ?: "")
            Field("Bandwidth", si?.let { if (it.radioBwKHz > 0) "%.1f kHz".format(it.radioBwKHz.toDouble()) else "" } ?: "")
            Field("Spreading factor", si?.radioSf?.takeIf { it > 0 }?.toString() ?: "")
            Field("Coding rate", si?.radioCr?.takeIf { it > 0 }?.toString() ?: "")
            Field("TX power", si?.txPower?.let { "$it dBm (max ${si.maxTxPower})" } ?: "")
            Text(
                "Changing radio parameters from Meshward is coming soon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Identity (read-only this pass) ----
        SectionCard("Identity") {
            Field("Advertised name", c.selfInfo?.name ?: "")
            Field("Public key", c.selfInfo?.publicKey ?: "")
            Text(
                "Editing the device identity is coming soon.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---- Bridge ----
        SectionCard("Bridge") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bridge enabled", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Forward packets between this radio and the nearby mesh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = c.config.bridgeEnabled,
                    onCheckedChange = { vm.companionSetBridge(c.address, it) },
                )
            }
            Field("Network code", c.config.networkCodeOverride.ifBlank { "(app active network)" })
            Field("Packets in (radio → mesh)", c.bridgeRxCount.toString())
            Field("Packets out (mesh → radio)", c.bridgeTxCount.toString())
            Field("Last activity", if (c.lastBridgeActivityMs > 0) companionAgo(c.lastBridgeActivityMs) else "—")
        }

        // ---- Statistics ----
        SectionCard("Statistics") {
            val p = c.packets
            Field("Received", p?.received?.toString() ?: "")
            Field("Sent", p?.sent?.toString() ?: "")
            Field("Flood RX / TX", p?.let { "${it.floodRx} / ${it.floodTx}" } ?: "")
            Field("Direct RX / TX", p?.let { "${it.directRx} / ${it.directTx}" } ?: "")
            Field("RX errors", p?.recvErrors?.toString() ?: "")
            Field("Noise floor", c.radio?.let { "${it.noiseFloor} dBm" } ?: "")
            Field("Air time RX / TX", c.radio?.let { "${formatUptime(it.rxAirSecs)} / ${formatUptime(it.txAirSecs)}" } ?: "")
        }
    }
}

@Composable
private fun AddCompanionDialog(
    vm: ChatViewModel,
    scanning: Boolean,
    scanResults: List<cz.meshcore.meshward.companion.CompanionScanResult>,
    onBleScan: () -> Unit,
    onDone: () -> Unit,
) {
    var method by remember { mutableStateOf(cz.meshcore.meshward.companion.CompanionTransportKind.BLE) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Add companion") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cz.meshcore.meshward.companion.CompanionTransportKind.values().forEach { kind ->
                        FilterChip(
                            selected = method == kind,
                            onClick = { method = kind },
                            label = { Text(transportLabel(kind)) },
                        )
                    }
                }
                when (method) {
                    cz.meshcore.meshward.companion.CompanionTransportKind.BLE ->
                        BleAddBody(scanning, scanResults, onScan = onBleScan, onPick = { a, n -> vm.companionAdd(a, n); onDone() })
                    cz.meshcore.meshward.companion.CompanionTransportKind.TCP ->
                        TcpAddBody(onAdd = { h, p -> vm.companionAddTcp(h, p); onDone() })
                    cz.meshcore.meshward.companion.CompanionTransportKind.USB ->
                        UsbAddBody(vm, onPick = { k, n -> vm.companionAddUsb(k, n); onDone() })
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Close") } },
    )
}

@Composable
private fun BleAddBody(
    scanning: Boolean,
    results: List<cz.meshcore.meshward.companion.CompanionScanResult>,
    onScan: () -> Unit,
    onPick: (String, String) -> Unit,
) {
    LaunchedEffect(Unit) { onScan() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Scanning…", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onScan) { Text("Rescan") }
        }
        if (results.isEmpty() && !scanning) Text("No devices found.", style = MaterialTheme.typography.bodySmall)
        results.forEach { r ->
            DevicePickRow(title = r.name.ifBlank { "(unnamed)" }, subtitle = "${r.address}  ·  ${r.rssi} dBm") {
                onPick(r.address, r.name)
            }
        }
    }
}

@Composable
private fun TcpAddBody(onAdd: (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("5000") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connect to a companion's TCP server.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("Host or IP") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Port") }, singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onAdd(host.trim(), port.toIntOrNull() ?: 5000) },
            enabled = host.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add") }
    }
}

@Composable
private fun UsbAddBody(vm: ChatViewModel, onPick: (String, String) -> Unit) {
    var devices by remember { mutableStateOf(vm.companionUsbDevices()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Attached USB serial devices.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { devices = vm.companionUsbDevices() }) { Text("Refresh") }
        }
        if (devices.isEmpty()) {
            Text("No USB serial device detected. Plug in a radio (you may need an OTG adapter).", style = MaterialTheme.typography.bodySmall)
        }
        devices.forEach { d ->
            DevicePickRow(title = d.name, subtitle = "VID ${d.vendorId} · PID ${d.productId}") { onPick(d.key, d.name) }
        }
    }
}

@Composable
private fun DevicePickRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun transportLabel(kind: cz.meshcore.meshward.companion.CompanionTransportKind): String =
    when (kind) {
        cz.meshcore.meshward.companion.CompanionTransportKind.BLE -> "Bluetooth"
        cz.meshcore.meshward.companion.CompanionTransportKind.USB -> "USB"
        cz.meshcore.meshward.companion.CompanionTransportKind.TCP -> "Network"
    }

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBattery(c: CompanionState): String {
    val mv = if (c.batteryMv > 0) c.batteryMv else c.core?.batteryMv ?: 0
    if (mv <= 0) return ""
    return "%.2f V".format(mv / 1000.0)
}

private fun formatUptime(secs: Long): String {
    if (secs <= 0) return "—"
    if (secs < 60) return "${secs}s"
    val d = secs / 86400
    val h = (secs % 86400) / 3600
    val m = (secs % 3600) / 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (h > 0) append("${h}h ")
        if (m > 0 || isEmpty()) append("${m}m")
    }.trim()
}

private fun companionAgo(ms: Long): String {
    val secs = (System.currentTimeMillis() - ms) / 1000
    return when {
        secs < 5 -> "just now"
        secs < 60 -> "${secs}s ago"
        secs < 3600 -> "${secs / 60}m ago"
        else -> "${secs / 3600}h ago"
    }
}
