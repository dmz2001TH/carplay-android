package com.carplay.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.carplay.android.media.AudioEncoder
import com.carplay.android.media.ScreenCaptureService
import com.carplay.android.media.VideoEncoder
import com.carplay.android.protocol.CarPlaySessionManager
import com.carplay.android.protocol.IAP2Constants
import com.carplay.android.protocol.IAP2Packet
import com.carplay.android.service.ReconnectManager
import com.carplay.android.service.TouchDispatcher
import com.carplay.android.ui.MainActivity
import com.carplay.android.usb.UsbTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * CarPlay Foreground Service
 *
 * Main service that runs the CarPlay connection, manages
 * USB transport, protocol session, and media encoding.
 *
 * Lifecycle:
 * 1. Service starts → initializes USB transport, session manager, encoders
 * 2. User taps Connect → enumerates USB devices, initiates handshake
 * 3. Link sync → MFi auth → session negotiation → Active
 * 4. Screen capture → H.264 encode → USB send to head unit
 * 5. Audio capture → AAC encode → USB send to head unit
 * 6. Touch events from head unit → dispatch to Android
 *
 * Features:
 * - Auto-reconnect with exponential backoff on unexpected disconnect
 * - Touch input dispatching from head unit to Android
 * - Heartbeat keepalive to detect stale connections
 */
class CarPlayService : Service() {

    companion object {
        private const val TAG = "CarPlayService"
        private const val NOTIFICATION_CHANNEL_ID = "carplay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 15_000L

        // Service actions
        const val ACTION_CONNECT = "com.carplay.CONNECT"
        const val ACTION_DISCONNECT = "com.carplay.DISCONNECT"
        const val ACTION_START_MEDIA = "com.carplay.START_MEDIA"
        const val ACTION_STOP_MEDIA = "com.carplay.STOP_MEDIA"
    }

    // ── Components ─────────────────────────────────────────

    private lateinit var usbTransport: UsbTransport
    private lateinit var sessionManager: CarPlaySessionManager
    private lateinit var videoEncoder: VideoEncoder
    private lateinit var audioEncoder: AudioEncoder
    private lateinit var screenCapture: ScreenCaptureService
    private lateinit var reconnectManager: ReconnectManager
    private lateinit var touchDispatcher: TouchDispatcher

    // MediaProjection for screen capture
    private var mediaProjection: MediaProjection? = null

    // Heartbeat / keepalive
    private var heartbeatHandler: android.os.Handler? = null
    private var heartbeatRunnable: Runnable? = null
    private var lastHeartbeatResponse = 0L

    // ── State ──────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _activeFeatures = MutableStateFlow(setOf<String>())
    val activeFeatures: StateFlow<Set<String>> = _activeFeatures

