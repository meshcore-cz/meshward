package cz.meshcore.meshward.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cz.meshcore.meshward.AdvertisedNode
import cz.meshcore.meshward.ChatListItem
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.MeshCoreUri
import cz.meshcore.meshward.data.ChannelKind
import cz.meshcore.meshward.data.NotifMode
import cz.meshcore.meshward.nameFromPubKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    vm: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenManageIdentities: () -> Unit,
) {
    val items by vm.chatItems.collectAsState()
    val discovered by vm.discoveredContacts.collectAsState()
    val joined by vm.channels.collectAsState()
    val publicJoined = remember(joined) { joined.any { it.kind == ChannelKind.PUBLIC } }
    val myNode by vm.nodeId.collectAsState()
    val myName by vm.myName.collectAsState()
    val myPubKeyHex by vm.myPubKeyHex.collectAsState()
    var chooser by remember { mutableStateOf(false) }
    var identitySheet by remember { mutableStateOf(false) }
    var showAddIdentity by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // Ticks once a second so the rows' relative timestamps ("now", "Ns ago", "Nm ago") stay current.
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // Tapping your own avatar opens the identity chooser (switch profile, add, manage).
                    IconButton(onClick = { identitySheet = true }) {
                        Avatar(
                            seed = myNode.toHex(),
                            label = myName.ifBlank { "Me" },
                            identiconKey = myPubKeyHex,
                            size = 32,
                        )
                    }
                },
                title = {
                    if (searching) {
                        SearchField(query, { query = it }, "Search chats & contacts")
                    } else {
                        Text("Meshward")
                    }
                },
                actions = {
                    ConnectionStatusButton(vm)
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searching) "Close search" else "Search",
                        )
                    }
                    OverflowMenu(onOpenSettings = onOpenSettings, onOpenAbout = onOpenAbout)
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { chooser = true }) {
                Icon(Icons.Default.Add, contentDescription = "New chat or channel")
            }
        },
        // Root scaffold reserves the bottom-nav space; TopAppBar handles the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        when {
            searching && query.isBlank() ->
                SearchHint("Start typing to search chats & contacts…", Modifier.fillMaxSize().padding(padding))
            searching ->
                SearchResults(
                    chats = items,
                    discovered = discovered,
                    query = query,
                    myNodeHex = myNode.toHex(),
                    onOpenConversation = onOpenConversation,
                    onOpenProfile = onOpenProfile,
                    modifier = Modifier.padding(padding),
                )
            items.isEmpty() ->
                EmptyState(Modifier.fillMaxSize().padding(padding), searching = false)
            else -> {
                val listState = rememberLazyListState()
                // When a conversation jumps to the top (new message → re-sorted), the keyed list stays
                // anchored to the previous first row, leaving the freshly-promoted one just above the
                // viewport. If the user is already at/near the top, follow it up so the new message is
                // seen; if they've scrolled down to read older chats, leave them where they are.
                val topPeer = items.firstOrNull()?.peerHex
                LaunchedEffect(topPeer) {
                    if (topPeer != null && listState.firstVisibleItemIndex <= 1) {
                        listState.animateScrollToItem(0)
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(padding)) {
                    items(items, key = { it.peerHex }) { item ->
                        ChatRow(
                            item,
                            isSelf = item.peerHex == myNode.toHex(),
                            nowMs = nowMs,
                            onClick = { onOpenConversation(item.peerHex) },
                            onAvatarClick = { onOpenProfile(item.peerHex) },
                        )
                    }
                }
            }
        }
    }

    if (identitySheet) {
        IdentitySheet(
            vm = vm,
            onAddIdentity = { showAddIdentity = true },
            onManage = onOpenManageIdentities,
            onMyProfile = { onOpenProfile(myNode.toHex()) },
            onDismiss = { identitySheet = false },
        )
    }
    if (showAddIdentity) {
        AddIdentityDialog(vm = vm, onDismiss = { showAddIdentity = false })
    }
    if (chooser) {
        AddChooserSheet(
            vm = vm,
            onNewChat = { chooser = false; showNewChat = true },
            onJoinChannel = { chooser = false; showJoin = true },
            onDismiss = { chooser = false },
        )
    }
    if (showNewChat) {
        NewChatSheet(
            vm = vm,
            onStartChat = { peerHex ->
                showNewChat = false
                onOpenConversation(peerHex)
            },
            onDismiss = { showNewChat = false },
        )
    }
    if (showJoin) {
        JoinChannelSheet(
            vm = vm,
            showPublic = !publicJoined,
            onJoined = { peerHex ->
                showJoin = false
                onOpenConversation(peerHex)
            },
            onDismiss = { showJoin = false },
        )
    }
}

