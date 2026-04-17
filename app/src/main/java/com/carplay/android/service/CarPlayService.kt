package com.carplay.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.carplay.android.media.AudioEncoder
import com.carplay.android.media.ScreenCaptureService
import com.carplay.android.media.VideoEncoder
import com.carplay.android.protocol.CarPlaySessionManager
import com.carplay.android.protocol.IAP2Constants
import com.carplay.android.protocol.IAP2Packet
import com.carplay.android.ui.MainActivity
import com.carplay.android.usb.UsbGadgetConfigurator
import com.carplay.android.usb.UsbTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CarPlay Foreground Service
 *
 * Main service that:
 * 1. Configures USB gadget as Apple device (if root available)
 * 2. Manages USB transport (Host or Gadget mode)
 * 3. Runs iAP2 protocol session
 * 4. Captures screen → H.264 → USB
 * 5. Captures audio → AAC → USB
 * 6. Handles touch input from head unit
 *
 * Debug: Every byte TX/RX logged in hex to /sdcard/carplay_debug.log
 */
class CarPlayService : Service() {

    companion object {
        private const val TAG = "CarPlayService"
        private const val NOTIFICATION_CHANNEL_ID = "carplay_channel"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_CONNECT = "com.carplay.CONNECT"
        const val ACTION_DISCONNECT = "com.carplay.DISCONNECT"
        const val ACTION_START_MEDIA = "com.carplay.START_MEDIA"
    }

    // ── Components ─────────────────────────────────────────
    private lateinit var usbTransport: UsbTransport
    private lateinit var sessionManager: CarPlaySessionManager
    private lateinit var videoEncoder: VideoEncoder
    private lateinit var audioEncoder: AudioEncoder
    private lateinit var screenCapture: ScreenCaptureService
    private lateinit var reconnectManager: ReconnectManager
    private lateinit var touchDispatcher: TouchDispatcher
    private lateinit var gadgetConfigurator: UsbGadgetConfigurator

    private var mediaProjection: MediaProjection? = null

    // ── State ──────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _activeFeatures = MutableStateFlow(setOf<String>())
    val activeFeatures: StateFlow<Set<String>> = _activeFeatures

