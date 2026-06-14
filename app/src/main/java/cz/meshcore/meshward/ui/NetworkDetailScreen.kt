package cz.meshcore.meshward.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Hub
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
) {
    val networks by vm.networks.collectAsState()
    val auto by vm.networkAuto.collectAsState()
    val detected by vm.detectedNetworks.collectAsState()
    val activeNetwork by vm.activeNetwork.collectAsState()
    val net = networks.firstOrNull { it.code == code }
    val isActive = activeNetwork?.code == code
    // This network is the one in effect because a nearby bridge announced it (vs. a manual pin).
    val isAutoDetected = auto && detected.any { it.code == code }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(net?.name ?: "Network") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (net == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Network not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetworkCodeChip(net.code)
                        Text(net.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        "Meshcore network",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (net.description.isNotBlank()) {
                        Text(net.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isActive) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                if (isAutoDetected) "Active network · auto-detected" else "Active network",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    } else if (isAutoDetected) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("Detected nearby", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!isActive) {
                            Button(onClick = { vm.setActiveNetwork(net.code) }) { Text("Set as active") }
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

            // Radio parameters (display only)
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

            // Links: CoreScope packet analyzers (stored as bare domains) and (future) MQTT endpoints
            val analyzers = remember(net.analyzerUrls) { parseAnalyzerEndpoints(net.analyzerUrls) }
            val mqttLinks = remember(net.mqttEndpoints) { net.mqttEndpoints.toLines() }
            if (analyzers.isNotEmpty() || mqttLinks.isNotEmpty()) {
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

            // Territory (GeoJSON) is intentionally hidden for now; the renderer below stays for later.
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
