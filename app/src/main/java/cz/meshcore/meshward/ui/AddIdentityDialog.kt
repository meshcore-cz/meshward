package cz.meshcore.meshward.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import cz.meshcore.meshward.ChatViewModel

/**
 * Add-or-import an identity in one modal: paste an existing private key (seed) or tap Generate for a
 * fresh one. The resulting public key is previewed live (identicon + key), so the user sees exactly
 * which identity they'll create. Create makes it active and reloads the app into it. A blank/invalid
 * seed disables Create.
 */
@Composable
fun AddIdentityDialog(vm: ChatViewModel, onDismiss: () -> Unit) {
    var seed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val pubKey = remember(seed) { vm.publicKeyForSeed(seed) }
    val valid = pubKey.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add identity") },
        text = {
            Column {
                Text(
                    "Paste a 64-character private key to import an existing identity, or generate a new one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = seed,
                    onValueChange = { seed = it; error = false },
                    singleLine = true,
                    isError = error,
                    label = { Text("Private key (hex)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { seed = vm.freshSeedHex(); error = false }) {
                    Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Generate new")
                }
                if (error) {
                    Text(
                        "Not a valid 64-character hex key.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (valid) {
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "Public key",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(seed = pubKey, label = "", identiconKey = pubKey, size = 40)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            formatPubKey(pubKey),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { if (vm.importIdentity(seed)) onDismiss() else error = true },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
