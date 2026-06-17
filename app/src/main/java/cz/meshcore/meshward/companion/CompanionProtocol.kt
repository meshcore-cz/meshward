package cz.meshcore.meshward.companion

/**
 * MeshCore *companion* protocol codec (the "companion-v3" wire format spoken by official MeshCore
 * companion radios). This is a focused Kotlin port of the subset Meshward needs to drive a
 * locally-attached BLE companion: the APP_START handshake, status/stats reads, and the raw-packet
 * bridge in both directions.
 *
 * Byte layouts mirror the reference implementation in `../meshcore-go`
 * (`protocol/companion/{codes,commands,decode,messages}.go`, `events.go`). Offsets that the
 * reference marks provisional / firmware-derived are reproduced as-is.
 *
 * Over BLE (Nordic UART Service) each packet is carried verbatim — there is **no** serial `<`/`>`
 * framing — so the bytes produced/consumed here are exactly one NUS write / notification.
 */
object CompanionProtocol {

    // ---- command codes (host -> device); first byte of the packet ----
    private const val CMD_APP_START: Byte = 1
    private const val CMD_DEVICE_QUERY: Byte = 22
    private const val CMD_GET_BATTERY: Byte = 20
    private const val CMD_GET_STATS: Byte = 56
    private const val CMD_SEND_SELF_ADVERT: Byte = 7
    private const val CMD_SET_ADVERT_NAME: Byte = 8
    private const val CMD_SET_RADIO_PARAMS: Byte = 11
    private const val CMD_SET_TX_POWER: Byte = 12
    private const val CMD_REBOOT: Byte = 19
    /** SEND_RAW_PACKET — transmit a pre-built OTA packet. Requires firmware PR #2543. */
    private const val CMD_SEND_RAW_PACKET: Byte = 65

    // ---- GET_STATS sub-types ----
    const val STATS_CORE: Byte = 0
    const val STATS_RADIO: Byte = 1
    const val STATS_PACKETS: Byte = 2

    // ---- response codes (device -> host) ----
    private const val RESP_OK = 0
    private const val RESP_ERR = 1
    private const val RESP_SELF_INFO = 5
    private const val RESP_BATTERY_VOLTAGE = 12
    private const val RESP_DEVICE_INFO = 13
    private const val RESP_STATS = 24

    // ---- push (async) codes; high bit set ----
    private const val PUSH_ADVERT = 0x80
    private const val PUSH_RAW_DATA = 0x84
    private const val PUSH_LOG_RX_DATA = 0x88

    /** The application name reported during APP_START. */
    private const val APP_NAME = "meshward"

    // =====================================================================================
    // Encoders
    // =====================================================================================

