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
import android.hardware.usb.UsbAccessory
import android.os.Build
import android.os.ParcelFileDescriptor
import timber.log.Timber
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB Transport Layer for CarPlay Communication
 *
 * Supports two modes:
 * 1. USB Host Mode — Bulk transfer with the car's head unit (primary for CarPlay)
 * 2. USB Accessory Mode (AOA) — Android Open Accessory protocol (fallback)
 *
 * In USB Host mode, the Android phone acts as the USB host and the car's
 * head unit acts as the device. This is the reverse of normal Android USB
 * and requires the phone to support USB OTG/Host mode.
 */
class UsbTransport(private val context: Context) {

    companion object {
        private const val TAG = "UsbTransport"
        private const val USB_PERMISSION = "com.carplay.android.USB_PERMISSION"
        private const val USB_ACCESSORY_PERMISSION = "com.carplay.android.USB_ACCESSORY_PERMISSION"
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
            // Generic head units
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

    // Accessory mode state
    private var accessory: UsbAccessory? = null
    private var accessoryFd: ParcelFileDescriptor? = null
    private var accessoryInput: FileInputStream? = null
    private var accessoryOutput: FileOutputStream? = null

    private val isConnected = AtomicBoolean(false)
    private val isReading = AtomicBoolean(false)

    // Connection mode
    enum class ConnectionMode { NONE, HOST_BULK, ACCESSORY }
    private var connectionMode = ConnectionMode.NONE

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

        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION)
            addAction(USB_ACCESSORY_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }

        // Check for already connected devices/accessories
        enumerateDevices()
        checkForAccessory()
    }

    /**
     * Scan for connected USB devices
     */
    fun enumerateDevices(): List<UsbDevice> {
        val devices = mutableListOf<UsbDevice>()

        usbManager.deviceList.values.forEach { usbDevice ->
            val deviceId = DeviceId(usbDevice.vendorId, usbDevice.productId)
            val isKnown = KNOWN_DEVICES.any {
                (it.vendorId == deviceId.vendorId && it.productId == deviceId.productId) ||
                (it.vendorId == 0 && it.productId == 0) // Wildcard
            }

            if (isKnown) {
                Timber.d("Found device: VID=0x${usbDevice.vendorId.toString(16)}, " +
                        "PID=0x${usbDevice.productId.toString(16)}, " +
                        "name=${usbDevice.deviceName}, " +
                        "interfaces=${usbDevice.interfaceCount}")
                devices.add(usbDevice)
                onDeviceFound?.invoke(usbDevice)
            }
        }

        return devices
    }

    /**
     * Check for USB accessories (AOA mode)
     */
    fun checkForAccessory() {
        val accessories = usbManager.accessoryList
        if (accessories != null && accessories.isNotEmpty()) {
            Timber.d("Found USB accessory: ${accessories[0].model}")
            connectAccessory(accessories[0])
        }
    }

    /**
     * Request permission and connect to device in USB Host mode
     */
    fun connect(device: UsbDevice) {
        Timber.d("Requesting USB permission for: ${device.deviceName}")

        this.device = device

        if (usbManager.hasPermission(device)) {
            establishHostConnection(device)
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
     * Connect in USB Accessory mode (AOA)
     */
    fun connectAccessory(accessory: UsbAccessory) {
        Timber.d("Connecting USB accessory: ${accessory.model}")

        this.accessory = accessory

        if (usbManager.hasPermission(accessory)) {
            openAccessory(accessory)
        } else {
            val permissionIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(USB_ACCESSORY_PERMISSION),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(accessory, permissionIntent)
        }
    }

    /**
     * Send data to head unit
     */
    fun send(data: ByteArray): Boolean {
        return when (connectionMode) {
            ConnectionMode.HOST_BULK -> sendBulk(data)
            ConnectionMode.ACCESSORY -> sendAccessory(data)
            ConnectionMode.NONE -> false
        }
    }

    /**
     * Send via USB bulk transfer
     */
    private fun sendBulk(data: ByteArray): Boolean {
        val conn = connection ?: return false
        val endpoint = writeEndpoint ?: return false

        return try {
            val result = conn.bulkTransfer(endpoint, data, data.size, WRITE_TIMEOUT_MS)
            if (result < 0) {
                Timber.e("Bulk send failed: error=$result")
                false
            } else {
                Timber.v("Bulk sent ${result}B")
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "Bulk send error")
            false
        }
    }

    /**
     * Send via USB accessory stream
     */
    private fun sendAccessory(data: ByteArray): Boolean {
        val output = accessoryOutput ?: return false

        return try {
            output.write(data)
            output.flush()
            Timber.v("Accessory sent ${data.size}B")
            true
        } catch (e: Exception) {
            Timber.e(e, "Accessory send error")
            false
        }
    }

    /**
     * Disconnect from device
     */
    fun disconnect() {
        Timber.d("Disconnecting USB (mode=$connectionMode)")
        isReading.set(false)

        when (connectionMode) {
            ConnectionMode.HOST_BULK -> {
                try {
                    connection?.releaseInterface(usbInterface)
                    connection?.close()
                } catch (e: Exception) {
                    Timber.e(e, "Error during bulk disconnect")
                }
                connection = null
                device = null
                readEndpoint = null
                writeEndpoint = null
                usbInterface = null
            }
            ConnectionMode.ACCESSORY -> {
                try {
                    accessoryInput?.close()
                    accessoryOutput?.close()
                    accessoryFd?.close()
                } catch (e: Exception) {
                    Timber.e(e, "Error during accessory disconnect")
                }
                accessoryInput = null
                accessoryOutput = null
                accessoryFd = null
                accessory = null
            }
            ConnectionMode.NONE -> {}
        }

        connectionMode = ConnectionMode.NONE

        if (isConnected.getAndSet(false)) {
            onConnectionChanged?.invoke(false)
        }
    }

    /**
     * Check if connected
     */
    fun isConnected() = isConnected.get()

    /**
     * Get current connection mode
     */
    fun getConnectionMode() = connectionMode

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

    // ── USB Host Mode ──────────────────────────────────────

    /**
     * Establish USB Host connection to device
     */
    private fun establishHostConnection(usbDevice: UsbDevice) {
        Timber.d("Establishing host connection to: ${usbDevice.deviceName}")

        try {
            val conn = usbManager.openDevice(usbDevice)
            if (conn == null) {
                Timber.e("Failed to open USB device")
                onConnectionChanged?.invoke(false)
                return
            }

            // Find interface with bulk endpoints
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var selectedInterface: UsbInterface? = null

            for (i in 0 until usbDevice.interfaceCount) {
                val iface = usbDevice.getInterface(i)
                var foundIn: UsbEndpoint? = null
                var foundOut: UsbEndpoint? = null

                for (j in 0 until iface.endpointCount) {
                    val endpoint = iface.getEndpoint(j)
                    if (endpoint.type == UsbInterface.USB_ENDPOINT_XFER_BULK) {
                        if (endpoint.direction == UsbInterface.USB_DIR_IN) {
                            foundIn = endpoint
                        } else {
                            foundOut = endpoint
                        }
                    }
                }

                if (foundIn != null && foundOut != null) {
                    bulkIn = foundIn
                    bulkOut = foundOut
                    selectedInterface = iface
                    break
                }
            }

            if (bulkIn == null || bulkOut == null || selectedInterface == null) {
                Timber.e("No bulk endpoints found on any interface")
                conn.close()
                onConnectionChanged?.invoke(false)
                return
            }

            if (!conn.claimInterface(selectedInterface, true)) {
                Timber.e("Failed to claim interface")
                conn.close()
                onConnectionChanged?.invoke(false)
                return
            }

            // Store connection state
            connection = conn
            usbInterface = selectedInterface
            readEndpoint = bulkIn
            writeEndpoint = bulkOut
            connectionMode = ConnectionMode.HOST_BULK

            if (isConnected.compareAndSet(false, true)) {
                Timber.d("USB Host connected! " +
                        "Read EP: ${bulkIn.maxPacketSize}B, Write EP: ${bulkOut.maxPacketSize}B")
                onConnectionChanged?.invoke(true)
                startBulkReadLoop()
            }

        } catch (e: Exception) {
            Timber.e(e, "Host connection error")
            onConnectionChanged?.invoke(false)
        }
    }

    /**
     * Continuous read loop for USB bulk transfer
     */
    private fun startBulkReadLoop() {
        if (!isReading.compareAndSet(false, true)) return

        Thread({
            Timber.d("USB bulk read loop started")
            val buffer = ByteArray(READ_BUFFER_SIZE)

            while (isReading.get() && isConnected.get()) {
                try {
                    val endpoint = readEndpoint ?: break
                    val conn = connection ?: break

                    val bytesRead = conn.bulkTransfer(endpoint, buffer, buffer.size, READ_TIMEOUT_MS)

                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        Timber.v("Bulk received ${bytesRead}B")
                        onDataReceived?.invoke(data)
                    }
                } catch (e: Exception) {
                    if (isReading.get()) {
                        Timber.e(e, "Bulk read error")
                    }
                }
            }

            Timber.d("USB bulk read loop ended")
        }, "USB-Bulk-Read").start()
    }

    // ── USB Accessory Mode (AOA) ───────────────────────────

    /**
     * Open USB accessory connection
     */
    private fun openAccessory(accessory: UsbAccessory) {
        Timber.d("Opening USB accessory: ${accessory.manufacturer} ${accessory.model}")

        try {
            val fd = usbManager.openAccessory(accessory)
            if (fd == null) {
                Timber.e("Failed to open accessory")
                onConnectionChanged?.invoke(false)
                return
            }

            accessoryFd = fd
            val fileDescriptor = fd.fileDescriptor
            accessoryInput = FileInputStream(fileDescriptor)
            accessoryOutput = FileOutputStream(fileDescriptor)
            connectionMode = ConnectionMode.ACCESSORY

            if (isConnected.compareAndSet(false, true)) {
                Timber.d("USB Accessory connected!")
                onConnectionChanged?.invoke(true)
                startAccessoryReadLoop()
            }

        } catch (e: Exception) {
            Timber.e(e, "Accessory connection error")
            onConnectionChanged?.invoke(false)
        }
    }

    /**
     * Continuous read loop for USB accessory mode
     */
    private fun startAccessoryReadLoop() {
        if (!isReading.compareAndSet(false, true)) return

        Thread({
            Timber.d("USB accessory read loop started")
            val buffer = ByteArray(READ_BUFFER_SIZE)

            while (isReading.get() && isConnected.get()) {
                try {
                    val input = accessoryInput ?: break
                    val bytesRead = input.read(buffer)

                    if (bytesRead > 0) {
                        val data = buffer.copyOf(bytesRead)
                        Timber.v("Accessory received ${bytesRead}B")
                        onDataReceived?.invoke(data)
                    } else if (bytesRead < 0) {
                        Timber.w("Accessory stream ended")
                        break
                    }
                } catch (e: Exception) {
                    if (isReading.get()) {
                        Timber.e(e, "Accessory read error")
                    }
                }
            }

            Timber.d("USB accessory read loop ended")
        }, "USB-Accessory-Read").start()
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
                        establishHostConnection(usbDevice)
                    } else {
                        Timber.w("USB permission denied")
                        onConnectionChanged?.invoke(false)
                    }
                }

                USB_ACCESSORY_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val acc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                    }

                    if (granted && acc != null) {
                        Timber.d("USB accessory permission granted")
                        openAccessory(acc)
                    } else {
                        Timber.w("USB accessory permission denied")
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

                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    Timber.d("USB accessory attached")
                    checkForAccessory()
                }

                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    Timber.d("USB accessory detached")
                    if (connectionMode == ConnectionMode.ACCESSORY) {
                        disconnect()
                    }
                }
            }
        }
    }
}
