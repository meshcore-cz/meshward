package cz.meshcore.meshward.ui

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.DatabaseStats
import cz.meshcore.meshward.toHex
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    // Manual refresh re-reads the database stats; a 2s tick keeps the live runtime metrics moving.
    var refresh by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(2_000); tick++ }
    }

    val stats by vm.stats.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    val rxTotal by vm.rxTotal.collectAsState()
    val meshTotal by vm.meshCoreTotal.collectAsState()
    val rxBuffered by vm.rxPackets.collectAsState()
    val meshBuffered by vm.meshCorePackets.collectAsState()
    val nodeId by vm.nodeId.collectAsState()
    val name by vm.myName.collectAsState()
    val pub by vm.myPubKeyHex.collectAsState()

    val dbStats by produceState<DatabaseStats?>(initialValue = null, refresh) {
        value = null
        value = runCatching { vm.databaseStats() }.getOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Debug")
                        Text(
                            "database & runtime",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refresh++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
            DebugSection("Identity") {
                DebugRow("Node ID", nodeId.toHex().ifBlank { "—" }, mono = true)
                DebugRow("Name", name.ifBlank { "—" })
                DebugRow("Public key", pub.ifBlank { "—" }, mono = true)
            }

            DebugSection("Mesh metrics") {
                DebugRow("Connected peers", "${peers.size}")
                DebugRow("Sidepath packets rx", "$rxTotal" + if (rxBuffered.size < rxTotal) "  (${rxBuffered.size} buffered)" else "")
                DebugRow("MeshCore packets rx", "$meshTotal" + if (meshBuffered.size < meshTotal) "  (${meshBuffered.size} buffered)" else "")
                DebugRow("Packets received", "${stats.packetsReceived}")
                DebugRow("Packets sent", "${stats.packetsSent}")
                DebugRow("Flood relays", "${stats.floodRelays}")
                DebugRow("ACKs sent", "${stats.acksSent}")
                DebugRow("Traces sent", "${stats.tracesSent}")
                DebugRow("Duplicates dropped", "${stats.duplicatesDropped}")
            }

            DebugSection("Database") {
                val s = dbStats
                if (s == null) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  reading…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    DebugRow("Total size", formatBytes(s.totalBytes), valueColor = MaterialTheme.colorScheme.primary)
                    DebugRow("Main file", formatBytes(s.fileBytes))
                    if (s.walBytes > 0) DebugRow("WAL", formatBytes(s.walBytes))
                    if (s.shmBytes > 0) DebugRow("SHM", formatBytes(s.shmBytes))
                    DebugRow("Page size", "${s.pageSize} B")
                    DebugRow("Pages", "${s.pageCount}")
                    val totalRows = s.tables.sumOf { it.rows }
                    Text(
                        "Tables (${s.tables.size}) · $totalRows rows",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    s.tables.forEach { t ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                t.name,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${t.rows} rows",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (t.bytes >= 0) Text(
                                formatBytes(t.bytes),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    if (!s.dbStatAvailable) Text(
                        "Per-table sizes unavailable (dbstat not compiled in).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Live runtime metrics — recomputed each tick.
            DebugSection("Runtime") {
                tick // read so this block recomposes on each tick
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                val pkg = remember {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                }
                @Suppress("DEPRECATION")
                val version = pkg?.let { "${it.versionName} (${it.versionCode})" } ?: "—"
                val uptimeMs = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
                DebugRow("App version", version)
                DebugRow("Process uptime", formatDuration(uptimeMs))
                DebugRow("JVM heap used", "${formatBytes(used)} / ${formatBytes(rt.maxMemory())}")
                DebugRow("JVM heap allocated", formatBytes(rt.totalMemory()))
                LinearProgressIndicator(
                    progress = { (used.toFloat() / rt.maxMemory().toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
                DebugRow("Native heap", formatBytes(Debug.getNativeHeapAllocatedSize()))
                DebugRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                DebugRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                DebugRow("Process ID", "${Process.myPid()}")
            }
        }
    }
}

@Composable
private fun DebugSection(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun DebugRow(
    label: String,
    value: String,
    mono: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            color = valueColor,
            modifier = Modifier.weight(1.4f),
        )
    }
}

private fun formatBytes(b: Long): String {
    if (b < 0) return "—"
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        h > 0 -> "${h}h ${m}m ${sec}s"
        m > 0 -> "${m}m ${sec}s"
        else -> "${sec}s"
    }
}
