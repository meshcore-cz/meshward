package cz.meshcore.meshward.companion

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How a companion's radio parameters are governed. */
enum class CompanionRadioMode { AUTO, MANUAL }

/**
 * Persisted per-companion configuration, keyed by [address] (a transport-qualified id, e.g.
 * `AA:BB:…` for BLE, `tcp:host:port`, or `usb:vid:pid`). [endpoint] is the transport-specific target
 * to connect to (MAC, `host:port`, or a USB device key); it defaults to [address] for BLE.
 */
data class CompanionConfig(
    val address: String,
    val transport: CompanionTransportKind = CompanionTransportKind.BLE,
    val endpoint: String = "",
    val label: String = "",
    /** Auto-connect / keep this companion active. */
    val enabled: Boolean = true,
    /** Forward packets between this radio and the nearby Sidepath mesh. */
    val bridgeEnabled: Boolean = true,
    /**
     * AUTO: the radio belongs to the chosen [radioNetworkCode]; verify the device's radio matches it
     * on each connect and block bridging on mismatch. MANUAL: the user sets radio params explicitly,
     * the radio has no network identity, and no verification is done.
     */
    val radioMode: CompanionRadioMode = CompanionRadioMode.AUTO,
    /**
     * This companion's MeshCore network in AUTO mode (ignored in MANUAL). Tags every bridged packet
     * (SPMC carrier) and the ANNOUNCE [cz.meshcore.sidepath.protocol.BridgeAd], and is the network
     * AUTO mode verifies the radio against. Blank in AUTO = no network chosen yet (packets untagged).
     */
    val radioNetworkCode: String = "",
) {
    /** The concrete target to dial; falls back to [address] (BLE MAC) when [endpoint] is unset. */
    val effectiveEndpoint: String get() = endpoint.ifBlank { address }
}

/** Live UI state for a single companion (config + connection + last-read device data). */
data class CompanionState(
    val config: CompanionConfig,
    val link: CompanionLinkState = CompanionLinkState.DISCONNECTED,
    val selfInfo: CompanionMessage.SelfInfo? = null,
    val deviceInfo: CompanionMessage.DeviceInfo? = null,
    val batteryMv: Int = 0,
    val core: StatsCore? = null,
    val radio: StatsRadio? = null,
    val packets: StatsPackets? = null,
    val bridgeRxCount: Long = 0,
    val bridgeTxCount: Long = 0,
    val lastBridgeActivityMs: Long = 0,
    val lastError: String = "",
    /** AUTO mode: true when the device's radio doesn't match the enforced network, so bridging is off. */
    val radioBlocked: Boolean = false,
    /** Human-readable radio match status (set by the manager, which knows the network defs). */
    val radioStatus: String = "",
    /** Code of the known network the device's live radio params match, or blank if none/unknown. */
    val matchedNetworkCode: String = "",
) {
    val address: String get() = config.address
    val displayName: String
        get() = config.label.ifBlank { selfInfo?.name?.ifBlank { null } ?: address }
}

private const val POLL_INTERVAL_MS = 10_000L
private const val HANDSHAKE_RESEND_MS = 1_500L
private const val HANDSHAKE_ATTEMPTS = 4
private const val RECENT_RX_CAP = 64

/**
 * Drives one companion: owns its [CompanionBleClient], runs the APP_START handshake, polls
 * battery/stats, decodes incoming packets, and exposes a [state] flow. The bridge wiring lives in
 * [CompanionManager]; this class calls [onRadioRx] for each raw OTA packet the radio heard (when the
 * companion's bridge is enabled) and offers [transmit] to put a packet on air.
 */
class CompanionConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    initialConfig: CompanionConfig,
    /** Called with a raw OTA packet heard on this radio, to be injected onto the mesh. */
    private val onRadioRx: (CompanionConnection, ByteArray) -> Unit,
    private val onLog: ((String) -> Unit)? = null,
) {
    private val _state = MutableStateFlow(CompanionState(initialConfig))
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    val address: String get() = _state.value.address
    val config: CompanionConfig get() = _state.value.config

    private var pollJob: Job? = null
    private var handshakeJob: Job? = null

    // Short ring of recently-received content keys, to suppress echoing a just-heard packet back to
    // this same radio when the mesh relays it around.
    private val recentRx = LinkedHashSet<String>()

    private val client: CompanionTransport = when (initialConfig.transport) {
        CompanionTransportKind.BLE -> CompanionBleClient(
            context = context,
            address = initialConfig.effectiveEndpoint,
            onPacket = ::onPacket,
            onState = ::onLinkState,
            onLog = onLog,
        )
        CompanionTransportKind.TCP -> CompanionTcpClient(
            endpoint = initialConfig.effectiveEndpoint,
            onPacket = ::onPacket,
            onState = ::onLinkState,
            onLog = onLog,
        )
        CompanionTransportKind.USB -> CompanionUsbClient(
            context = context,
            deviceKey = initialConfig.effectiveEndpoint,
            onPacket = ::onPacket,
            onState = ::onLinkState,
            onLog = onLog,
        )
    }

    fun connect() {
        client.connect()
    }

    fun disconnect() {
        handshakeJob?.cancel()
        pollJob?.cancel()
        client.disconnect()
    }

    fun close() {
        handshakeJob?.cancel()
        pollJob?.cancel()
        client.disconnect()
    }

    /** Apply a config change (label / enabled / bridge / network / radio mode). */
    fun updateConfig(transform: (CompanionConfig) -> CompanionConfig) {
        _state.update { it.copy(config = transform(it.config)) }
    }

    /**
     * Write radio parameters to the device (manual mode), then re-run APP_START so the refreshed
     * [CompanionMessage.SelfInfo] reflects the new settings. Inputs are true Hz / dBm.
     */
    fun applyRadio(freqHz: Long, bwHz: Long, sf: Int, cr: Int, txPower: Int) {
        client.send(CompanionProtocol.setRadioParams(freqHz, bwHz, sf, cr))
        if (txPower > 0) client.send(CompanionProtocol.setTxPower(txPower))
        scope.launch {
            delay(300) // let the device apply before we re-read identity
            client.send(CompanionProtocol.appStart())
        }
    }

    /** Put a raw OTA packet on this radio via SEND_RAW_PACKET. Returns false if not ready. */
    fun transmit(rawOta: ByteArray): Boolean {
        if (!config.bridgeEnabled) return false
        if (recentlyReceived(rawOta)) return false // it just came from this radio; don't echo back
        val ok = client.send(CompanionProtocol.sendRawPacket(rawOta))
        if (ok) {
            _state.update {
                it.copy(bridgeTxCount = it.bridgeTxCount + 1, lastBridgeActivityMs = System.currentTimeMillis())
            }
        }
        return ok
    }

    private fun recentlyReceived(rawOta: ByteArray): Boolean = synchronized(recentRx) {
        recentRx.contains(contentKey(rawOta))
    }

    private fun rememberReceived(rawOta: ByteArray) = synchronized(recentRx) {
        val key = contentKey(rawOta)
        recentRx.remove(key)
        recentRx.add(key)
        while (recentRx.size > RECENT_RX_CAP) recentRx.iterator().let { if (it.hasNext()) { it.next(); it.remove() } }
    }

    private fun onLinkState(link: CompanionLinkState) {
        _state.update { it.copy(link = link, lastError = if (link == CompanionLinkState.FAILED) "connection failed" else "") }
        when (link) {
            CompanionLinkState.READY -> startSession()
            CompanionLinkState.DISCONNECTED, CompanionLinkState.FAILED -> {
                handshakeJob?.cancel()
                pollJob?.cancel()
            }
            else -> Unit
        }
    }

    /** On READY: resend APP_START until SelfInfo arrives, query device, then poll status. */
    private fun startSession() {
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            repeat(HANDSHAKE_ATTEMPTS) {
                if (_state.value.selfInfo != null) return@launch
                client.send(CompanionProtocol.appStart())
                client.send(CompanionProtocol.deviceQuery())
                delay(HANDSHAKE_RESEND_MS)
            }
        }
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                client.send(CompanionProtocol.getBattery())
                delay(150)
                client.send(CompanionProtocol.getStats(CompanionProtocol.STATS_CORE))
                delay(150)
                client.send(CompanionProtocol.getStats(CompanionProtocol.STATS_RADIO))
                delay(150)
                client.send(CompanionProtocol.getStats(CompanionProtocol.STATS_PACKETS))
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun onPacket(bytes: ByteArray) {
        when (val msg = CompanionProtocol.decode(bytes)) {
            is CompanionMessage.SelfInfo -> _state.update { it.copy(selfInfo = msg) }
            is CompanionMessage.DeviceInfo -> _state.update { it.copy(deviceInfo = msg) }
            is CompanionMessage.Battery -> _state.update { it.copy(batteryMv = msg.millivolts) }
            is CompanionMessage.Stats -> _state.update {
                it.copy(
                    core = msg.core ?: it.core,
                    radio = msg.radio ?: it.radio,
                    packets = msg.packets ?: it.packets,
                )
            }
            is CompanionMessage.RfPacket -> {
                rememberReceived(msg.packet)
                _state.update {
                    it.copy(bridgeRxCount = it.bridgeRxCount + 1, lastBridgeActivityMs = System.currentTimeMillis())
                }
                if (config.bridgeEnabled && msg.packet.isNotEmpty()) onRadioRx(this, msg.packet)
            }
            is CompanionMessage.Err -> _state.update { it.copy(lastError = "device error ${msg.code}") }
            else -> Unit // Ok, other pushes, undecoded frames
        }
    }

    private fun contentKey(b: ByteArray): String {
        // Cheap stable key over the packet bytes; collisions only cost a redundant TX skip.
        var h = 1125899906842597L
        for (x in b) h = 31 * h + x
        return "${b.size}:$h"
    }
}
