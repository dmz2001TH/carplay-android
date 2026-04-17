package com.carplay.android.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * CarPlay Session Manager
 *
 * Manages the full CarPlay session lifecycle:
 * 1. Link synchronization (SYN/ACK)
 * 2. MFi authentication
 * 3. Session negotiation (control, screen, audio, touch)
 * 4. Active session management
 * 5. Cleanup
 */
class CarPlaySessionManager {

    companion object {
        private const val TAG = "CarPlaySession"
        private const val MAX_AUTH_RETRIES = 3
        private const val SESSION_TIMEOUT_MS = 10_000L
    }

    // ── Session State ──────────────────────────────────────

    enum class SessionState {
        DISCONNECTED,
        LINK_SYNC,           // SYN/ACK handshake
        AUTH_CHALLENGE,      // Waiting for auth challenge
        AUTH_RESPONSE,       // Sending auth response
        AUTH_PENDING,        // Waiting for auth result
        AUTHENTICATED,       // Auth success
        SESSION_NEGOTIATING, // Starting sessions
        ACTIVE,              // All sessions running
        ERROR
    }

    private val _state = MutableStateFlow(SessionState.DISCONNECTED)
    val state: StateFlow<SessionState> = _state

    // ── Components ─────────────────────────────────────────

    val authHandler = MFiAuthHandler()

    // Active sessions
    private val activeSessions = mutableMapOf<Byte, Boolean>()

    // Callback for sending data
    var onDataSend: ((ByteArray) -> Unit)? = null

    // Callback for session events
    var onSessionEvent: ((SessionEvent) -> Unit)? = null

    private var authRetries = 0

    // ── Public API ─────────────────────────────────────────

    /**
     * Start the CarPlay handshake sequence
     */
    fun startHandshake() {
        Timber.d("Starting CarPlay handshake")
        _state.value = SessionState.LINK_SYNC
        authRetries = 0

        // Step 1: Send SYN packet
        val synPacket = IAP2Packet.buildSyn()
        sendPacket(synPacket)
    }

    /**
     * Handle incoming data from head unit
     */
    fun handleData(data: ByteArray) {
        when (_state.value) {
            SessionState.LINK_SYNC -> handleLinkSync(data)
            SessionState.AUTH_CHALLENGE -> handleAuthChallenge(data)
            SessionState.AUTH_PENDING -> handleAuthResult(data)
            SessionState.AUTHENTICATED -> handleAuthenticated(data)
            SessionState.SESSION_NEGOTIATING -> handleSessionNegotiation(data)
            SessionState.ACTIVE -> handleActiveSession(data)
            else -> Timber.w("Unexpected data in state: ${_state.value}")
        }
    }

    /**
     * Open a specific session type
     */
    fun openSession(sessionType: Byte) {
        Timber.d("Opening session: 0x${sessionType.toUByte().toString(16)}")
        val packet = IAP2Packet.buildStartSession(sessionType)
        sendPacket(packet)
    }

    /**
     * Close session and cleanup
     */
    fun closeSession() {
        Timber.d("Closing CarPlay session")

        // Send stop for all active sessions
        activeSessions.keys.forEach { sessionType ->
            val stopPacket = IAP2Packet.build(IAP2Constants.CTRL_STOP_SESSION.toByte(), sessionType)
            sendPacket(stopPacket)
        }

        activeSessions.clear()
        authHandler.reset()
        _state.value = SessionState.DISCONNECTED
        onSessionEvent?.invoke(SessionEvent.Disconnected)
    }

    /**
     * Check if session is active
     */
    fun isActive() = _state.value == SessionState.ACTIVE

    /**
     * Get active session types
     */
    fun getActiveSessions(): Set<Byte> = activeSessions.keys.toSet()

    // ── State Handlers ─────────────────────────────────────

    private fun handleLinkSync(data: ByteArray) {
        Timber.d("Handling link sync response")

        // Expect ACK from head unit
        if (data.isNotEmpty() && data[0] == IAP2Constants.LINK_PACKET_ACK) {
            Timber.d("Received ACK - link established")
            _state.value = SessionState.AUTH_CHALLENGE
            onSessionEvent?.invoke(SessionEvent.LinkEstablished)

            // Request device info
            val deviceInfoReq = IAP2Packet.buildDeviceInfoRequest()
            sendPacket(deviceInfoReq)
        } else {
            Timber.w("Expected ACK, got: 0x${data.firstOrNull()?.toUByte()?.toString(16) ?: "empty"}")
            // Some head units send SYN-ACK, handle it
            if (data.size >= 2 && data[0] == IAP2Constants.LINK_PACKET_SYN) {
                val ackPacket = IAP2Packet.buildAck()
                sendPacket(ackPacket)
                _state.value = SessionState.AUTH_CHALLENGE
                onSessionEvent?.invoke(SessionEvent.LinkEstablished)
            }
        }
    }

