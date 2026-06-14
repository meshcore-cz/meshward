package cz.meshcore.meshward.ui

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.IdentityUi

/**
 * Manage screen for identities (profiles): rename, back up (copy seed), or delete each one, and
 * add or import new identities. The active identity and the last remaining one can't be deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageIdentitiesScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val identities by vm.identities.collectAsState()
    var rename by remember { mutableStateOf<IdentityUi?>(null) }
    var confirmDelete by remember { mutableStateOf<IdentityUi?>(null) }
    var showSeed by remember { mutableStateOf<IdentityUi?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Identities") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add identity")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(identities, key = { it.id }) { id ->
                IdentityManageRow(
                    id = id,
                    nowMs = now,
                    canDelete = !id.isActive && identities.size > 1,
                    onRename = { rename = id },
                    onCopySeed = { showSeed = id },
                    onDelete = { confirmDelete = id },
                )
            }
        }
    }

    rename?.let { id ->
        RenameIdentityDialog(
            current = id.label,
            nameIsCustom = id.nameIsCustom,
            onConfirm = { vm.renameIdentity(id.id, it); rename = null },
            onDismiss = { rename = null },
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete identity?") },
            text = {
                Text(
                    "“${id.label}” and all its chats, contacts, and channels will be permanently " +
                        "deleted from this device. Back up its seed first if you want to restore it later.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteIdentity(id.id); confirmDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    showSeed?.let { id ->
        val clipboard = LocalClipboardManager.current
        val context = LocalContext.current
        val seed = remember(id.id) { vm.seedForIdentity(id.id) }
        AlertDialog(
            onDismissRequest = { showSeed = null },
            title = { Text("Backup seed") },
            text = {
                Column {
                    Text(
                        "Anyone with this seed can restore this identity. Keep it secret.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(12.dp))
                    SelectionContainer {
                        Text(seed, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(seed))
                    Toast.makeText(context, "Seed copied", Toast.LENGTH_SHORT).show()
                    showSeed = null
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Copy")
                }
            },
            dismissButton = { TextButton(onClick = { showSeed = null }) { Text("Close") } },
        )
    }

    if (showAdd) {
        AddIdentityDialog(vm = vm, onDismiss = { showAdd = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityManageRow(
    id: IdentityUi,
    nowMs: Long,
    canDelete: Boolean,
    onRename: () -> Unit,
    onCopySeed: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(seed = id.pubKeyHex.ifBlank { id.id }, label = id.label, identiconKey = id.pubKeyHex)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(id.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (id.isActive) {
                    Text(
                        "Active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val pub = formatPubKey(id.pubKeyHex)
            if (pub.isNotEmpty()) {
                Text(
                    pub,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                if (id.isActive) "Active now"
                else if (id.lastActiveMs > 0) "Last active ${formatRelativeAge(id.lastActiveMs, nowMs)}"
                else "Never used",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = { menu = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Backup seed") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = { menu = false; onCopySeed() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                enabled = canDelete,
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun RenameIdentityDialog(current: String, nameIsCustom: Boolean, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    // A custom name prefills the field (editable/deletable); a derived name shows as a placeholder
    // over an empty field so the user types fresh.
    var text by remember { mutableStateOf(if (nameIsCustom) current else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename identity") },
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
