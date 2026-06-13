package cz.meshcore.meshward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.QrCode2
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import cz.meshcore.meshward.data.ChannelKind

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
) {
    val profile by remember(peerHex) { vm.profileFor(peerHex) }.collectAsState()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

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

            // Mark bridged MeshCore identities — they're full contacts but not directly DM-reachable.
            if (profile.isMeshCore) {
                Spacer(Modifier.size(6.dp))
                MeshCoreProfileBadge()
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

            // Information block.
            if (profile.isChannel) {
                ChannelInfo(profile)
            } else {
                UserInfo(profile)
                // Neighbors this node directly connects to, learned from its signed ANNOUNCE.
                if (profile.neighborHexes.isNotEmpty()) {
                    Spacer(Modifier.size(20.dp))
                    NeighborsSection(vm, profile.neighborHexes, onOpenProfile)
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Neighbors (${neighborHexes.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        neighborHexes.forEach { hex ->
            val label = vm.nameForHex(hex).ifBlank { "node ${hex.take(8)}" }
            Row(
                Modifier.fillMaxWidth().clickable { onOpenProfile(hex) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(seed = hex, label = label, size = 32)
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
    }
}

@Composable
private fun UserInfo(p: ProfileInfo) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        InfoRow("Node ID", p.nodeHex)
        if (p.pubKeyHex.isNotBlank()) InfoRow("Public key", p.pubKeyHex)
        if (p.platform.isNotBlank()) InfoRow("Platform", p.platform)
        InfoRow("Status", if (p.online) "Online (announcing)" else "Not currently visible")
        InfoRow("In contacts", if (p.isContact) "Yes" else "No")
        // Extra section for bridged MeshCore nodes (from the last ADVERT we heard).
        if (p.isMeshCore) {
            InfoRow("Network", "MeshCore (bridged — not directly reachable)")
            if (p.nodeType != 0) InfoRow("Node type", nodeTypeLabel(p.nodeType))
            InfoRow("Signature", if (p.sigVerified) "verified" else "unverified")
            if (p.hasGps) InfoRow("Location", "%.6f, %.6f".format(p.lat, p.lon))
            if (p.nodeAdvertisedMs > 0) InfoRow("Advertised", formatRelative(p.nodeAdvertisedMs))
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
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Start empty with the current name only as a placeholder — leaving it blank keeps the
    // current name (no need to clear the field first just to rename).
    var text by remember { mutableStateOf("") }
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
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isBlank()) onDismiss() else onConfirm(text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
