package cz.meshcore.meshward.companion

import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "CompanionTcp"
private const val CONNECT_TIMEOUT_MS = 8_000
private const val READ_BUF = 4096

/**
 * TCP transport for a MeshCore companion server (e.g. a WiFi-enabled radio or a serial-to-TCP
 * bridge). The byte stream uses MeshCore "serial V3" framing ([SerialV3]) in both directions. A
 * dedicated reader thread deframes inbound packets; [send] writes framed packets.
 *
 * [endpoint] is `host:port` (port defaults to 5000 — MeshCore's companion TCP port — if omitted).
 */
class CompanionTcpClient(
    private val endpoint: String,
    private val onPacket: (ByteArray) -> Unit,
    private val onState: (CompanionLinkState) -> Unit,
    private val onLog: ((String) -> Unit)? = null,
) : CompanionTransport {

    @Volatile private var socket: Socket? = null
    @Volatile private var output: java.io.OutputStream? = null
    @Volatile private var intentionalClose = false
    @Volatile private var ready = false
    private val writeLock = Any()
    private var reader: Thread? = null

    override fun connect() {
        close(silent = true)
        intentionalClose = false
        setState(CompanionLinkState.CONNECTING)
        reader = Thread({ runConnection() }, "companion-tcp").also { it.isDaemon = true; it.start() }
    }

    override fun send(packet: ByteArray): Boolean {
        if (!ready || packet.isEmpty()) return false
        val out = output ?: return false
        val framed = SerialV3.frameToDevice(packet)
        return try {
            synchronized(writeLock) {
                out.write(framed)
                out.flush()
            }
            true
        } catch (e: Exception) {
            log("write failed: ${e.message}")
            false
        }
    }

    override fun disconnect() {
        close(silent = false)
    }

    private fun runConnection() {
        val (host, port) = parseEndpoint(endpoint)
        if (host.isEmpty()) { log("invalid endpoint '$endpoint'"); setState(CompanionLinkState.FAILED); return }
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.tcpNoDelay = true
            socket = sock
            output = sock.getOutputStream()
            ready = true
            log("connected to $host:$port")
            setState(CompanionLinkState.READY)

            val input = sock.getInputStream()
            val deframer = SerialV3.Deframer()
            val buf = ByteArray(READ_BUF)
            while (!intentionalClose) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                for (pkt in deframer.feed(buf.copyOf(n))) {
                    if (pkt.isNotEmpty()) onPacket(pkt)
                }
            }
        } catch (e: Exception) {
            if (!intentionalClose) {
                log("connection error: ${e.message}")
                ready = false
                runCatching { sock.close() }
                socket = null
                output = null
                setState(CompanionLinkState.FAILED)
                return
            }
        }
        ready = false
        runCatching { sock.close() }
        if (socket === sock) socket = null
        output = null
        if (!intentionalClose) setState(CompanionLinkState.DISCONNECTED)
    }

    private fun close(silent: Boolean) {
        intentionalClose = true
        ready = false
        runCatching { socket?.close() }
        socket = null
        output = null
        if (!silent) setState(CompanionLinkState.DISCONNECTED)
    }

    private fun setState(s: CompanionLinkState) = onState(s)

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun parseEndpoint(ep: String): Pair<String, Int> {
        val trimmed = ep.trim().removePrefix("tcp://")
        val idx = trimmed.lastIndexOf(':')
        return if (idx <= 0) {
            trimmed to 5000
        } else {
            val host = trimmed.substring(0, idx)
            val port = trimmed.substring(idx + 1).toIntOrNull() ?: 5000
            host to port
        }
    }
}
