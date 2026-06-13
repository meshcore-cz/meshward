package cz.meshcore.meshward.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.sidepath.meshcore.MeshCoreEnvelope
import cz.meshcore.sidepath.meshcore.MeshCorePacket
import cz.meshcore.sidepath.meshcore.MeshCoreType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A MeshCore-optimized Rx Log: the same idea as the Sidepath [RxLogScreen] but showing only the
 * MeshCore packets seen on the mesh, decoded by the meshpkt library. Each row surfaces the
 * MeshCore payload type, route mode and hops; a matched channel message shows its decrypted text
 * inline. The detail dialog adds the full decoded envelope, the Sidepath carrier info, and a
 * hexdump of the inner MeshCore bytes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshCoreRxLogScreen(vm: ChatViewModel, onBack: () -> Unit, onOpenProfile: (String) -> Unit = {}) {
    val packets by vm.meshCorePackets.collectAsState()
    val total by vm.meshCoreTotal.collectAsState()
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(350)
        }
    }
    var detail by remember { mutableStateOf<MeshCorePacket?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("MeshCore Rx Log")
                        Text(
                            if (packets.size < total)
                                "last ${packets.size} packets ($total total)"
                            else
                                "${packets.size} packet${if (packets.size == 1) "" else "s"}",
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
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (packets.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(
                    "No MeshCore packets received yet. They arrive when a MeshCore bridge is " +
                        "forwarding LoRa traffic into this mesh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(packets) { p ->
                    MeshCoreRow(p = p, nowMs = nowMs) { detail = p }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    detail?.let { MeshCoreDetailDialog(it, vm, onDismiss = { detail = null }) }
}

private val mcTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun MeshCoreRow(p: MeshCorePacket, nowMs: Long, onClick: () -> Unit) {
    val ageMs = (nowMs - p.timestampMs).coerceAtLeast(0)
    val freshAlpha = ((7_000L - ageMs).coerceIn(0L, 7_000L).toFloat() / 7_000f) * 0.13f
    val color = meshCoreColor(p.envelope?.type)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = freshAlpha))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        val wide = maxWidth >= 600.dp
        Column {
            if (wide) {
                // Roomy: everything on one line.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeshCorePill(p.envelope?.type)
                    MetaText(mcTimeFmt.format(Date(p.timestampMs)))
                    MetaText(meshCoreMeta(p.envelope), Modifier.weight(1f))
                    if (p.receiveCount > 1) MeshCoreCountBadge(p.receiveCount)
                    SignalLabel(p.directRssi, "rssi", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                // Narrow (phone): type + time + signal on line 1, packet meta on line 2.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeshCorePill(p.envelope?.type)
                    MetaText(mcTimeFmt.format(Date(p.timestampMs)), Modifier.weight(1f))
                    if (p.receiveCount > 1) MeshCoreCountBadge(p.receiveCount)
                    SignalLabel(p.directRssi, "rssi", style = MaterialTheme.typography.labelSmall)
                }
                MetaText(meshCoreMeta(p.envelope), Modifier.padding(top = 1.dp))
            }
            // A decoded channel message (one of our joined channels matched) shows inline.
            if (p.channelText != null) {
                Text(
                    "${p.channelSender.orEmpty().ifBlank { "?" }}: ${p.channelText}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun MetaText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun MeshCoreCountBadge(count: Int) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(
            "×$count",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MeshCorePill(type: String?) {
    val label = type ?: "??"
    val color = meshCoreColor(type)
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun meshCoreColor(type: String?): Color = when (type) {
    MeshCoreType.GRP_TXT -> Color(0xFF00838F)
    MeshCoreType.TXT_MSG -> Color(0xFF3949AB)
    MeshCoreType.ADVERT -> Color(0xFF546E7A)
    MeshCoreType.ACK -> Color(0xFF2E7D32)
    MeshCoreType.TRACE, MeshCoreType.PATH -> Color(0xFF6A1B9A)
    null -> Color(0xFFC62828)
    else -> Color(0xFF8D6E63)
}

private fun routeLabel(env: MeshCoreEnvelope?): String {
    if (env == null) return "undecodable"
    val codes = env.transportCodes
    val transport = if (env.isTransport && codes != null)
        " transport %04x/%04x".format(codes[0], codes[1]) else ""
    return env.route + transport
}

private fun meshCoreMeta(env: MeshCoreEnvelope?): String {
    if (env == null) return "could not decode"
    val hops = if (env.hopCount > 0) " · ${env.hopCount} hop${if (env.hopCount == 1) "" else "s"}" else ""
    return "${routeLabel(env)} · ${env.payload.size}B$hops"
}

@Composable
internal fun MeshCoreDetailDialog(p: MeshCorePacket, vm: ChatViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val analyzers by vm.analyzerUrls.collectAsState()
    // CoreScope content hash — route-independent logical packet id, used for the analyzer link.
    val contentHash by produceState<String?>(initialValue = null, p.raw) {
        value = vm.meshContentHash(p.raw)
    }
    var showAnalyzerChooser by remember { mutableStateOf(false) }
    fun openAnalyzer(base: String) {
        val hash = contentHash ?: return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(base + hash)))
        }
    }

    if (showAnalyzerChooser) {
        AlertDialog(
            onDismissRequest = { showAnalyzerChooser = false },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAnalyzerChooser = false }) { Text("Cancel") } },
            title = { Text("Open in analyzer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    analyzers.forEach { base ->
                        TextButton(onClick = { showAnalyzerChooser = false; openAnalyzer(base) }) {
                            Text(base, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            OutlinedButton(
                enabled = contentHash != null,
                onClick = { if (analyzers.size <= 1) openAnalyzer(analyzers.firstOrNull() ?: return@OutlinedButton) else showAnalyzerChooser = true },
            ) { Text("Open in analyzer") }
        },
        title = { Text("MeshCore packet") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                McField("Time", mcTimeFmt.format(Date(p.timestampMs)))
                McField("Content hash", contentHash ?: "—")
                if (p.contentId.isNotBlank()) McField("Packet id", p.contentId)
                val env = p.envelope
                if (env == null) {
                    McField("Decode", "failed — raw bytes only")
                } else {
                    McField("Type", "${env.type} (0x%02x)".format(env.typeCode))
                    McField("Route", "${routeLabel(env)} (code ${env.routeCode})")
                    McField("Version", env.version.toString())
                    McField("Path hash", "${env.pathHashSize}B")
                    McField("Hops", if (env.hops.isEmpty()) "none" else
                        env.hops.joinToString(" → ") { it.toHexLower() })
                    McField("Payload", "${env.payload.size} bytes")
                    if (p.channelText != null) {
                        McField("Channel", "${p.channelSender.orEmpty()}: ${p.channelText}")
                    }
                }

                Spacer(Modifier.width(4.dp))
                Text("Sidepath carrier", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                McField("Received", p.receiveCount.toString())
                McField("Source", "${vm.nameForHex(p.source.toHex())}\n${p.source.toHex()}")
                McField("Datagram", p.datagramId.toHexLower())
                if (p.path.isNotEmpty()) McField("BLE path", p.path.joinToString(" → ") {
                    "${vm.nameForHex(it.toHex())} (${it.toHex().take(20)})"
                })

                Spacer(Modifier.width(4.dp))
                RawPacketView("Raw MeshCore packet", p.raw)
            }
        },
    )
}

@Composable
private fun McField(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

private fun ByteArray.toHexLower(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }
