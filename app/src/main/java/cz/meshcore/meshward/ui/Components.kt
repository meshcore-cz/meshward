package cz.meshcore.meshward.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.meshcore.meshward.AvatarStyle
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.ConnState
import cz.meshcore.meshward.MeshCoreHopMatch
import cz.meshcore.meshward.data.MeshCoreHeard
import cz.meshcore.meshward.toHex
import cz.meshcore.meshward.data.ChannelKind
import cz.meshcore.meshward.data.Message
import cz.meshcore.meshward.data.MsgStatus
import cz.meshcore.sidepath.service.DmDelivery
import cz.meshcore.sidepath.service.RSSI_UNKNOWN
import cz.meshcore.meshward.data.isChannelPeer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A borderless search field for a TopAppBar title. Requests focus and pops the keyboard as soon
 * as it appears so the user can start typing immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
}

/** Centered placeholder shown while a search is active but the query is still empty. */
@Composable
fun SearchHint(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val avatarColors = listOf(
    Color(0xFF6750A4), Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFFB71C1C),
    Color(0xFFEF6C00), Color(0xFF00838F), Color(0xFFAD1457), Color(0xFF4527A0),
)

/** How avatars are drawn app-wide; provided at the root from the user's setting. */
val LocalAvatarStyle = staticCompositionLocalOf { AvatarStyle.IDENTICON }

/**
 * A stable, distinct color for a "virtual" identity — a bridged MeshCore channel author we know
 * only by a declared name (no public key). Keyed on the name so the same author reads the same
 * color, visibly different from our own primary-colored, verified senders.
 */
fun virtualNameColor(name: String): Color =
    avatarColors[(name.lowercase().hashCode().ushr(1)) % avatarColors.size]

/**
 * Avatar for a contact/channel. When the identicon style is on AND an [identiconKey] is given
 * (a contact's public key), draws a deterministic identicon from that key; otherwise a colored
 * initials circle. Channels (no public key) always fall back to initials.
 */
@Composable
fun Avatar(seed: String, label: String, size: Int = 44, identiconKey: String? = null, onClick: (() -> Unit)? = null) {
    val base = Modifier.size(size.dp).clip(CircleShape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    if (LocalAvatarStyle.current == AvatarStyle.IDENTICON && !identiconKey.isNullOrBlank()) {
        Identicon(identiconKey, base)
    } else {
        val color = avatarColors[(seed.hashCode().ushr(1)) % avatarColors.size]
        val initials = label.trim().take(2).uppercase().ifBlank { "?" }
        Box(base.background(color), contentAlignment = Alignment.Center) {
            Text(initials, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size / 2.6).sp)
        }
    }
}

/** Avatar for the "Note to Self" conversation: a note glyph on a tinted circle (Signal-style). */
@Composable
fun NoteToSelfAvatar(size: Int = 44, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = "Note to Self",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size((size * 0.55).dp),
        )
    }
}

// A spread of distinct, vivid identicon colours across the whole hue wheel — picking from these
// gives far more visible variety than a low-saturation HSV sweep (which read as muted blues).
private val identiconColors = listOf(
    Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
    Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF00ACC1), Color(0xFF00897B),
    Color(0xFF43A047), Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835),
    Color(0xFFFFB300), Color(0xFFFB8C00), Color(0xFFF4511E), Color(0xFF6D4C41),
)

/** A deterministic GitHub-style identicon (5×5 mirrored grid hashed from [key]). */
@Composable
private fun Identicon(key: String, modifier: Modifier) {
    // Same vivid foreground in both themes; only the backdrop swaps to stay legible in dark mode.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val digest = remember(key) { java.security.MessageDigest.getInstance("MD5").digest(key.toByteArray()) }
    // Colour from one hash byte, grid pattern from the others, so similar keys still diverge.
    val fg = remember(key) {
        val idx = ((digest[5].toInt() and 0xFF) xor (digest[11].toInt() and 0xFF)) % identiconColors.size
        identiconColors[idx]
    }
    val bg = if (dark) Color(0xFF26262A) else Color(0xFFEDEDED)
    Canvas(modifier.background(bg)) {
        val cell = size.minDimension / 5f
        for (row in 0 until 5) {
            for (col in 0 until 3) {
                val on = (digest[row * 3 + col].toInt() and 1) == 0
                if (!on) continue
                for (c in intArrayOf(col, 4 - col)) {
                    drawRect(color = fg, topLeft = Offset(c * cell, row * cell), size = Size(cell, cell))
                }
            }
        }
    }
}

