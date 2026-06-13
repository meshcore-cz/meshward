package cz.meshcore.meshward.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.sidepath.service.MessageNotifier

/**
 * Navigation destinations, ordered by [depth] (how deep in the push stack they are). The
 * depth drives the slide direction: going to a deeper screen slides in from the right;
 * going back (shallower) slides in from the left.
 */
private sealed class Dest(val depth: Int) {
    data object Tabs : Dest(0)
    data object Settings : Dest(1)
    data object RxLog : Dest(1)
    data object MeshCoreLog : Dest(1)
    data object Topology : Dest(1)
    data object About : Dest(2)
    data object Debug : Dest(2)
    data class Conversation(val peer: String) : Dest(1)
    data class Profile(val peer: String) : Dest(2)
    data class Trace(val peer: String) : Dest(3)

    /**
     * Stable key for the saveable-state holder, so each screen's `rememberSaveable` state
     * (e.g. a LazyColumn's scroll position) is restored when we navigate back to it. Peer-keyed
     * screens namespace by peer so two different conversations don't share scroll state.
     */
    val saveableKey: String
        get() = when (this) {
            is Conversation -> "conv:$peer"
            is Profile -> "profile:$peer"
            is Trace -> "trace:$peer"
            else -> this::class.simpleName ?: "dest"
        }
}

@Composable
fun ChatRoot(vm: ChatViewModel) {
    var openPeer by rememberSaveable { mutableStateOf<String?>(null) }
    var openProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var openTrace by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showRxLog by rememberSaveable { mutableStateOf(false) }
    var showMeshCoreLog by rememberSaveable { mutableStateOf(false) }
    var showTopology by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showDebug by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }

    // A meshcore:// deep link (contact/channel) asks us to open its conversation.
    val pendingOpen by vm.pendingOpenPeer.collectAsState()
    LaunchedEffect(pendingOpen) {
        pendingOpen?.let {
            openProfile = null; openTrace = null
            openPeer = it
            vm.consumePendingOpen()
        }
    }

    // The top-most destination, recomputed from the navigation flags. Trace sits above a
    // profile, which sits above a conversation, which sits above the tabs — so backing out
    // unwinds in that order.
    val top: Dest = when {
        openTrace != null -> Dest.Trace(openTrace!!)
        showAbout -> Dest.About
        showDebug -> Dest.Debug
        openProfile != null -> Dest.Profile(openProfile!!)
        openPeer != null -> Dest.Conversation(openPeer!!)
        showTopology -> Dest.Topology
        showMeshCoreLog -> Dest.MeshCoreLog
        showRxLog -> Dest.RxLog
        showSettings -> Dest.Settings
        else -> Dest.Tabs
    }

    val popTop = {
        when (top) {
            is Dest.Trace -> {
                vm.clearTrace()
                openTrace = null
            }
            Dest.About -> showAbout = false
            Dest.Debug -> showDebug = false
            is Dest.Profile -> openProfile = null
            is Dest.Conversation -> openPeer = null
            Dest.MeshCoreLog -> showMeshCoreLog = false
            Dest.Topology -> showTopology = false
            Dest.RxLog -> showRxLog = false
            Dest.Settings -> showSettings = false
            Dest.Tabs -> Unit
        }
    }
    BackHandler(enabled = top !is Dest.Tabs) { popTop() }

    val activeConversationPeer = (top as? Dest.Conversation)?.peer
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, activeConversationPeer) {
        fun syncActiveConversation() {
            MessageNotifier.setActiveConversation(
                activeConversationPeer
                    ?.takeIf { lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            )
        }

        val observer = LifecycleEventObserver { _, _ -> syncActiveConversation() }
        lifecycleOwner.lifecycle.addObserver(observer)
        syncActiveConversation()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            MessageNotifier.setActiveConversation(null)
        }
    }

    val avatarStyle by vm.avatarStyle.collectAsState()
    val stateHolder = rememberSaveableStateHolder()
    CompositionLocalProvider(
        LocalAvatarStyle provides avatarStyle,
        // App-wide mesh navigation: Trace and Rx Log are reachable from any universal
        // component (e.g. the connection-status sheet) without threading callbacks.
        LocalMeshNav provides MeshNav(
            openTrace = { openTrace = it },
            openRxLog = { showRxLog = true },
            openMeshCoreLog = { showMeshCoreLog = true },
            openTopology = { showTopology = true },
            openProfile = { openProfile = it },
        ),
    ) {
    AnimatedContent(
        targetState = top,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            // Push (deeper): new enters from the right, old exits left. Back: the reverse.
            if (targetState.depth >= initialState.depth) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            } else {
                slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
            }
        },
        label = "nav",
    ) { dest ->
        // Preserve each destination's rememberSaveable state (incl. list scroll position) across
        // navigation, so backing out to a tab list returns you to where you were scrolled.
        stateHolder.SaveableStateProvider(dest.saveableKey) {
        when (dest) {
            is Dest.Trace -> TraceScreen(vm, dest.peer, onBack = popTop)
            is Dest.Profile -> ProfileScreen(
                vm, dest.peer,
                onBack = popTop,
                onOpenConversation = { openProfile = null; openPeer = it },
                onTrace = { openTrace = it },
                onOpenProfile = { openProfile = it },
                // Profile (depth 2) outranks Settings (depth 1) in `top`, so close the profile
                // first; otherwise Settings would never surface.
                onOpenSettings = { openProfile = null; showSettings = true },
            )
            is Dest.Conversation -> ConversationScreen(
                vm, dest.peer,
                onBack = popTop,
                onOpenProfile = { openProfile = it },
            )
            Dest.Settings -> SettingsScreen(
                vm,
                onBack = popTop,
                onOpenProfile = { openProfile = it },
                onOpenAbout = { showAbout = true },
                onOpenDebug = { showDebug = true },
            )
            Dest.RxLog -> RxLogScreen(
                vm,
                onBack = popTop,
                onOpenProfile = { openProfile = it },
                onOpenMeshCoreLog = { showMeshCoreLog = true },
            )
            Dest.MeshCoreLog -> MeshCoreRxLogScreen(
                vm,
                onBack = popTop,
                onOpenProfile = { openProfile = it },
            )
            Dest.Topology -> TopologyScreen(
                vm,
                onBack = popTop,
                onOpenProfile = { openProfile = it },
            )
            Dest.About -> AboutScreen(onBack = popTop)
            Dest.Debug -> DebugScreen(vm, onBack = popTop)
            Dest.Tabs -> TabsScaffold(
                vm,
                tab = tab,
                onSelectTab = { tab = it },
                onOpenConversation = { openPeer = it },
                onOpenProfile = { openProfile = it },
                onOpenSettings = { showSettings = true },
                onOpenAbout = { showAbout = true },
            )
        }
        }
    }
    }
}

