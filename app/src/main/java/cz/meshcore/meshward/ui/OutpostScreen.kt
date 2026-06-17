package cz.meshcore.meshward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.outpost.OutpostBoard
import cz.meshcore.meshward.outpost.OutpostPost
import cz.meshcore.meshward.outpost.OutpostPostStatus
import cz.meshcore.meshward.outpost.protocol.CloseReason
import cz.meshcore.meshward.outpost.protocol.ExchangeCategory
import cz.meshcore.meshward.outpost.protocol.OutpostTtl

/**
 * The Outpost tab: a community board built on Outpost's signed, replicated objects. It is written to
 * read like a noticeboard, not a network tool — transport and verification detail surfaces only as
 * compact status, and only when it explains why a post is unverified, expired, or unavailable.
 *
 * Navigation lives inside the tab: a board list → a board's posts → (sheets) post detail / compose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutpostScreen(
    vm: ChatViewModel,
    onOpenConversation: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    // MVP: a single board, the Public board, which every user is auto-subscribed to. No board list,
    // no discovery, no manual sync — the tab opens straight onto the community feed.
    val board by vm.outpost.publicBoard.collectAsState()
    // A warm StateFlow held in the controller: already computed by the time the tab opens, so there's
    // no cold re-derivation per visit. null = first derivation not finished → render nothing rather
    // than flashing the empty state.
    val rawPosts by vm.outpost.publicPosts.collectAsState()

    var composing by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<OutpostPost?>(null) }
    val canPublish = remember { vm.outpost.canPublish() }

    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var hideClosedExpired by rememberSaveable { mutableStateOf(true) }
    val category = categoryName?.let { n -> runCatching { ExchangeCategory.valueOf(n) }.getOrNull() }

    val loading = board == null || rawPosts == null
    val posts = rawPosts.orEmpty()

    // Filter options surface only when relevant (the "lazy" rule): the hide toggle only when there is
    // something closed/expired to hide; category chips only for types actually present.
    val hasClosedOrExpired = remember(posts) {
        posts.any { it.status == OutpostPostStatus.EXPIRED || it.status == OutpostPostStatus.UNAVAILABLE }
    }
    val base = remember(posts, hideClosedExpired) {
        if (hideClosedExpired) posts.filter { it.status != OutpostPostStatus.EXPIRED && it.status != OutpostPostStatus.UNAVAILABLE }
        else posts
    }
    val categoryOptions = remember(base) { base.mapNotNull { it.category }.distinct().sortedBy { it.ordinal } }
    val shown = remember(base, category, query) {
        var l = base
        category?.let { c -> l = l.filter { it.category == c } }
        val q = query.trim().lowercase()
        if (q.isNotEmpty()) l = l.filter {
            it.title.lowercase().contains(q) || it.description.lowercase().contains(q) ||
                it.authorName.lowercase().contains(q) || it.priceLabel.lowercase().contains(q)
        }
        l
    }
    val showFilters = posts.isNotEmpty() && (categoryOptions.isNotEmpty() || hasClosedOrExpired)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    if (searching) SearchField(query, { query = it }, "Search this board")
                    else Text("Outpost")
                },
                actions = {
                    // Same order as Chats: status chip, then search, then the overflow menu. The wide
                    // status chip and the menu hide while searching so the field has room.
                    if (!searching) ConnectionStatusButton(vm)
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = if (searching) "Close search" else "Search",
                        )
                    }
                    if (!searching) OverflowMenu(onOpenSettings = onOpenSettings, onOpenAbout = onOpenAbout)
                },
            )
        },
        floatingActionButton = {
            if (canPublish && board != null) FloatingActionButton(onClick = { composing = true }) {
                Icon(Icons.Default.Add, contentDescription = "New post")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (showFilters) {
                OutpostFilterRow(
                    hasClosedOrExpired = hasClosedOrExpired,
                    hideClosedExpired = hideClosedExpired,
                    onToggleHide = { hideClosedExpired = !hideClosedExpired },
                    categoryOptions = categoryOptions,
                    selected = category,
                    onSelect = { categoryName = it?.name },
                )
            }
            when {
                loading -> Box(Modifier.fillMaxSize()) {}
                posts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyHint(
                        icon = Icons.Default.Storefront,
                        title = "No posts yet",
                        body = "Be the first to post on the Public board — share an offer, a wanted item, a service, or a heads-up with people nearby. Posts from peers appear here as they arrive.",
                    )
                }
                shown.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyHint(
                        icon = Icons.Default.Storefront,
                        title = "Nothing matches",
                        body = "No posts match your search or filters. Try clearing them.",
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.objectIdHex }) { p ->
                        val nodeHex = p.authorPubKeyHex?.take(20)
                        PostCard(
                            p,
                            onClick = { detail = p },
                            onAuthor = nodeHex?.let { hex -> { onOpenProfile(hex) } },
                        )
                    }
                }
            }
        }
    }

    val activeBoard = board
    if (composing && activeBoard != null) {
        ComposeSheet(
            onDismiss = { composing = false },
            onPublish = { cat, desc, price, currency, ttl ->
                vm.outpost.publish(activeBoard.boardId, cat, desc, price, currency, ttl)
                composing = false
            },
        )
    }

    detail?.let { p ->
        PostDetailSheet(
            post = p,
            onDismiss = { detail = null },
            onContact = {
                detail = null
                p.authorPubKeyHex?.let { onOpenConversation(it.take(20)) }
            },
            onProfile = {
                detail = null
                p.authorPubKeyHex?.let { onOpenProfile(it.take(20)) }
            },
            onClose = { reason ->
                vm.outpost.close(p.listingKey, reason, "")
                detail = null
            },
        )
    }
}

@Composable
private fun PostCard(p: OutpostPost, onClick: () -> Unit, onAuthor: (() -> Unit)?) {
    val dimmed = p.status != OutpostPostStatus.VERIFIED
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (dimmed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                p.category?.let { CategoryChip(it) }
                Spacer(Modifier.weight(1f))
                StatusChip(p.status)
            }
            Spacer(Modifier.size(8.dp))
            Text(
                p.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
            )
            if (p.priceLabel.isNotBlank()) {
                Spacer(Modifier.size(2.dp))
                Text(p.priceLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.size(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AuthorRow(p, onClick = onAuthor, modifier = Modifier.weight(1f))
                Text(
                    relativeTime(p.createdAt * 1000),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The post author shown the way people appear everywhere else in the app: an avatar, the resolved
 * name, and the public key as `<a45ede...ee4a34>` below it, tappable through to the profile. When the
 * author reference can't be tied to any known identity, it reads "Unknown author" over the 10-byte
 * reference, and isn't tappable.
 */
