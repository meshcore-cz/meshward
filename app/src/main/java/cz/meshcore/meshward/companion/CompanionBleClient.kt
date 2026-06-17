package cz.meshcore.meshward.companion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

private const val TAG = "CompanionBle"

/** Nordic UART Service UUIDs used by MeshCore companion BLE firmware. */
object CompanionUUIDs {
    val SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    /** RX: host writes companion packets here. */
    val RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    /** TX: device notifies companion packets to the host here. */
    val TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/** Connection lifecycle of a single companion link. */
enum class CompanionLinkState { DISCONNECTED, CONNECTING, READY, FAILED }

/** A companion device seen while scanning. */
data class CompanionScanResult(val address: String, val name: String, val rssi: Int)

/**
 * BLE central for a single MeshCore companion radio over the Nordic UART Service. Each NUS
 * notification on [CompanionUUIDs.TX] is delivered to [onPacket] as one raw companion packet; each
 * [send] writes one packet to [CompanionUUIDs.RX]. There is no serial framing over BLE.
 *
 * Threading: all GATT operations are serialised onto the main looper. Public methods are safe to
 * call from any thread.
 */
@SuppressLint("MissingPermission") // callers gate on BLUETOOTH_CONNECT/SCAN before connecting/scanning
class CompanionBleClient(
    private val context: Context,
    private val address: String,
    private val onPacket: (ByteArray) -> Unit,
    private val onState: (CompanionLinkState) -> Unit,
    private val onLog: ((String) -> Unit)? = null,
) : CompanionTransport {
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var rxChar: BluetoothGattCharacteristic? = null
    @Volatile private var intentionalClose = false

    @Volatile var state: CompanionLinkState = CompanionLinkState.DISCONNECTED
        private set

    private fun setState(s: CompanionLinkState) {
        state = s
        main.post { onState(s) }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog?.let { main.post { it(msg) } }
    }

    /** Connect to the companion at the configured BLE MAC address. Safe to call from any thread. */
    override fun connect() {
        main.post {
            close(silent = true)
            intentionalClose = false
            val adapter = bluetoothAdapter() ?: run { setState(CompanionLinkState.FAILED); return@post }
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
                ?: run { log("invalid address $address"); setState(CompanionLinkState.FAILED); return@post }
            setState(CompanionLinkState.CONNECTING)
            log("connecting to $address")
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
        }
    }

    /** Write one raw companion packet to RX. No-op until the link is READY. */
    override fun send(packet: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = rxChar ?: return false
        if (state != CompanionLinkState.READY || packet.isEmpty()) return false
        val writeType =
            if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, packet, writeType) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                ch.writeType = writeType
                ch.value = packet
                g.writeCharacteristic(ch)
            }
        }
    }

    /** Tear down the link. */
    override fun disconnect() {
        main.post { close(silent = false) }
    }

    private fun close(silent: Boolean) {
        intentionalClose = true
        rxChar = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        if (!silent) setState(CompanionLinkState.DISCONNECTED)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("connected, discovering services")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    runCatching { g.requestMtu(247) }
                }
                main.post { runCatching { g.discoverServices() } }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("disconnected (status=$status)")
                val wasIntentional = intentionalClose
                runCatching { g.close() }
                if (gatt === g) gatt = null
                rxChar = null
                setState(if (wasIntentional) CompanionLinkState.DISCONNECTED else CompanionLinkState.FAILED)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            log("mtu=$mtu status=$status")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("service discovery failed status=$status"); setState(CompanionLinkState.FAILED); return
            }
            val service = g.getService(CompanionUUIDs.SERVICE) ?: run {
                log("NUS service not found"); setState(CompanionLinkState.FAILED); return
            }
            rxChar = service.getCharacteristic(CompanionUUIDs.RX)
            val tx = service.getCharacteristic(CompanionUUIDs.TX)
            if (rxChar == null || tx == null) {
                log("RX/TX characteristic missing"); setState(CompanionLinkState.FAILED); return
            }
            // Subscribe to TX notifications, then write the CCCD so the device starts pushing.
            g.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CompanionUUIDs.CCCD)
            if (cccd == null) {
                log("TX CCCD missing"); setState(CompanionLinkState.FAILED); return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CompanionUUIDs.CCCD) {
                log("notifications enabled (status=$status)")
                setState(CompanionLinkState.READY)
            }
        }

        // API 33+ delivers the value as a parameter; older APIs read characteristic.value.
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == CompanionUUIDs.TX && value.isNotEmpty()) {
                val copy = value.copyOf()
                main.post { onPacket(copy) }
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = ch.value ?: return
            if (ch.uuid == CompanionUUIDs.TX && value.isNotEmpty()) {
                val copy = value.copyOf()
                main.post { onPacket(copy) }
            }
        }
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
}

/**
 * Scans for MeshCore companion radios advertising the Nordic UART Service. Callers must hold
 * BLUETOOTH_SCAN (API 31+) / location (older) before calling [start].
 */
@SuppressLint("MissingPermission")
class CompanionBleScanner(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var callback: ScanCallback? = null

    /** Start scanning; [onResult] is called on the main thread for each unique device found. */
    fun start(onResult: (CompanionScanResult) -> Unit) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        val s = adapter.bluetoothLeScanner ?: return
        scanner = s
        val seen = HashSet<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: ""
                if (seen.add(device.address)) {
                    main.post { onResult(CompanionScanResult(device.address, name, result.rssi)) }
                }
            }
        }
        callback = cb
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(CompanionUUIDs.SERVICE)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        runCatching { s.startScan(filters, settings, cb) }
    }

    fun stop() {
        val cb = callback ?: return
        runCatching { scanner?.stopScan(cb) }
        callback = null
    }
}
