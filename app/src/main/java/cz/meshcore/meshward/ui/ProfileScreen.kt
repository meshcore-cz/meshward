package cz.meshcore.meshward.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.MeshCoreUri
import cz.meshcore.meshward.ProfileInfo
import cz.meshcore.meshward.hasRealLocation
import cz.meshcore.meshward.data.ChannelKind
import cz.meshcore.meshward.data.DiscoverySource
import cz.meshcore.sidepath.meshcore.MeshCorePacket
import cz.meshcore.sidepath.service.RxPacket
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    vm: ChatViewModel,
    peerHex: String,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onTrace: (String) -> Unit,
    onOpenProfile: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenNetworkDetail: (String) -> Unit = {},
) {
    val profile by remember(peerHex) { vm.profileFor(peerHex) }.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    // Last-heard announcements for this node, surfaced with a button to open the originating packet.
    // Persisted in the DB (keyed by node + source) so they survive a restart, after the live Rx Log
    // buffers have aged out. Sidepath = last signed ANNOUNCE; MeshCore = last ADVERT.
    val peers by vm.connectedPeers.collectAsState()
    val networks by vm.networks.collectAsState()
    val userLoc by vm.userLocation.collectAsState()
    // Distance to this node, when both we and it have coordinates.
    val distance = userLoc
        ?.takeIf { profile.hasGps && hasRealLocation(profile.lat, profile.lon) && hasRealLocation(it.lat, it.lon) }
        ?.let { formatDistance(distanceMeters(it, profile.lat, profile.lon)) }
    val announcements by remember(profile.nodeHex, profile.isChannel) {
        if (profile.isChannel || profile.nodeHex.isBlank()) flowOf(emptyList())
        else vm.announcementsForNode(profile.nodeHex)
    }.collectAsState(emptyList())
    val sidepathAnn = announcements.firstOrNull { it.source == DiscoverySource.SIDEPATH }
    val meshAnn = announcements.firstOrNull { it.source == DiscoverySource.MESHCORE }
    var showAnnounce by remember { mutableStateOf<RxPacket?>(null) }
    var showAdvert by remember { mutableStateOf<MeshCorePacket?>(null) }
    // Ticks so the announcement ages stay live while the profile is open.
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }

    // A MeshCore share URI other apps can scan to add this contact / join this channel.
    val shareUri = when {
        profile.isChannel && profile.pskHex.isNotBlank() -> MeshCoreUri.channel(profile.name, profile.pskHex)
        !profile.isChannel && profile.pubKeyHex.isNotBlank() -> MeshCoreUri.contact(profile.name, profile.pubKeyHex)
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profile.isChannel) "Channel info" else "Profile") },
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
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Big centered avatar. Identicons are keyed on a contact's public key; channels
            // (and contacts without a known key) fall back to initials.
            Avatar(
                seed = peerHex,
                label = profile.name,
                size = 120,
                identiconKey = if (!profile.isChannel) profile.pubKeyHex else null,
            )
            Spacer(Modifier.size(16.dp))
            Text(
                if (profile.isChannel) channelLabel(profile.name, profile.channelKind) else profile.name,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            // "Saved" chip right under the name when this node is in our contacts (replaces the old
            // "In contacts: Yes" detail row).
            if (!profile.isChannel && profile.isContact) {
                Spacer(Modifier.size(6.dp))
                SavedChip()
            }

            // Mark bridged MeshCore identities — they're full contacts but not directly DM-reachable.
            // When the node was tiered to a MeshCore Network, show its code chip; tapping opens the
            // network's detail.
            if (profile.isMeshCore) {
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeshCoreProfileBadge()
                    if (profile.networkCode.isNotBlank()) {
                        Row(
                            Modifier.clickable { onOpenNetworkDetail(profile.networkCode) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            NetworkProfileBadge(profile.networkCode)
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "Open network",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }

            // Public key (users) shown right under the name, same compact form as the chat list,
            // with a key icon to reveal the full key and copy it to the clipboard.
            if (!profile.isChannel && profile.pubKeyHex.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatPubKey(profile.pubKeyHex),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                    IconButton(onClick = { showKey = true }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = "Show full public key",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // Direct messages to a contact are end-to-end encrypted.
            if (!profile.isChannel) {
                Spacer(Modifier.size(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "End-to-end encrypted",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The node's own free-form description is shown only here, between the hero and the
            // actions (the deterministic name is the primary label everywhere else).
            if (!profile.isChannel && profile.description.isNotBlank()) {
                Spacer(Modifier.size(12.dp))
                Text(
                    profile.description,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.size(20.dp))

            // Actions, kept compact and up near the hero. Primary action is full-width; the
            // rest are a row of small icon-over-label buttons.
            Button(
                // Messaging a discovered node first saves it as a contact so it persists in Chats.
                onClick = {
                    if (!profile.isChannel) vm.startChat(peerHex)
                    onOpenConversation(peerHex)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (profile.isChannel) "Open channel" else "Message")
            }
            Spacer(Modifier.size(8.dp))
            // Primary actions. Our own profile gets Settings (no Rename/Trace of self); everyone
            // else gets Rename + Trace. "Show QR" opens the share sheet. Messaging a discovered node
            // adds it as a contact automatically, so there's no separate "Add contact".
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (shareUri != null) {
                    CompactAction(Icons.Default.QrCode2, "Show QR") { showShare = true }
                }
                if (profile.isSelf) {
                    CompactAction(Icons.Default.Settings, "Settings") { onOpenSettings() }
                } else {
                    CompactAction(Icons.Default.Edit, "Rename") { renaming = true }
                    if (!profile.isChannel) {
                        CompactAction(Icons.Default.Route, "Trace") { onTrace(peerHex) }
                    }
                }
            }

            Spacer(Modifier.size(28.dp))

            // Information block, grouped into logical sections: common details, then Sidepath-specific
            // facts, then MeshCore-specific ones. Each section is shown only when it has content.
            if (profile.isChannel) {
                ChannelInfo(profile)
            } else {
                CommonDetails(profile, distance)

                // Sidepath section: presence, neighbors, supplied bridges, last signed ANNOUNCE. Shown
                // for native Sidepath nodes, or any node that has Sidepath data to show.
                val hasSidepath = !profile.isMeshCore || profile.online ||
                    profile.neighborHexes.isNotEmpty() || profile.bridges.isNotEmpty() || sidepathAnn != null
                if (hasSidepath) {
                    Spacer(Modifier.size(24.dp))
                    ProfileSection("Sidepath") {
                        InfoRow("Status", if (profile.online) "Online (announcing)" else "Not currently visible")
                        if (profile.neighborHexes.isNotEmpty()) {
                            NeighborsSection(vm, profile.neighborHexes, onOpenProfile)
                        }
                        if (profile.bridges.isNotEmpty()) {
                            BridgesSection(profile.bridges, networks, onOpenNetworkDetail)
                        }
                        if (sidepathAnn != null) {
                            AnnouncementRow("Last announcement", formatRelativeAge(sidepathAnn.timestampMs, nowMs)) {
                                showAnnounce = vm.decodePacket(sidepathAnn.rawHex, timestampMs = sidepathAnn.timestampMs)
                            }
                        }
                    }
                }

                // MeshCore section: bridged-network facts and the last ADVERT we heard.
                if (profile.isMeshCore) {
                    Spacer(Modifier.size(24.dp))
                    ProfileSection("MeshCore") {
                        if (profile.networkCode.isNotBlank()) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpenNetworkDetail(profile.networkCode) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) { InfoRow("Network", profile.networkCode) }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Open network",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        InfoRow("Reachability", "Bridged — not directly DM-reachable")
                        if (profile.nodeType != 0) InfoRow("Node type", nodeTypeLabel(profile.nodeType))
                        InfoRow("Signature", if (profile.sigVerified) "Verified" else "Unverified")
                        if (profile.nodeAdvertisedMs > 0) InfoRow("Advertised", formatRelative(profile.nodeAdvertisedMs))
                        if (meshAnn != null) {
                            AnnouncementRow("Last advert", formatRelativeAge(meshAnn.timestampMs, nowMs)) {
                                showAdvert = vm.decodeMeshCorePacketRaw(meshAnn.rawHex, meshAnn.timestampMs)
                            }
                        }
                    }
                }
            }

            // Destructive action (Leave channel / Delete contact) kept apart from the
            // primary actions, at the bottom.
            if (profile.isChannel || profile.isContact) {
                Spacer(Modifier.size(28.dp))
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(if (profile.isChannel) Icons.Default.Logout else Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(if (profile.isChannel) "Leave channel" else "Delete contact")
                }
            }
        }
    }

    if (showShare && shareUri != null) {
        ShareQrSheet(
            title = if (profile.isChannel) "Share ${channelLabel(profile.name, profile.channelKind)}" else "Share ${profile.name}",
            subtitle = if (profile.isChannel) "Scan to join this channel" else "Scan to add this contact",
            uri = shareUri,
            onDismiss = { showShare = false },
        )
    }

    if (renaming) {
        RenameDialog(
            current = profile.name,
            isChannel = profile.isChannel,
            nameIsCustom = profile.nameIsCustom,
            onConfirm = { name ->
                if (profile.isChannel) vm.renameChannel(profile.pskHex, name)
                else vm.renameContact(profile.nodeHex, name)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(if (profile.isChannel) "Leave channel?" else "Delete contact?") },
            text = {
                Text(
                    if (profile.isChannel) "You'll stop receiving messages on ${channelLabel(profile.name, profile.channelKind)}. You can rejoin later."
                    else "Remove ${profile.name} from your contacts and delete this conversation. You can re-add them from Explore.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (profile.isChannel) vm.leaveChannel(profile.pskHex)
                    else vm.deleteContact(profile.nodeHex)
                    confirmDelete = false
                    onBack()
                }) { Text(if (profile.isChannel) "Leave" else "Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
    if (showKey && profile.pubKeyHex.isNotBlank()) {
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showKey = false },
            title = { Text("Public key") },
            text = {
                SelectionContainer {
                    Text(
                        profile.pubKeyHex,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(profile.pubKeyHex))
                    Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                    showKey = false
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Copy")
                }
            },
            dismissButton = { TextButton(onClick = { showKey = false }) { Text("Close") } },
        )
    }

    showAnnounce?.let {
        PacketDetailDialog(
            p = it,
            vm = vm,
            peers = peers,
            onOpenProfile = { hex -> showAnnounce = null; onOpenProfile(hex) },
            onDismiss = { showAnnounce = null },
        )
    }
    showAdvert?.let {
        MeshCoreDetailDialog(it, vm, onDismiss = { showAdvert = null })
    }
}

/** One announcement row: a [title] + relative time on the left, "View packet" on the right. */
@Composable
private fun AnnouncementRow(title: String, relative: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(title, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Text(
                relative,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClick) { Text("View packet") }
    }
}

/** A small icon-over-label outlined button that shares a row evenly with its siblings. */
@Composable
private fun RowScope.CompactAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Lists a node's directly-connected neighbors (from its signed ANNOUNCE); tap to open one. */
@Composable
private fun NeighborsSection(
    vm: ChatViewModel,
    neighborHexes: List<String>,
    onOpenProfile: (String) -> Unit,
) {
    // A neighbor list is 10-byte NodeIDs only. Those we can resolve to a full public key (heard their
    // announce, or connected to them) get a real name + identicon; the rest are NodeID-only and can't,
    // so they're grouped apart and shown plainly as "Unknown node" rather than mixed in.
    val (known, unknown) = neighborHexes.partition { vm.pubKeyForHex(it).isNotBlank() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Neighbors (${neighborHexes.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        known.forEach { hex ->
            val label = vm.nameForHex(hex).ifBlank { "node ${hex.take(8)}" }
            Row(
                Modifier.fillMaxWidth().clickable { onOpenProfile(hex) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(seed = hex, label = label, size = 32, identiconKey = vm.pubKeyForHex(hex))
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Text(
                        hex.take(20),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Unidentified neighbors: NodeID only, kept visually apart and de-emphasised.
        if (unknown.isNotEmpty()) {
            if (known.isNotEmpty()) {
                androidx.compose.material3.HorizontalDivider(
                    Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            Text(
                "Unidentified (${unknown.size}) — NodeID only, no key heard yet",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            unknown.forEach { hex -> UnknownNodeRow(hex, onOpenProfile) }
        }
    }
}

/** A neighbor we can't resolve to a full public key: NodeID only, no identicon, no real name. */
@Composable
private fun UnknownNodeRow(hex: String, onOpenProfile: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpenProfile(hex) }.alpha(0.6f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.QuestionMark,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Unknown node", style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            Text(
                hex.take(20),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A titled block of profile details: a separator line, then a section header, then its rows. */
@Composable
private fun ProfileSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

/** Details that apply to any node, regardless of transport: device and location. The identity (public
 * key) lives in the hero up top, and "in contacts" is shown as the "Saved" chip under the name. */
@Composable
private fun CommonDetails(p: ProfileInfo, distance: String?) {
    // (0,0) is the "no fix" sentinel — don't surface it as a real location.
    val hasLoc = p.hasGps && hasRealLocation(p.lat, p.lon)
    // Nothing common to show for a bare node — skip the section (and its header) entirely.
    if (p.platform.isBlank() && !hasLoc) return
    ProfileSection("Details") {
        if (p.platform.isNotBlank()) InfoRow("Platform", p.platform)
        if (hasLoc) LocationRow(p.lat, p.lon, distance)
    }
}

/** Coordinates with a pin icon, and an approximate-distance chip when our own location is known. */
@Composable
private fun LocationRow(lat: Double, lon: Double, distance: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Default.Place,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.5f, %.5f".format(lat, lon), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
        if (distance != null) {
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Default.NearMe, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        "≈ $distance",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/** Lists the external networks a gateway node bridges (from its signed ANNOUNCE); tap to open one. */
@Composable
private fun BridgesSection(
    bridges: List<cz.meshcore.sidepath.protocol.BridgeAd>,
    networks: List<cz.meshcore.meshward.data.MeshNetwork>,
    onOpenNetworkDetail: (String) -> Unit,
) {
    val byCode = remember(networks) { networks.associateBy { it.code } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Bridges (${bridges.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        bridges.forEach { ad ->
            val net = cz.meshcore.meshward.resolveBridge(ad, byCode)
            Row(
                Modifier.fillMaxWidth().clickable { onOpenNetworkDetail(ad.code) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetworkCodeChip(ad.code)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(net.name.ifBlank { ad.code }, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    val summary = networkRadioSummary(net)
                    if (summary.isNotBlank()) {
                        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Network-code pill shown next to [MeshCoreProfileBadge]; sized to match it exactly. */
@Composable
private fun NetworkProfileBadge(code: String) {
    androidx.compose.material3.Surface(
        color = androidx.compose.ui.graphics.Color(0xFF00838F).copy(alpha = 0.16f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Text(
            code,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = androidx.compose.ui.graphics.Color(0xFF00838F),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** "Saved" chip shown under the name for nodes already in our contacts (mirrors Explore's badge). */
@Composable
private fun SavedChip() {
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Saved",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Small "MeshCore" pill shown under the name for bridged MeshCore identities. */
@Composable
private fun MeshCoreProfileBadge() {
    androidx.compose.material3.Surface(
        color = androidx.compose.ui.graphics.Color(0xFF00838F).copy(alpha = 0.16f),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Default.Hub,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = androidx.compose.ui.graphics.Color(0xFF00838F),
            )
            Text(
                "MeshCore",
                style = MaterialTheme.typography.labelMedium,
                color = androidx.compose.ui.graphics.Color(0xFF00838F),
            )
        }
    }
}

@Composable
private fun ChannelInfo(p: ProfileInfo) {
    val kindLabel = when (p.channelKind) {
        ChannelKind.PUBLIC -> "Public (MeshCore default)"
        ChannelKind.NAMED -> "Named (key derived from name)"
        ChannelKind.SECRET -> "Secret (shared key)"
        else -> p.channelKind.ifBlank { "—" }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        InfoRow("Type", kindLabel)
        InfoRow("Channel hash", "0x%02x".format(p.channelHash))
        InfoRow("Pre-shared key", p.pskHex)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RenameDialog(
    current: String,
    isChannel: Boolean,
    nameIsCustom: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // A custom name (a previous Rename) is editable text the user can delete/adjust. An announce/
    // derived name is shown only as a placeholder over an empty field, so they type from scratch and
    // leaving it blank keeps the current name.
    var text by remember { mutableStateOf(if (nameIsCustom) current else "") }
    // Focus the field and pop the keyboard as soon as the dialog appears.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isChannel) "Rename channel" else "Rename contact") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Name") },
                placeholder = { Text(current) },
                modifier = Modifier.focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isBlank()) onDismiss() else onConfirm(text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