@Composable
private fun AuthorRow(p: OutpostPost, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val name = when {
        p.isMine -> "You"
        p.authorPubKeyHex != null -> p.authorName
        else -> "Unknown author"
    }
    // Prefer the full resolved key; fall back to the 10-byte author reference for an unknown author.
    val keyHex = p.authorPubKeyHex ?: p.authorRefHex
    Row(
        modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = keyHex, label = name, size = 32, identiconKey = p.authorPubKeyHex)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                formatPubKey(keyHex),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ---- post detail ----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostDetailSheet(
    post: OutpostPost,
    onDismiss: () -> Unit,
    onContact: () -> Unit,
    onProfile: () -> Unit,
    onClose: (CloseReason) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmClose by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                post.category?.let { CategoryChip(it) }
                Spacer(Modifier.weight(1f))
                StatusChip(post.status)
            }
            Text(post.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (post.priceLabel.isNotBlank()) {
                Text(post.priceLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (post.description.isNotBlank() && post.description != post.title) {
                Text(post.description, style = MaterialTheme.typography.bodyMedium)
            }

            StatusExplanation(post)

            HorizontalDivider()

            // Author (avatar + name + key, tappable through to the profile) and the post's timing.
            AuthorRow(post, onClick = post.authorPubKeyHex?.let { { onProfile() } })
            Text(
                "Posted ${relativeTime(post.createdAt * 1000)} · expires ${relativeTime(post.expiresAt * 1000)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!post.isMine && post.status != OutpostPostStatus.UNRESOLVED) {
                if (post.authorIsContact) {
                    Button(onClick = onContact, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Message author")
                    }
                } else if (post.authorPubKeyHex != null) {
                    OutlinedButton(onClick = onProfile, modifier = Modifier.fillMaxWidth()) {
                        Text("View author")
                    }
                }
            }

            if (post.isMine && post.status == OutpostPostStatus.VERIFIED) {
                OutlinedButton(onClick = { confirmClose = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.RemoveCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Close listing")
                }
            }
        }
    }

    if (confirmClose) {
        ModalBottomSheet(onDismissRequest = { confirmClose = false }) {
            Column(Modifier.fillMaxWidth().padding(20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Close this listing as…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Closing publishes a signed update so peers stop showing it. It can't force every device to erase a copy already received.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                listOf(CloseReason.SOLD, CloseReason.FULFILLED, CloseReason.WITHDRAWN, CloseReason.CLOSED).forEach { r ->
                    OutlinedButton(onClick = { confirmClose = false; onClose(r) }, modifier = Modifier.fillMaxWidth()) {
                        Text(r.label)
                    }
                }
            }
        }
    }
}

/** A one-line, human explanation of the post's state — the only place protocol detail leaks through. */
@Composable
private fun StatusExplanation(post: OutpostPost) {
    val (color, text) = when (post.status) {
        OutpostPostStatus.VERIFIED ->
            MaterialTheme.colorScheme.primary to "Signature verified — this post genuinely comes from ${if (post.isMine) "you" else post.authorName}."
        OutpostPostStatus.UNRESOLVED ->
            MaterialTheme.colorScheme.tertiary to "Waiting to confirm the author's identity. Sync with a peer who knows them to verify this post."
        OutpostPostStatus.EXPIRED ->
            MaterialTheme.colorScheme.onSurfaceVariant to "This post has passed its expiry and is kept only briefly."
        OutpostPostStatus.UNAVAILABLE ->
            MaterialTheme.colorScheme.onSurfaceVariant to "The author marked this ${post.closeReason?.label?.lowercase() ?: "closed"}."
    }
    Row(verticalAlignment = Alignment.Top) {
        Icon(statusIcon(post.status), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
    if (post.conflicted) {
        Text(
            "Two conflicting signed versions exist for this listing.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// ---- compose --------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ComposeSheet(
    onDismiss: () -> Unit,
    onPublish: (ExchangeCategory, String, Long, String, OutpostTtl) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember { mutableStateOf(ExchangeCategory.OFFER) }
    var description by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("CZK") }
    var ttl by remember { mutableStateOf(OutpostTtl.D7) }

    // The whole object must fit in 160 bytes; the description shares ~66 payload bytes with the
    // category/price header, so we cap it and show how much room is left.
    val descLimit = 58
    val priced = category != ExchangeCategory.FREE && category != ExchangeCategory.HELP

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("New post", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

            SectionLabel("Type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExchangeCategory.entries.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
                }
            }

            OutlinedTextField(
                value = description,
                onValueChange = { if (it.toByteArray(Charsets.UTF_8).size <= descLimit) description = it },
                label = { Text("Description") },
                placeholder = { Text("e.g. Heltec V3, good condition, Praha") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                supportingText = { Text("${descLimit - description.toByteArray(Charsets.UTF_8).size} characters left") },
            )

            if (priced) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { t -> priceText = t.filter { it.isDigit() }.take(7) },
                        label = { Text("Price") },
                        placeholder = { Text("0 = negotiable") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().filter { c -> c in 'A'..'Z' }.take(3) },
                        label = { Text("Cur.") },
                        modifier = Modifier.width(96.dp),
                    )
                }
            }

            SectionLabel("Visible for")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(OutpostTtl.D1, OutpostTtl.D3, OutpostTtl.D7, OutpostTtl.D14, OutpostTtl.D30).forEach { t ->
                    FilterChip(selected = ttl == t, onClick = { ttl = t }, label = { Text(t.label) })
                }
            }

            Text(
                "Posts are signed with your identity and replicated by peers. Anyone on this board can read and keep them, even after expiry — don't include precise location or anything sensitive.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    val price = priceText.toLongOrNull() ?: 0L
                    onPublish(category, description.trim(), if (priced) price else 0L, if (priced) currency else "", ttl)
                },
                enabled = description.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Publish") }
        }
    }
}