    private val _mediaState = MutableStateFlow(MediaState.IDLE)
    val mediaState: StateFlow<MediaState> = _mediaState

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATING,
        NEGOTIATING,
        ACTIVE,
        ERROR
    }

    enum class MediaState {
        IDLE,
        CAPTURING,
        ENCODING,
        STREAMING
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
        // Start foreground with connectedDevice type only
        // (mediaProjection type will be added when screen capture is actually granted)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification("Initializing..."),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        }

        initComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            ACTION_START_MEDIA -> startMedia()
            ACTION_STOP_MEDIA -> stopMedia()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("CarPlay service destroyed")
        disconnect()
    }

    // ── Public API ─────────────────────────────────────────

    /**
     * Start CarPlay connection
     */
    fun connect() {
        Timber.d("Starting CarPlay connection")
        _connectionState.value = ConnectionState.CONNECTING
        updateNotification("Connecting...")

        // Try USB Host mode first
        val devices = usbTransport.enumerateDevices()
        if (devices.isNotEmpty()) {
            usbTransport.connect(devices.first())
        } else {
            // Fall back to checking for accessories
            usbTransport.checkForAccessory()
            if (!usbTransport.isConnected()) {
                Timber.w("No USB devices or accessories found")
                _connectionState.value = ConnectionState.ERROR
                updateNotification("No device found")
            }
        }
    }

    /**
     * Disconnect CarPlay
     */
    fun disconnect() {
        Timber.d("Disconnecting CarPlay")
        reconnectManager.cancel()
        stopHeartbeat()
        stopMedia()
        sessionManager.closeSession()
        usbTransport.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _activeFeatures.value = emptySet()
        updateNotification("Disconnected")
    }

    /**
     * Start media capture (screen + audio)
     * Call this AFTER getting MediaProjection permission from user
     */
    fun startMedia() {
        Timber.d("Starting media capture")
        _mediaState.value = MediaState.CAPTURING

        // Start screen capture (needs MediaProjection to be set via startScreenCapture)
        if (mediaProjection != null) {
            screenCapture.startProjection(this, mediaProjection!!)
        }

        // Start audio encoder
        audioEncoder.start()
        videoEncoder.start()

        _mediaState.value = MediaState.STREAMING
        updateNotification("CarPlay Active — Streaming")
    }

    /**
     * Set MediaProjection (from Activity permission result)
     * Call this from MainActivity after getting user permission.
     * Also updates foreground service type to include mediaProjection.
     */
    fun setMediaProjection(projection: MediaProjection) {
        Timber.d("MediaProjection set")
        this.mediaProjection = projection
        screenCapture.setMediaProjection(projection)

        // Update foreground service type to include mediaProjection (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val notification = createNotification("Screen capture ready")
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                Timber.d("Foreground service type updated with mediaProjection")
            } catch (e: Exception) {
                Timber.w(e, "Could not update foreground service type")
            }
        }
    }

    /**
     * Stop media capture
     */
    fun stopMedia() {
        Timber.d("Stopping media capture")
        videoEncoder.stop()
        audioEncoder.stop()
        screenCapture.stop()
        _mediaState.value = MediaState.IDLE
    }

    /**
     * Send touch event to head unit
     */
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
    }

    /**
     * Request video keyframe
     */
    fun requestKeyframe() {
        videoEncoder.requestKeyframe()
    }

    /**
     * Get session manager (for UI to observe state)
     */
    fun getSessionManager() = sessionManager

    /**
     * Get touch dispatcher (for calibration/configuration)
     */
    fun getTouchDispatcher() = touchDispatcher

    /**
     * Get reconnect manager (for UI control)
     */
    fun getReconnectManager() = reconnectManager

    // ── Heartbeat / Keepalive ──────────────────────────────

    /**
     * Start connection heartbeat to detect stale connections
     */
    private fun startHeartbeat() {
        lastHeartbeatResponse = System.currentTimeMillis()
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (_connectionState.value == ConnectionState.ACTIVE) {
                    val elapsed = System.currentTimeMillis() - lastHeartbeatResponse
                    if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                        Timber.w("Heartbeat timeout (${elapsed}ms since last response)")
                        // Connection appears stale — let reconnect manager handle it
                        disconnect()
                    } else {
                        // Send keepalive ping
                        val ping = IAP2Packet.build(IAP2Constants.SESSION_CONTROL, 0x00, byteArrayOf(0x00))
                        usbTransport.send(ping)
                    }
                }
                heartbeatHandler?.postDelayed(this, HEARTBEAT_INTERVAL_MS)
            }
        }
        heartbeatHandler?.postDelayed(heartbeatRunnable!!, HEARTBEAT_INTERVAL_MS)
    }

    /**
     * Stop heartbeat monitoring
     */
    private fun stopHeartbeat() {
        heartbeatRunnable?.let { heartbeatHandler?.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    /**
     * Call when receiving any data from head unit (resets heartbeat timer)
     */
    fun onHeartbeatResponse() {
        lastHeartbeatResponse = System.currentTimeMillis()
    }

    // ── Initialization ─────────────────────────────────────

    private fun initComponents() {
        // USB Transport
        usbTransport = UsbTransport(this).apply {
            onConnectionChanged = { connected ->
                if (connected) {
                    Timber.d("USB connected, starting protocol handshake")
                    _connectionState.value = ConnectionState.AUTHENTICATING
                    updateNotification("Authenticating...")
                    sessionManager.startHandshake()
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    updateNotification("USB disconnected")
                }
            }

            onDataReceived = { data ->
                onHeartbeatResponse()
                sessionManager.handleData(data)
            }
        }
        usbTransport.init()

        // Session Manager
        sessionManager = CarPlaySessionManager().apply {
            onDataSend = { data ->
                usbTransport.send(data)
            }

            onSessionEvent = { event ->
                handleSessionEvent(event)
            }
        }

        // Video Encoder
        videoEncoder = VideoEncoder().apply {
            onEncodedFrame = { frameData, isKeyframe ->
                // Build video packet for head unit
                val header = ByteArray(4)
                header[0] = if (isKeyframe) IAP2Constants.VIDEO_KEYFRAME_REQUEST
                            else IAP2Constants.VIDEO_FRAME
                header[1] = ((frameData.size shr 16) and 0xFF).toByte()
                header[2] = ((frameData.size shr 8) and 0xFF).toByte()
                header[3] = (frameData.size and 0xFF).toByte()

                val packet = IAP2Packet.build(
                    IAP2Constants.SESSION_SCREEN, 0x00, header + frameData
                )
                usbTransport.send(packet)
            }
        }
        videoEncoder.init()

        // Audio Encoder
        audioEncoder = AudioEncoder().apply {
            onEncodedAudio = { audioData ->
                val packet = IAP2Packet.build(
                    IAP2Constants.SESSION_AUDIO_IN, 0x00, audioData
                )
                usbTransport.send(packet)
            }
        }
        audioEncoder.init()

        // Screen Capture Service
        screenCapture = ScreenCaptureService().apply {
            setVideoEncoder(this@CarPlayService.videoEncoder)
        }

        // Touch Dispatcher
        touchDispatcher = TouchDispatcher(this)

        // Reconnect Manager
        reconnectManager = ReconnectManager(
            onReconnect = {
                Timber.d("Attempting reconnect...")
                _connectionState.value = ConnectionState.CONNECTING
                updateNotification("Reconnecting... (attempt ${reconnectManager.getRetryCount()})")
                connect()
            },
            onReconnectFailed = {
                Timber.e("All reconnect attempts failed")
                _connectionState.value = ConnectionState.ERROR
                updateNotification("Connection failed — tap to retry")
            }
        )

        // Heartbeat handler for connection keepalive
        heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }

    // ── Event Handling ─────────────────────────────────────

    private fun handleSessionEvent(event: CarPlaySessionManager.SessionEvent) {
        when (event) {
            is CarPlaySessionManager.SessionEvent.LinkEstablished -> {
                Timber.d("Link established")
                updateNotification("Link established")
            }

            is CarPlaySessionManager.SessionEvent.Authenticated -> {
                Timber.d("Authenticated!")
                _connectionState.value = ConnectionState.NEGOTIATING
                updateNotification("Negotiating sessions...")
            }

            is CarPlaySessionManager.SessionEvent.SessionNegotiating -> {
                _connectionState.value = ConnectionState.NEGOTIATING
            }

            is CarPlaySessionManager.SessionEvent.SessionActive -> {
                Timber.d("CarPlay ACTIVE! Sessions: ${event.sessions}")
                _connectionState.value = ConnectionState.ACTIVE
                updateNotification("CarPlay Active ✓")

                // Reset reconnect counter on successful connection
                reconnectManager.reset()

                // Start heartbeat monitoring
                startHeartbeat()

                // Update active features
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
                stopHeartbeat()

                // Auto-reconnect on unexpected disconnect
                if (reconnectManager.getRetryCount() < 10) {
                    Timber.d("Unexpected disconnect, starting auto-reconnect")
                    reconnectManager.startReconnect()
                }
            }

            is CarPlaySessionManager.SessionEvent.TouchInput -> {
                Timber.d("Touch from head unit: ${event.x}, ${event.y} action=${event.action}")
                // Dispatch touch event to Android input system
                touchDispatcher.dispatchTouch(event.x, event.y, event.action)
            }

            is CarPlaySessionManager.SessionEvent.Error -> {
                Timber.e("Session error: ${event.message}")
                _connectionState.value = ConnectionState.ERROR
                updateNotification("Error: ${event.message}")
            }

            else -> {}
        }
    }

    // ── Notification ───────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "CarPlay Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "CarPlay head unit connection status"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setPriority(Notification.PRIORITY_LOW)
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
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }
}
