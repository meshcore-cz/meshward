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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
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
    data object Networks : Dest(2)
    data object About : Dest(2)
    data object Debug : Dest(2)
    data object ConnectionLog : Dest(3)
    data object ManageIdentities : Dest(1)
    data class Conversation(val peer: String) : Dest(1)
    data class Profile(val peer: String) : Dest(2)
    data class NetworkDetail(val code: String) : Dest(2)
    // Standalone Trace tool. [prefill] (a node hex) seeds the in-screen node selector when opened
    // from a profile; empty means the screen opens with no node chosen.
    data class Trace(val prefill: String = "") : Dest(3)

    /**
     * Stable key for the saveable-state holder, so each screen's `rememberSaveable` state
     * (e.g. a LazyColumn's scroll position) is restored when we navigate back to it. Peer-keyed
     * screens namespace by peer so two different conversations don't share scroll state.
     */
    val saveableKey: String
        get() = when (this) {
            is Conversation -> "conv:$peer"
            is Profile -> "profile:$peer"
            is NetworkDetail -> "network:$code"
            // Standalone: one Trace screen, so its state is shared regardless of prefill.
            is Trace -> "Trace"
            else -> this::class.simpleName ?: "dest"
        }

    companion object {
        /** Inverse of [saveableKey], used to restore the back stack across process death. */
        fun fromKey(key: String): Dest = when {
            key.startsWith("conv:") -> Conversation(key.removePrefix("conv:"))
            key.startsWith("profile:") -> Profile(key.removePrefix("profile:"))
            key.startsWith("network:") -> NetworkDetail(key.removePrefix("network:"))
            key == "Trace" -> Trace()
            key == "Settings" -> Settings
            key == "RxLog" -> RxLog
            key == "MeshCoreLog" -> MeshCoreLog
            key == "Topology" -> Topology
            key == "Networks" -> Networks
            key == "About" -> About
            key == "Debug" -> Debug
            key == "ConnectionLog" -> ConnectionLog
            key == "ManageIdentities" -> ManageIdentities
            else -> Tabs
        }
    }
}

/** Persists the navigation back stack (as its destination keys) across process death. */
private val DestStackSaver = androidx.compose.runtime.saveable.listSaver<SnapshotStateList<Dest>, String>(
    save = { stack -> stack.map { it.saveableKey } },
    restore = { keys -> keys.map(Dest::fromKey).toMutableStateList() },
)

@Composable
fun ChatRoot(vm: ChatViewModel) {
    // A real push/pop back stack: each navigation pushes a destination, Back pops the last one, so
    // backing out always returns to the screen you came from (e.g. NetworkDetail → bridge profile →
    // back returns to NetworkDetail, not the tab). The tabs are the implicit base (empty stack).
    val backStack = rememberSaveable(saver = DestStackSaver) { mutableStateListOf<Dest>() }
    var tab by rememberSaveable { mutableStateOf(0) }

    fun push(dest: Dest) {
        if (backStack.lastOrNull() != dest) backStack.add(dest)
    }

    // A meshcore:// deep link (contact/channel) resets to that conversation.
    val pendingOpen by vm.pendingOpenPeer.collectAsState()
    LaunchedEffect(pendingOpen) {
        pendingOpen?.let {
            backStack.clear()
            backStack.add(Dest.Conversation(it))
            vm.consumePendingOpen()
        }
    }

    val top: Dest = backStack.lastOrNull() ?: Dest.Tabs

    val popTop = {
        val popped = backStack.removeLastOrNull()
        if (popped is Dest.Trace) vm.clearTrace()
    }
    BackHandler(enabled = backStack.isNotEmpty()) { popTop() }

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
            openTrace = { push(Dest.Trace(it)) },
            openRxLog = { push(Dest.RxLog) },
            openMeshCoreLog = { push(Dest.MeshCoreLog) },
            openTopology = { push(Dest.Topology) },
            openProfile = { push(Dest.Profile(it)) },
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
            is Dest.Trace -> TraceScreen(vm, dest.prefill, onBack = popTop)
            is Dest.Profile -> ProfileScreen(
                vm, dest.peer,
                onBack = popTop,
                onOpenConversation = { push(Dest.Conversation(it)) },
                onTrace = { push(Dest.Trace(it)) },
                onOpenProfile = { push(Dest.Profile(it)) },
                onOpenSettings = { push(Dest.Settings) },
                onOpenNetworkDetail = { push(Dest.NetworkDetail(it)) },
            )
            is Dest.Conversation -> ConversationScreen(
                vm, dest.peer,
                onBack = popTop,
                onOpenProfile = { push(Dest.Profile(it)) },
            )
            Dest.Settings -> SettingsScreen(
                vm,
                onBack = popTop,
                onOpenProfile = { push(Dest.Profile(it)) },
                onOpenAbout = { push(Dest.About) },
                onOpenDebug = { push(Dest.Debug) },
                onOpenNetworks = { push(Dest.Networks) },
            )
            Dest.Networks -> NetworksScreen(
                vm,
                onBack = popTop,
                onOpenDetail = { push(Dest.NetworkDetail(it)) },
            )
            is Dest.NetworkDetail -> NetworkDetailScreen(
                vm, dest.code,
                onBack = popTop,
                onOpenNetworks = { push(Dest.Networks) },
                onOpenProfile = { push(Dest.Profile(it)) },
                // The active network is a MeshCore bridge, so its log is the MeshCore Rx Log.
                onOpenRxLog = { push(Dest.MeshCoreLog) },
            )
            Dest.RxLog -> RxLogScreen(
                vm,
                onBack = popTop,
                onOpenProfile = { push(Dest.Profile(it)) },
                onOpenMeshCoreLog = { push(Dest.MeshCoreLog) },
            )
            Dest.MeshCoreLog -> MeshCoreRxLogScreen(
                vm,
                onBack = popTop,
                onOpenProfile = { push(Dest.Profile(it)) },
            )
            Dest.Topology -> TopologyScreen(
                vm,
                onBack = popTop,
                onOpenProfile = { push(Dest.Profile(it)) },
            )
            Dest.About -> AboutScreen(onBack = popTop)
            Dest.Debug -> DebugScreen(vm, onBack = popTop, onOpenConnectionLog = { push(Dest.ConnectionLog) })
            Dest.ConnectionLog -> ConnectionLogScreen(vm, onBack = popTop)
            Dest.ManageIdentities -> ManageIdentitiesScreen(vm, onBack = popTop)
            Dest.Tabs -> TabsScaffold(
                vm,
                tab = tab,
                onSelectTab = { tab = it },
                onOpenConversation = { push(Dest.Conversation(it)) },
                onOpenProfile = { push(Dest.Profile(it)) },
                onOpenSettings = { push(Dest.Settings) },
                onOpenAbout = { push(Dest.About) },
                onOpenNetworkDetail = { push(Dest.NetworkDetail(it)) },
                onOpenManageIdentities = { push(Dest.ManageIdentities) },
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
    onOpenNetworkDetail: (String) -> Unit,
    onOpenManageIdentities: () -> Unit,
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
                    icon = { Icon(Icons.Default.Hub, contentDescription = "Sidepath") },
                    label = { Text("Sidepath") },
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
                    onOpenManageIdentities = onOpenManageIdentities,
                )
                1 -> ExploreScreen(
                    vm,
                    onOpenConversation = onOpenConversation,
                    onOpenProfile = onOpenProfile,
                    onOpenSettings = onOpenSettings,
                    onOpenAbout = onOpenAbout,
                    onOpenNetworkDetail = onOpenNetworkDetail,
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
