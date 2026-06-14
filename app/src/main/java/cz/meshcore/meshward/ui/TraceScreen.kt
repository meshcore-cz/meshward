package cz.meshcore.meshward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.sidepath.protocol.NodeId
import cz.meshcore.sidepath.protocol.TraceResponseBody

/**
 * Standalone Trace route tool. The target node is chosen here via the selector at the top — the
 * screen is not bound to any one node. [prefill] (a node hex) seeds the selector and auto-runs one
 * trace when arriving from a node's profile; otherwise the user picks a node and presses Trace.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceScreen(vm: ChatViewModel, prefill: String, onBack: () -> Unit) {
    val trace by vm.trace.collectAsState()
    val topo by vm.topology.collectAsState()
    val contacts by vm.contacts.collectAsState()

    var target by rememberSaveable { mutableStateOf(prefill) }

    // Traceable nodes: everything we've heard (topology) or saved (contacts), minus self. Keep the
    // prefilled target even if it isn't in topology (e.g. a discovered contact), so it stays shown.
    val candidates = remember(topo, contacts, target) {
        val me = vm.myNodeHex()
        (topo.map { it.nodeId.toHex() } + contacts.map { it.nodeHex } +
            listOfNotNull(target.takeIf { it.isNotBlank() }))
            .filter { it != me }
            .distinct()
            .sortedBy { vm.nameForHex(it).lowercase() }
    }

    // Coming from a profile: run one trace automatically against the prefilled node.
    LaunchedEffect(prefill) { if (prefill.isNotBlank()) vm.startTrace(prefill) }

    val back = {
        vm.clearTrace()
        onBack()
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("Trace route", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TargetSelector(vm, candidates, target) { target = it; vm.clearTrace() }

            Button(
                onClick = { vm.startTrace(target) },
                enabled = target.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Route, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Trace")
            }

            if (target.isBlank()) {
                Text(
                    "Pick a node above, then press Trace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            val title = vm.nameForHex(target)
            CustomRoutePanel(vm, target)

            HorizontalDivider()

            val t = trace
            when {
                t == null || t.peerHex != target ->
                    Text(
                        "Press Trace to find the route to $title.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                t.error != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { vm.startTrace(target) }) { Text("Try again (auto route)") }
                }
                t.running -> Loading("Tracing route to $title…")
                t.result != null -> TraceResultView(t.result!!, t.rttMs, vm) { vm.startTrace(target) }
            }
        }
    }
}

/** Top-of-screen dropdown for choosing which node to trace. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TargetSelector(
    vm: ChatViewModel,
    candidates: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (selected.isBlank()) "Select a node" else vm.nameForHex(selected)

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Node to trace") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (candidates.isEmpty()) {
                DropdownMenuItem(text = { Text("No known nodes yet") }, onClick = {}, enabled = false)
            }
            candidates.forEach { hex ->
                DropdownMenuItem(
                    text = { Text(vm.nameForHex(hex)) },
                    onClick = { onSelect(hex); expanded = false },
                )
            }
        }
    }
}

/**
 * Lets the user trace along a route they choose, instead of the auto-selected one — either by
 * picking known nodes one hop at a time, or by typing the route manually as comma-separated
 * 8-byte node IDs (e.g. "d503fdbcb61c654f,be0d40fda9b839b5,d503fdbcb61c654f"). The destination is
 * appended automatically if the route doesn't already end on it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CustomRoutePanel(vm: ChatViewModel, peerHex: String) {
    val topo by vm.topology.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(listOf<NodeId>()) }
    var manual by remember { mutableStateOf("") }
    var manualError by remember { mutableStateOf<String?>(null) }

    Row(
        Modifier.fillMaxWidth().clickable { expanded = !expanded },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Custom route",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (expanded) "Hide" else "Specify…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (!expanded) return

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // ---- Pick nodes one by one --------------------------------------------------
            Text("Pick hops", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            if (picked.isEmpty()) {
                Text(
                    "Tap nodes below to add them as hops, in order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    picked.forEachIndexed { i, node ->
                        InputChip(
                            selected = false,
                            onClick = { picked = picked.toMutableList().also { it.removeAt(i) } },
                            label = { Text("${i + 1}. ${vm.nameForHex(node.toHex())}") },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", Modifier.size(16.dp)) },
                        )
                    }
                }
            }

            val candidates = remember(topo) {
                topo.map { it.nodeId }.filter { it.toHex() != vm.myNodeHex() }
            }
            if (candidates.isEmpty()) {
                Text(
                    "No known nodes to pick yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    Modifier.heightIn(max = 200.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    candidates.forEach { node ->
                        AssistChip(
                            onClick = { picked = picked + node },
                            label = { Text(vm.nameForHex(node.toHex()), maxLines = 1) },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            Button(
                onClick = { vm.startTrace(peerHex, picked) },
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Trace this route") }

            HorizontalDivider()

            // ---- Manual entry -----------------------------------------------------------
            Text("Or type it", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it; manualError = null },
                label = { Text("Route (comma-separated node IDs)") },
                placeholder = { Text("d503fdbcb61c654f,be0d40fda9b839b5", fontFamily = FontFamily.Monospace) },
                isError = manualError != null,
                supportingText = manualError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    val route = vm.parseManualRoute(manual)
                    if (route == null) {
                        manualError = "Each hop must be 16 hex chars (an 8-byte node ID), comma-separated."
                    } else {
                        manualError = null
                        vm.startTrace(peerHex, route)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Trace manual route") }
        }
    }
}

@Composable
private fun Loading(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TraceResultView(result: TraceResponseBody, rttMs: Long?, vm: ChatViewModel, onRetry: () -> Unit) {
    val hops = result.forwardPath.size
    Text("Reached in $hops hop${if (hops == 1) "" else "s"} · metric: ${metricName(result.metric)}", fontWeight = FontWeight.Medium)
    if (rttMs != null) {
        Text(
            "RTT: ${rttMs} ms",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }

    HorizontalDivider()
    Text("Forward path", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    if (result.forwardPath.isEmpty()) {
        Text("Direct (no relays)", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        result.forwardPath.forEachIndexed { i, node ->
            HopRow(i + 1, node, result.forwardSamples.getOrNull(i), result.metric, vm)
        }
    }

    if (result.returnSamples.isNotEmpty()) {
        HorizontalDivider()
        Text(
            "Return samples: ${result.returnSamples.joinToString(", ") { it.toString() }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.size(8.dp))
    Button(onClick = onRetry) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Trace again")
    }
}

@Composable
private fun metricName(metric: Int): String = when (metric) {
    1 -> "rssi"
    2 -> "snr"
    else -> "unknown"
}

@Composable
private fun HopRow(index: Int, node: NodeId, sample: Short?, metric: Int, vm: ChatViewModel) {
    val hex = node.toHex()
    val profile by remember(hex) { vm.profileFor(hex) }.collectAsState()
    val label = profile.name.ifBlank { vm.nameForHex(hex) }
    val identiconKey = profile.pubKeyHex.ifBlank { null }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$index.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
        Avatar(seed = hex, label = label, size = 28, identiconKey = identiconKey)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(hex.take(16), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (sample != null) {
            SignalLabel(sample.toInt(), metricName(metric))
        }
    }
}
