package com.carplay.android.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * CarPlay Session Manager
 *
 * Manages the full CarPlay session lifecycle:
 * 1. Link synchronization (SYN → SYN-ACK → ACK)
 * 2. MFi authentication (Challenge → Certificate → Response → Result)
 * 3. Session negotiation (Control, Screen, Audio, Touch, Phone)
 * 4. Active session management
 * 5. Cleanup and disconnect
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
        LINK_SYNC,           // Sending SYN, waiting for SYN-ACK
        LINK_ESTABLISHED,    // Link layer connected
        AUTH_CHALLENGE,      // Waiting for auth challenge
        AUTH_RESPONSE,       // Sending certificate + auth response
        AUTH_PENDING,        // Waiting for auth result
        AUTHENTICATED,       // Auth success
        SESSION_NEGOTIATING, // Opening sessions
        ACTIVE,              // All sessions running
        ERROR
    }

    private val _state = MutableStateFlow(SessionState.DISCONNECTED)
    val state: StateFlow<SessionState> = _state

    // ── Components ─────────────────────────────────────────

    val authHandler = MFiAuthHandler()

    // Active sessions: sessionType → isActive
    private val activeSessions = mutableMapOf<Byte, Boolean>()

    // Callbacks
    var onDataSend: ((ByteArray) -> Unit)? = null
    var onSessionEvent: ((SessionEvent) -> Unit)? = null

    private var authRetries = 0
    private var linkRetryCount = 0

    // ── Public API ─────────────────────────────────────────

    /**
     * Start the CarPlay handshake sequence
     */
    fun startHandshake() {
        Timber.d("Starting CarPlay handshake")
        _state.value = SessionState.LINK_SYNC
        authRetries = 0
        linkRetryCount = 0

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
            SessionState.LINK_ESTABLISHED -> handleLinkEstablished(data)
            SessionState.AUTH_CHALLENGE -> handleAuthChallenge(data)
            SessionState.AUTH_PENDING -> handleAuthResult(data)
            SessionState.AUTHENTICATED,
            SessionState.SESSION_NEGOTIATING -> handleSessionMessage(data)
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
            val stopPacket = IAP2Packet.buildStopSession(sessionType)
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

    /**
     * Get current state
     */
    fun getState() = _state.value

    // ── State Handlers ─────────────────────────────────────

    /**
     * Handle link sync phase — waiting for SYN-ACK or ACK
     */
    private fun handleLinkSync(data: ByteArray) {
        val packet = IAP2Packet.parse(data)
        if (packet == null) {
            Timber.w("Failed to parse link sync response")
            return
        }

        Timber.d("Link sync: received packet type=0x${packet.type.toUByte().toString(16)}, " +
                "isLink=${packet.isLinkPacket}")

        when {
            packet.isLinkSynAck() || packet.isLinkAck() -> {
                // Head unit acknowledged — send ACK back
                Timber.d("Received SYN-ACK/ACK — sending ACK")
                val ackPacket = IAP2Packet.buildAck()
                sendPacket(ackPacket)

                _state.value = SessionState.LINK_ESTABLISHED
                linkRetryCount = 0
                onSessionEvent?.invoke(SessionEvent.LinkEstablished)
            }
            packet.isLinkSyn() -> {
                // Head unit also sent SYN — respond with ACK
                Timber.d("Received SYN — sending ACK")
                val ackPacket = IAP2Packet.buildAck()
                sendPacket(ackPacket)

                _state.value = SessionState.LINK_ESTABLISHED
                onSessionEvent?.invoke(SessionEvent.LinkEstablished)
            }
            else -> {
                Timber.w("Unexpected packet in LINK_SYNC state")
                // Retry SYN
                if (linkRetryCount < 3) {
                    linkRetryCount++
                    val synPacket = IAP2Packet.buildSyn()
                    sendPacket(synPacket)
                }
            }
        }
    }

    /**
     * Handle data after link is established — waiting for auth challenge
     */
    private fun handleLinkEstablished(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return

        when {
            packet.isAuthChallenge() -> {
                Timber.d("Received auth challenge")
                _state.value = SessionState.AUTH_CHALLENGE
                handleAuthChallengeInternal(packet)
            }
            packet.isDeviceInfoResponse() -> {
                Timber.d("Received device info response — still waiting for auth challenge")
            }
            else -> {
                Timber.d("Received unexpected packet in LINK_ESTABLISHED, " +
                        "type=0x${packet.type.toUByte().toString(16)}")
            }
        }
    }

    /**
     * Handle auth challenge
     */
    private fun handleAuthChallenge(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return
        handleAuthChallengeInternal(packet)
    }

    private fun handleAuthChallengeInternal(packet: IAP2Packet.ParsedPacket) {
        if (!packet.isAuthChallenge()) {
            Timber.w("Expected auth challenge, got type=0x${packet.type.toUByte().toString(16)}")
            return
        }

        Timber.d("Processing auth challenge: ${packet.payload.size}B")
        _state.value = SessionState.AUTH_RESPONSE

        // Step 1: Send MFi certificate
        val cert = authHandler.handleCertificateRequest()
        val certPacket = IAP2Packet.build(
            IAP2Constants.SESSION_CONTROL,
            IAP2Constants.CTRL_AUTH_CERTIFICATE.toByte(),
            cert
        )
        sendPacket(certPacket)

        // Step 2: Generate and send auth response
        val response = authHandler.handleChallenge(packet.payload)
        if (response.isNotEmpty()) {
            sendPacket(response)
            _state.value = SessionState.AUTH_PENDING
            Timber.d("Auth response sent, waiting for result")
        } else {
            handleAuthError("Failed to generate auth response")
        }
    }

    /**
     * Handle authentication result
     */
    private fun handleAuthResult(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return

        when {
            packet.isAuthSuccess() -> {
                Timber.d("✅ Authentication SUCCESS!")
                authHandler.onAuthSuccess(null)
                _state.value = SessionState.AUTHENTICATED
                onSessionEvent?.invoke(SessionEvent.Authenticated)

                // Request device info then start session negotiation
                val deviceInfoReq = IAP2Packet.buildDeviceInfoRequest()
                sendPacket(deviceInfoReq)

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
                Timber.w("Unexpected packet in AUTH_PENDING: type=0x${packet.type.toUByte().toString(16)}")
            }
        }
    }

    /**
     * Handle session negotiation messages
     */
    private fun handleSessionMessage(data: ByteArray) {
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
            packet.isAuthSuccess() -> {
                Timber.d("Re-auth success (duplicate)")
            }
            else -> {
                Timber.d("Session message: type=0x${packet.type.toUByte().toString(16)}")
            }
        }
    }

    /**
     * Handle data in active session
     */
    private fun handleActiveSession(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return

        when {
            packet.isTouchInput() -> {
                val touchData = packet.getTouchData()
                if (touchData != null) {
                    onSessionEvent?.invoke(
                        SessionEvent.TouchInput(touchData.x, touchData.y, touchData.action)
                    )
                }
            }
            packet.isVideoData() -> {
                onSessionEvent?.invoke(SessionEvent.VideoData(packet.payload))
            }
            packet.isAudioData() -> {
                onSessionEvent?.invoke(SessionEvent.AudioData(packet.payload))
            }
            packet.isSessionAck() -> {
                Timber.d("Late session ACK")
            }
            else -> {
                onSessionEvent?.invoke(
                    SessionEvent.DataReceived(packet.type, packet.payload)
                )
            }
        }
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

        // Send CarPlay device info
        val carPlayInfo = IAP2Packet.buildCarPlayInfo()
        sendPacket(carPlayInfo)

        onSessionEvent?.invoke(SessionEvent.SessionNegotiating)
    }

    private fun checkAllSessionsReady() {
        // Minimum required sessions for CarPlay to work
        val requiredSessions = setOf(
            IAP2Constants.SESSION_CONTROL,
            IAP2Constants.SESSION_SCREEN,
            IAP2Constants.SESSION_AUDIO_OUT
        )

        if (activeSessions.keys.containsAll(requiredSessions)) {
            Timber.d("✅ All required sessions active! Total: ${activeSessions.size}")
            _state.value = SessionState.ACTIVE
            onSessionEvent?.invoke(SessionEvent.SessionActive(activeSessions.keys.toList()))
        } else {
            val missing = requiredSessions - activeSessions.keys
            Timber.d("Waiting for sessions: ${missing.map { "0x${it.toUByte().toString(16)}" }}")
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
        data class VideoData(val data: ByteArray) : SessionEvent()
        data class AudioData(val data: ByteArray) : SessionEvent()
        data class DataReceived(val type: Byte, val data: ByteArray) : SessionEvent()
        data class Error(val message: String) : SessionEvent()
    }
}