/** Renders a public key (hex) compactly as `<e9534284...49e01345>`; blank in → blank out. */
fun formatPubKey(hex: String): String = when {
    hex.isBlank() -> ""
    hex.length >= 16 -> "<${hex.take(8)}...${hex.takeLast(8)}>"
    else -> "<$hex>"
}

/**
 * Channel display label: only name-derived ("public hash") channels get the `#` prefix
 * (no space, e.g. `#test`); Public and Secret channels show their name as-is.
 */
fun channelLabel(name: String, kind: String): String =
    if (kind == ChannelKind.NAMED) "#$name" else name

/** Channel mentions are stored/transmitted as `@[Name]`; this matches one. */
val mentionRegex = Regex("""@\[([^\]]+)\]""")

/**
 * Number of intermediate relays a packet passed through. The stored route is the packet
 * trace, whose last hop is always this node, so relays = hops − 1 (0 = direct).
 */
fun relayCount(routeHex: String): Int =
    (routeHex.split(",").count { it.isNotBlank() } - 1).coerceAtLeast(0)

/** Compact "direct / N relays" chip shown on a message bubble. */
@Composable
fun RouteIndicator(routeHex: String) {
    if (routeHex.isBlank()) return
    val relays = relayCount(routeHex)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(
            if (relays == 0) Icons.Default.Bolt else Icons.Default.Route,
            contentDescription = if (relays == 0) "Direct" else "$relays relays",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            if (relays == 0) "direct" else "$relays",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Colored connection-health dot shown next to the app name. */
@Composable
fun ConnectionDot(state: ConnState, size: Int = 10) {
    val color = when (state) {
        ConnState.CONNECTED -> Color(0xFF2E7D32) // green
        ConnState.NO_PEERS -> Color(0xFFEF6C00)  // orange
        ConnState.OFFLINE -> Color(0xFF9E9E9E)   // grey
        ConnState.ERROR -> Color(0xFFC62828)     // red
    }
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
fun DeliveryTick(status: Int) {
    val (icon, tint) = when (status) {
        MsgStatus.SENDING -> Icons.Default.Schedule to MaterialTheme.colorScheme.onSurfaceVariant
        MsgStatus.SENT -> Icons.Default.Done to MaterialTheme.colorScheme.onSurfaceVariant
        MsgStatus.DELIVERED -> Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
        else -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
}

/**
 * Delivery tick for an outgoing DM that pulses while we're still waiting for the recipient's ACK
 * (i.e. [status] is SENT and a live [delivery] is in flight, not yet acked/failed) — so a single
 * checkmark visibly signals "in progress". Otherwise renders the static [DeliveryTick].
 */
@Composable
fun AnimatedDeliveryTick(status: Int, delivery: DmDelivery?) {
    val awaiting = status == MsgStatus.SENT && delivery != null && !delivery.acked && !delivery.failed
    if (!awaiting) {
        DeliveryTick(status)
        return
    }
    val transition = rememberInfiniteTransition(label = "delivery")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "tickAlpha",
    )
    Icon(
        Icons.Default.Done,
        contentDescription = "Awaiting acknowledgement",
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        modifier = Modifier.size(15.dp),
    )
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFmt = SimpleDateFormat("MMM d", Locale.getDefault())
private val fullDayFmt = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

fun formatClock(ts: Long): String = timeFmt.format(Date(ts))

/** Always-relative age ("just now", "Ns/m/h/d ago"), for the relative half of a message's timestamp. */
fun formatAgo(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 5_000 -> "just now"
        diff < 60_000 -> "${diff / 1000}s ago"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}

fun formatRelative(ts: Long): String {
    val now = System.currentTimeMillis()
    val sameDay = dayFmt.format(Date(ts)) == dayFmt.format(Date(now))
    return if (sameDay) timeFmt.format(Date(ts)) else dayFmt.format(Date(ts))
}

/**
 * Like [formatRelative] but renders the last hour as a relative age ("now", "Ns ago", "Nm ago"),
 * falling back to the clock (same day) or date. Used for recently discovered contacts on Explore.
 */
fun formatRelativeAge(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return if (diff in 0 until 3_600_000) formatMessageTime(ts) else formatRelative(ts)
}

/**
 * Per-message timestamp: "now" (<5s), "Ns ago" (<1m), "Nm ago" (<1h), else the clock time.
 * Recomputed on recomposition (not a live ticker).
 */
fun formatMessageTime(ts: Long): String {
    val diff = System.currentTimeMillis() - ts
    return when {
        diff < 0 -> timeFmt.format(Date(ts))
        diff < 5_000 -> "now"
        diff < 60_000 -> "${diff / 1000}s ago"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        else -> timeFmt.format(Date(ts))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
        ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
}

/** A day separator label: "Today", "Yesterday", or e.g. "Monday, Jun 9". */
fun dateLabel(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        isSameDay(ts, now) -> "Today"
        isSameDay(ts, now - 86_400_000L) -> "Yesterday"
        else -> fullDayFmt.format(Date(ts))
    }
}

/** True when [a] and [b] fall on different calendar days (used to insert date separators). */
fun differentDay(a: Long, b: Long): Boolean = !isSameDay(a, b)

/** Compact elapsed-duration label, e.g. "9s", "4m", "2h", "3d". Used for peer connection uptime. */
fun shortDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3_600 -> "${s / 60}m"
        s < 86_400 -> "${s / 3_600}h"
        else -> "${s / 86_400}d"
    }
}

/** Compact elapsed delay for an echo: "130ms", "2.3s", or "1m 5s" for longer round-trips. */
fun formatEchoDelay(ms: Long): String {
    val d = ms.coerceAtLeast(0)
    return when {
        d < 1_000 -> "${d}ms"
        d < 60_000 -> "%.1fs".format(d / 1000.0)
        else -> "${d / 60_000}m ${(d % 60_000) / 1000}s"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailsSheet(
    msg: Message,
    vm: ChatViewModel,
    onOpenProfile: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    // Echoes are persisted per-message (survive restart), unlike the in-memory routing logs.
    val echoesMap by vm.echoes.collectAsState()
    val repeatSamples = echoesMap[msg.id].orEmpty()
    val dmDeliveries by vm.dmDeliveries.collectAsState()
    val delivery = dmDeliveries[msg.id]
    val isChannel = isChannelPeer(msg.peerHex)
    val channelEchoed = isChannel && repeatSamples.isNotEmpty()

    // Cross-link to the packet detail dialogs. For an outgoing message we keep its raw datagram on
    // the message itself (persistent); otherwise fall back to the (trimmed) Rx Log. A MeshCore-
    // bridged message also carries the inner MeshCore packet's content id.
    val meshCorePackets by vm.meshCorePackets.collectAsState()
    val rxPackets by vm.rxPackets.collectAsState()
    val peers by vm.connectedPeers.collectAsState()
    val blePacket = remember(msg.packetHex, rxPackets, msg.id) {
        if (msg.packetHex.isNotBlank()) vm.decodePacket(msg.packetHex, timestampMs = msg.timestampMs)
        else rxPackets.firstOrNull { it.id.toHex() == msg.id }
    }
    // Prefer the live Rx Log entry (full carrier info); else rebuild from the bytes persisted on the
    // message, so "Examine" / the MeshCore packet button work after it ages out or a restart.
    val meshPacket = remember(meshCorePackets, msg.meshCorePacketId, msg.meshCorePacketHex) {
        msg.meshCorePacketId.takeIf { it.isNotBlank() }?.let { id -> meshCorePackets.firstOrNull { it.contentId == id } }
            ?: msg.meshCorePacketHex.takeIf { it.isNotBlank() }?.let { vm.decodeMeshCorePacket(it, msg) }
    }
    // The ACK datagram for a delivered DM, persisted on the message so the round-trip delay and its
    // packet detail survive a restart.
    val ackPacket = remember(msg.ackPacketHex) {
        msg.ackPacketHex.takeIf { it.isNotBlank() }?.let { vm.decodePacket(it, timestampMs = msg.ackTimestampMs) }
    }
    var showBlePacket by remember { mutableStateOf(false) }
    var showMeshPacket by remember { mutableStateOf(false) }
    var showAckPacket by remember { mutableStateOf(false) }
    // A specific echo's reception packet, opened by tapping that echo row.
    var echoPacket by remember { mutableStateOf<cz.meshcore.sidepath.service.RxPacket?>(null) }
    // A specific MeshCore heard, opened as the full-screen "View MeshCore Path".
    var pathHeard by remember { mutableStateOf<cz.meshcore.meshward.data.MeshCoreHeard?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header: title + the Sidepath datagram id (tap to copy). For a bridged MeshCore message
            // the stored row key is a synthetic "mc:" dedup id, so prefer the local carrier
            // datagram's id (decoded from the persisted packet) to keep this Sidepath-first.
            val clipboard = LocalClipboardManager.current
            val displayId = if (msg.id.startsWith("mc:")) blePacket?.id?.toHex() ?: msg.id else msg.id
            Text("Message details", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString(displayId)) },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ID", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    displayId.ifBlank { "—" },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy id", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // --- Consistent core block, in the order: type, direction, sender, status, time. ---
            val messageType = when {
                msg.peerHex == vm.myNodeHex() -> "Note to self"
                isChannel -> "Channel message"
                else -> "Direct message"
            }
            DetailRow("Type", messageType)
            DirectionRow(msg.incoming)

            // Sender. A bridged MeshCore channel author is unverifiable — we only know its declared
            // name — so the bridge node and the declared sender are shown separately. Native channel
            // messages and DMs carry a real originating identity.
            if (isChannel && msg.viaMeshCore) {
                if (msg.bridgeHex.isNotBlank()) {
                    SenderRow("Sender (bridge)", msg.bridgeHex, vm, onOpenProfile)
                }
                val declaredHex = msg.senderName.takeIf { it.isNotBlank() }?.let { vm.nodeHexForName(it) }
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (declaredHex != null && onOpenProfile != null) Modifier.clickable { onOpenProfile(declaredHex) } else Modifier),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Declared sender", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        msg.senderName.ifBlank { "—" },
                        fontWeight = FontWeight.Medium,
                        color = if (declaredHex != null) MaterialTheme.colorScheme.primary
                        else virtualNameColor(msg.senderName),
                    )
                }
                Text(
                    if (declaredHex != null) "Name matches a saved contact (still unverified — no public key)."
                    else "Unverified — bridged names carry no public key, so this can't be linked.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Native channel / DM / note-to-self: the sender is a real node. For our own outgoing
                // message that's us; for an incoming DM it's the peer.
                val senderHex = when {
                    msg.senderHex.isNotBlank() -> msg.senderHex
                    !msg.incoming -> vm.myNodeHex()
                    else -> msg.peerHex
                }
                if (senderHex.isNotBlank() && !isChannelPeer(senderHex)) {
                    SenderRow("Sender", senderHex, vm, onOpenProfile)
                }
            }

            // Status — with a glyph (green check once confirmed) and, for a delivered DM, the ACK
            // round-trip delay.
            val ackDelay = if (msg.ackTimestampMs > 0L && msg.ackTimestampMs >= msg.timestampMs)
                formatEchoDelay(msg.ackTimestampMs - msg.timestampMs) else null
            val (statusText, statusIcon, statusTint) = messageStatus(
                msg, isChannel, channelEchoed, repeatSamples.size, delivery, ackDelay,
            )
            StatusRow(
                statusText, statusIcon, statusTint,
                chip = if (ackPacket != null) "ACK packet" else null,
                onChip = { showAckPacket = true },
            )
            // Retry detail for a DM that needed (or is making) more than one attempt.
            if (!msg.incoming && !isChannel && delivery != null && delivery.maxTries > 1) {
                DetailRow("Delivery attempts", "${delivery.attemptsSent} of ${delivery.maxTries}")
            }

            // Time — absolute clock plus a relative age.
            DetailRow("Time", "${dayFmt.format(Date(msg.timestampMs))} ${formatClock(msg.timestampMs)} · ${formatAgo(msg.timestampMs)}")

            // Bridged: a gateway relayed this (outgoing channel) message onto MeshCore (ACK_BRIDGED).
            if (msg.bridgedToMeshCore) {
                DetailRow(
                    "Bridged to MeshCore",
                    if (msg.bridgedByHex.isNotBlank()) "via ${vm.nameForHex(msg.bridgedByHex).ifBlank { msg.bridgedByHex.take(12) }}" else "yes",
                )
            }

            // Origin: where this message entered our world (a local Sidepath datagram, or one bridged
            // in from MeshCore). The MeshCore carrier itself gets its own section below.
            DetailRow("Origin", if (msg.viaMeshCore) "MeshCore (bridged)" else "Sidepath")

            val relays = relayCount(msg.routeHex)
            // Real intermediate relays only — drop the final hop (always this node); the
            // sender/recipient endpoints aren't shown.
            val relayHops = msg.routeHex.split(",").filter { it.isNotBlank() }.dropLast(1)
            DetailRow("Delivery", when {
                msg.routeHex.isBlank() -> when {
                    msg.incoming -> "—"
                    isChannel -> if (channelEchoed)
                        "Echoed by ${repeatSamples.size} node${if (repeatSamples.size == 1) "" else "s"}"
                    else "Broadcast — no echo yet"
                    else -> "Awaiting confirmation"
                }
                relays == 0 -> "Direct (no relays)"
                else -> "$relays relay${if (relays == 1) "" else "s"}"
            })
            if (relayHops.isNotEmpty()) {
                Text(
                    "Relays",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                relayHops.forEachIndexed { i, hop ->
                    Text(
                        "${i + 1}. ${vm.nameForHex(hop)}  ·  ${hop.take(8)}",
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    )
                }
            }
            // Reception signal for an incoming message: the link RSSI we heard it at, with a link to
            // its packet detail — styled like the Echoes table, and persisted (survives a restart).
            if (msg.incoming && (blePacket != null || msg.rssi != RSSI_UNKNOWN)) {
                val directPeerHex = msg.routeHex.split(",").filter { it.isNotBlank() }.lastOrNull()
                val via = directPeerHex?.let { vm.nameForHex(it) }?.takeIf { it.isNotBlank() }
                Text(
                    "Signal",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(if (blePacket != null) Modifier.clickable { showBlePacket = true } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        if (via != null) "Received · via $via" else "Received",
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // The link RSSI is always a local Sidepath reception (the carrier arrived over BLE),
                    // so show it for bridged MeshCore messages too.
                    SignalLabel(msg.rssi, "rssi")
                    if (blePacket != null) Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "packet details",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Repeats of this flooded message we heard echoed back, with the RSSI of each reception.
            if (repeatSamples.isNotEmpty()) {
                DetailRow("Repeats heard", "${repeatSamples.size}")
                Text(
                    "Echoes",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                repeatSamples.forEachIndexed { i, s ->
                    val via = if (s.viaMeshCore) "MeshCore"
                    else s.forwarderHex.takeIf { it.isNotBlank() }?.let { vm.nameForHex(it) }
                    // Round-trip delay since we sent the message — more useful than a wall-clock time.
                    val delay = formatEchoDelay(s.timestampMs - msg.timestampMs)
                    val clickable = s.packetHex.isNotBlank()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (clickable) Modifier.clickable { echoPacket = vm.decodePacket(s.packetHex, s.rssi, s.timestampMs) } else Modifier),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${i + 1}. +$delay${if (via != null) "  · via $via" else ""}",
                            fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (!s.viaMeshCore) SignalLabel(s.rssi, "rssi")
                        if (clickable) Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = "packet details",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Sidepath section ends with its packet — the local datagram is always "Packet detail".
            if (blePacket != null) PacketButton("Packet detail") { showBlePacket = true }

            // --- MeshCore section: the bridged carrier's own details, kept separate from the Sidepath
            // facts above. Only for messages that crossed the MeshCore bridge. ---
            if (msg.viaMeshCore) {
                val heardsMap by vm.meshCoreHeards.collectAsState()
                // Distinct-path receptions, persisted. Shortest path first. Fall back to a single
                // synthetic heard from the message's own packet (older messages / first sighting).
                val heards = heardsMap[msg.id].orEmpty().sortedBy { it.hopCount }
                val pathHashSize = heards.firstOrNull()?.pathHashSize
                    ?: meshPacket?.envelope?.pathHashSize ?: 0

                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(
                    "MeshCore",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (msg.meshCoreType.isNotBlank()) DetailRow("Type", msg.meshCoreType)
                if (pathHashSize > 0) DetailRow("Path hash size", "$pathHashSize byte${if (pathHashSize == 1) "" else "s"}")
                if (msg.meshCorePacketId.isNotBlank()) DetailRow("Packet", msg.meshCorePacketId)

                // Heards: every distinct path this message reached us by. Each opens the full route.
                if (heards.isNotEmpty()) {
                    Text(
                        "MeshCore heards (${heards.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    heards.forEachIndexed { i, h ->
                        val via = vm.nameForHex(h.forwarderHex).takeIf { it.isNotBlank() }
                        // The count of hops we can actually list (matches the path view). MeshCore's
                        // own reported hopCount is appended when it differs, for transparency.
                        val listed = h.hopsHex.split(",").count { it.isNotBlank() }
                        val reported = if (h.hopCount != listed) " (reported ${h.hopCount})" else ""
                        Row(
                            Modifier.fillMaxWidth().clickable { pathHeard = h },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "${i + 1}. $listed hop${if (listed == 1) "" else "s"}$reported${if (via != null) "  · via $via" else ""}",
                                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            SignalLabel(h.rssi, "rssi")
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "view path",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (msg.meshCoreRoute.isNotBlank()) {
                    // No persisted heards (older message) — show the single route summary we have.
                    val hopsText = if (msg.meshCoreHops > 0) " · ${msg.meshCoreHops} hop${if (msg.meshCoreHops == 1) "" else "s"}" else ""
                    DetailRow("Route", msg.meshCoreRoute + hopsText)
                }

                if (meshPacket != null) PacketButton("MeshCore packet detail") { showMeshPacket = true }
            }
        }
    }

    pathHeard?.let { h ->
        MeshCorePathDialog(
            heard = h,
            senderName = msg.senderName.ifBlank { "MeshCore node" },
            myName = vm.nameForHex(vm.myNodeHex()).ifBlank { "You" },
            vm = vm,
            onDismiss = { pathHeard = null },
        )
    }

    if (showBlePacket && blePacket != null) {
        PacketDetailDialog(
            p = blePacket,
            vm = vm,
            peers = peers,
            onOpenProfile = { hex -> showBlePacket = false; onOpenProfile?.invoke(hex) },
            onDismiss = { showBlePacket = false },
        )
    }
    if (showMeshPacket && meshPacket != null) {
        MeshCoreDetailDialog(meshPacket, vm, onDismiss = { showMeshPacket = false })
    }
    if (showAckPacket && ackPacket != null) {
        PacketDetailDialog(
            p = ackPacket,
            vm = vm,
            peers = peers,
            onOpenProfile = { hex -> showAckPacket = false; onOpenProfile?.invoke(hex) },
            onDismiss = { showAckPacket = false },
        )
    }
    echoPacket?.let { ep ->
        PacketDetailDialog(
            p = ep,
            vm = vm,
            peers = peers,
            onOpenProfile = { hex -> echoPacket = null; onOpenProfile?.invoke(hex) },
            onDismiss = { echoPacket = null },
        )
    }
}

/**
 * Full-screen "View MeshCore Path" for one heard: the route the bridged channel message took to
 * reach us, hop by hop. Each hop is a path-hash prefix; we resolve it to a node where we can, and
 * surface the ambiguity (a short prefix can match several nodes) with an expandable candidate list.
 * Renders entirely from the persisted [heard], so it works offline / after a restart.
 */
@Composable
fun MeshCorePathDialog(
    heard: MeshCoreHeard,
    senderName: String,
    myName: String,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val hops = heard.hopsHex.split(",").filter { it.isNotBlank() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text("View MeshCore Path", style = MaterialTheme.typography.titleLarge)
                        val label = buildString {
                            append("${hops.size} hop${if (hops.size == 1) "" else "s"}")
                            if (heard.routeLabel.isNotBlank()) append(" · ${heard.routeLabel}")
                            if (heard.pathHashSize > 0) append(" · ${heard.pathHashSize}-byte hash")
                        }
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PathEndpointRow(senderName, senderName, "Sent the message")
                    hops.forEachIndexed { i, hex ->
                        PathHopRow(hex, i + 1, vm.meshCoreHopCandidates(hex))
                    }
                    PathEndpointRow(myName, myName, "You received the message")
                }
            }
        }
    }
}

/** An origin/destination node in [MeshCorePathDialog] — a coloured initial badge + name. */
@Composable
private fun PathEndpointRow(seed: String, title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(seed.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One repeater hop in [MeshCorePathDialog]: prefix badge + resolved name, with ambiguity expandable. */
@Composable
private fun PathHopRow(prefixHex: String, hopNumber: Int, candidates: List<MeshCoreHopMatch>) {
    var expanded by remember { mutableStateOf(false) }
    val known = candidates.isNotEmpty()
    val ambiguous = candidates.size > 1
    Column(
        Modifier.fillMaxWidth().then(if (ambiguous) Modifier.clickable { expanded = !expanded } else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).clip(CircleShape)
                    .background(if (known) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    prefixHex.take(4),
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = if (known) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (known) candidates.first().name else "Unknown repeater",
                    fontWeight = FontWeight.Medium,
                    color = if (known) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    buildString {
                        append("Hop $hopNumber")
                        when {
                            !known -> append(" · no known node for $prefixHex")
                            ambiguous -> append(" · ${candidates.size} possible nodes — tap to ${if (expanded) "hide" else "show"}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded && ambiguous) {
            candidates.forEach { c ->
                Text(
                    "• ${c.name}  ·  ${c.nodeHex.take(8)}",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 48.dp, top = 2.dp),
                )
            }
        }
    }
}

/** A sender/bridge row in message details: avatar + name, tappable to open the node's profile. */
@Composable
private fun SenderRow(label: String, nodeHex: String, vm: ChatViewModel, onOpenProfile: ((String) -> Unit)?) {
    val profile by remember(nodeHex) { vm.profileFor(nodeHex) }.collectAsState()
    val name = profile.name.ifBlank { vm.nameForHex(nodeHex) }
    Row(
        Modifier.fillMaxWidth()
            .then(if (onOpenProfile != null) Modifier.clickable { onOpenProfile(nodeHex) } else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(seed = nodeHex, label = name, size = 28, identiconKey = profile.pubKeyHex.ifBlank { null })
            Spacer(Modifier.size(8.dp))
            Text(
                name,
                fontWeight = FontWeight.Medium,
                color = if (onOpenProfile != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/** Direction row with a directional arrow (incoming ↙ / outgoing ↗). */
@Composable
private fun DirectionRow(incoming: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Direction", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                if (incoming) Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(if (incoming) "Incoming" else "Outgoing", fontWeight = FontWeight.Medium)
        }
    }
}

/** Status row: a glyph (green check once confirmed) + text, with an optional trailing action chip. */
@Composable
private fun StatusRow(value: String, icon: ImageVector, tint: Color, chip: String?, onChip: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Status", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            Text(value, fontWeight = FontWeight.Medium)
            if (chip != null) {
                Surface(
                    color = Color(0xFF00838F).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable(onClick = onChip),
                ) {
                    Text(
                        chip,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF00838F),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** Full-width "Packet detail"-style outlined button used at the bottom of message details. */
@Composable
private fun PacketButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.AutoMirrored.Filled.ListAlt, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** Status line text + glyph + tint for a message, including the ACK round-trip delay when present. */
private fun messageStatus(
    msg: Message,
    isChannel: Boolean,
    channelEchoed: Boolean,
    repeats: Int,
    delivery: DmDelivery?,
    ackDelay: String?,
): Triple<String, ImageVector, Color> {
    val green = Color(0xFF2E7D32)
    val grey = Color(0xFF9E9E9E)
    val red = Color(0xFFC62828)
    if (msg.incoming) return Triple("Received", Icons.Default.CheckCircle, green)
    return when {
        // Channels are broadcast and never ACKed — hearing our own message echoed back is the only
        // confirmation it propagated, so treat an echo as "delivered".
        isChannel -> if (channelEchoed)
            Triple("Delivered · heard echoed ${repeats}×", Icons.Default.CheckCircle, green)
        else Triple("Sent to mesh (no echo yet)", Icons.Default.Done, grey)
        msg.status == MsgStatus.SENDING -> Triple("Sending…", Icons.Default.Schedule, grey)
        msg.status == MsgStatus.SENT -> {
            val awaiting = delivery != null && !delivery.acked && !delivery.failed
            val text = buildString {
                append(if (awaiting) "Sent — try ${delivery.attemptsSent} of ${delivery.maxTries}, awaiting ACK" else "Sent to mesh")
                if (repeats > 0) append(" · $repeats repeat${if (repeats == 1) "" else "s"} heard")
            }
            Triple(text, if (awaiting) Icons.Default.HourglassEmpty else Icons.Default.Done, grey)
        }
        msg.status == MsgStatus.DELIVERED -> {
            val base = if (delivery != null && delivery.attemptsSent > 1)
                "Delivered (ACK after ${delivery.attemptsSent} tries)" else "Delivered"
            Triple(base + (ackDelay?.let { " · +$it" } ?: ""), Icons.Default.DoneAll, green)
        }
        else -> Triple(
            if (delivery != null) "Failed — no ACK after ${delivery.attemptsSent} tries" else "Failed to send",
            Icons.Default.ErrorOutline, red,
        )
    }
}

/** A [DetailRow] whose value can carry a trailing teal action chip (e.g. "Examine"). */
@Composable
private fun DetailRowWithChip(label: String, value: String, chip: String?, onChip: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, fontWeight = FontWeight.Medium)
            if (chip != null) {
                Surface(
                    color = Color(0xFF00838F).copy(alpha = 0.16f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable(onClick = onChip),
                ) {
                    Text(
                        chip,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF00838F),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * The raw-packet section shared by the Sidepath and MeshCore packet dialogs: a "[label] (N bytes)"
 * header with a copy-to-clipboard button, above a monospace offset/hex/ascii dump of [bytes].
 */
@Composable
fun RawPacketView(label: String, bytes: ByteArray) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("$label (${bytes.size} bytes)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        IconButton(onClick = { clipboard.setText(AnnotatedString(bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) })) }) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Copy packet hex", modifier = Modifier.size(18.dp))
        }
    }
    Text(
        bytes.toHexDump(),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
    )
}

/** Classic offset / hex / ascii dump, 16 bytes per line. Shared by the packet detail dialogs. */
private fun ByteArray.toHexDump(): String {
    if (isEmpty()) return "(empty)"
    val sb = StringBuilder()
    var i = 0
    while (i < size) {
        sb.append("%04x  ".format(i))
        val end = minOf(i + 16, size)
        for (j in i until end) sb.append("%02x ".format(this[j].toInt() and 0xFF))
        repeat(16 - (end - i)) { sb.append("   ") }
        sb.append(" ")
        for (j in i until end) {
            val c = this[j].toInt() and 0xFF
            sb.append(if (c in 0x20..0x7e) c.toChar() else '.')
        }
        sb.append('\n')
        i = end
    }
    return sb.toString().trimEnd('\n')
}

// ---- Signal quality indicator -----------------------------------------------

/**
 * Maps a signal reading to a traffic-light colour. [metric] is "snr" (dB, higher is better) or
 * anything else = RSSI (dBm, less-negative is better). A null / unknown value is grey.
 */
fun signalColor(value: Int?, metric: String): Color {
    if (value == null || value == RSSI_UNKNOWN) return Color(0xFF9E9E9E) // grey — unknown
    val q = if (metric == "snr") {
        when { value >= 10 -> 4; value >= 5 -> 3; value >= 0 -> 2; value >= -5 -> 1; else -> 0 }
    } else {
        when { value >= -60 -> 4; value >= -70 -> 3; value >= -80 -> 2; value >= -90 -> 1; else -> 0 }
    }
    return when (q) {
        4 -> Color(0xFF2E7D32) // strong — green
        3 -> Color(0xFF7CB342) // good — light green
        2 -> Color(0xFFF9A825) // fair — amber
        1 -> Color(0xFFEF6C00) // weak — orange
        else -> Color(0xFFC62828) // poor — red
    }
}

/** A small colour-coded dot indicating signal quality, for use next to an RSSI/SNR number. */
@Composable
fun SignalDot(value: Int?, metric: String = "rssi", modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(signalColor(value, metric)))
}

/** A signal dot followed by its value, e.g. "● -63 dBm". [value] = RSSI_UNKNOWN renders "n/a". */
@Composable
fun SignalLabel(
    value: Int?,
    metric: String = "rssi",
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        SignalDot(value, metric)
        val unit = if (metric == "snr") "dB" else "dBm"
        Text(
            if (value == null || value == RSSI_UNKNOWN) "n/a" else "$value $unit",
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