// ---- filters --------------------------------------------------------------

/**
 * A compact, horizontally-scrolling filter row in the Explore style. It is rendered only when there
 * is something to filter; within it, each control appears lazily: the "Hide closed & expired" toggle
 * only when such posts exist, and a category chip only for categories actually present.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutpostFilterRow(
    hasClosedOrExpired: Boolean,
    hideClosedExpired: Boolean,
    onToggleHide: () -> Unit,
    categoryOptions: List<ExchangeCategory>,
    selected: ExchangeCategory?,
    onSelect: (ExchangeCategory?) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Type filters first — each chip toggles its own category (unselected = outline, selected =
        // filled). No "All" chip: clearing the active type is the off state.
        categoryOptions.forEach { c ->
            FilterChip(
                selected = selected == c,
                onClick = { onSelect(if (selected == c) null else c) },
                label = { Text(c.label) },
            )
        }
        // …and the compact icon-only "hide closed & expired" toggle at the end, active by default.
        if (hasClosedOrExpired) {
            FilterChip(
                selected = hideClosedExpired,
                onClick = onToggleHide,
                label = {
                    Icon(
                        if (hideClosedExpired) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = "Hide closed & expired",
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
    }
}

// ---- small shared pieces --------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CategoryChip(category: ExchangeCategory) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(category.label) },
        colors = AssistChipDefaults.assistChipColors(
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            disabledLabelColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun StatusChip(status: OutpostPostStatus) {
    val (color, label) = when (status) {
        OutpostPostStatus.VERIFIED -> MaterialTheme.colorScheme.primary to "Verified"
        OutpostPostStatus.UNRESOLVED -> MaterialTheme.colorScheme.tertiary to "Unverified"
        OutpostPostStatus.EXPIRED -> MaterialTheme.colorScheme.onSurfaceVariant to "Expired"
        OutpostPostStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant to "Unavailable"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(statusIcon(status), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

private fun statusIcon(status: OutpostPostStatus) = when (status) {
    OutpostPostStatus.VERIFIED -> Icons.Default.CheckCircle
    OutpostPostStatus.UNRESOLVED -> Icons.Default.HourglassEmpty
    OutpostPostStatus.EXPIRED -> Icons.AutoMirrored.Filled.HelpOutline
    OutpostPostStatus.UNAVAILABLE -> Icons.Default.RemoveCircleOutline
}

@Composable
private fun EmptyHint(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Compact relative time, e.g. "3m ago", "in 6d". Works for past and future instants (ms). */
private fun relativeTime(epochMs: Long): String {
    if (epochMs <= 0) return "unknown"
    val deltaMs = epochMs - System.currentTimeMillis()
    val future = deltaMs > 0
    val s = kotlin.math.abs(deltaMs) / 1000
    val text = when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> "${s / 3600}h"
        else -> "${s / 86400}d"
    }
    return when {
        text == "just now" -> text
        future -> "in $text"
        else -> "$text ago"
    }
}
