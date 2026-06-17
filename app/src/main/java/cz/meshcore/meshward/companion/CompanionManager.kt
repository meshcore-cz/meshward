package cz.meshcore.meshward.companion

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.hoho.android.usbserial.driver.UsbSerialProber
import cz.meshcore.meshward.dataStore
import cz.meshcore.meshward.data.MeshNetwork
import cz.meshcore.sidepath.protocol.BridgeAd
import cz.meshcore.sidepath.service.SidepathService
import kotlin.math.abs
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

/** True when this network defines a full LoRa radio config (so a device can be verified against it). */
fun MeshNetwork.hasRadioParams(): Boolean =
    freqMhz > 0.0 && bandwidthKhz > 0.0 && spreadingFactor > 0 && codingRate > 0

/**
 * Whether a device's live radio params match this network's. Frequency/bandwidth are compared with a
 * small tolerance (the device reports kHz-resolution values), SF/CR exactly.
 */
fun MeshNetwork.matchesDevice(si: CompanionMessage.SelfInfo): Boolean {
    val netFreqHz = (freqMhz * 1_000_000.0).toLong()
    val netBwHz = (bandwidthKhz * 1_000.0).toLong()
    return spreadingFactor == si.radioSf &&
        codingRate == si.radioCr &&
        abs(netFreqHz - si.radioFreqHz) <= 2_000 &&
        abs(netBwHz - si.radioBwHz) <= 1_000
}

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

    /** Known networks (built-in + custom), used to verify a device's radio params in AUTO mode. */
    @Volatile private var networks: List<MeshNetwork> = emptyList()

    private val scanner = CompanionBleScanner(context)

    /** Shared forwarding/dedup rules, ported from the Go bridge (see [MeshCoreBridgeRules]). */
    private val dedup = BridgeDedup()

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

    /** Update the known networks used for AUTO-mode radio verification and "save as network". */
    fun setNetworks(list: List<MeshNetwork>) {
        networks = list
        refreshAggregate()
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

    fun setRadioMode(address: String, mode: CompanionRadioMode) {
        connections[address]?.updateConfig { it.copy(radioMode = mode) }
        persistAndRefresh()
    }

    fun setRadioNetwork(address: String, code: String) {
        connections[address]?.updateConfig { it.copy(radioNetworkCode = code) }
        persistAndRefresh()
    }

    /** Manual mode: write radio params to the device (true Hz / dBm). */
    fun applyManualRadio(address: String, freqHz: Long, bwHz: Long, sf: Int, cr: Int, txPower: Int) {
        connections[address]?.applyRadio(freqHz, bwHz, sf, cr, txPower)
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
            onRadioRx = { conn, raw -> onRadioPacket(conn, raw) },
            onLog = { msg -> appendLog("${cfg.address.takeLast(5)}: $msg") },
        ).also { conn ->
            // Mirror each connection's live state into the aggregate and persist config changes.
            scope.launch { conn.state.collect { refreshAggregate() } }
        }

    /**
     * The network code tagged onto this companion's bridged packets: its chosen network in AUTO mode,
     * or blank (untagged) in MANUAL mode — a manually-configured radio has no network identity.
     */
    private fun effectiveCode(conn: CompanionConnection): String =
        if (conn.config.radioMode == CompanionRadioMode.AUTO) conn.config.radioNetworkCode else ""

    /**
     * Inbound (radio → mesh): apply the shared bridge rules before injecting. The radio is gated by
     * AUTO-mode radio match; the packet is classified (skip undecodable/unsupported) and de-duped on
     * its route-independent content digest so MeshCore's repeated re-floods reach the mesh once.
     */
    private fun onRadioPacket(conn: CompanionConnection, raw: ByteArray) {
        if (radioBlocked(conn)) return
        val cls = MeshCoreBridgeRules.classify(raw)
        if (cls.mode == ForwardMode.SKIP) return
        if (!dedup.shouldForwardPacket(raw, cls.isAdvert)) return
        // Sidepath delivery is flood-only here; DIRECT-targeted packets are still forwarded (the
        // target hash is an optimisation the Go bridge uses for direct delivery — not yet wired).
        service?.injectMeshCoreFromRadio(raw, effectiveCode(conn))
    }

    /** Outbound sink: put a mesh-sourced packet on every bridge-enabled, connected, unblocked radio. */
    private fun fanOutToRadios(rawOta: ByteArray) {
        // De-dup identical wire bytes across the whole bridge (mirrors Go shouldInjectRaw).
        if (!dedup.shouldInjectRaw(rawOta)) return
        connections.values.forEach { conn ->
            if (conn.config.bridgeEnabled && conn.state.value.link == CompanionLinkState.READY && !radioBlocked(conn)) {
                conn.transmit(rawOta)
            }
        }
    }

    private fun refreshAggregate() {
        _companions.value = connections.values.map { conn ->
            val st = conn.state.value
            st.copy(
                radioBlocked = radioBlocked(conn),
                radioStatus = radioStatus(conn),
                matchedNetworkCode = st.selfInfo?.let { matchedNetwork(it)?.code } ?: "",
            )
        }
        pushBridges()
    }

    // ---- AUTO-mode radio verification ----

    /** The network AUTO mode enforces for [conn]: the one it's been assigned, or null if none chosen. */
    private fun targetNetwork(conn: CompanionConnection): MeshNetwork? {
        val code = effectiveCode(conn)
        return networks.firstOrNull { it.code == code }
    }

    /** True when AUTO mode is on and the device's live radio params don't match the target network. */
    private fun radioBlocked(conn: CompanionConnection): Boolean {
        if (conn.config.radioMode != CompanionRadioMode.AUTO) return false
        val si = conn.state.value.selfInfo ?: return false // not read yet — can't decide, don't block
        val net = targetNetwork(conn)?.takeIf { it.hasRadioParams() } ?: return false
        return !net.matchesDevice(si)
    }

    private fun radioStatus(conn: CompanionConnection): String {
        val si = conn.state.value.selfInfo ?: return ""
        if (conn.config.radioMode == CompanionRadioMode.MANUAL) return "Manual configuration"
        val code = effectiveCode(conn)
        if (code.isBlank()) return "No network selected — choose one to bridge"
        val net = targetNetwork(conn) ?: return "Network “$code” is unknown — cannot verify"
        if (!net.hasRadioParams()) return "Network ${net.code} has no radio params to verify"
        return if (net.matchesDevice(si)) "Matches ${net.code}"
        else "Device radio doesn't match ${net.code} — bridge disabled"
    }

    /** The known network whose radio params match the device, or null. Used to offer "save as network". */
    private fun matchedNetwork(si: cz.meshcore.meshward.companion.CompanionMessage.SelfInfo): MeshNetwork? =
        networks.firstOrNull { it.hasRadioParams() && it.matchesDevice(si) }

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
            if (!conn.config.bridgeEnabled || st.link != CompanionLinkState.READY || radioBlocked(conn)) return@forEach
            val code = effectiveCode(conn)
            if (code.isBlank() || byCode.containsKey(code)) return@forEach
            val si = st.selfInfo
            val custom = si?.let {
                BridgeAd(
                    code = code,
                    freqHz = it.radioFreqHz,
                    bandwidthHz = it.radioBwHz,
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
                    radioMode = runCatching { CompanionRadioMode.valueOf(o.optString("radioMode", "AUTO")) }
                        .getOrDefault(CompanionRadioMode.AUTO),
                    radioNetworkCode = o.optString("radioNetwork", ""),
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
                    .put("radioMode", c.radioMode.name)
                    .put("radioNetwork", c.radioNetworkCode),
            )
        }
        context.dataStore.edit { it[COMPANIONS_KEY] = arr.toString() }
    }
}