/** A merged-list row: a direct conversation or a channel (selected by [ChatListItem.isChannel]). */
@Composable
internal fun ChatRow(
    item: ChatListItem,
    isSelf: Boolean,
    onClick: () -> Unit,
    onAvatarClick: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isSelf) {
            NoteToSelfAvatar(onClick = onAvatarClick)
        } else {
            Avatar(
                seed = item.peerHex,
                label = item.title,
                identiconKey = if (item.isChannel) null else item.pubKeyHex,
                onClick = onAvatarClick,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (item.isChannel) channelLabel(item.title, item.channelKind) else item.title,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.isChannel) {
                    Icon(
                        Icons.Default.Public,
                        contentDescription = "Channel",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (item.notifMode) {
                    NotifMode.NONE -> Icon(
                        Icons.Default.NotificationsOff,
                        contentDescription = "Muted",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NotifMode.MENTIONS -> Icon(
                        Icons.Default.AlternateEmail,
                        contentDescription = "Mentions only",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NotifMode.ALL -> {}
                }
            }
            if (!item.isChannel && !isSelf) {
                val pub = formatPubKey(item.pubKeyHex)
                if (pub.isNotEmpty()) {
                    Text(
                        pub,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            val subtitle = when {
                item.lastText.isBlank() -> "No messages yet"
                item.isChannel && item.lastSender.isNotBlank() -> "${item.lastSender}: ${item.lastText}"
                else -> item.lastText
            }
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (item.lastTimestampMs > 0) {
                Text(
                    formatRelativeAge(item.lastTimestampMs, nowMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.unread > 0) {
                Spacer(Modifier.size(4.dp))
                androidx.compose.material3.Badge { Text("${item.unread}") }
            }
        }
    }
}

/** The "+" chooser: start a chat or join a channel. A QR scan (top-right) handles either kind of
 *  `meshcore://` link (contact or channel) and routes it straight to the opened conversation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddChooserSheet(
    vm: ChatViewModel,
    onNewChat: () -> Unit,
    onJoinChannel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        // handleSharedUri joins/adds and triggers navigation to the conversation; just close the sheet.
        if (vm.handleSharedUri(contents)) onDismiss()
    }
    fun launchScan() = scan.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setBeepEnabled(false)
            .setCaptureActivity(cz.meshcore.meshward.PortraitCaptureActivity::class.java)
            .setPrompt("Scan a MeshCore contact or channel QR"),
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Add", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { launchScan() }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR code")
                }
            }
            ChooserRow(Icons.Default.PersonAdd, "New chat", "Add a contact by key, QR, or nearby node", onNewChat)
            HorizontalDivider()
            ChooserRow(Icons.Default.Public, "Join channel", "Public, named, or secret group channel", onJoinChannel)
        }
    }
}

@Composable
private fun ChooserRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, searching: Boolean) {
    Column(
        modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Chat,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        if (searching) {
            Text("No matching chats", style = MaterialTheme.typography.titleMedium)
        } else {
            Text("No chats yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap + to start a chat or join a channel.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * New chat: add a contact by pasting their public key (+ optional name), scanning their QR (top-right
 * or the field's trailing icon), or tapping one of the nearby Sidepath nodes at the bottom. Each path
 * adds the contact and opens the conversation via [onStartChat].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(vm: ChatViewModel, onStartChat: (String) -> Unit, onDismiss: () -> Unit) {
    val nodes by vm.advertisedNodes.collectAsState()
    var pubKey by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    // Scan a contact QR → pre-fill the key + name fields (user reviews, then taps Start chat).
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        MeshCoreUri.parseContact(contents)?.let { c ->
            pubKey = c.publicKeyHex
            name = c.name
        }
    }
    fun launchScan() = scan.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setBeepEnabled(false)
            .setCaptureActivity(cz.meshcore.meshward.PortraitCaptureActivity::class.java)
            .setPrompt("Scan a contact QR"),
    )
    val cleanKey = pubKey.trim().lowercase()
    val validKey = cleanKey.length == 64 && cleanKey.all { it in "0123456789abcdef" }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("New chat", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { launchScan() }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan contact QR")
                }
            }
            Text(
                "Add a contact by public key, scan their QR, or pick a nearby node below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = pubKey,
                onValueChange = { pubKey = it.trim() },
                label = { Text("Public key (64 hex)") },
                singleLine = true,
                isError = pubKey.isNotEmpty() && !validKey,
                trailingIcon = {
                    IconButton(onClick = { launchScan() }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                    }
                },
                supportingText = {
                    if (pubKey.isNotEmpty() && !validKey) Text("Must be exactly 64 hexadecimal characters.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { vm.addContactManually(pubKey, name)?.let(onStartChat) },
                enabled = validKey,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start chat") }

            if (nodes.isNotEmpty()) {
                HorizontalDivider()
                Text("Nearby Sidepath nodes", style = MaterialTheme.typography.labelLarge)
                nodes.forEach { node ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { vm.startChat(node); onStartChat(node.nodeHex) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val label = nameFromPubKey(node.pubKeyHex).ifBlank { node.nodeHex.take(16) }
                        Avatar(seed = node.nodeHex, label = label, identiconKey = node.pubKeyHex)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(label, fontWeight = FontWeight.Medium)
                            Text(
                                node.nodeHex.take(16),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
