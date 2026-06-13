package cz.meshcore.meshward.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.ProfileInfo
import cz.meshcore.sidepath.chat.ChatKind
import cz.meshcore.sidepath.protocol.ControlKind
import cz.meshcore.sidepath.protocol.PayloadProtocol
import cz.meshcore.sidepath.service.PeerInfo
import cz.meshcore.sidepath.service.RSSI_UNKNOWN
import cz.meshcore.sidepath.service.RxPacket
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RxLogScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit = {},
    onOpenMeshCoreLog: () -> Unit = {},
) {
    val packets by vm.rxPackets.collectAsState()
    val total by vm.rxTotal.collectAsState()
    val myNode by vm.nodeId.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(350)
        }
    }
    var detail by remember { mutableStateOf<RxPacket?>(null) }
    // The Rx Log is newest-first; always jump back to the newest entry when re-entering the screen
    // (the saveable-state holder would otherwise restore the previous scroll position).
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { listState.scrollToItem(0) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column {
                        Text("Rx Log")
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
                actions = {
                    IconButton(onClick = onOpenMeshCoreLog) {
                        Icon(Icons.Default.Hub, contentDescription = "MeshCore log")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (packets.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("No Sidepath packets received yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
                items(packets) { p ->
                    PacketRow(
                        p = p,
                        vm = vm,
                        myNodeHex = myNode.toHex(),
                        nowMs = nowMs,
                    ) { detail = p }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }

    detail?.let {
        PacketDetailDialog(
            p = it,
            vm = vm,
            peers = peers,
            onOpenProfile = { peerHex ->
                detail = null
                onOpenProfile(peerHex)
            },
            onDismiss = { detail = null },
        )
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun PacketRow(
    p: RxPacket,
    vm: ChatViewModel,
    myNodeHex: String,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val ageMs = (nowMs - p.timestampMs).coerceAtLeast(0)
    val freshAlpha = ((7_000L - ageMs).coerceIn(0L, 7_000L).toFloat() / 7_000f) * 0.13f
    val typeColor = packetColor(p)
    val isMine = p.source.toHex() == myNodeHex
    val bg = typeColor.copy(alpha = freshAlpha)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TypePill(p)
                if (isMine) MineChip()
                p.droppedReason?.let { DropChip(it) }
                Text(timeFmt.format(Date(p.timestampMs)), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Text(
                    "${vm.nameForHex(p.source.toHex())} → ${destLabel(p, vm)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                SignalLabel(p.rssi, "rssi", style = MaterialTheme.typography.labelSmall)
                Text(packetMeta(p), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypePill(p)
                    if (isMine) MineChip()
                    p.droppedReason?.let { DropChip(it) }
                    Text(
                        "${vm.nameForHex(p.source.toHex())} → ${destLabel(p, vm)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${timeFmt.format(Date(p.timestampMs))} · ${packetMeta(p)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    SignalLabel(p.rssi, "rssi", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Short colored label naming the packet's nature (payload type for DATA, else the packet type). */
@Composable
private fun TypePill(p: RxPacket) {
    val label = packetLabel(p)
    val color = packetColor(p)
    Surface(
        color = color.copy(alpha = 0.18f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** A small red chip flagging a routing drop, e.g. "dup" for a duplicate datagram. */
@Composable
private fun DropChip(reason: String) {
    val label = if (reason == "duplicate") "dup" else reason
    Surface(
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MineChip() {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.CallMade,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "mine",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun controlKindName(k: Int?): String = when (k) {
    ControlKind.ANNOUNCE -> "ANNOUNCE"
    ControlKind.ACK -> "ACK"
    ControlKind.TRACE_REQUEST -> "TRACE_REQUEST"
    ControlKind.TRACE_RESPONSE -> "TRACE_RESPONSE"
    else -> "UNKNOWN"
}

private fun chatKindName(k: Int?): String = when (k) {
    ChatKind.PUBLIC_TEXT -> "PUBLIC_TEXT"
    ChatKind.DIRECT_TEXT -> "DIRECT_TEXT"
    ChatKind.TYPING -> "TYPING"
    ChatKind.CHANNEL_TEXT -> "CHANNEL_TEXT"
    else -> "UNKNOWN"
}

private fun protocolName(proto: Int): String = when (proto) {
    PayloadProtocol.SIDEPATH_CONTROL -> "SIDEPATH_CONTROL"
    PayloadProtocol.MESHCORE_PACKET -> "MESHCORE_PACKET"
    PayloadProtocol.SIDEPATH_CHAT -> "SIDEPATH_CHAT"
    else -> "UNKNOWN"
}

/** The packet's payload subtype (control/chat kind), or null for protocols without one. */
private fun subtypeName(p: RxPacket): String? = when (p.protocol) {
    PayloadProtocol.SIDEPATH_CONTROL -> controlKindName(p.controlKind)
    PayloadProtocol.SIDEPATH_CHAT -> chatKindName(p.chatKind)
    else -> null
}

/** Compact pill label: `CONTROL:ANNOUNCE`, `CHAT:CHANNEL_TEXT`, `MESHCORE`, or a raw protocol id. */
private fun packetLabel(p: RxPacket): String = when (p.protocol) {
    PayloadProtocol.SIDEPATH_CONTROL -> "CONTROL:${controlKindName(p.controlKind)}"
    PayloadProtocol.SIDEPATH_CHAT -> "CHAT:${chatKindName(p.chatKind)}"
    PayloadProtocol.MESHCORE_PACKET -> "MESHCORE"
    else -> "0x%04x".format(p.protocol)
}

@Composable
private fun packetColor(p: RxPacket): Color =
    when {
        p.controlKind == ControlKind.ACK -> Color(0xFF2E7D32)
        p.controlKind == ControlKind.ANNOUNCE -> Color(0xFF546E7A)
        p.controlKind == ControlKind.TRACE_REQUEST || p.controlKind == ControlKind.TRACE_RESPONSE -> Color(0xFF6A1B9A)
        p.protocol == PayloadProtocol.SIDEPATH_CONTROL -> Color(0xFF546E7A)
        p.chatKind == ChatKind.CHANNEL_TEXT -> Color(0xFF00838F)
        p.chatKind == ChatKind.TYPING -> Color(0xFFEF6C00)
        p.protocol == PayloadProtocol.SIDEPATH_CHAT -> MaterialTheme.colorScheme.primary
        p.protocol == PayloadProtocol.MESHCORE_PACKET -> Color(0xFF8D6E63)
        else -> MaterialTheme.colorScheme.secondary
    }

private fun routingLabel(p: RxPacket): String =
    if (p.sourceRouted) "source-route ${p.routeCursor}/${p.route.size}" else "flood"

private fun packetMeta(p: RxPacket): String =
    "${routingLabel(p)} · ttl ${p.ttl} · ${p.payloadSize}B" +
        if (p.path.isNotEmpty()) " · ${p.path.size} hop${if (p.path.size == 1) "" else "s"}" else ""

private fun destLabel(p: RxPacket, vm: ChatViewModel): String =
    if (p.destination.isBroadcast()) "broadcast" else vm.nameForHex(p.destination.toHex())

@Composable
internal fun PacketDetailDialog(
    p: RxPacket,
    vm: ChatViewModel,
    peers: List<PeerInfo>,
    onOpenProfile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sourceHex = p.source.toHex()
    val profile by remember(sourceHex) { vm.profileFor(sourceHex) }.collectAsState()
    val peer = peers.firstOrNull { it.nodeId.toHex() == sourceHex }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Packet detail") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (shouldShowProfile(profile, peer)) {
                    PeerProfileBox(profile, peer) { onOpenProfile(sourceHex) }
                    Spacer(Modifier.width(4.dp))
                }
                Field("Time", timeFmt.format(Date(p.timestampMs)))
                Field("Protocol", "${protocolName(p.protocol)} (0x%04x)".format(p.protocol))
                subtypeName(p)?.let { Field("Subtype", it) }
                Field("Datagram id", p.id.toHexLower())
                Field("Source", "${vm.nameForHex(p.source.toHex())}\n${p.source.toHex()}")
                Field("Destination", if (p.destination.isBroadcast()) "broadcast" else
                    "${vm.nameForHex(p.destination.toHex())}\n${p.destination.toHex()}")
                Field("Routing", "${routingLabel(p)} · ttl ${p.ttl} · ${if (p.forUs) "for us" else "overheard"}")
                Field("Signal", if (p.rssi == RSSI_UNKNOWN) "n/a (inbound link)" else "${p.rssi} dBm (at receipt)")
                p.droppedReason?.let { Field("Dropped", it) }
                Field("Flags", if (p.ackRequested) "ACK_REQUESTED" else if (p.flags == 0) "none" else "0x%04x".format(p.flags))
                if (p.route.isNotEmpty()) Field("Route", p.route.joinToString(" → ") { "${vm.nameForHex(it.toHex())} (${it.toHex().take(20)})" })
                if (p.path.isNotEmpty()) Field("Path", p.path.joinToString(" → ") { "${vm.nameForHex(it.toHex())} (${it.toHex().take(20)})" })
                Field("Payload", "${p.payloadSize} bytes")

                Spacer(Modifier.width(4.dp))
                RawPacketView("Raw packet", p.raw)
            }
        },
    )
}

private fun shouldShowProfile(profile: ProfileInfo, peer: PeerInfo?): Boolean =
    peer != null || profile.pubKeyHex.isNotBlank() || profile.description.isNotBlank() ||
        profile.platform.isNotBlank() || profile.online

@Composable
private fun PeerProfileBox(profile: ProfileInfo, peer: PeerInfo?, onClick: () -> Unit) {
    val displayName = profile.name.ifBlank { profile.peerHex.take(16).ifBlank { "Unknown peer" } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Avatar(
                seed = profile.peerHex,
                label = displayName,
                size = 40,
                identiconKey = profile.pubKeyHex.ifBlank { null },
            )
            Column(Modifier.weight(1f)) {
                Text(displayName, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    profile.nodeHex.ifBlank { profile.peerHex }.take(16),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val platform = profile.platform
                if (platform.isNotBlank() || peer != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SignalDot(peer?.rssi, "rssi")
                        Text(
                            listOfNotNull(
                                platform.takeIf { it.isNotBlank() },
                                peer?.let { if (it.incoming) "inbound" else "outbound" },
                                peer?.txPhy?.toString(),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
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
