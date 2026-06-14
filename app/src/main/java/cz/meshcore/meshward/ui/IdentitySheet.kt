package cz.meshcore.meshward.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel

/**
 * The avatar chooser: lists every identity (profile) with the active one checked, and footer
 * actions to add a new identity, manage existing ones, or open the current profile. Tapping an
 * inactive identity switches to it (which relaunches the app).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentitySheet(
    vm: ChatViewModel,
    onAddIdentity: () -> Unit,
    onManage: () -> Unit,
    onMyProfile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val identities by vm.identities.collectAsState()
    val now = System.currentTimeMillis()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                "Identities",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            identities.forEach { id ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            if (!id.isActive) vm.switchIdentity(id.id)
                            onDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(seed = id.pubKeyHex.ifBlank { id.id }, label = id.label, identiconKey = id.pubKeyHex)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            id.label,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (id.isActive) "Active now"
                            else if (id.lastActiveMs > 0) "Last active ${formatRelativeAge(id.lastActiveMs, now)}"
                            else "Never used",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (id.isActive) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            IdentityActionRow(Icons.Default.PersonAdd, "Add identity") {
                onDismiss(); onAddIdentity()
            }
            IdentityActionRow(Icons.Default.ManageAccounts, "Manage identities") {
                onDismiss(); onManage()
            }
            IdentityActionRow(Icons.Default.Person, "My profile") {
                onDismiss(); onMyProfile()
            }
        }
    }
}

@Composable
private fun IdentityActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(title, fontWeight = FontWeight.Medium)
    }
}