    private val _mediaState = MutableStateFlow(MediaState.IDLE)
    val mediaState: StateFlow<MediaState> = _mediaState

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, AUTHENTICATING, NEGOTIATING, ACTIVE, ERROR
    }
    enum class MediaState {
        IDLE, CAPTURING, ENCODING, STREAMING
    }

    // ── Binder ─────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): CarPlayService = this@CarPlayService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    // ── Lifecycle ──────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Timber.d("CarPlay service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        initComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            ACTION_START_MEDIA -> startMedia()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    // ── Public API ─────────────────────────────────────────

    fun connect() {
        Timber.d("Starting CarPlay connection")
        _connectionState.value = ConnectionState.CONNECTING
        updateNotification("Connecting...")
        logHex("=== CONNECTION START ===")

        // Try USB gadget mode first (root required)
        if (gadgetConfigurator.canConfigure()) {
            logHex("Root available — configuring USB gadget as Apple device")
            val success = gadgetConfigurator.configureAsAppleDevice(withAdb = true)
            if (success) {
                logHex("USB gadget configured, setting up NCM network")
                gadgetConfigurator.setupNcmNetwork()
                gadgetConfigurator.setupHidGadget()
                // Start listening on NCM interface
                _connectionState.value = ConnectionState.AUTHENTICATING
                updateNotification("Gadget configured — waiting for head unit")
                // The head unit will enumerate our device and start iAP2 handshake
                sessionManager.startHandshake()
                return
            }
        }

        // Fallback: USB Host mode (phone as host, head unit as device)
        logHex("No root — trying USB Host mode")
        val devices = usbTransport.enumerateDevices()
        if (devices.isNotEmpty()) {
            usbTransport.connect(devices.first())
        } else {
            Timber.w("No USB devices found")
            _connectionState.value = ConnectionState.ERROR
            updateNotification("No device found")
        }
    }

    fun disconnect() {
        Timber.d("Disconnecting")
        logHex("=== DISCONNECT ===")
        reconnectManager.cancel()
        stopMedia()
        sessionManager.closeSession()
        usbTransport.disconnect()

        // Reset USB gadget to default
        if (gadgetConfigurator.canConfigure()) {
            gadgetConfigurator.resetToDefault()
        }

        _connectionState.value = ConnectionState.DISCONNECTED
        _activeFeatures.value = emptySet()
        updateNotification("Disconnected")
    }

    fun startMedia() {
        Timber.d("Starting media capture")
        _mediaState.value = MediaState.CAPTURING
        if (mediaProjection != null) {
            screenCapture.startProjection(this, mediaProjection!!)
        }
        audioEncoder.start()
        videoEncoder.start()
        _mediaState.value = MediaState.STREAMING
        updateNotification("CarPlay Active — Streaming")
    }

    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
        screenCapture.setMediaProjection(projection)
    }

    fun stopMedia() {
        videoEncoder.stop()
        audioEncoder.stop()
        screenCapture.stop()
        _mediaState.value = MediaState.IDLE
    }

    fun sendTouchEvent(x: Int, y: Int, action: Byte) {
        val payload = byteArrayOf(
            action,
            ((x shr 8) and 0xFF).toByte(),
            (x and 0xFF).toByte(),
            ((y shr 8) and 0xFF).toByte(),
            (y and 0xFF).toByte()
        )
        val packet = IAP2Packet.build(IAP2Constants.SESSION_TOUCH, 0x00, payload)
        usbTransport.send(packet)
        logHex("TX TOUCH: x=$x y=$y action=${String.format("0x%02X", action)}")
    }

    fun getSessionManager() = sessionManager
    fun getTouchDispatcher() = touchDispatcher
    fun getGadgetConfigurator() = gadgetConfigurator

    /**
     * Get the full hex debug log
     */
    fun getDebugLog(): String {
        return try {
            File(IAP2Constants.DEBUG_LOG_PATH).readText()
        } catch (e: Exception) {
            "No log available"
        }
    }

    /**
     * Clear the debug log
     */
    fun clearDebugLog() {
        try {
            File(IAP2Constants.DEBUG_LOG_PATH).writeText("")
        } catch (e: Exception) {
            Timber.e(e, "Cannot clear debug log")
        }
    }

    // ── Init ───────────────────────────────────────────────

    private fun initComponents() {
        // Gadget configurator
        gadgetConfigurator = UsbGadgetConfigurator(this)

        // USB Transport with hex logging
        usbTransport = UsbTransport(this).apply {
            onConnectionChanged = { connected ->
                if (connected) {
                    _connectionState.value = ConnectionState.AUTHENTICATING
                    updateNotification("Authenticating...")
                    sessionManager.startHandshake()
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    updateNotification("USB disconnected")
                }
            }
            onDataReceived = { data ->
                logHex("RX ${data.size}B: ${bytesToHex(data, 64)}")
                sessionManager.handleData(data)
            }
        }
        usbTransport.init()

        // Session Manager with logging
        sessionManager = CarPlaySessionManager().apply {
            onDataSend = { data ->
                logHex("TX ${data.size}B: ${bytesToHex(data, 64)}")
                usbTransport.send(data)
            }
            onSessionEvent = { event -> handleSessionEvent(event) }
        }

        // Video Encoder
        videoEncoder = VideoEncoder().apply {
            onEncodedFrame = { frameData, isKeyframe ->
                val header = ByteArray(4)
                header[0] = if (isKeyframe) IAP2Constants.VIDEO_KEYFRAME_REQUEST
                            else IAP2Constants.VIDEO_FRAME
                header[1] = ((frameData.size shr 16) and 0xFF).toByte()
                header[2] = ((frameData.size shr 8) and 0xFF).toByte()
                header[3] = (frameData.size and 0xFF).toByte()
                val packet = IAP2Packet.build(IAP2Constants.SESSION_SCREEN, 0x00, header + frameData)
                usbTransport.send(packet)
            }
        }
        videoEncoder.init()

        // Audio Encoder
        audioEncoder = AudioEncoder().apply {
            onEncodedAudio = { audioData ->
                val packet = IAP2Packet.build(IAP2Constants.SESSION_AUDIO_IN, 0x00, audioData)
                usbTransport.send(packet)
            }
        }
        audioEncoder.init()

        // Screen Capture
        screenCapture = ScreenCaptureService()
        screenCapture.setVideoEncoder(videoEncoder)

        // Touch Dispatcher
        touchDispatcher = TouchDispatcher(this)

        // Reconnect Manager
        reconnectManager = ReconnectManager(
            onReconnect = {
                _connectionState.value = ConnectionState.CONNECTING
                updateNotification("Reconnecting... (${reconnectManager.getRetryCount()})")
                connect()
            },
            onReconnectFailed = {
                _connectionState.value = ConnectionState.ERROR
                updateNotification("Connection failed")
            }
        )
    }

    // ── Event Handling ─────────────────────────────────────

    private fun handleSessionEvent(event: CarPlaySessionManager.SessionEvent) {
        when (event) {
            is CarPlaySessionManager.SessionEvent.LinkEstablished -> {
                logHex("SESSION: Link established")
                updateNotification("Link established")
            }
            is CarPlaySessionManager.SessionEvent.Authenticated -> {
                logHex("SESSION: Authenticated!")
                _connectionState.value = ConnectionState.NEGOTIATING
                updateNotification("Negotiating sessions...")
            }
            is CarPlaySessionManager.SessionEvent.SessionActive -> {
                logHex("SESSION: ACTIVE! Sessions: ${event.sessions}")
                _connectionState.value = ConnectionState.ACTIVE
                updateNotification("CarPlay Active ✓")
                reconnectManager.reset()

                val features = mutableSetOf<String>()
                event.sessions.forEach { session ->
                    when (session) {
                        IAP2Constants.SESSION_SCREEN -> features.add("screen")
                        IAP2Constants.SESSION_MEDIA -> features.add("media")
                        IAP2Constants.SESSION_AUDIO_IN -> features.add("mic")
                        IAP2Constants.SESSION_AUDIO_OUT -> features.add("audio")
                        IAP2Constants.SESSION_TOUCH -> features.add("touch")
                        IAP2Constants.SESSION_PHONE -> features.add("phone")
                    }
                }
                _activeFeatures.value = features
            }
            is CarPlaySessionManager.SessionEvent.Disconnected -> {
                _connectionState.value = ConnectionState.DISCONNECTED
                _activeFeatures.value = emptySet()
                if (reconnectManager.getRetryCount() < 10) {
                    reconnectManager.startReconnect()
                }
            }
            is CarPlaySessionManager.SessionEvent.TouchInput -> {
                touchDispatcher.dispatchTouch(event.x, event.y, event.action)
            }
            is CarPlaySessionManager.SessionEvent.Error -> {
                _connectionState.value = ConnectionState.ERROR
                updateNotification("Error: ${event.message}")
            }
            else -> {}
        }
    }

    // ── Debug Hex Logging ──────────────────────────────────

    private fun logHex(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$timestamp] $message"
        Timber.d(line)
        try {
            File(IAP2Constants.DEBUG_LOG_PATH).appendText(line + "\n")
        } catch (e: Exception) {
            // Silent fail
        }
    }

    private fun bytesToHex(bytes: ByteArray, maxLen: Int): String {
        val sb = StringBuilder()
        val limit = minOf(bytes.size, maxLen)
        for (i in 0 until limit) {
            sb.append(String.format("%02X ", bytes[i]))
        }
        if (bytes.size > maxLen) sb.append("...(${bytes.size}B)")
        return sb.toString().trim()
    }

    // ── Notification ───────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "CarPlay Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CarPlay connection status"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }

        return builder
            .setContentTitle("CarPlay Android")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }
}