    /** APP_START: `[1][app_target=3][6 reserved][name…]`; elicits a SelfInfo reply. */
    fun appStart(name: String = APP_NAME): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        return ByteArray(8 + n.size).also {
            it[0] = CMD_APP_START
            it[1] = 3
            n.copyInto(it, 8)
        }
    }

    /** DEVICE_QUERY: `[22][app_ver=3]`; elicits DeviceInfo. */
    fun deviceQuery(): ByteArray = byteArrayOf(CMD_DEVICE_QUERY, 3)

    /** GET_BATTERY: `[20]`. */
    fun getBattery(): ByteArray = byteArrayOf(CMD_GET_BATTERY)

    /** GET_STATS: `[56][type]` where type is one of STATS_CORE/RADIO/PACKETS. */
    fun getStats(type: Byte): ByteArray = byteArrayOf(CMD_GET_STATS, type)

    /**
     * SEND_RAW_PACKET: `[65][priority][raw OTA packet…]`. [packet] must be a complete wire-format
     * MeshCore packet (header, optional transport codes, path_len, path, payload). The device replies
     * OK, or Err(ILLEGAL_ARG) on a parse failure, or Err(TABLE_FULL) when the pool is exhausted.
     */
    fun sendRawPacket(packet: ByteArray, priority: Byte = 0): ByteArray =
        ByteArray(2 + packet.size).also {
            it[0] = CMD_SEND_RAW_PACKET
            it[1] = priority
            packet.copyInto(it, 2)
        }

    /**
     * SET_RADIO_PARAMS: `[11][freq(LE32)][bw(LE32)][sf][cr]`. Inputs are true Hz (as in [SelfInfo] and
     * the app's network defs); the wire uses the device's raw units — frequency in kHz (MHz×1000) and
     * bandwidth in Hz (kHz×1000) — so we divide the frequency by 1000 and pass bandwidth through.
     * Firmware-derived layout; the device replies OK on success.
     */
    fun setRadioParams(freqHz: Long, bwHz: Long, sf: Int, cr: Int): ByteArray {
        val rawFreq = freqHz / 1000
        val buf = ByteArray(11)
        buf[0] = CMD_SET_RADIO_PARAMS
        le32(buf, 1, rawFreq)
        le32(buf, 5, bwHz)
        buf[9] = sf.toByte()
        buf[10] = cr.toByte()
        return buf
    }

    /** SET_TX_POWER: `[12][dbm]`. */
    fun setTxPower(dbm: Int): ByteArray = byteArrayOf(CMD_SET_TX_POWER, dbm.toByte())

    // --- write/identity helpers (not all wired to UI; firmware-derived, verify on hardware) ---

    /** SEND_SELF_ADVERT: `[7][type]` (0 = zero-hop, 1 = flood). */
    fun sendSelfAdvert(flood: Boolean): ByteArray = byteArrayOf(CMD_SEND_SELF_ADVERT, if (flood) 1 else 0)

    /** SET_ADVERT_NAME: `[8][name…]`. */
    fun setAdvertName(name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        return ByteArray(1 + n.size).also { it[0] = CMD_SET_ADVERT_NAME; n.copyInto(it, 1) }
    }

    /** REBOOT: `[19]`. */
    fun reboot(): ByteArray = byteArrayOf(CMD_REBOOT)

    // =====================================================================================
    // Decoder
    // =====================================================================================

    /** Parse a complete companion packet. Unknown / short packets degrade to [Raw]. */
    fun decode(packet: ByteArray): CompanionMessage {
        if (packet.isEmpty()) return CompanionMessage.Raw(0, ByteArray(0), push = false)
        val code = packet[0].toInt() and 0xFF
        val body = packet.copyOfRange(1, packet.size)
        return when (code) {
            RESP_OK -> CompanionMessage.Ok
            RESP_ERR -> CompanionMessage.Err(if (body.isNotEmpty()) body[0].toInt() and 0xFF else 0)
            RESP_SELF_INFO -> decodeSelfInfo(body)
            RESP_DEVICE_INFO -> decodeDeviceInfo(body)
            RESP_BATTERY_VOLTAGE ->
                if (body.size >= 2) CompanionMessage.Battery(u16(body, 0)) else raw(code, body)
            RESP_STATS -> decodeStats(body)
            PUSH_LOG_RX_DATA -> decodeRfPacket(body)
            else -> raw(code, body, push = code and 0x80 != 0)
        }
    }

    /**
     * RESP_SELF_INFO: device identity + radio config. Offsets (relative to body) per MeshCore v1.15:
     * `[0]adv_type [1]tx_power [2]max_tx_power [3:35]pubkey [35:39]lat [39:43]lon [43:47]reserved
     * [47:51]freq(kHz) [51:55]bw(kHz) [55]sf [56]cr [57:]name`.
     */
    private fun decodeSelfInfo(b: ByteArray): CompanionMessage.SelfInfo {
        fun g(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else 0
        // The device packs both radio fields as (natural unit × 1000): radio_freq is MHz×1000 (i.e.
        // kHz) and radio_bw is kHz×1000 (i.e. Hz). Normalise both to Hz here so callers never have to
        // juggle the asymmetry. (e.g. raw 869432 → 869.432 MHz; raw 62500 → 62.5 kHz.)
        return CompanionMessage.SelfInfo(
            txPower = g(1),
            maxTxPower = g(2),
            publicKey = if (b.size >= 35) b.copyOfRange(3, 35).toHexLocal() else "",
            latitude = i32(b, 35) / 1e6,
            longitude = i32(b, 39) / 1e6,
            radioFreqHz = u32(b, 47) * 1000,
            radioBwHz = u32(b, 51),
            radioSf = g(55),
            radioCr = g(56),
            name = if (b.size > 57) trimString(b, 57) else "",
        )
    }

    /**
     * RESP_DEVICE_INFO: a firmware-code byte followed by NUL-padded strings (build date, model,
     * version). Tolerant token extraction, mirroring the reference.
     */
    private fun decodeDeviceInfo(b: ByteArray): CompanionMessage.DeviceInfo {
        val firmwareCode = if (b.isNotEmpty()) b[0].toInt() and 0xFF else 0
        var model = ""
        var buildDate = ""
        var version = ""
        for (tok in printableTokens(b)) {
            when {
                looksLikeVersion(tok) -> if (version.isEmpty()) version = tok
                looksLikeDate(tok) -> if (buildDate.isEmpty()) buildDate = tok
                else -> if (model.isEmpty()) model = tok
            }
        }
        return CompanionMessage.DeviceInfo(firmwareCode, model, buildDate, version)
    }

    /** RESP_STATS: `[type]` then a type-specific block. */
    private fun decodeStats(b: ByteArray): CompanionMessage {
        if (b.isEmpty()) return raw(RESP_STATS, b)
        val type = b[0]
        val body = b.copyOfRange(1, b.size)
        return when (type) {
            STATS_CORE -> if (body.size >= 9) CompanionMessage.Stats(
                core = StatsCore(
                    batteryMv = u16(body, 0),
                    uptimeSecs = u32(body, 2),
                    errorFlags = u16(body, 6),
                    queueLen = body[8].toInt() and 0xFF,
                ),
            ) else raw(RESP_STATS, b)

            STATS_RADIO -> if (body.size >= 12) CompanionMessage.Stats(
                radio = StatsRadio(
                    noiseFloor = i16(body, 0),
                    lastRssi = body[2].toInt().toByte().toInt(),
                    lastSnr = body[3].toInt().toByte().toInt() / 4.0,
                    txAirSecs = u32(body, 4),
                    rxAirSecs = u32(body, 8),
                ),
            ) else raw(RESP_STATS, b)

            STATS_PACKETS -> if (body.size >= 28) CompanionMessage.Stats(
                packets = StatsPackets(
                    received = u32(body, 0),
                    sent = u32(body, 4),
                    floodTx = u32(body, 8),
                    directTx = u32(body, 12),
                    floodRx = u32(body, 16),
                    directRx = u32(body, 20),
                    recvErrors = u32(body, 24),
                ),
            ) else raw(RESP_STATS, b)

            else -> raw(RESP_STATS, b)
        }
    }

    /**
     * PUSH_LOG_RX_DATA (0x88): `[int8 SNR*4][int8 RSSI][raw OTA MeshCore packet…]`. The trailing bytes
     * are exactly the over-the-air packet — the raw bridge source. (Reference: `events.go`.)
     */
    private fun decodeRfPacket(body: ByteArray): CompanionMessage {
        if (body.size < 2) return raw(PUSH_LOG_RX_DATA, body, push = true)
        return CompanionMessage.RfPacket(
            snr = body[0].toInt().toByte().toInt() / 4.0,
            rssi = body[1].toInt().toByte().toInt(),
            packet = body.copyOfRange(2, body.size),
        )
    }

    private fun raw(code: Int, body: ByteArray, push: Boolean = false) =
        CompanionMessage.Raw(code, body, push)

    // ---- little-endian / string helpers ----
    private fun u16(b: ByteArray, i: Int) =
        if (i + 2 <= b.size) (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) else 0

    private fun i16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()

    private fun u32(b: ByteArray, i: Int): Long =
        if (i + 4 <= b.size) {
            ((b[i].toLong() and 0xFF)) or
                ((b[i + 1].toLong() and 0xFF) shl 8) or
                ((b[i + 2].toLong() and 0xFF) shl 16) or
                ((b[i + 3].toLong() and 0xFF) shl 24)
        } else 0L

    private fun i32(b: ByteArray, i: Int): Int = u32(b, i).toInt()

    private fun le32(b: ByteArray, i: Int, v: Long) {
        b[i] = (v and 0xFF).toByte()
        b[i + 1] = ((v shr 8) and 0xFF).toByte()
        b[i + 2] = ((v shr 16) and 0xFF).toByte()
        b[i + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun trimString(b: ByteArray, from: Int): String {
        var end = from
        while (end < b.size && b[end].toInt() != 0) end++
        return String(b, from, end - from, Charsets.UTF_8)
    }

    private fun printableTokens(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            val t = cur.toString().trim()
            if (t.length >= 2) out.add(t)
            cur.setLength(0)
        }
        for (c in b) {
            val v = c.toInt() and 0xFF
            if (v in 0x20..0x7e) cur.append(v.toChar()) else flush()
        }
        flush()
        return out
    }

    private fun looksLikeVersion(t: String): Boolean {
        if (t.length >= 2 && (t[0] == 'v' || t[0] == 'V') && t[1].isDigit()) return true
        return t[0].isDigit() && t.contains('.')
    }

    private fun looksLikeDate(t: String): Boolean =
        t.contains('-') && t.any { it.isLetter() }

    private fun ByteArray.toHexLocal(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/** A decoded companion-protocol packet. */
sealed class CompanionMessage {
    /** True for asynchronous push notifications (not solicited replies). */
    open val isPush: Boolean get() = false

    /** RESP_OK — bare positive acknowledgement. */
    data object Ok : CompanionMessage()

    /** RESP_ERR with an optional device error code. */
    data class Err(val code: Int) : CompanionMessage()

    /** RESP_SELF_INFO — device identity + radio configuration. */
    data class SelfInfo(
        val txPower: Int,
        val maxTxPower: Int,
        val publicKey: String,
        val latitude: Double,
        val longitude: Double,
        val radioFreqHz: Long,
        val radioBwHz: Long,
        val radioSf: Int,
        val radioCr: Int,
        val name: String,
    ) : CompanionMessage()

    /** RESP_DEVICE_INFO — firmware/build details. */
    data class DeviceInfo(
        val firmwareCode: Int,
        val model: String,
        val buildDate: String,
        val version: String,
    ) : CompanionMessage()

    /** RESP_BATTERY_VOLTAGE in millivolts. */
    data class Battery(val millivolts: Int) : CompanionMessage()

    /** RESP_STATS — one of the local stats blocks is populated. */
    data class Stats(
        val core: StatsCore? = null,
        val radio: StatsRadio? = null,
        val packets: StatsPackets? = null,
    ) : CompanionMessage()

    /**
     * PUSH_LOG_RX_DATA — a raw over-the-air MeshCore packet the radio just heard, with link metadata.
     * [packet] is the bytes to bridge onto the mesh.
     */
    data class RfPacket(val snr: Double, val rssi: Int, val packet: ByteArray) : CompanionMessage() {
        override val isPush get() = true

        override fun equals(other: Any?) =
            other is RfPacket && snr == other.snr && rssi == other.rssi && packet.contentEquals(other.packet)

        override fun hashCode() = (31 * snr.hashCode() + rssi) * 31 + packet.contentHashCode()
    }

    /** Anything not decoded into a typed message; preserved for inspection. */
    data class Raw(val code: Int, val body: ByteArray, val push: Boolean) : CompanionMessage() {
        override val isPush get() = push

        override fun equals(other: Any?) =
            other is Raw && code == other.code && push == other.push && body.contentEquals(other.body)

        override fun hashCode() = (31 * code + push.hashCode()) * 31 + body.contentHashCode()
    }
}

data class StatsCore(val batteryMv: Int, val uptimeSecs: Long, val errorFlags: Int, val queueLen: Int)
data class StatsRadio(
    val noiseFloor: Int,
    val lastRssi: Int,
    val lastSnr: Double,
    val txAirSecs: Long,
    val rxAirSecs: Long,
)
data class StatsPackets(
    val received: Long,
    val sent: Long,
    val floodTx: Long,
    val directTx: Long,
    val floodRx: Long,
    val directRx: Long,
    val recvErrors: Long,
)
