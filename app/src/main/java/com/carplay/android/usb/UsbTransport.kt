package com.carplay.android.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB Transport Layer for CarPlay Communication
 *
 * Handles USB Host mode communication with the car's head unit.
 * Manages connection, data transfer, and device enumeration.
 */
class UsbTransport(private val context: Context) {

    companion object {
        private const val TAG = "UsbTransport"
        private const val USB_PERMISSION = "com.carplay.android.USB_PERMISSION"
        private const val READ_TIMEOUT_MS = 1000
        private const val WRITE_TIMEOUT_MS = 1000
        private const val READ_BUFFER_SIZE = 16384  // 16KB

        // Known CarPlay-compatible head unit VID/PID combinations
        val KNOWN_DEVICES = listOf(
            // Apple devices (for testing)
            DeviceId(0x05AC, 0x12A8),  // iPhone
            DeviceId(0x05AC, 0x12AA),  // iPhone (newer)
            // Nissan head units
            DeviceId(0x15BA, 0x0030),  // Nissan common
            DeviceId(0x15BA, 0x0037),  // Nissan Almera
            DeviceId(0x0BDA, 0x0129),  // Nissan USB hub
            // Generic
            DeviceId(0x0000, 0x0000)   // Any device (debug mode)
        )
    }

    data class DeviceId(val vendorId: Int, val productId: Int)

    // ── Connection State ───────────────────────────────────

    private var usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var device: UsbDevice? = null
    private var connection: UsbDeviceConnection? = null
    private var readEndpoint: UsbEndpoint? = null
    private var writeEndpoint: UsbEndpoint? = null
    private var usbInterface: UsbInterface? = null

    private val isConnected = AtomicBoolean(false)
    private val isReading = AtomicBoolean(false)

    // Callbacks
    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onConnectionChanged: ((Boolean) -> Unit)? = null
    var onDeviceFound: ((UsbDevice) -> Unit)? = null

    // ── Public API ─────────────────────────────────────────

    /**
     * Initialize USB transport and register receivers
     */
    fun init() {
        Timber.d("Initializing USB transport")

        // Register USB permission receiver
        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        // Check for already connected devices
        enumerateDevices()
    }

    /**
     * Scan for connected USB devices
     */
    fun enumerateDevices(): List<UsbDevice> {
        val devices = mutableListOf<UsbDevice>()

        usbManager.deviceList.values.forEach { usbDevice ->
            val deviceId = DeviceId(usbDevice.vendorId, usbDevice.productId)
            val isKnown = KNOWN_DEVICES.any {
                it.vendorId == deviceId.vendorId && it.productId == deviceId.productId
            }

            if (isKnown || KNOWN_DEVICES.any { it.vendorId == 0 }) {
                Timber.d("Found device: VID=0x${usbDevice.vendorId.toString(16)}, " +
                        "PID=0x${usbDevice.productId.toString(16)}, " +
                        "name=${usbDevice.deviceName}")
                devices.add(usbDevice)
                onDeviceFound?.invoke(usbDevice)
            }
        }

        return devices
    }

