package cz.meshcore.meshward.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.sidepath.service.LogEntry
import cz.meshcore.sidepath.service.LogTag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Tags relevant to BLE connectivity — scans, discovered nodes, connect attempts/failures,
// disconnections, the GATT handshake, and PHY. ROUTER/MSG are about routing & app traffic, so they're
// off by default but still toggleable.
private val CONNECTION_TAGS = listOf(LogTag.SYS, LogTag.SCAN, LogTag.PEER, LogTag.SERVER, LogTag.GATT, LogTag.PHY)
private val DEFAULT_TAGS = setOf(LogTag.SCAN, LogTag.PEER, LogTag.SERVER, LogTag.GATT, LogTag.PHY)

private val logTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionLogScreen(vm: ChatViewModel, onBack: () -> Unit) {
    val log by vm.routingLog.collectAsState()
    var selected by remember { mutableStateOf(DEFAULT_TAGS) }

    // Newest first, filtered to the selected tags.
    val shown = remember(log, selected) {
        log.asReversed().filter { it.tag in selected }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) listState.scrollToItem(0) }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = {
                    Column {
                        Text("Connection log")
                        Text(
                            "${shown.size} of ${log.size} events",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CONNECTION_TAGS.forEach { tag ->
                    val on = tag in selected
                    FilterChip(
                        selected = on,
                        onClick = {
                            selected = if (on) selected - tag else selected + tag
                        },
                        label = { Text(tag.name) },
                    )
                }
            }
            HorizontalDivider()

            if (shown.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text(
                        if (log.isEmpty()) "No connection events yet. Start the mesh to begin scanning."
                        else "No events match the selected filters.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(shown) { entry ->
                        LogRow(entry)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val color = tagColor(entry.tag)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagPill(entry.tag, color)
            Text(
                logTimeFmt.format(Date(entry.timestampMs)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun TagPill(tag: LogTag, color: Color) {
    Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(6.dp)) {
        Text(
            tag.name,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun tagColor(tag: LogTag): Color = when (tag) {
    LogTag.SCAN -> Color(0xFF00838F)
    LogTag.PEER -> Color(0xFF2E7D32)
    LogTag.SERVER -> Color(0xFF6A1B9A)
    LogTag.GATT -> Color(0xFF546E7A)
    LogTag.PHY -> Color(0xFFEF6C00)
    LogTag.ROUTER -> Color(0xFF5E35B1)
    LogTag.MSG -> Color(0xFF1565C0)
    LogTag.SYS -> Color(0xFF757575)
}
