package cz.meshcore.meshward.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.NOTE_TO_SELF
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cz.meshcore.meshward.MeshCoreUri
import cz.meshcore.meshward.ProfileInfo
import cz.meshcore.meshward.data.ChannelKind
import cz.meshcore.meshward.data.Message
import cz.meshcore.meshward.data.MsgStatus
import cz.meshcore.meshward.data.channelPskHexOf
import cz.meshcore.meshward.data.isChannelPeer

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(
    vm: ChatViewModel,
    peerHex: String,
    onBack: (() -> Unit)?,
    onOpenProfile: ((String) -> Unit)? = null,
) {
    val isChannel = isChannelPeer(peerHex)
    val isSelf = peerHex == vm.myNodeHex()
    var searching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Paged history (newest-first); searching swaps in a filtered pager. The list is reverse-laid-out
    // so index 0 (newest) sits at the bottom and older pages load as the user scrolls up.
    val messageFlow = remember(peerHex, searching, searchQuery) {
        if (searching && searchQuery.isNotBlank()) vm.searchMessages(peerHex, searchQuery)
        else vm.messages(peerHex)
    }
    val lazyMessages = messageFlow.collectAsLazyPagingItems()
    val profile by remember(peerHex) { vm.profileFor(peerHex) }.collectAsState()
    var draft by remember { mutableStateOf(TextFieldValue("")) }
    var detailsFor by remember { mutableStateOf<Message?>(null) }
    // Message the long-press actions sheet is open for, and (separately) one whose reaction we're
    // picking a non-quick emoji for via the full picker.
    var actionsFor by remember { mutableStateOf<Message?>(null) }
    var emojiReactFor by remember { mutableStateOf<Message?>(null) }
    // Message whose reaction viewer (who reacted) is open, and the emoji tab to preselect.
    var reactionsFor by remember { mutableStateOf<Message?>(null) }
    var reactionsEmoji by remember { mutableStateOf<String?>(null) }
    // Scroll position is kept per-conversation in the VM, so reopening a room lands exactly where it
    // was left (and saves the latest position every time you leave). Seeded from the VM; absent =
    // bottom (index 0, since the list is reverse-laid-out). Saved on dispose below.
    val listState = remember(peerHex) {
        val saved = vm.scrollPositionFor(peerHex)
        LazyListState(saved?.first ?: 0, saved?.second ?: 0)
    }
    DisposableEffect(peerHex, listState) {
        onDispose {
            vm.saveScrollPosition(peerHex, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showEmoji by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmDeleteChat by remember { mutableStateOf(false) }
    // A tapped @mention / declared sender that doesn't match any saved contact.
    var unknownMention by remember { mutableStateOf<String?>(null) }

    // Persisted unread-incoming count for this conversation (drives the jump-to-newest badge and the
    // Chats-list badge). Messages are marked read only while parked at the bottom (see below), so
    // ones that arrive while scrolled up stay unread until the user actually scrolls down to them.
    val unreadCount by remember(peerHex) { vm.unreadCountFor(peerHex) }.collectAsState()

    // Whether the remote peer is currently typing (DMs only).
    val typingPeers by vm.typingPeers.collectAsState()
    val peerTyping = !isChannel && peerHex in typingPeers

    // Persisted echoes of our own flooded messages heard back, keyed by message id (survive restart).
    val floodRepeats by vm.echoes.collectAsState()
    // Per-DM delivery progress (attempts/acked/failed), keyed by message id.
    val dmDeliveries by vm.dmDeliveries.collectAsState()
    // Saved contacts indexed by name — resolves a bridged declared sender / @mention to a profile.
    val contactIndex by vm.contactNameIndex.collectAsState()
    // Emoji reactions, grouped by target message id.
    val reactionsByMsg by vm.reactions.collectAsState()
    // This conversation's notification mode (drives the three-dot Notifications submenu).
    val notifModes by vm.notifModes.collectAsState()
    val notifMode = notifModes[peerHex] ?: cz.meshcore.meshward.data.NotifMode.ALL
    // The conversation's display name, reused by the search field/hint to say where we're searching.
    val convoName = if (isSelf) NOTE_TO_SELF else profile.name
    // The network this channel is currently bridged on — shown on the MeshCore chips in place of the
    // generic "MeshCore" label. Blank when no network is active (falls back to "MeshCore").
    val activeNetwork by vm.activeNetwork.collectAsState()
    val myHex = vm.myNodeHex()

    // Outgoing typing hints are STARTED only from a genuine user edit (see the input's
    // onChange below) — never merely from opening the conversation or a programmatic draft
    // change. This idle timer only STOPS: ~5s after the last text change with no new
    // keystroke we consider the user stopped. Leaving the screen also stops it.
    LaunchedEffect(draft.text, peerHex, isChannel) {
        if (isChannel || isSelf || draft.text.isBlank()) return@LaunchedEffect
        delay(5_000)
        vm.stopTyping(peerHex)
    }
    DisposableEffect(peerHex) { onDispose { vm.stopTyping(peerHex) } }

    // Channel participants (name → node id), sourced from the DB (distinct senders) rather than a
    // full in-memory message scan; used to resolve a tapped @mention and power the @-autocomplete.
    val mentionTargets by remember(peerHex) { vm.channelSenders(peerHex) }.collectAsState()
    // Resolve a name to a profile: a saved contact first ("our database"), then a real (native)
    // channel author seen in this room. Unresolved names show a "can't find contact" notice.
    fun resolveName(name: String): String? = contactIndex[name.trim().lowercase()] ?: mentionTargets[name]
    val onMentionClick: (String) -> Unit = { name ->
        val hex = resolveName(name)
        if (hex != null) onOpenProfile?.invoke(hex) else unknownMention = name
    }

    val mentionQuery = if (isChannel) mentionQueryOf(draft.text) else null
    val suggestions = if (mentionQuery != null) {
        mentionTargets.keys.filter { it.contains(mentionQuery, ignoreCase = true) }.sorted().take(6)
    } else emptyList()

    val shareUri = when {
        isChannel && profile.pskHex.isNotBlank() -> MeshCoreUri.channel(profile.name, profile.pskHex)
        !isChannel && profile.pubKeyHex.isNotBlank() -> MeshCoreUri.contact(profile.name, profile.pubKeyHex)
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (searching) {
                        // While searching, anchor the bar with the conversation's avatar so it's clear
                        // which chat is being searched (the back arrow's job is taken by the ✕ action).
                        Box(Modifier.padding(start = 12.dp, end = 4.dp)) {
                            if (isSelf) {
                                NoteToSelfAvatar(size = 32)
                            } else {
                                Avatar(
                                    seed = peerHex,
                                    label = profile.name,
                                    size = 32,
                                    identiconKey = if (!isChannel) profile.pubKeyHex else null,
                                )
                            }
                        }
                    } else if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    if (searching) {
                        SearchField(searchQuery, { searchQuery = it }, "Search in $convoName")
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (onOpenProfile != null) Modifier.clickable { onOpenProfile(peerHex) } else Modifier,
                        ) {
                            if (isSelf) {
                                NoteToSelfAvatar(size = 36)
                            } else {
                                Avatar(
                                    seed = peerHex,
                                    label = profile.name,
                                    size = 36,
                                    identiconKey = if (!isChannel) profile.pubKeyHex else null,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isSelf) NOTE_TO_SELF else profile.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    when (notifMode) {
                                        cz.meshcore.meshward.data.NotifMode.NONE -> {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Default.NotificationsOff,
                                                contentDescription = "Muted",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        cz.meshcore.meshward.data.NotifMode.MENTIONS -> {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Default.AlternateEmail,
                                                contentDescription = "Mentions only",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        cz.meshcore.meshward.data.NotifMode.ALL -> {}
                                    }
                                }
                                val showKey = !isSelf && !isChannel && !peerTyping && profile.pubKeyHex.isNotBlank()
                                val subtitle = when {
                                    isSelf -> "Only you can see these"
                                    peerTyping -> "typing…"
                                    isChannel && profile.channelKind == ChannelKind.SECRET -> "Channel · shared-key encrypted"
                                    isChannel -> "Channel · anyone can read this"
                                    profile.pubKeyHex.isNotBlank() -> formatPubKey(profile.pubKeyHex)
                                    else -> "End-to-end encrypted"
                                }
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = if (showKey) FontFamily.Monospace else FontFamily.Default,
                                    color = if (peerTyping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                actions = {
                    // While searching, the title is the search field — just offer a close action.
                    // Otherwise the status chip (which can be wide) sits next to the overflow menu,
                    // and search moves into the menu to keep the bar from overflowing.
                    if (searching) {
                        IconButton(onClick = { searching = false; searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    } else {
                    ConnectionStatusButton(vm)
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Search messages") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            onClick = { menuOpen = false; searching = true },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (isChannel) "Channel info" else "Contact info") },
                            leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                            onClick = { menuOpen = false; onOpenProfile?.invoke(peerHex) },
                        )
                        if (shareUri != null) {
                            DropdownMenuItem(
                                text = { Text(if (isChannel) "Share channel" else "Share contact") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = { menuOpen = false; showShare = true },
                            )
                        }
                        HorizontalDivider()
                        // Per-conversation notifications: All / Mentions only / None. The active mode
                        // is check-marked; choosing one persists it and updates the chat-list bell.
                        Text(
                            "Notifications",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                        )
                        NotifModeItem(
                            label = "All messages",
                            icon = Icons.Default.Notifications,
                            selected = notifMode == cz.meshcore.meshward.data.NotifMode.ALL,
                        ) { menuOpen = false; vm.setNotifMode(peerHex, cz.meshcore.meshward.data.NotifMode.ALL) }
                        // "Mentions only" is meaningful only for channels — a DM is always addressed
                        // to you, so it would behave identically to "All".
                        if (isChannel) {
                            NotifModeItem(
                                label = "Mentions only",
                                icon = Icons.Default.AlternateEmail,
                                selected = notifMode == cz.meshcore.meshward.data.NotifMode.MENTIONS,
                            ) { menuOpen = false; vm.setNotifMode(peerHex, cz.meshcore.meshward.data.NotifMode.MENTIONS) }
                        }
                        NotifModeItem(
                            label = "None (muted)",
                            icon = Icons.Default.NotificationsOff,
                            selected = notifMode == cz.meshcore.meshward.data.NotifMode.NONE,
                        ) { menuOpen = false; vm.setNotifMode(peerHex, cz.meshcore.meshward.data.NotifMode.NONE) }
                        HorizontalDivider()
                        if (isChannel) {
                            DropdownMenuItem(
                                text = { Text("Leave channel") },
                                leadingIcon = { Icon(Icons.Default.Logout, contentDescription = null) },
                                onClick = { menuOpen = false; confirmLeave = true },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Delete chat") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuOpen = false; confirmDeleteChat = true },
                            )
                        }
                    }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                if (suggestions.isNotEmpty()) {
                    MentionSuggestions(suggestions) { name ->
                        val t = insertMention(draft.text, name)
                        draft = TextFieldValue(t, TextRange(t.length))
                    }
                }
                MessageInput(
                    value = draft,
                    onChange = { newValue ->
                        // A real user edit in the input box: only now do we emit a "typing"
                        // hint (DMs only), and only when the text actually changed to non-blank.
                        val textChanged = newValue.text != draft.text
                        draft = newValue
                        if (!isChannel && !isSelf && textChanged) {
                            if (newValue.text.isNotBlank()) vm.onUserTyping(peerHex)
                            else vm.stopTyping(peerHex)
                        }
                    },
                    fullScreen = onBack != null,
                    onEmoji = { showEmoji = true },
                    onSend = {
                        val text = draft.text
                        if (text.isNotBlank()) {
                            if (isChannel) vm.sendChannelMessage(channelPskHexOf(peerHex), text)
                            else vm.sendChat(peerHex, text)
                            draft = TextFieldValue("")
                            vm.stopTyping(peerHex) // a real message supersedes the typing hint
                        }
                    },
                )
            }
        },
        // TopAppBar + composer handle their own system-bar/ime insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (searching && searchQuery.isBlank()) {
            SearchHint("Start typing to search messages in $convoName…", Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        // reverseLayout pins the newest message (paged index 0) to the bottom and loads older pages
        // as the user scrolls up. The list state is rememberSaveable, so the scroll position is kept
        // across navigation (open a profile and come back → same spot). We only auto-scroll to the
        // bottom when a *new* message arrives AND the user is already parked there — never on open or
        // return, and never while reading history.
        // Stick to the bottom when a genuinely NEW message arrives while already parked there.
        // Crucially this must not run on the initial load, or it would override the restored scroll
        // position — so we record the first newest id without scrolling and only act on later ones.
        val newestId = if (lazyMessages.itemCount > 0) lazyMessages.peek(0)?.id else null
        var lastNewestId by remember(peerHex) { mutableStateOf<String?>(null) }
        LaunchedEffect(newestId) {
            if (newestId != null) {
                // Follow new messages only when already parked at the bottom. Index <= 2 covers the
                // row shift from inserting our own sent message at index 0.
                if (lastNewestId != null && lastNewestId != newestId && !searching &&
                    listState.firstVisibleItemIndex <= 2
                ) {
                    listState.animateScrollToItem(0)
                }
                lastNewestId = newestId
            }
        }
        // Show a "jump to newest" button once the newest message is scrolled out of view.
        val showScrollDown by remember {
            derivedStateOf { listState.firstVisibleItemIndex > 1 }
        }
        // Mark messages read only while parked at the bottom (newest visible). Re-evaluated whenever
        // the bottom state or the newest message changes, so a reply arriving while you're at the
        // bottom is marked read at once — and ones arriving while scrolled up stay unread (and show
        // on the badge) until you scroll down to them.
        LaunchedEffect(peerHex) {
            snapshotFlow {
                (listState.firstVisibleItemIndex <= 1) to
                    (if (lazyMessages.itemCount > 0) lazyMessages.peek(0)?.id else null)
            }.collect { (bottom, _) -> if (bottom) vm.markRead(peerHex) }
        }
        Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            // Minimal breathing room at the top and bottom of the message list.
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // reverseLayout draws the first DSL item at the BOTTOM, so order is:
            // typing bubble (bottom) → newest…oldest messages → header panel (top).
            // Animated "…" bubble where the peer's next message will appear, while they type.
            if (peerTyping && !searching) {
                item(key = "__typing__") { TypingBubble() }
            }
            items(
                count = lazyMessages.itemCount,
                key = lazyMessages.itemKey { it.id },
            ) { i ->
                val msg = lazyMessages[i] ?: return@items
                val sender = if (isChannel) msg.senderName.ifBlank { vm.nameForHex(msg.senderHex) }
                else vm.nameForHex(msg.senderHex)
                // Resolve a channel author to a saved/real identity. A bridged ("virtual") author
                // only links when its declared name matches a contact; otherwise it gets a distinct
                // hashed color and tapping it explains we can't link it.
                val matchedHex = when {
                    !isChannel -> null
                    msg.viaMeshCore -> resolveName(msg.senderName)
                    else -> msg.senderHex.takeIf { it.isNotBlank() }
                }
                val virtual = isChannel && msg.viaMeshCore && matchedHex == null
                val senderColor = if (virtual) virtualNameColor(sender) else MaterialTheme.colorScheme.primary
                val onSenderClick: (() -> Unit)? = if (isChannel && msg.incoming) {
                    {
                        if (matchedHex != null) onOpenProfile?.invoke(matchedHex)
                        else unknownMention = sender
                    }
                } else null
                // Newest-first paging: i+1 is the chronologically OLDER neighbor, i-1 the NEWER one.
                // peek doesn't trigger page loads and throws out of bounds, so guard the list ends;
                // null = group boundary (or a neighbor page not yet loaded).
                val older = if (i + 1 < lazyMessages.itemCount) lazyMessages.peek(i + 1) else null
                val newer = if (i > 0) lazyMessages.peek(i - 1) else null
                // Sender name on the FIRST (oldest) message of a consecutive same-sender group.
                val showSenderHeader = when {
                    !isChannel || !msg.incoming -> false
                    searching -> true
                    older == null || differentDay(older.timestampMs, msg.timestampMs) -> true
                    older.id.startsWith("mcpath:") -> true
                    !older.incoming -> true
                    msg.timestampMs - older.timestampMs > 5 * 60_000L -> true
                    msg.senderHex.isNotBlank() && older.senderHex.isNotBlank() ->
                        msg.senderHex != older.senderHex
                    else -> msg.senderName.trim() != older.senderName.trim()
                }
                // Avatar on the LAST (newest) message of a group — newer neighbor differs or absent.
                val showAvatar = when {
                    !isChannel || !msg.incoming -> false
                    newer == null || differentDay(msg.timestampMs, newer.timestampMs) -> true
                    newer.id.startsWith("mcpath:") -> true
                    !newer.incoming -> true
                    newer.timestampMs - msg.timestampMs > 5 * 60_000L -> true
                    msg.senderHex.isNotBlank() && newer.senderHex.isNotBlank() ->
                        msg.senderHex != newer.senderHex
                    else -> msg.senderName.trim() != newer.senderName.trim()
                }
                // Collapsed = continuation of same-sender group (no date separator above).
                val isCollapsed = isChannel && msg.incoming && !showSenderHeader &&
                    !msg.id.startsWith("mcpath:") && !searching
                // Date separator above the chronological first message of a day (older neighbor is a
                // different day or absent). Item internals aren't flipped, so it sits above the bubble.
                val showDateSeparator = !searching &&
                    (older == null || differentDay(older.timestampMs, msg.timestampMs))
                Column(Modifier.padding(top = if (isCollapsed) 0.dp else 4.dp)) {
                    if (showDateSeparator) DateSeparator(dateLabel(msg.timestampMs))
                    val repeatCount = floodRepeats[msg.id]?.size ?: 0
                    MessageBubble(
                        msg, isChannel, sender, senderColor, onSenderClick,
                        repeatCount, dmDeliveries[msg.id], onMentionClick,
                        reactions = reactionsByMsg[msg.id].orEmpty(),
                        myHex = myHex,
                        networkCode = activeNetwork?.code.orEmpty(),
                        showSenderHeader = showSenderHeader,
                        showAvatar = showAvatar,
                        senderIdenticonKey = matchedHex,
                        mentionResolved = { name -> resolveName(name) != null },
                        onChipClick = { emoji -> reactionsEmoji = emoji; reactionsFor = msg },
                        onLongPress = { actionsFor = msg },
                        onClick = { detailsFor = msg },
                    )
                }
            }
            // A header panel with a big avatar + basic details; also fills the empty space of a
            // brand-new conversation. Last DSL item → renders at the top, above the oldest message.
            // Suppressed during the initial page load: while messages are streaming in it would be
            // the only item and reverseLayout's key-anchoring would latch onto it and yank the list
            // to the top. Shown once messages are loaded, or when the conversation is truly empty.
            val refreshing = lazyMessages.loadState.refresh is LoadState.Loading
            if (!searching && (lazyMessages.itemCount > 0 || !refreshing)) {
                item(key = "__header__") {
                    ConversationHeaderPanel(profile, isChannel, isSelf, onClick = { onOpenProfile?.invoke(peerHex) })
                }
            }
        }
        // Signal-style jump-to-newest button, bottom-end above the composer.
        androidx.compose.animation.AnimatedVisibility(
            visible = showScrollDown,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
        ) {
            androidx.compose.material3.BadgedBox(
                badge = {
                    if (unreadCount > 0) {
                        androidx.compose.material3.Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) { Text("$unreadCount") }
                    }
                },
            ) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = "Scroll to newest",
                    )
                }
            }
        }
        }
    }

    detailsFor?.let { msg ->
        MessageDetailsSheet(msg, vm, onOpenProfile = onOpenProfile) { detailsFor = null }
    }
    actionsFor?.let { msg ->
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        MessageActionsSheet(
            msg = msg,
            onReact = { emoji ->
                vm.toggleReaction(msg, emoji)
                actionsFor = null
                // A new reaction overhangs the bubble bottom — scroll the newest (index 0) into view.
                if (lazyMessages.peek(0)?.id == msg.id) scope.launch { listState.animateScrollToItem(0) }
            },
            onMoreEmoji = { actionsFor = null; emojiReactFor = msg },
            onCopy = {
                clipboard.setText(AnnotatedString(msg.text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                actionsFor = null
            },
            onInfo = { actionsFor = null; detailsFor = msg },
            onDismiss = { actionsFor = null },
        )
    }
    emojiReactFor?.let { msg ->
        EmojiPickerSheet(
            onPick = { emoji ->
                vm.toggleReaction(msg, emoji)
                emojiReactFor = null
                if (lazyMessages.peek(0)?.id == msg.id) scope.launch { listState.animateScrollToItem(0) }
            },
            onDismiss = { emojiReactFor = null },
        )
    }
    reactionsFor?.let { msg ->
        ReactionsSheet(
            msg = msg,
            vm = vm,
            myHex = myHex,
            initialEmoji = reactionsEmoji,
            onDismiss = { reactionsFor = null; reactionsEmoji = null },
        )
    }
    if (showShare && shareUri != null) {
        ShareQrSheet(
            title = if (isChannel) "Share ${channelLabel(profile.name, profile.channelKind)}" else "Share ${profile.name}",
            subtitle = if (isChannel) "Scan to join this channel" else "Scan to add this contact",
            uri = shareUri,
            onDismiss = { showShare = false },
        )
    }
    if (showEmoji) {
        EmojiPickerSheet(
            onPick = { emoji ->
                // Append the emoji, drop the cursor at the end, and close the picker.
                val t = draft.text + emoji
                draft = TextFieldValue(t, TextRange(t.length))
                showEmoji = false
            },
            onDismiss = { showEmoji = false },
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave channel?") },
            text = { Text("You'll stop receiving messages on ${channelLabel(profile.name, profile.channelKind)}. You can rejoin later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.leaveChannel(profile.pskHex)
                    onBack?.invoke()
                }) { Text("Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
    if (confirmDeleteChat) {
        AlertDialog(
            onDismissRequest = { confirmDeleteChat = false },
            title = { Text("Delete chat?") },
            text = { Text("This removes ${profile.name} from your contacts and deletes this conversation on this device. You can re-add them from Explore.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteChat = false
                    vm.deleteChat(peerHex)
                    onBack?.invoke()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteChat = false }) { Text("Cancel") } },
        )
    }
    unknownMention?.let { name ->
        AlertDialog(
            onDismissRequest = { unknownMention = null },
            title = { Text("Unknown contact") },
            text = { Text("We can't find “$name” in your contacts. Bridged MeshCore names carry no public key, so they can't be linked to a profile.") },
            confirmButton = { TextButton(onClick = { unknownMention = null }) { Text("OK") } },
        )
    }
}

/** An incoming-style bubble with three pulsing dots, shown while the peer is typing. */
@Composable
private fun TypingBubble() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(16.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val transition = rememberInfiniteTransition(label = "typing")
                val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
                repeat(3) { i ->
                    val alpha by transition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 600, delayMillis = i * 180),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dot$i",
                    )
                    Box(
                        Modifier.size(8.dp)
                            .clip(CircleShape)
                            .background(dotColor.copy(alpha = alpha)),
                    )
                }
            }
        }
    }
}

/** Quick-reaction emojis offered in the long-press bar (Signal-style); "+" opens the full picker. */
private val quickReactions = listOf("❤️", "👍", "👎", "😂", "😮", "😢")

/**
 * The long-press message menu: a row of quick emoji reactions over a list of actions. Reply,
 * Forward, Select and Delete are placeholders (disabled) for now; Copy and Info are active.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    msg: Message,
    onReact: (String) -> Unit,
    onMoreEmoji: () -> Unit,
    onCopy: () -> Unit,
    onInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                quickReactions.forEach { emoji ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(40.dp).clickable { onReact(emoji) },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(emoji, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(40.dp).clickable(onClick = onMoreEmoji),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = "More emoji")
                    }
                }
            }
            HorizontalDivider()
            ActionRow(Icons.AutoMirrored.Filled.Reply, "Reply", enabled = false) {}
            ActionRow(Icons.AutoMirrored.Filled.Forward, "Forward", enabled = false) {}
            ActionRow(Icons.Default.ContentCopy, "Copy", enabled = msg.text.isNotBlank(), onClick = onCopy)
            ActionRow(Icons.Default.Done, "Select", enabled = false) {}
            ActionRow(Icons.Default.Info, "Info", enabled = true, onClick = onInfo)
            ActionRow(Icons.Default.Delete, "Delete", enabled = false, destructive = true) {}
        }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, color = color)
    }
}

/**
 * The reaction viewer (opened by tapping a reaction pill): a tab row (All + per-emoji counts) over
 * the list of people who reacted. Your own reaction shows "Tap to remove" and removing it here
 * toggles it off. Closes itself once the last reaction is gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionsSheet(
    msg: Message,
    vm: ChatViewModel,
    myHex: String,
    initialEmoji: String?,
    onDismiss: () -> Unit,
) {
    val reactionsByMsg by vm.reactions.collectAsState()
    val all = reactionsByMsg[msg.id].orEmpty()
    if (all.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val grouped = remember(all) { all.groupBy { it.emoji }.toList().sortedByDescending { it.second.size } }
    var selected by remember { mutableStateOf(initialEmoji?.takeIf { e -> grouped.any { it.first == e } }) }
    val shown = if (selected == null) all else all.filter { it.emoji == selected }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { ReactionTab("All · ${all.size}", selected == null) { selected = null } }
                items(grouped, key = { it.first }) { (emoji, list) ->
                    ReactionTab("$emoji ${list.size}", selected == emoji) { selected = emoji }
                }
            }
            HorizontalDivider()
            shown.sortedByDescending { it.timestampMs }.forEach { r ->
                val mineR = r.authorHex == myHex
                val name = if (mineR) "You" else vm.nameForHex(r.authorHex)
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (mineR) Modifier.clickable { vm.toggleReaction(msg, r.emoji) } else Modifier)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(seed = r.authorHex, label = name, size = 40, identiconKey = vm.pubKeyForHex(r.authorHex).ifBlank { null })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, fontWeight = FontWeight.Medium)
                        if (mineR) {
                            Text(
                                "Tap to remove",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(r.emoji, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun ReactionTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: Message,
    isChannel: Boolean,
    senderLabel: String,
    senderColor: Color,
    onSenderClick: (() -> Unit)?,
    repeatCount: Int,
    delivery: cz.meshcore.sidepath.service.DmDelivery?,
    onMentionClick: (String) -> Unit,
    reactions: List<cz.meshcore.meshward.data.Reaction>,
    myHex: String,
    networkCode: String,
    showSenderHeader: Boolean,
    showAvatar: Boolean,
    senderIdenticonKey: String?,
    mentionResolved: (String) -> Boolean,
    onChipClick: (String) -> Unit,
    onLongPress: () -> Unit,
    onClick: () -> Unit,
) {
    val mine = !msg.incoming
    val hasReactions = reactions.isNotEmpty()
    val isMeshCorePathInfo = msg.id.startsWith("mcpath:")
    if (isMeshCorePathInfo) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        msg.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatMessageTime(msg.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
        return
    }
    // The network shown on this message's MeshCore info: the one recorded on the message, falling
    // back to the channel's current network for rows saved before it was persisted.
    val net = msg.networkCode.ifBlank { networkCode }
    Row(
        // Reserve room below for the reaction pills, which overhang the bubble's bottom edge.
        Modifier.fillMaxWidth().padding(bottom = if (hasReactions) 14.dp else 0.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        // Avatar column: fixed 32 dp wide so all bubbles in the group align.
        // Avatar is shown only on the LAST message of a consecutive same-sender run.
        if (!mine && isChannel) {
            Box(Modifier.size(32.dp)) {
                if (showAvatar) {
                    Avatar(
                        seed = senderIdenticonKey ?: senderLabel,
                        label = senderLabel,
                        size = 32,
                        identiconKey = senderIdenticonKey,
                        onClick = onSenderClick,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }
        // Incoming channel bubbles: left corners flatten toward the avatar to signal grouping.
        //   single  → all 16dp  (showSenderHeader && showAvatar)
        //   first   → no bottom-left rounding  (showSenderHeader && !showAvatar)
        //   middle  → no left rounding at all  (!showSenderHeader && !showAvatar)
        //   last    → no top-left rounding  (!showSenderHeader && showAvatar)
        val bubbleShape = if (!mine && isChannel) {
            RoundedCornerShape(
                topStart = if (showSenderHeader) 16.dp else 4.dp,
                topEnd = 16.dp,
                bottomEnd = 16.dp,
                bottomStart = if (showAvatar) 16.dp else 4.dp,
            )
        } else RoundedCornerShape(16.dp)
        Box {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = bubbleShape,
            modifier = Modifier.widthIn(max = 280.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Sender name: only on first message of a consecutive group.
                if (isChannel && msg.incoming && showSenderHeader) {
                    Text(
                        senderLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = senderColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = if (onSenderClick != null) Modifier.clickable(onClick = onSenderClick) else Modifier,
                    )
                }
                MessageContent(
                    msg.text,
                    enableMentions = isChannel,
                    onMentionClick = onMentionClick,
                    mentionResolved = mentionResolved,
                )
                Spacer(Modifier.size(2.dp))
                // Meta row: 50% opacity applied to the whole row so every element —
                // text, icons, and delivery ticks — reads at the same reduced weight.
                Row(
                    modifier = Modifier.alpha(0.5f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        formatMessageTime(msg.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (mine) {
                        if (isChannel) {
                            // Broadcast — no ACK. Hearing it echoed back is our delivery signal.
                            DeliveryTick(if (repeatCount > 0) MsgStatus.DELIVERED else MsgStatus.SENT)
                        } else {
                            AnimatedDeliveryTick(msg.status, delivery)
                            // While retrying, show how many attempts have gone out.
                            if (delivery != null && delivery.attemptsSent > 1 && msg.status != MsgStatus.DELIVERED) {
                                Text(
                                    "try ${delivery.attemptsSent}/${delivery.maxTries}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    } else RouteIndicator(msg.routeHex)
                    // MeshCore routing info last: "· CZ · 4 hops" or "· CZ" for bridged outgoing.
                    if (msg.incoming && msg.viaMeshCore) {
                        val hopText = when {
                            msg.meshCoreHops <= 0 -> "direct"
                            msg.meshCoreHops == 1 -> "1 hop"
                            else -> "${msg.meshCoreHops} hops"
                        }
                        Text(
                            "· ${net.ifBlank { "MeshCore" }} · $hopText",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else if (mine && msg.bridgedToMeshCore) {
                        Text(
                            "· ${net.ifBlank { "MeshCore" }}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        // Reaction pills overhang the bubble's bottom edge, on the side OPPOSITE the bubble:
        // mine (right) → bottom-left corner; incoming (left) → bottom-right corner.
        if (hasReactions) {
            Box(
                Modifier.align(if (mine) Alignment.BottomStart else Alignment.BottomEnd)
                    .offset(x = if (mine) 8.dp else (-8).dp, y = 12.dp),
            ) {
                ReactionChips(reactions, myHex, onChipClick)
            }
        }
        }
    }
}

/** Grouped emoji-reaction pills overhanging a message; tap one to open the reaction viewer. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ReactionChips(
    reactions: List<cz.meshcore.meshward.data.Reaction>,
    myHex: String,
    onChipClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = remember(reactions) { reactions.groupBy { it.emoji }.toList().sortedByDescending { it.second.size } }
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        grouped.forEach { (emoji, list) ->
            // Grey pill (with a subtle outline so it reads over a same-colored bubble); count when >1.
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 3.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.clickable { onChipClick(emoji) },
            ) {
                Row(
                    Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(emoji, style = MaterialTheme.typography.labelMedium)
                    if (list.size > 1) {
                        Text(
                            "${list.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MessageInput(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    fullScreen: Boolean,
    onEmoji: () -> Unit,
    onSend: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth()
                .then(if (fullScreen) Modifier.navigationBarsPadding() else Modifier)
                .imePadding()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onEmoji) {
                Icon(Icons.Default.Mood, contentDescription = "Emoji")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    // Enter sends; Shift+Enter inserts a newline at the cursor.
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                        if (e.isShiftPressed) {
                            val sel = value.selection
                            val text = value.text.substring(0, sel.start) + "\n" + value.text.substring(sel.end)
                            onChange(TextFieldValue(text, TextRange(sel.start + 1)))
                            true
                        } else { onSend(); true }
                    } else false
                },
                placeholder = { Text("Message") },
                maxLines = 5,
                // Highlight @[mentions] as they're typed; the stored text is unchanged.
                visualTransformation = mentionInputTransformation(accent),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
            IconButton(onClick = onSend, enabled = value.text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/** Horizontal strip of @-mention candidates shown above the composer while typing "@". */
@Composable
private fun MentionSuggestions(names: List<String>, onPick: (String) -> Unit) {
    Surface(tonalElevation = 3.dp) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(names, key = { it }) { name ->
                AssistChip(onClick = { onPick(name) }, label = { Text("@$name") })
            }
        }
    }
}

/** Centered day separator ("Today" / "Yesterday" / date) between message groups. */
@Composable
private fun DateSeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
            Text(
                label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Header at the top of the message stream: a large avatar and a few basic details. Doubles as
 * the filler for a brand-new, empty conversation.
 */
@Composable
private fun ConversationHeaderPanel(profile: ProfileInfo, isChannel: Boolean, isSelf: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isSelf) {
            NoteToSelfAvatar(size = 88)
            Spacer(Modifier.size(10.dp))
            Text(NOTE_TO_SELF, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.size(4.dp))
            Text(
                "Jot notes to yourself. They stay on this device.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        Avatar(
            seed = profile.peerHex,
            label = profile.name,
            size = 88,
            identiconKey = if (!isChannel) profile.pubKeyHex else null,
        )
        Spacer(Modifier.size(10.dp))
        Text(
            if (isChannel) channelLabel(profile.name, profile.channelKind) else profile.name,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(4.dp))
        if (isChannel) {
            Text(
                "Group channel · 0x%02x".format(profile.channelHash),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(4.dp))
            // Public and Named channels use well-known / name-derived keys — anyone can read them.
            // Only Secret channels are protected by a private shared key.
            val secret = profile.channelKind == ChannelKind.SECRET
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (secret) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    if (secret) "Shared-key encrypted" else "Anyone can read this",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            if (profile.pubKeyHex.isNotBlank()) {
                Text(
                    formatPubKey(profile.pubKeyHex),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(4.dp))
                Text(
                    "End-to-end encrypted",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One row of the three-dot Notifications submenu — its [icon]/[label] with a trailing check when [selected]. */
@Composable
private fun NotifModeItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = {
            if (selected) Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        },
        onClick = onClick,
    )
}

/** The active mention query: the partial after a trailing "@" (word-boundary), or null. */
fun mentionQueryOf(draft: String): String? =
    Regex("""(?:^|\s)@([^\s@]*)$""").find(draft)?.groupValues?.get(1)

/** Replaces the trailing "@partial" the user is typing with a `@[Name]` token (and a space). */
fun insertMention(draft: String, name: String): String =
    Regex("""(?:^|\s)@([^\s@]*)$""").replace(draft) { m ->
        val lead = if (m.value.startsWith("@")) "" else m.value.take(1)
        "$lead@[$name] "
    }