    /**
     * Request permission and connect to device
     */
    fun connect(device: UsbDevice) {
        Timber.d("Requesting USB permission for: ${device.deviceName}")

        this.device = device

        if (usbManager.hasPermission(device)) {
            establishConnection(device)
        } else {
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(USB_PERMISSION),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    /**
     * Send data to head unit
     */
    fun send(data: ByteArray): Boolean {
        val conn = connection ?: return false
        val endpoint = writeEndpoint ?: return false

        return try {
            val result = conn.bulkTransfer(endpoint, data, data.size, WRITE_TIMEOUT_MS)
            if (result < 0) {
                Timber.e("Send failed: error=$result")
                false
            } else {
                Timber.v("Sent ${result}B")
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Send error")
            false
        }
    }

    /**
     * Disconnect from device
     */
    fun disconnect() {
        Timber.d("Disconnecting USB")
        isReading.set(false)

        try {
            connection?.releaseInterface(usbInterface)
            connection?.close()
        } catch (e: Exception) {
            Timber.e(e, "Error during disconnect")
        }

        connection = null
        device = null
        readEndpoint = null
        writeEndpoint = null
        usbInterface = null

        if (isConnected.getAndSet(false)) {
            onConnectionChanged?.invoke(false)
        }
    }

    /**
     * Check if connected
     */
    fun isConnected() = isConnected.get()

    /**
     * Cleanup resources
     */
    fun destroy() {
        disconnect()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    // ── Private Methods ────────────────────────────────────

    /**
     * Establish USB connection to device
     */
    private fun establishConnection(usbDevice: UsbDevice) {
        Timber.d("Establishing connection to: ${usbDevice.deviceName}")

        try {
            val conn = usbManager.openDevice(usbDevice)
            if (conn == null) {
                Timber.e("Failed to open USB device")
                onConnectionChanged?.invoke(false)
                return
            }

            // Find interface and endpoints
            val iface = usbDevice.getInterface(0)
            if (!conn.claimInterface(iface, true)) {
                Timber.e("Failed to claim interface")
                conn.close()
                onConnectionChanged?.invoke(false)
                return
            }

            // Find bulk endpoints
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null

            for (i in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(i)
                if (endpoint.type == UsbInterface.USB_ENDPOINT_XFER_BULK) {
                    if (endpoint.direction == UsbInterface.USB_DIR_IN) {
                        bulkIn = endpoint
                    } else {
                        bulkOut = endpoint
                    }
                }
            }

            if (bulkIn == null || bulkOut == null) {
                Timber.e("Missing bulk endpoints (in=$bulkIn, out=$bulkOut)")
                conn.close()
                onConnectionChanged?.invoke(false)
                return
            }

            // Store connection
            connection = conn
            usbInterface = iface
            readEndpoint = bulkIn
            writeEndpoint = bulkOut

            if (isConnected.compareAndSet(false, true)) {
                Timber.d("USB connected! Read endpoint: ${bulkIn.maxPacketSize}B, " +
                        "Write endpoint: ${bulkOut.maxPacketSize}B")
                onConnectionChanged?.invoke(true)

                // Start read loop
                startReadLoop()
            }

        } catch (e: Exception) {
            Timber.e(e, "Connection error")
            onConnectionChanged?.invoke(false)
        }
    }

    /**
     * Continuous read loop for incoming data
     */
    private fun startReadLoop() {
        if (!isReading.compareAndSet(false, true)) return

        Thread({
            Timber.d("USB read loop started")
            val buffer = ByteArray(READ_BUFFER_SIZE)

            while (isReading.get() && isConnected.get()) {
                try {
                    val endpoint = readEndpoint ?: break
                    val conn = connection ?: break

                    val bytesRead = conn.bulkTransfer(endpoint, buffer, buffer.size, READ_TIMEOUT_MS)

                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        Timber.v("Received ${bytesRead}B")
                        onDataReceived?.invoke(data)
                    }
                    // bytesRead == 0 means timeout, continue
                    // bytesRead < 0 means error, but we continue to try

                } catch (e: Exception) {
                    if (isReading.get()) {
                        Timber.e(e, "Read error")
                    }
                }
            }

            Timber.d("USB read loop ended")
        }, "USB-Read-Thread").start()
    }

    // ── USB Broadcast Receiver ─────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (granted && usbDevice != null) {
                        Timber.d("USB permission granted")
                        establishConnection(usbDevice)
                    } else {
                        Timber.w("USB permission denied")
                        onConnectionChanged?.invoke(false)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Timber.d("USB device attached")
                    enumerateDevices()
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (usbDevice != null && device != null &&
                        usbDevice.vendorId == device?.vendorId &&
                        usbDevice.productId == device?.productId) {
                        Timber.d("Connected device detached")
                        disconnect()
                    }
                }
            }
        }
    }
}
