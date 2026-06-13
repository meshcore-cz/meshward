package cz.meshcore.meshward.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.AdvertisedNode
import cz.meshcore.meshward.ChatListItem
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.data.ChannelKind
import cz.meshcore.meshward.nameFromPubKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    vm: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val items by vm.chatItems.collectAsState()
    val discovered by vm.discoveredContacts.collectAsState()
    val joined by vm.channels.collectAsState()
    val publicJoined = remember(joined) { joined.any { it.kind == ChannelKind.PUBLIC } }
    val myNode by vm.nodeId.collectAsState()
    val myName by vm.myName.collectAsState()
    val myPubKeyHex by vm.myPubKeyHex.collectAsState()
    var chooser by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // Tapping your own avatar opens your profile (Settings is in the ⋮ menu).
                    IconButton(onClick = { onOpenProfile(myNode.toHex()) }) {
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
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(items, key = { it.peerHex }) { item ->
                    ChatRow(
                        item,
                        isSelf = item.peerHex == myNode.toHex(),
                        onClick = { onOpenConversation(item.peerHex) },
                        onAvatarClick = { onOpenProfile(item.peerHex) },
                    )
                }
            }
        }
    }

    if (chooser) {
        AddChooserSheet(
            onNewChat = { chooser = false; showNewChat = true },
            onJoinChannel = { chooser = false; showJoin = true },
            onDismiss = { chooser = false },
        )
    }
    if (showNewChat) {
        NewChatSheet(
            vm = vm,
            onPick = { node ->
                vm.startChat(node)
                showNewChat = false
                onOpenConversation(node.nodeHex)
            },
            onDismiss = { showNewChat = false },
        )
    }
    if (showJoin) {
        JoinChannelSheet(
            vm = vm,
            showPublic = !publicJoined,
            onJoined = { showJoin = false },
            onDismiss = { showJoin = false },
        )
    }
}

/** A merged-list row: a direct conversation or a channel (selected by [ChatListItem.isChannel]). */
@Composable
internal fun ChatRow(item: ChatListItem, isSelf: Boolean, onClick: () -> Unit, onAvatarClick: () -> Unit) {
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
                    formatRelative(item.lastTimestampMs),
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

/** The "+" chooser: start a contact chat or join a channel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddChooserSheet(onNewChat: () -> Unit, onJoinChannel: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text("Add", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
            ChooserRow(Icons.Default.PersonAdd, "New contact chat", "Message a nearby node directly", onNewChat)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(vm: ChatViewModel, onPick: (AdvertisedNode) -> Unit, onDismiss: () -> Unit) {
    val nodes by vm.advertisedNodes.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "New chat",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            if (nodes.isEmpty()) {
                Text(
                    "No nodes discovered yet. Make sure another Sidepath node is nearby and advertising.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                nodes.forEach { node ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(node) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
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
