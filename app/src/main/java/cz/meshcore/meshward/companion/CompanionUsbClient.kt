package cz.meshcore.meshward.companion

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager

private const val TAG = "CompanionUsb"
private const val BAUD = 115200
private const val WRITE_TIMEOUT_MS = 2000
private const val ACTION_USB_PERMISSION = "cz.meshcore.meshward.USB_PERMISSION"

/**
 * USB CDC-ACM (and FTDI / CP210x / CH34x via usb-serial-for-android) transport for a wired MeshCore
 * companion. The byte stream uses MeshCore "serial V3" framing ([SerialV3]); a background
 * [SerialInputOutputManager] thread deframes inbound packets and [send] writes framed packets.
 *
 * [deviceKey] is `usb:vendorId:productId` (decimal). Connecting requests USB permission if needed.
 */
class CompanionUsbClient(
    private val context: Context,
    private val deviceKey: String,
    private val onPacket: (ByteArray) -> Unit,
    private val onState: (CompanionLinkState) -> Unit,
    private val onLog: ((String) -> Unit)? = null,
) : CompanionTransport {

    private val usbManager get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var ioManager: SerialInputOutputManager? = null
    @Volatile private var ready = false
    @Volatile private var permissionReceiver: BroadcastReceiver? = null
    private val deframer = SerialV3.Deframer()

    override fun connect() {
        setState(CompanionLinkState.CONNECTING)
        val device = findDevice() ?: run {
            log("USB device $deviceKey not attached"); setState(CompanionLinkState.FAILED); return
        }
        if (usbManager.hasPermission(device)) {
            open(device)
        } else {
            requestPermission(device)
        }
    }

    override fun send(packet: ByteArray): Boolean {
        if (!ready || packet.isEmpty()) return false
        val p = port ?: return false
        return try {
            p.write(SerialV3.frameToDevice(packet), WRITE_TIMEOUT_MS)
            true
        } catch (e: Exception) {
            log("write failed: ${e.message}")
            false
        }
    }

    override fun disconnect() {
        ready = false
        unregisterReceiver()
        ioManager?.let { runCatching { it.stop() } }
        ioManager = null
        port?.let { runCatching { it.close() } }
        port = null
        deframer.reset()
        setState(CompanionLinkState.DISCONNECTED)
    }

    private fun requestPermission(device: UsbDevice) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                unregisterReceiver()
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) open(device) else {
                    log("USB permission denied"); setState(CompanionLinkState.FAILED)
                }
            }
        }
        permissionReceiver = receiver
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else 0
        val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
        usbManager.requestPermission(device, pi)
        log("requesting USB permission")
    }

    private fun open(device: UsbDevice) {
        val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: run {
            log("no serial driver for device"); setState(CompanionLinkState.FAILED); return
        }
        val connection = usbManager.openDevice(device) ?: run {
            log("openDevice failed (in use or no permission)"); setState(CompanionLinkState.FAILED); return
        }
        val p = driver.ports.firstOrNull() ?: run {
            log("driver has no ports"); setState(CompanionLinkState.FAILED); return
        }
        try {
            p.open(connection)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { p.setDTR(true) }
            runCatching { p.setRTS(true) }
        } catch (e: Exception) {
            log("port open failed: ${e.message}"); runCatching { p.close() }; setState(CompanionLinkState.FAILED); return
        }
        port = p
        val listener = object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                for (pkt in deframer.feed(data)) if (pkt.isNotEmpty()) onPacket(pkt)
            }
            override fun onRunError(e: Exception) {
                if (ready) { log("read error: ${e.message}"); ready = false; setState(CompanionLinkState.FAILED) }
            }
        }
        ioManager = SerialInputOutputManager(p, listener).also { it.start() }
        ready = true
        log("USB connected")
        setState(CompanionLinkState.READY)
    }

    private fun findDevice(): UsbDevice? {
        val (vid, pid) = parseKey(deviceKey)
        return usbManager.deviceList.values.firstOrNull {
            (vid < 0 || it.vendorId == vid) && (pid < 0 || it.productId == pid)
        }
    }

    private fun unregisterReceiver() {
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
    }

    private fun setState(s: CompanionLinkState) = onState(s)

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.invoke(msg)
    }

    private fun parseKey(key: String): Pair<Int, Int> {
        val parts = key.removePrefix("usb:").split(":")
        val vid = parts.getOrNull(0)?.toIntOrNull() ?: -1
        val pid = parts.getOrNull(1)?.toIntOrNull() ?: -1
        return vid to pid
    }
}

/** Build the stable [CompanionConfig.address]/endpoint key for a USB device. */
fun usbDeviceKey(device: UsbDevice): String = "usb:${device.vendorId}:${device.productId}"
