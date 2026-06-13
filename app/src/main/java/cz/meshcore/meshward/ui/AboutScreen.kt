package cz.meshcore.meshward.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import cz.meshcore.meshward.R

private data class LicenseItem(
    val name: String,
    val license: String,
    val detail: String,
)

private data class SourceLink(
    val label: String,
    val url: String,
)

private val licenses = listOf(
    LicenseItem("AndroidX Core, Activity, Lifecycle, DataStore", "Apache License 2.0", "Jetpack runtime and app infrastructure."),
    LicenseItem("Jetpack Compose, Material 3, Material Icons", "Apache License 2.0", "Declarative Android UI toolkit and icon set."),
    LicenseItem("Room", "Apache License 2.0", "Local chat database and compiler."),
    LicenseItem("Kotlin", "Apache License 2.0", "Language, compiler plugin, and standard tooling."),
    LicenseItem("Android Gradle Plugin", "Apache License 2.0", "Android build system integration."),
    LicenseItem("ZXing Core", "Apache License 2.0", "QR code encoding and decoding."),
    LicenseItem("JourneyApps ZXing Android Embedded", "Apache License 2.0", "Android QR scanner integration."),
    LicenseItem("Bouncy Castle Provider", "Bouncy Castle Licence", "Ed25519, X25519, HKDF, and AES-GCM cryptography primitives."),
    LicenseItem("CBOR-Java by Peter Occil", "CC0-1.0", "Concise Binary Object Representation codec."),
)

private val sourceLinks = listOf(
    SourceLink("Meshward repository", "https://github.com/meshcore-cz/meshward"),
    SourceLink("Sidepath Protocol repository", "https://github.com/meshcore-cz/sidepath-protocol"),
    SourceLink("Sidepath protocol specification", "https://github.com/meshcore-cz/sidepath-protocol/blob/main/docs/PROTOCOL.md"),
    SourceLink("Sidepath chat payload specification", "https://github.com/meshcore-cz/sidepath-protocol/blob/main/docs/CHAT_PROTOCOL.md"),
    SourceLink("MeshCore project", "https://github.com/meshcore-dev"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionName = packageInfo.versionName ?: "unknown"
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.meshward_logo),
                contentDescription = null,
                modifier = Modifier.size(132.dp),
            )
            Text(
                "Meshward",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Local-first BLE mesh chat and diagnostics.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            InfoCard("App") {
                InfoRow("Version", "$versionName ($versionCode)")
                InfoRow("Package", context.packageName)
                InfoRow("Android", "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            }

            InfoCard("Authors") {
                Text(
                    "Created by burningtree and Sidepath contributors.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Sidepath experiments with phone-to-phone and microcontroller BLE mesh routing, encrypted direct messages, group channels, trace diagnostics, and long-range relay workflows.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InfoCard("Sidepath Protocol") {
                Text(
                    "Sidepath is the transport layer underneath Meshward. It carries opaque payloads across nearby phones, relays, and microcontrollers over Bluetooth LE, handles datagram routing, deduplication, topology, trace diagnostics, and optional long-range PHY modes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Meshward is the chat application and payload protocol built on top of Sidepath.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            InfoCard("Sources") {
                sourceLinks.forEachIndexed { index, link ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    LinkRow(link.label, link.url)
                }
            }

            InfoCard("Software Licences") {
                licenses.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(item.license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(label: String, url: String) {
    val context = LocalContext.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (label == "Package") FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.65f),
        )
    }
}
