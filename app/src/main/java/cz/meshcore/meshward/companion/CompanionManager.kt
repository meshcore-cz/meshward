package cz.meshcore.meshward.companion

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hoho.android.usbserial.driver.UsbSerialProber
import cz.meshcore.meshward.dataStore
import cz.meshcore.sidepath.protocol.BridgeAd
import cz.meshcore.sidepath.service.SidepathService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val COMPANIONS_KEY = stringPreferencesKey("meshcore_companions")

/** An attached USB serial device offered in the add-companion picker. */
data class CompanionUsbDevice(val key: String, val name: String, val vendorId: Int, val productId: Int)

/**
 * Owns all locally-attached MeshCore companions. Supports *N* radios (each its own
 * [CompanionConnection] keyed by BLE address, with independent config / bridge / stats) while the
 * common single-companion case needs no extra ceremony.
 *
 * Bridge wiring:
 *  - **inbound** (radio → mesh): each connection reports raw OTA packets it heard; the manager
 *    injects them onto the Sidepath mesh via [SidepathService.injectMeshCoreFromRadio], tagged with
 *    the companion's effective network code.
 *  - **outbound** (mesh → radio): the manager registers the *single*
 *    [SidepathService.meshCoreRadioSink] and fans each packet out to every bridge-enabled, connected
 *    companion (each connection suppresses echoing a packet it just heard).
 *
 * Owned by the ChatViewModel; pass its `viewModelScope`.
 */
class CompanionManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val connections = LinkedHashMap<String, CompanionConnection>()

    private val _companions = MutableStateFlow<List<CompanionState>>(emptyList())
    val companions: StateFlow<List<CompanionState>> = _companions.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _scanResults = MutableStateFlow<List<CompanionScanResult>>(emptyList())
    val scanResults: StateFlow<List<CompanionScanResult>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    @Volatile private var service: SidepathService? = null
    private val activeNetworkCode = MutableStateFlow("")

    private val scanner = CompanionBleScanner(context)

    init {
        scope.launch {
            val saved = loadConfigs()
            saved.forEach { cfg ->
                val conn = createConnection(cfg)
                connections[cfg.address] = conn
                if (cfg.enabled) conn.connect()
            }
            refreshAggregate()
        }
    }

    // ---- external wiring (from ChatViewModel) ----

    /** Attach (or detach with null) the running Sidepath service and register the outbound sink. */
    fun attachService(svc: SidepathService?) {
        service?.meshCoreRadioSink = null
        service = svc
        svc?.meshCoreRadioSink = { raw -> fanOutToRadios(raw) }
        pushBridges()
    }

    /** The app's active MeshCore network code, used to tag bridged packets when no per-companion override. */
    fun setActiveNetworkCode(code: String) {
        if (activeNetworkCode.value == code) return
        activeNetworkCode.value = code
        pushBridges()
    }

    // ---- user actions ----

    /** Begin scanning; results accumulate in [scanResults]. Already-known companions are excluded. */
    fun startScan() {
        _scanResults.value = emptyList()
        _scanning.value = true
        scanner.start { result ->
            if (!connections.containsKey(result.address) && _scanResults.value.none { it.address == result.address }) {
                _scanResults.update { it + result }
            }
        }
    }

    fun stopScan() {
        scanner.stop()
        _scanning.value = false
    }

    /** Add a newly-picked BLE companion (by MAC) and connect to it. */
    fun addAndConnect(address: String, label: String = "") =
        add(CompanionConfig(address = address, transport = CompanionTransportKind.BLE, endpoint = address, label = label))

    /** Add a TCP companion at `host:port` and connect. */
    fun addTcp(host: String, port: Int, label: String = "") {
        val endpoint = "$host:$port"
        add(CompanionConfig(address = "tcp:$endpoint", transport = CompanionTransportKind.TCP, endpoint = endpoint, label = label))
    }

    /** Add a USB companion (by its `usb:vid:pid` key) and connect. */
    fun addUsb(key: String, label: String = "") =
        add(CompanionConfig(address = key, transport = CompanionTransportKind.USB, endpoint = key, label = label))

    private fun add(cfg: CompanionConfig) {
        if (connections.containsKey(cfg.address)) {
            connect(cfg.address)
            return
        }
        val conn = createConnection(cfg)
        connections[cfg.address] = conn
        conn.connect()
        persistAndRefresh()
    }

    /** Attached USB devices that have a recognised serial driver, for the add-USB picker. */
    fun usbDevices(): List<CompanionUsbDevice> {
        val mgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()
        val prober = UsbSerialProber.getDefaultProber()
        return mgr.deviceList.values
            .filter { prober.probeDevice(it) != null }
            .map { d ->
                CompanionUsbDevice(
                    key = usbDeviceKey(d),
                    name = (runCatching { d.productName }.getOrNull() ?: "").ifBlank { "USB ${d.vendorId}:${d.productId}" },
                    vendorId = d.vendorId,
                    productId = d.productId,
                )
            }
    }

    fun connect(address: String) {
        connections[address]?.let {
            it.updateConfig { c -> c.copy(enabled = true) }
            it.connect()
        }
        persistAndRefresh()
    }

    fun disconnect(address: String) {
        connections[address]?.let {
            it.updateConfig { c -> c.copy(enabled = false) }
            it.disconnect()
        }
        persistAndRefresh()
    }

    fun forget(address: String) {
        connections.remove(address)?.close()
        persistAndRefresh()
    }

    fun setBridgeEnabled(address: String, enabled: Boolean) {
        connections[address]?.updateConfig { it.copy(bridgeEnabled = enabled) }
        persistAndRefresh()
    }

    fun setLabel(address: String, label: String) {
        connections[address]?.updateConfig { it.copy(label = label) }
        persistAndRefresh()
    }

    fun setNetworkOverride(address: String, code: String) {
        connections[address]?.updateConfig { it.copy(networkCodeOverride = code) }
        persistAndRefresh()
    }

    /** Disconnect everything (called when the ViewModel/service tears down). */
    fun shutdown() {
        service?.meshCoreRadioSink = null
        service = null
        connections.values.forEach { it.close() }
        scanner.stop()
    }

    // ---- bridge plumbing ----

    private fun createConnection(cfg: CompanionConfig): CompanionConnection =
        CompanionConnection(
            context = context,
            scope = scope,
            initialConfig = cfg,
            onRadioRx = { conn, raw -> service?.injectMeshCoreFromRadio(raw, effectiveCode(conn)) },
            onLog = { msg -> appendLog("${cfg.address.takeLast(5)}: $msg") },
        ).also { conn ->
            // Mirror each connection's live state into the aggregate and persist config changes.
            scope.launch { conn.state.collect { refreshAggregate() } }
        }

    private fun effectiveCode(conn: CompanionConnection): String =
        conn.config.networkCodeOverride.ifBlank { activeNetworkCode.value }

    /** Outbound sink: put a mesh-sourced packet on every bridge-enabled, connected radio. */
    private fun fanOutToRadios(rawOta: ByteArray) {
        connections.values.forEach { conn ->
            if (conn.config.bridgeEnabled && conn.state.value.link == CompanionLinkState.READY) {
                conn.transmit(rawOta)
            }
        }
    }

    private fun refreshAggregate() {
        _companions.value = connections.values.map { it.state.value }
        pushBridges()
    }

    /**
     * Advertise the networks our live bridges serve in the Sidepath ANNOUNCE. One [BridgeAd] per
     * distinct effective network code across connected, bridge-enabled companions; the device's own
     * radio params ride along as a custom entry when known and valid, else the code alone (the peer
     * resolves canonical params from its dataset). The service dedupes unchanged sets.
     */
    private fun pushBridges() {
        val svc = service ?: return
        val byCode = LinkedHashMap<String, BridgeAd>()
        connections.values.forEach { conn ->
            val st = conn.state.value
            if (!conn.config.bridgeEnabled || st.link != CompanionLinkState.READY) return@forEach
            val code = effectiveCode(conn)
            if (code.isBlank() || byCode.containsKey(code)) return@forEach
            val si = st.selfInfo
            val custom = si?.let {
                BridgeAd(
                    code = code,
                    freqHz = it.radioFreqKHz * 1000,
                    bandwidthHz = it.radioBwKHz * 1000,
                    sf = it.radioSf,
                    cr = it.radioCr,
                )
            }
            val ad = if (custom != null && custom.isValid()) custom else BridgeAd(code)
            if (ad.isValid()) byCode[code] = ad
        }
        svc.setBridgedNetworks(byCode.values.toList())
    }

    private fun persistAndRefresh() {
        refreshAggregate()
        scope.launch { saveConfigs(connections.values.map { it.config }) }
    }

    private fun appendLog(line: String) {
        _log.update { (listOf("${System.currentTimeMillis()} $line") + it).take(200) }
    }

    // ---- persistence (DataStore JSON) ----

    private suspend fun loadConfigs(): List<CompanionConfig> {
        val raw = context.dataStore.data.first()[COMPANIONS_KEY] ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CompanionConfig(
                    address = o.getString("address"),
                    transport = runCatching { CompanionTransportKind.valueOf(o.optString("transport", "BLE")) }
                        .getOrDefault(CompanionTransportKind.BLE),
                    endpoint = o.optString("endpoint", ""),
                    label = o.optString("label", ""),
                    enabled = o.optBoolean("enabled", true),
                    bridgeEnabled = o.optBoolean("bridge", true),
                    networkCodeOverride = o.optString("network", ""),
                )
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun saveConfigs(configs: List<CompanionConfig>) {
        val arr = JSONArray()
        configs.forEach { c ->
            arr.put(
                JSONObject()
                    .put("address", c.address)
                    .put("transport", c.transport.name)
                    .put("endpoint", c.endpoint)
                    .put("label", c.label)
                    .put("enabled", c.enabled)
                    .put("bridge", c.bridgeEnabled)
                    .put("network", c.networkCodeOverride),
            )
        }
        context.dataStore.edit { it[COMPANIONS_KEY] = arr.toString() }
    }
}
