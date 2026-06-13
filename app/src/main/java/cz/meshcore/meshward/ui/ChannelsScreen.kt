package cz.meshcore.meshward.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.MeshCoreUri
import cz.meshcore.meshward.isHex32
import cz.meshcore.meshward.toHex
import java.security.SecureRandom

/**
 * Bottom sheet for joining a channel (Public / Named / Secret). Shared by the merged Chats "+"
 * chooser. (The standalone Channels tab was replaced by Explore; this sheet lives on.)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinChannelSheet(
    vm: ChatViewModel,
    showPublic: Boolean,
    onJoined: () -> Unit,
    onDismiss: () -> Unit,
) {
    var namedName by remember { mutableStateOf("") }
    var secretName by remember { mutableStateOf("") }
    var secretValue by remember { mutableStateOf("") }

    // Scan a MeshCore QR; a channel URI pre-fills the secret-channel fields.
    val scan = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@rememberLauncherForActivityResult
        MeshCoreUri.parseChannel(contents)?.let { ch ->
            secretName = ch.name
            secretValue = ch.secretHex
        }
    }
    fun launchScan() = scan.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setPrompt("Scan a MeshCore channel QR"),
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Join channel", style = MaterialTheme.typography.titleMedium)
                // Scan affordance, top-right of the title.
                IconButton(onClick = { launchScan() }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan MeshCore QR")
                }
            }

            // Public — only offered when not already joined.
            if (showPublic) {
                Text("Public", style = MaterialTheme.typography.labelLarge)
                Text(
                    "MeshCore's default public channel (hash 0x11).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { vm.joinPublic(); onJoined() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Join Public")
                }
                HorizontalDivider()
            }

            // Named (public hash, derived from name)
            Text("Named channel (hash channels)", style = MaterialTheme.typography.labelLarge)
            Text(
                "Anyone who knows the name joins the same channel (key = SHA-256(name)).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = namedName,
                onValueChange = { namedName = it },
                label = { Text("Channel name") },
                prefix = { Text("#") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { namedName = randomChannelName() }) {
                        Icon(Icons.Default.Casino, contentDescription = "Suggest a name")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.joinNamedChannel(namedName); onJoined() },
                enabled = namedName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join named channel") }

            HorizontalDivider()

            // Secret — a raw 16-byte PSK (32 hex chars), shared out-of-band or scanned.
            Text("Secret channel", style = MaterialTheme.typography.labelLarge)
            Text(
                "Share the 32-hex-character key out-of-band, or scan a MeshCore QR.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = secretName,
                onValueChange = { secretName = it },
                label = { Text("Channel name") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { secretName = randomChannelName() }) {
                        Icon(Icons.Default.Casino, contentDescription = "Suggest a name")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            val secretValid = isHex32(secretValue)
            OutlinedTextField(
                value = secretValue,
                onValueChange = { secretValue = it.trim() },
                label = { Text("Secret (32 hex chars)") },
                singleLine = true,
                isError = secretValue.isNotEmpty() && !secretValid,
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    Row {
                        // Generate a fresh random key — tap again for another.
                        IconButton(onClick = { secretValue = randomSecretHex() }) {
                            Icon(Icons.Default.Casino, contentDescription = "Generate random secret")
                        }
                        IconButton(onClick = { launchScan() }) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR")
                        }
                    }
                },
                supportingText = {
                    if (secretValue.isNotEmpty() && !secretValid) Text("Must be exactly 32 hexadecimal characters.")
                    else Text("Tap the dice to generate a new random key.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { vm.joinSecretChannel(secretName, secretValue); onJoined() },
                enabled = secretValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join secret channel") }
        }
    }
}

// Friendly word pool for suggesting channel names (three hyphenated words, e.g. "amber-river-fox").
private val nameWords = listOf(
    "amber", "river", "fox", "echo", "delta", "north", "ember", "lunar", "cedar", "quartz",
    "harbor", "pixel", "cobalt", "maple", "raven", "summit", "tundra", "violet", "willow", "zephyr",
    "comet", "drift", "flint", "glade", "haze", "ivory", "jade", "kelp", "lark", "moss",
    "nova", "onyx", "pine", "reef", "sage", "thorn", "umber", "vapor", "wren", "yarrow",
)

private fun randomChannelName(): String = (1..3).map { nameWords.random() }.joinToString("-")

private fun randomSecretHex(): String =
    ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