    private fun handleAuthChallenge(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return

        if (packet.isAuthChallenge()) {
            Timber.d("Received auth challenge")
            _state.value = SessionState.AUTH_RESPONSE

            // Step 1: Send certificate first
            val cert = authHandler.handleCertificateRequest()
            val certPacket = IAP2Packet.build(IAP2Constants.CTRL_AUTH_CERTIFICATE.toByte(), 0x00, cert)
            sendPacket(certPacket)

            // Step 2: Send challenge response
            val response = authHandler.handleChallenge(packet.payload)
            if (response.isNotEmpty()) {
                sendPacket(response)
                _state.value = SessionState.AUTH_PENDING
                Timber.d("Auth response sent, waiting for result")
            } else {
                handleAuthError("Failed to generate auth response")
            }
        } else if (packet.isDeviceInfoResponse()) {
            Timber.d("Received device info response")
            // Still waiting for auth challenge
        } else {
            Timber.w("Unexpected packet in AUTH_CHALLENGE state: ctrl=0x${packet.control.toUByte().toString(16)}")
        }
    }

    private fun handleAuthResult(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return

        when {
            packet.isAuthSuccess() -> {
                Timber.d("Authentication SUCCESS!")
                authHandler.onAuthSuccess(null)
                _state.value = SessionState.AUTHENTICATED
                onSessionEvent?.invoke(SessionEvent.Authenticated)

                // Start session negotiation
                startSessionNegotiation()
            }
            packet.isAuthFailed() -> {
                authRetries++
                if (authRetries < MAX_AUTH_RETRIES) {
                    Timber.w("Auth failed, retry $authRetries/$MAX_AUTH_RETRIES")
                    _state.value = SessionState.LINK_SYNC
                    startHandshake()
                } else {
                    handleAuthError("Auth failed after $MAX_AUTH_RETRIES retries")
                }
            }
            else -> {
                Timber.w("Unexpected packet in AUTH_PENDING state")
            }
        }
    }

    private fun handleAuthenticated(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return

        when {
            packet.isSessionAck() -> {
                val sessionType = packet.control
                Timber.d("Session ACK for: 0x${sessionType.toUByte().toString(16)}")
                activeSessions[sessionType] = true
                checkAllSessionsReady()
            }
            packet.isSessionOpened() -> {
                val sessionType = packet.control
                Timber.d("Session opened: 0x${sessionType.toUByte().toString(16)}")
                activeSessions[sessionType] = true
                checkAllSessionsReady()
            }
        }
    }

    private fun handleSessionNegotiation(data: ByteArray) {
        handleAuthenticated(data) // Same logic
    }

    private fun handleActiveSession(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return

        // Handle touch input from head unit
        if (packet.isTouchInput()) {
            val touchData = packet.getTouchData()
            if (touchData != null) {
                onSessionEvent?.invoke(SessionEvent.TouchInput(touchData.x, touchData.y, touchData.action))
            }
        }

        // Handle other active session messages
        onSessionEvent?.invoke(SessionEvent.DataReceived(packet.type, packet.payload))
    }

    // ── Session Negotiation ────────────────────────────────

    private fun startSessionNegotiation() {
        Timber.d("Starting session negotiation")
        _state.value = SessionState.SESSION_NEGOTIATING

        // Open required sessions in order
        val sessions = listOf(
            IAP2Constants.SESSION_CONTROL,
            IAP2Constants.SESSION_SCREEN,
            IAP2Constants.SESSION_MEDIA,
            IAP2Constants.SESSION_AUDIO_IN,
            IAP2Constants.SESSION_AUDIO_OUT,
            IAP2Constants.SESSION_TOUCH,
            IAP2Constants.SESSION_PHONE
        )

        sessions.forEach { sessionType ->
            openSession(sessionType)
        }

        // Send CarPlay info
        val carPlayInfo = IAP2Packet.buildCarPlayInfo()
        sendPacket(carPlayInfo)

        onSessionEvent?.invoke(SessionEvent.SessionNegotiating)
    }

    private fun checkAllSessionsReady() {
        val requiredSessions = setOf(
            IAP2Constants.SESSION_CONTROL,
            IAP2Constants.SESSION_SCREEN,
            IAP2Constants.SESSION_AUDIO_OUT
        )

        if (activeSessions.keys.containsAll(requiredSessions)) {
            Timber.d("All required sessions active! Total: ${activeSessions.size}")
            _state.value = SessionState.ACTIVE
            onSessionEvent?.invoke(SessionEvent.SessionActive(activeSessions.keys.toList()))
        }
    }

    // ── Error Handling ─────────────────────────────────────

    private fun handleAuthError(reason: String) {
        Timber.e("Auth error: $reason")
        authHandler.onAuthFailed(reason)
        _state.value = SessionState.ERROR
        onSessionEvent?.invoke(SessionEvent.Error(reason))
    }

    // ── Helpers ────────────────────────────────────────────

    private fun sendPacket(data: ByteArray) {
        onDataSend?.invoke(data)
    }

    // ── Session Events ─────────────────────────────────────

    sealed class SessionEvent {
        object LinkEstablished : SessionEvent()
        object Authenticated : SessionEvent()
        object SessionNegotiating : SessionEvent()
        data class SessionActive(val sessions: List<Byte>) : SessionEvent()
        object Disconnected : SessionEvent()
        data class TouchInput(val x: Int, val y: Int, val action: Byte) : SessionEvent()
        data class DataReceived(val type: Byte, val data: ByteArray) : SessionEvent()
        data class Error(val message: String) : SessionEvent()
    }
}
