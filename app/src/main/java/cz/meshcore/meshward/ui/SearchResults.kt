package cz.meshcore.meshward.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatListItem
import cz.meshcore.meshward.data.DiscoveredContact

/**
 * Integrated search shared by the Chats and Explore tabs: matching chats first, then matching
 * discovered contacts. Discovered contacts that already have a chat row are dropped so a node you
 * already talk to appears only once (as its chat).
 *
 * [query] is assumed non-blank; callers show a hint while it's empty.
 */
@Composable
fun SearchResults(
    chats: List<ChatListItem>,
    discovered: List<DiscoveredContact>,
    query: String,
    myNodeHex: String,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val q = query.trim()

    val matchedChats = remember(chats, q) {
        chats.filter {
            it.title.contains(q, ignoreCase = true) ||
                it.peerHex.contains(q, ignoreCase = true) ||
                it.pubKeyHex.contains(q, ignoreCase = true)
        }
    }
    val chatPeers = remember(chats) { chats.mapTo(HashSet()) { it.peerHex } }
    val matchedDiscovered = remember(discovered, q, chatPeers) {
        discovered.filter {
            it.nodeHex !in chatPeers &&
                (it.name.contains(q, ignoreCase = true) || it.nodeHex.contains(q, ignoreCase = true))
        }
    }

    if (matchedChats.isEmpty() && matchedDiscovered.isEmpty()) {
        SearchHint("No matching chats or contacts", modifier.fillMaxSize())
        return
    }

    LazyColumn(modifier.fillMaxSize()) {
        if (matchedChats.isNotEmpty()) {
            item { SectionHeader("Chats", matchedChats.size) }
            items(matchedChats, key = { "chat:" + it.peerHex }) { item ->
                ChatRow(
                    item,
                    isSelf = item.peerHex == myNodeHex,
                    onClick = { onOpenConversation(item.peerHex) },
                    onAvatarClick = { onOpenProfile(item.peerHex) },
                )
            }
        }
        if (matchedDiscovered.isNotEmpty()) {
            item { SectionHeader("Discovered contacts", matchedDiscovered.size) }
            items(matchedDiscovered, key = { "disc:" + it.pubKeyHex }) { d ->
                DiscoveredRow(d) { onOpenProfile(d.nodeHex) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Text(
        "$label ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
