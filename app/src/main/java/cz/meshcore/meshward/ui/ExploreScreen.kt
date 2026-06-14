package cz.meshcore.meshward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.LocationPrecision
import cz.meshcore.meshward.data.DiscoveredContact
import cz.meshcore.meshward.data.DiscoverySource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    vm: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenNetworkDetail: (String) -> Unit = {},
) {
    val discovered by vm.discoveredContacts.collectAsState()
    val activeNetwork by vm.activeNetwork.collectAsState()
    val detectedNetworks by vm.detectedNetworks.collectAsState()
    val networkAuto by vm.networkAuto.collectAsState()
    val chatItems by vm.chatItems.collectAsState()
    val myNode by vm.nodeId.collectAsState()
    val userLoc by vm.userLocation.collectAsState()
    val locationEnabled by vm.locationEnabled.collectAsState()
    val promptDismissed by vm.locationPromptDismissed.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    // Filter state lives in the VM so selections survive navigating to another page and back (the
    // Explore subtree leaves composition on navigation, which would drop screen-local state).
    val typeFilter by vm.exploreTypeFilter.collectAsState()
    val roleFilter by vm.exploreRoleFilter.collectAsState()
    val networkFilter by vm.exploreNetworkFilter.collectAsState()
    val sortByDistance by vm.exploreSortByDistance.collectAsState()

    // Ask for coarse location when the user opts in (routes to app settings if permanently denied).
    val requestLocation = rememberLocationEnabler(vm)
    // Grab a fresh fix whenever Explore is shown with location enabled.
    LaunchedEffect(locationEnabled) { if (locationEnabled) vm.refreshLocation() }

    val showLocationBanner = !promptDismissed && !locationEnabled
    val canSortByDistance = locationEnabled && userLoc != null
    val shown = remember(discovered, typeFilter, roleFilter, networkFilter, sortByDistance, userLoc) {
        var list = discovered
        typeFilter?.let { t -> list = list.filter { it.source == t } }
        roleFilter?.let { r -> list = list.filter { it.nodeType == r } }
        networkFilter?.let { n -> list = list.filter { it.networkCode == n } }
        val loc = userLoc
        if (sortByDistance && loc != null) {
            list = list.sortedBy { if (it.hasGps) distanceMeters(loc, it.lat, it.lon) else Float.MAX_VALUE }
        }
        list
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) SearchField(query, { query = it }, "Search chats & contacts")
                    else Text("Explore")
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
                    OverflowMenu(
                        onOpenSettings = onOpenSettings,
                        onOpenAbout = onOpenAbout,
                        extraItems = { dismiss ->
                            DropdownMenuItem(
                                text = { Text("Clear discovered contacts") },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    dismiss()
                                    confirmClear = true
                                },
                            )
                        },
                    )
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        when {
            searching && query.isBlank() ->
                SearchHint("Start typing to search chats & contacts…", Modifier.fillMaxSize().padding(padding))
            searching ->
                SearchResults(
                    chats = chatItems,
                    discovered = discovered,
                    query = query,
                    myNodeHex = myNode.toHex(),
                    onOpenConversation = onOpenConversation,
                    onOpenProfile = onOpenProfile,
                    modifier = Modifier.padding(padding),
                )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                activeNetwork?.let { net ->
                    item {
                        ActiveNetworkCard(
                            code = net.code,
                            name = net.name,
                            summary = networkRadioSummary(net),
                            autoDetected = networkAuto,
                            onClick = { onOpenNetworkDetail(net.code) },
                        )
                    }
                }
                if (showLocationBanner) {
                    item {
                        LocationOptInBanner(
                            onAllow = { precision -> requestLocation(precision) },
                            onDeny = { vm.dismissLocationPrompt() },
                        )
                    }
                }
                if (discovered.isNotEmpty()) {
                    item {
                        FilterBar(
                            typeFilter = typeFilter,
                            onTypeFilter = { vm.setExploreTypeFilter(it) },
                            roleFilter = roleFilter,
                            onRoleFilter = { vm.setExploreRoleFilter(it) },
                            // Keep the selected code chip available even if its bridge dropped out,
                            // so the filter can always be cleared.
                            networkCodes = (detectedNetworks.map { it.code } + networkFilter).filterNotNull().distinct(),
                            networkFilter = networkFilter,
                            onNetworkFilter = { vm.setExploreNetworkFilter(it) },
                            canSortByDistance = canSortByDistance,
                            sortByDistance = sortByDistance,
                            onToggleSort = { vm.setExploreSortByDistance(!sortByDistance) },
                        )
                    }
                    item {
                        Text(
                            "Discovered contacts (${shown.size}${if (shown.size != discovered.size) " of ${discovered.size}" else ""})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                if (discovered.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Default.TravelExplore, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(12.dp))
                            Text("No discovered contacts", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Nodes you hear advertising — over Sidepath or bridged from MeshCore — appear here.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                } else {
                    items(shown, key = { it.pubKeyHex }) { d ->
                        val distance = userLoc?.takeIf { d.hasGps }?.let { formatDistance(distanceMeters(it, d.lat, d.lon)) }
                        DiscoveredRow(d, distance = distance) { onOpenProfile(d.nodeHex) }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear discovered contacts?") },
            text = { Text("This removes all discovered contacts from Explore. Saved contacts and chat history are kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearDiscoveredContacts()
                        confirmClear = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Compact header card on Explore showing the active Meshcore Network; taps through to its detail.
 * Clarifies that this is the Meshcore network and whether it was auto-detected from a nearby bridge
 * or pinned manually.
 */
@Composable
private fun ActiveNetworkCard(code: String, name: String, summary: String, autoDetected: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetworkCodeChip(code)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name.ifBlank { code }, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    Text(
                        if (autoDetected) "Meshcore network · auto-detected" else "Meshcore network · manual",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (summary.isNotBlank()) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun DiscoveredRow(d: DiscoveredContact, distance: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = d.nodeHex, label = d.name, identiconKey = d.pubKeyHex.ifBlank { null })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                DiscoveredBadge(d)
            }
            val sub = buildString {
                append(formatPubKey(d.pubKeyHex.ifBlank { d.nodeHex }))
                if (d.source == DiscoverySource.MESHCORE) append(" · ${nodeTypeLabel(d.nodeType)}")
            }
            Text(
                sub,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatRelativeAge(d.lastAdvertisedMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (distance != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Icon(
                        Icons.Default.NearMe,
                        contentDescription = "Distance",
                        modifier = Modifier.size(11.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        distance,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * The opt-in banner asking whether — and how precisely — to use location for distance estimates.
 * Approximate grants coarse (~110 m) location; Precise grants exact GPS.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocationOptInBanner(onAllow: (LocationPrecision) -> Unit, onDeny: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.NearMe, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    "Use your location to calculate distances to nearby nodes?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Text(
                "Use your location to estimate the distance to MeshCore repeaters and other nodes that " +
                    "advertise GPS. Approximate keeps only a rounded (~110 m) position; Precise uses exact " +
                    "GPS. Your coordinates stay on this device and are never sent anywhere — change it " +
                    "anytime in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.End),
            ) {
                TextButton(onClick = onDeny) { Text("Not now") }
                OutlinedButton(onClick = { onAllow(LocationPrecision.COARSE) }) { Text("Approximate") }
                Button(onClick = { onAllow(LocationPrecision.FINE) }) { Text("Precise") }
            }
        }
    }
}

/** Filter chips for source/role plus a "Nearest" sort toggle (only when a location is known). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(
    typeFilter: String?,
    onTypeFilter: (String?) -> Unit,
    roleFilter: Int?,
    onRoleFilter: (Int?) -> Unit,
    networkCodes: List<String>,
    networkFilter: String?,
    onNetworkFilter: (String?) -> Unit,
    canSortByDistance: Boolean,
    sortByDistance: Boolean,
    onToggleSort: () -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Network (one chip per detected bridge network; shown only when a bridge is around)
        networkCodes.forEach { code ->
            FilterChip(
                selected = networkFilter == code,
                onClick = { onNetworkFilter(if (networkFilter == code) null else code) },
                label = { Text(code) },
            )
        }
        // Source
        FilterChip(
            selected = typeFilter == DiscoverySource.SIDEPATH,
            onClick = { onTypeFilter(if (typeFilter == DiscoverySource.SIDEPATH) null else DiscoverySource.SIDEPATH) },
            label = { Text("Sidepath") },
        )
        FilterChip(
            selected = typeFilter == DiscoverySource.MESHCORE,
            onClick = { onTypeFilter(if (typeFilter == DiscoverySource.MESHCORE) null else DiscoverySource.MESHCORE) },
            label = { Text("MeshCore") },
        )
        // Role (MeshCore node types)
        listOf(2 to "Repeater", 1 to "Chat", 3 to "Room", 4 to "Sensor").forEach { (type, label) ->
            FilterChip(
                selected = roleFilter == type,
                onClick = { onRoleFilter(if (roleFilter == type) null else type) },
                label = { Text(label) },
            )
        }
        if (canSortByDistance) {
            FilterChip(
                selected = sortByDistance,
                onClick = { onToggleSort() },
                leadingIcon = { Icon(Icons.Default.NearMe, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Nearest") },
            )
        }
    }
}

/** MeshCore brand teal, used for the source/network badges so a MeshCore origin always reads the same. */
private val MeshCoreColor = Color(0xFF00838F)

/**
 * The origin badge on a discovered row: a MeshCore contact shows its tiered network code (small teal
 * chip), or — when the region is unknown — just the mesh icon; a Sidepath node shows its source badge.
 */
@Composable
private fun DiscoveredBadge(d: DiscoveredContact) {
    when {
        d.source == DiscoverySource.MESHCORE && d.networkCode.isNotBlank() ->
            Surface(color = MeshCoreColor.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
                Text(
                    d.networkCode,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MeshCoreColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        d.source == DiscoverySource.MESHCORE ->
            Surface(color = MeshCoreColor.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
                Icon(
                    Icons.Default.Hub,
                    contentDescription = "MeshCore",
                    tint = MeshCoreColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp).size(13.dp),
                )
            }
        else -> SourceBadge(d.source)
    }
}

@Composable
private fun SourceBadge(source: String) {
    val (label, color) = when (source) {
        DiscoverySource.MESHCORE -> "MESHCORE" to MeshCoreColor
        else -> "SIDEPATH" to Color(0xFF546E7A)
    }
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** MeshCore node-type code → label. Shared with the profile's MeshCore section. */
fun nodeTypeLabel(t: Int): String = when (t) {
    1 -> "chat"
    2 -> "repeater"
    3 -> "room"
    4 -> "sensor"
    else -> "unknown"
}