@Composable
private fun TabsScaffold(
    vm: ChatViewModel,
    tab: Int,
    onSelectTab: (Int) -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    val unread = remember(conversations) { conversations.sumOf { it.unread } }

    Scaffold(
        // Each tab screen owns its own system-bar insets via its TopAppBar / composer,
        // so the root must not also pad the content (that double-counted the status bar).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // The tab bar is always shown. Full-screen views (conversation, profile, settings)
            // render as separate destinations, so the only keyboard that ever coexists with
            // the bar is the Chats search field — keeping the bar visible there is fine.
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { onSelectTab(0) },
                    icon = {
                        BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
                            Icon(Icons.Default.Forum, contentDescription = "Chats")
                        }
                    },
                    label = { Text("Chats") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { onSelectTab(1) },
                    icon = { Icon(Icons.Default.TravelExplore, contentDescription = "Explore") },
                    label = { Text("Explore") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { onSelectTab(2) },
                    icon = { Icon(Icons.Default.Hub, contentDescription = "Network") },
                    label = { Text("Network") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> ChatsScreen(
                    vm,
                    onOpenConversation = onOpenConversation,
                    onOpenProfile = onOpenProfile,
                    onOpenSettings = onOpenSettings,
                    onOpenAbout = onOpenAbout,
                )
                1 -> ExploreScreen(
                    vm,
                    onOpenConversation = onOpenConversation,
                    onOpenProfile = onOpenProfile,
                    onOpenSettings = onOpenSettings,
                    onOpenAbout = onOpenAbout,
                )
                else -> NetworkScreen(
                    vm,
                    onOpenProfile = onOpenProfile,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}
