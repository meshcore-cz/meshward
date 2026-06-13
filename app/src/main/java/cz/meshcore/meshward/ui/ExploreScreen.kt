package cz.meshcore.meshward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
) {
    val discovered by vm.discoveredContacts.collectAsState()
    val chatItems by vm.chatItems.collectAsState()
    val myNode by vm.nodeId.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

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
            discovered.isEmpty() ->
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    verticalArrangement = Arrangement.Center,
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
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Text(
                        "Discovered contacts (${discovered.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                items(discovered, key = { it.pubKeyHex }) { d ->
                    DiscoveredRow(d) { onOpenProfile(d.nodeHex) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
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

@Composable
internal fun DiscoveredRow(d: DiscoveredContact, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = d.nodeHex, label = d.name, identiconKey = d.pubKeyHex.ifBlank { null })
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                SourceBadge(d.source)
            }
            val sub = buildString {
                append(d.nodeHex.take(16))
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
        Text(
            formatRelativeAge(d.lastAdvertisedMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceBadge(source: String) {
    val (label, color) = when (source) {
        DiscoverySource.MESHCORE -> "MESHCORE" to Color(0xFF00838F)
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
