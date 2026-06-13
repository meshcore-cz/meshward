package cz.meshcore.meshward.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cz.meshcore.meshward.ChatViewModel
import cz.meshcore.sidepath.topology.TopologyGraph
import cz.meshcore.sidepath.topology.TopologyNode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * Force-directed visualisation of the mesh, rendered by a fully offline WebView ([topology.html] +
 * the bundled force-graph.min.js in assets). Kotlin pushes the topology as JSON; tapping a node
 * raises a native bottom sheet. Empty/loading states are drawn in Compose over the WebView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TopologyScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val graph by vm.topologyGraph.collectAsState()
    var ready by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    val colors = MaterialTheme.colorScheme
    val themeJson = remember(colors) {
        fun hex(c: androidx.compose.ui.graphics.Color) = String.format("#%06X", 0xFFFFFF and c.toArgb())
        JSONObject().apply {
            put("node", hex(colors.onSurfaceVariant))
            put("primary", hex(colors.primary))
            put("text", hex(colors.onSurface))
            put("link", hex(colors.outlineVariant))
            put("linkStrong", hex(colors.primary))
        }.toString()
    }

    // Push the current graph whenever it changes once the page is ready. collectLatest + a small
    // delay debounces bursts (a newer snapshot cancels the pending push) — this is the throttle.
    LaunchedEffectReady(ready) {
        val wv = webView ?: return@LaunchedEffectReady
        wv.evaluateJavascript("window.setTheme(${JSONObject.quote(themeJson)});", null)
        var lastJson: String? = null
        vm.topologyGraph.collectLatest { g ->
            delay(300)
            val json = topologyGraphJson(g)
            if (json != lastJson) {
                lastJson = json
                wv.evaluateJavascript("window.setGraph(${JSONObject.quote(json)});", null)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Topology") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // No network is ever loaded — assets are local-only.
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) { ready = true }
                        }
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface fun onNodeClick(id: String) { post { selected = id } }
                                @JavascriptInterface fun onReady() { post { ready = true } }
                            },
                            "AndroidBridge",
                        )
                        loadUrl("file:///android_asset/topology.html")
                        webView = this
                    }
                },
            )

            when {
                !ready -> LoadingOverlay()
                graph.hasOnlySelf() -> EmptyOverlay()
            }
        }
    }

    selected?.let { id ->
        val node = graph.nodes.firstOrNull { it.id == id }
        if (node != null) {
            NodeDetailSheet(
                vm = vm,
                node = node,
                graph = graph,
                onDismiss = { selected = null },
                onOpenProfile = {
                    selected = null
                    onOpenProfile(id)
                },
            )
        }
    }
}

@Composable
private fun LoadingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Building topology…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EmptyOverlay() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.Hub,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("No topology discovered yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Once other nodes are heard on the mesh, they'll appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeDetailSheet(
    vm: ChatViewModel,
    node: TopologyNode,
    graph: TopologyGraph,
    onDismiss: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Neighbors = every other endpoint of a link touching this node (either direction).
    val neighbors = remember(node.id, graph) {
        graph.links.mapNotNull { l ->
            when (node.id) {
                l.source -> l.target
                l.target -> l.source
                else -> null
            }
        }.distinct()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                node.name.ifBlank { node.id.take(12) },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (node.isLocal) {
                Text("This device", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            DetailRow("Node ID", shortId(node.id))
            DetailRow("Last seen", if (node.isLocal) "now" else formatMessageTime(node.lastSeenMs))
            DetailRow("Neighbors", "${neighbors.size}")
            if (neighbors.isNotEmpty()) {
                Text(
                    "Connected to",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                neighbors.forEach { nb ->
                    Text(
                        vm.nameForHex(nb).ifBlank { shortId(nb) },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (!node.isLocal) {
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenProfile,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open profile") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Spacer(Modifier.width(8.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

/** Shorten a 20-char NodeID hex to "aaaa…zzzz" for compact display. */
private fun shortId(hex: String): String =
    if (hex.length <= 12) hex else "${hex.take(8)}…${hex.takeLast(4)}"

/** Serialise the graph to the JSON shape consumed by topology.html's window.setGraph. */
private fun topologyGraphJson(g: TopologyGraph): String {
    val nodes = JSONArray()
    for (n in g.nodes) {
        nodes.put(
            JSONObject()
                .put("id", n.id)
                .put("name", n.name)
                .put("isLocal", n.isLocal)
                .put("lastSeenMs", n.lastSeenMs)
                .put("neighborCount", n.neighborCount),
        )
    }
    val links = JSONArray()
    for (l in g.links) {
        links.put(
            JSONObject()
                .put("source", l.source)
                .put("target", l.target)
                .put("rssi", l.rssi)
                .put("lastSeenMs", l.lastSeenMs)
                .put("bidirectional", l.bidirectional),
        )
    }
    return JSONObject()
        .put("now", System.currentTimeMillis())
        .put("nodes", nodes)
        .put("links", links)
        .toString()
}

/**
 * A LaunchedEffect keyed on [ready] that only runs its body when ready is true — restarting (and
 * thus re-pushing the latest StateFlow value) the moment the WebView page finishes loading.
 */
@Composable
private fun LaunchedEffectReady(ready: Boolean, block: suspend () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(ready) {
        if (ready) block()
    }
}
