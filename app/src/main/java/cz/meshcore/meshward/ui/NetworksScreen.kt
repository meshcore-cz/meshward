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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.data.MeshNetwork

/**
 * Manage MeshCore Networks: pick the active one, view details, and add/edit/delete custom networks.
 * Built-in presets (from the sidepath-protocol definitions dataset) can be selected and "Customized"
 * (an editable copy is stored under the same code), but not deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworksScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val networks by vm.networks.collectAsState()
    val detected by vm.detectedNetworks.collectAsState()
    val activeNetwork by vm.activeNetwork.collectAsState()
    val activeIsLocal by vm.activeNetworkIsLocal.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var refreshStatus by remember { mutableStateOf<String?>(null) }

    // null = list view; non-null = the add/edit form is open (a blank network for "Add").
    var editing by remember { mutableStateOf<MeshNetwork?>(null) }
    var confirmDelete by remember { mutableStateOf<MeshNetwork?>(null) }

    if (editing != null) {
        NetworkEditForm(
            initial = editing!!,
            isNewCode = networks.none { it.code == editing!!.code },
            onCancel = { editing = null },
            onSave = { vm.upsertNetwork(it); editing = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MeshCore Networks") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "These are MeshCore Network definitions. Your device's active network is determined " +
                    "automatically — set by a connected local companion radio, or detected from nearby " +
                    "bridge announces. Radio parameters here are for reference and don't reconfigure a radio.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Read-only: which network is currently active and where it came from.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Active network", style = MaterialTheme.typography.titleSmall)
                    val source = when {
                        activeNetwork == null -> ""
                        activeIsLocal -> " · from local companion"
                        else -> " · detected nearby"
                    }
                    Text(
                        activeNetwork?.let { "${it.code} · ${it.name}$source" } ?: "None",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val detectedLabel = if (detected.isEmpty()) "No bridge detected nearby"
                    else "Detected: " + detected.joinToString(", ") { it.code }
                    Text(
                        detectedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Refresh the bundled definitions from the network-definitions URL.
            OutlinedButton(
                onClick = {
                    refreshStatus = null
                    refreshing = true
                    scope.launch {
                        val result = vm.refreshNetworkDefs(cz.meshcore.meshward.DEFAULT_NETWORK_DEFS_URL)
                        refreshing = false
                        refreshStatus = result.fold(
                            onSuccess = { "Definitions updated" },
                            onFailure = { "Refresh failed: ${it.message ?: "unknown error"}" },
                        )
                    }
                },
                enabled = !refreshing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (refreshing) "Refreshing…" else "Refresh definitions")
            }
            refreshStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            FilledTonalButton(
                onClick = { editing = MeshNetwork(code = "", name = "") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Add network")
            }

            networks.forEach { net ->
                NetworkRow(
                    net = net,
                    isActive = net.code == activeNetwork?.code,
                    isBuiltin = vm.isBuiltinNetwork(net.code),
                    onOpen = { onOpenDetail(net.code) },
                    onEdit = {
                        // Editing a built-in stores an editable custom copy under the same code.
                        editing = net.copy(isBuiltin = false)
                    },
                    onDelete = { confirmDelete = net },
                )
            }
        }
    }

    confirmDelete?.let { net ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${net.code}?") },
            text = { Text("Remove the “${net.name}” network. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteNetwork(net.code); confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NetworkRow(
    net: MeshNetwork,
    isActive: Boolean,
    isBuiltin: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).clickable(onClick = onOpen).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NetworkCodeChip(net.code)
                    Text(net.name.ifBlank { net.code }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (isActive) ActiveTag()
                    if (isBuiltin) BuiltinTag()
                }
                Text(
                    networkRadioSummary(net),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = if (isBuiltin) "Customize" else "Edit")
            }
            if (!isBuiltin) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkEditForm(
    initial: MeshNetwork,
    isNewCode: Boolean,
    onCancel: () -> Unit,
    onSave: (MeshNetwork) -> Unit,
) {
    var code by remember { mutableStateOf(initial.code) }
    var name by remember { mutableStateOf(initial.name) }
    var freq by remember { mutableStateOf(if (initial.freqMhz != 0.0) initial.freqMhz.toString() else "") }
    var bw by remember { mutableStateOf(if (initial.bandwidthKhz != 0.0) initial.bandwidthKhz.toString() else "") }
    var sf by remember { mutableStateOf(if (initial.spreadingFactor != 0) initial.spreadingFactor.toString() else "") }
    var cr by remember { mutableStateOf(if (initial.codingRate != 0) initial.codingRate.toString() else "") }
    var txp by remember { mutableStateOf(if (initial.txPower != 0) initial.txPower.toString() else "") }
    var analyzers by remember { mutableStateOf(initial.analyzerUrls) }
    var mqtt by remember { mutableStateOf(initial.mqttEndpoints) }
    var geo by remember { mutableStateOf(initial.geoJson) }
    var desc by remember { mutableStateOf(initial.description) }

    val codeValid = code.trim().length in 1..5
    val canSave = codeValid && name.trim().isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNewCode) "Add network" else "Edit ${initial.code}") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(5).uppercase() },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = isNewCode, // the code is the primary key — fixed once created
                    label = { Text("Code") },
                    isError = code.isNotEmpty() && !codeValid,
                    supportingText = { Text("≤5 chars") },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(2f),
                    singleLine = true,
                    label = { Text("Name") },
                )
            }

            Text("Radio (display only)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = freq, onValueChange = { freq = it }, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("Freq (MHz)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = bw, onValueChange = { bw = it }, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("BW (kHz)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = sf, onValueChange = { sf = it.filter(Char::isDigit).take(2) }, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("Spreading factor") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = cr, onValueChange = { cr = it.filter(Char::isDigit).take(1) }, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("Coding rate 4/N") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = txp, onValueChange = { txp = it.filter(Char::isDigit).take(2) }, modifier = Modifier.weight(1f),
                    singleLine = true, label = { Text("TX power (dBm)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }

            OutlinedTextField(
                value = analyzers, onValueChange = { analyzers = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("CoreScope analyzers (one domain per line)") },
                supportingText = { Text("Just the domain, e.g. analyzer.meshcore.cz — links are built automatically.") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedTextField(
                value = mqtt, onValueChange = { mqtt = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("MQTT endpoints (one per line)") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedTextField(
                value = geo, onValueChange = { geo = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Territory (GeoJSON geometry)") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedTextField(
                value = desc, onValueChange = { desc = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Description") },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(
                            MeshNetwork(
                                code = code.trim(),
                                name = name.trim(),
                                freqMhz = freq.toDoubleOrNull() ?: 0.0,
                                bandwidthKhz = bw.toDoubleOrNull() ?: 0.0,
                                spreadingFactor = sf.toIntOrNull() ?: 0,
                                codingRate = cr.toIntOrNull() ?: 0,
                                txPower = txp.toIntOrNull() ?: 0,
                                analyzerUrls = analyzers.trim(),
                                mqttEndpoints = mqtt.trim(),
                                geoJson = geo.trim(),
                                description = desc.trim(),
                            )
                        )
                    },
                    enabled = canSave,
                ) { Text("Save") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

/** The MeshCore network accent (the former MeshCore badge teal), shared by every network code chip. */
val NetworkChipColor = androidx.compose.ui.graphics.Color(0xFF00838F)

/** A monospace pill showing a network's short code, e.g. `CZ`. Shared across network surfaces. */
@Composable
fun NetworkCodeChip(code: String) {
    Surface(
        color = NetworkChipColor.copy(alpha = 0.16f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    ) {
        Text(
            code.ifBlank { "—" },
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = NetworkChipColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun BuiltinTag() {
    Text(
        "built-in",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ActiveTag() {
    Text(
        "active",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** One-line "869.5 MHz · SF11 · BW250 · 4/5" summary; omits parts that are unset. */
fun networkRadioSummary(net: MeshNetwork): String = listOfNotNull(
    net.freqMhz.takeIf { it != 0.0 }?.let { "%.3f MHz".format(it).trimEnd('0').trimEnd('.') },
    net.spreadingFactor.takeIf { it != 0 }?.let { "SF$it" },
    net.bandwidthKhz.takeIf { it != 0.0 }?.let { "BW${it.toInt()}" },
    net.codingRate.takeIf { it != 0 }?.let { "4/$it" },
).joinToString(" · ").ifEmpty { "No radio parameters set" }
