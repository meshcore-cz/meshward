package cz.meshcore.meshward.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.meshward.LatLon
import cz.meshcore.meshward.parseGeoRings
import kotlin.math.cos

/** Extended overview of a single Meshcore Network: identity, tech parameters, links, and territory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDetailScreen(
    vm: ChatViewModel,
    code: String,
    onBack: () -> Unit,
) {
    val networks by vm.networks.collectAsState()
    val activeCode by vm.activeNetworkCode.collectAsState()
    val net = networks.firstOrNull { it.code == code }
    val isActive = code == activeCode

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(net?.name ?: "Network") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (net == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Network not found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NetworkCodeChip(net.code)
                        Text(net.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    if (net.description.isNotBlank()) {
                        Text(net.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (isActive) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("Active network", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Button(onClick = { vm.setActiveNetwork(net.code) }) { Text("Set as active") }
                    }
                }
            }

            // Radio parameters (display only)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Radio parameters", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    ParamLine("Frequency", net.freqMhz.takeIf { it != 0.0 }?.let { "%.3f MHz".format(it) })
                    ParamLine("Bandwidth", net.bandwidthKhz.takeIf { it != 0.0 }?.let { "${it.toInt()} kHz" })
                    ParamLine("Spreading factor", net.spreadingFactor.takeIf { it != 0 }?.let { "SF$it" })
                    ParamLine("Coding rate", net.codingRate.takeIf { it != 0 }?.let { "4/$it" })
                    Text(
                        "Shown for reference — these don't reconfigure the connected radio.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Links: packet analyzers and (future) MQTT endpoints
            val analyzerLinks = remember(net.analyzerUrls) { net.analyzerUrls.toLines() }
            val mqttLinks = remember(net.mqttEndpoints) { net.mqttEndpoints.toLines() }
            if (analyzerLinks.isNotEmpty() || mqttLinks.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (analyzerLinks.isNotEmpty()) {
                            Text("Packet analyzers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            analyzerLinks.forEach { LinkRow(it) }
                        }
                        if (mqttLinks.isNotEmpty()) {
                            Spacer(Modifier.size(4.dp))
                            Text("MQTT endpoints", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            mqttLinks.forEach { ep ->
                                Text(ep, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }

            // Territory
            val rings = remember(net.geoJson) { parseGeoRings(net.geoJson) }
            if (rings.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text("Territory", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        TerritoryMap(rings, Modifier.fillMaxWidth().aspectRatio(1.4f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamLine(label: String, value: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value ?: "—", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LinkRow(url: String) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clickable {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            url,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Icon(Icons.Default.OpenInNew, contentDescription = "Open", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/**
 * Draws a network's territory rings offline (no map tiles / no network). Uses an equirectangular
 * projection with longitude scaled by cos(mean latitude) so the shape isn't horizontally stretched,
 * fit to the canvas with padding.
 */
@Composable
private fun TerritoryMap(rings: List<List<LatLon>>, modifier: Modifier = Modifier) {
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val stroke = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    val pts = rings.flatten()
    val minLat = pts.minOf { it.lat }
    val maxLat = pts.maxOf { it.lat }
    val minLon = pts.minOf { it.lon }
    val maxLon = pts.maxOf { it.lon }
    val cosLat = cos(Math.toRadians((minLat + maxLat) / 2.0)).coerceAtLeast(0.01)

    Surface(color = surface, shape = RoundedCornerShape(12.dp), modifier = modifier) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val xrange = ((maxLon - minLon) * cosLat).coerceAtLeast(1e-6)
            val yrange = (maxLat - minLat).coerceAtLeast(1e-6)
            val scale = minOf(size.width / xrange, size.height / yrange).toFloat()
            // Center the projected shape within the canvas.
            val dx = (size.width - (xrange * scale).toFloat()) / 2f
            val dy = (size.height - (yrange * scale).toFloat()) / 2f
            fun project(p: LatLon) = Offset(
                dx + ((p.lon - minLon) * cosLat * scale).toFloat(),
                dy + ((maxLat - p.lat) * scale).toFloat(), // flip Y so north is up
            )
            rings.forEach { ring ->
                if (ring.size < 2) return@forEach
                val path = Path().apply {
                    val first = project(ring.first())
                    moveTo(first.x, first.y)
                    ring.drop(1).forEach { val o = project(it); lineTo(o.x, o.y) }
                    close()
                }
                drawPath(path, color = fill)
                drawPath(path, color = stroke, style = Stroke(width = 2.5f))
            }
        }
    }
}

/** Split a newline-separated stored field into trimmed, non-empty entries. */
private fun String.toLines(): List<String> = lines().map { it.trim() }.filter { it.isNotEmpty() }
