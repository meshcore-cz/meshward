package cz.meshcore.meshward.companion

/** Which physical link a companion is reached over. */
enum class CompanionTransportKind { BLE, USB, TCP }

/**
 * A link to a companion radio that carries whole companion-protocol packets. Implementations own
 * their wire framing: BLE (Nordic UART) has packet boundaries and carries raw companion packets,
 * while the byte-stream transports (USB CDC-ACM, TCP) wrap each packet in MeshCore "serial V3"
 * framing ([SerialV3]). Either way [send] takes — and the `onPacket` callback delivers — exactly one
 * companion-protocol packet.
 */
interface CompanionTransport {
    /** Begin connecting. Progress is reported through the transport's state callback. */
    fun connect()

    /** Send one companion-protocol packet. Returns false if the link isn't ready. */
    fun send(packet: ByteArray): Boolean

    /** Tear the link down. */
    fun disconnect()
}

/**
 * MeshCore companion "serial V3" framing for byte-stream transports. Each logical packet is wrapped
 * with a one-byte direction marker, a little-endian uint16 length and the payload:
 *
 * ```
 * host -> device:  '<' | len(2, LE) | payload
 * device -> host:  '>' | len(2, LE) | payload
 * ```
 *
 * Byte-for-byte compatible with the meshcore-go reference (`transport/serial/framing.go`).
 */
object SerialV3 {
    private const val TO_DEVICE: Byte = '<'.code.toByte() // 0x3c
    private const val TO_HOST: Byte = '>'.code.toByte()   // 0x3e
    const val MAX_FRAME_LEN = 8192

    /** Wrap a host→device packet: `'<' | len(LE16) | payload`. */
    fun frameToDevice(payload: ByteArray): ByteArray {
        val n = payload.size
        return ByteArray(3 + n).also {
            it[0] = TO_DEVICE
            it[1] = (n and 0xFF).toByte()
            it[2] = ((n shr 8) and 0xFF).toByte()
            payload.copyInto(it, 3)
        }
    }

    /**
     * Stateful deframer for an inbound device→host byte stream. Tolerates leading noise (boot
     * banners) and resynchronises on the `'>'` marker. [feed] returns every complete packet found,
     * buffering any partial trailing frame for the next call.
     */
    class Deframer {
        private var buf = ByteArray(0)

        fun feed(chunk: ByteArray): List<ByteArray> {
            val combined = if (buf.isEmpty()) chunk else buf + chunk
            val out = ArrayList<ByteArray>()
            var i = 0
            while (i < combined.size) {
                if (combined[i] != TO_HOST) { i++; continue }
                if (i + 3 > combined.size) break // need marker + 2 length bytes
                val len = (combined[i + 1].toInt() and 0xFF) or ((combined[i + 2].toInt() and 0xFF) shl 8)
                if (len > MAX_FRAME_LEN) { i++; continue } // implausible — resync past this marker
                if (i + 3 + len > combined.size) break // wait for the full payload
                out.add(combined.copyOfRange(i + 3, i + 3 + len))
                i += 3 + len
            }
            buf = if (i < combined.size) combined.copyOfRange(i, combined.size) else ByteArray(0)
            return out
        }

        fun reset() { buf = ByteArray(0) }
    }
}
