package com.carplay.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.carplay.android.media.AudioEncoder
import com.carplay.android.media.ScreenCaptureService
import com.carplay.android.media.VideoEncoder
import com.carplay.android.protocol.CarPlaySessionManager
import com.carplay.android.protocol.IAP2Constants
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
 * Runs as foreground service to maintain persistent connection
 * with the car's head unit.
 */
class CarPlayService : Service() {

    companion object {
        private const val TAG = "CarPlayService"
        private const val NOTIFICATION_CHANNEL_ID = "carplay_channel"
        private const val NOTIFICATION_ID = 1001

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

    // ── State ──────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _activeFeatures = MutableStateFlow(setOf<String>())
    val activeFeatures: StateFlow<Set<String>> = _activeFeatures

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATING,
        NEGOTIATING,
        ACTIVE,
        ERROR
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

        // Enumerate and connect USB devices
        val devices = usbTransport.enumerateDevices()
        if (devices.isNotEmpty()) {
            usbTransport.connect(devices.first())
        } else {
            Timber.w("No USB devices found")
            _connectionState.value = ConnectionState.ERROR
            updateNotification("No device found")
        }
    }

    /**
     * Disconnect CarPlay
     */
    fun disconnect() {
        Timber.d("Disconnecting CarPlay")
        stopMedia()
        sessionManager.closeSession()
        usbTransport.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
        _activeFeatures.value = emptySet()
        updateNotification("Disconnected")
    }

    /**
     * Start media capture (video + audio)
     */
    fun startMedia() {
        Timber.d("Starting media capture")
        videoEncoder.start()
        audioEncoder.start()
    }

    /**
     * Stop media capture
     */
    fun stopMedia() {
        Timber.d("Stopping media capture")
        videoEncoder.stop()
        audioEncoder.stop()
        screenCapture.stop()
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
        val packet = com.carplay.android.protocol.IAP2Packet.build(
            IAP2Constants.SESSION_TOUCH, 0x00, payload
        )
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
                // Send video frame to head unit
                val header = byteArrayOf(
                    if (isKeyframe) IAP2Constants.VIDEO_KEYFRAME_REQUEST
                    else IAP2Constants.VIDEO_FRAME,
                    ((frameData.size shr 16) and 0xFF).toByte(),
                    ((frameData.size shr 8) and 0xFF).toByte(),
                    (frameData.size and 0xFF).toByte()
                )
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
        screenCapture = ScreenCaptureService().apply {
            videoEncoder = this@CarPlayService.videoEncoder
        }
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
            }

            is CarPlaySessionManager.SessionEvent.TouchInput -> {
                // Touch from head unit - dispatch to Android touch system
                Timber.d("Touch: ${event.x}, ${event.y} action=${event.action}")
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

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
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
