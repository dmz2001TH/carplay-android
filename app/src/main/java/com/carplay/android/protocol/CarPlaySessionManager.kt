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
 *
 * NOTE: MFi auth will fail on real head units without a hardware chip.
 * This implementation handles the protocol handshake for development/testing.
 */
class CarPlaySessionManager {

    companion object {
        private const val TAG = "CarPlaySession"
        private const val MAX_AUTH_RETRIES = 3
        private const val MAX_LINK_RETRIES = 5
    }

    // ── Session State ──────────────────────────────────────

    enum class SessionState {
        DISCONNECTED,
        LINK_SYNC,
        LINK_ESTABLISHED,
        AUTH_CHALLENGE,
        AUTH_RESPONSE,
        AUTH_PENDING,
        AUTHENTICATED,
        SESSION_NEGOTIATING,
        ACTIVE,
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
    var onError: ((String, Throwable?) -> Unit)? = null

    private var authRetries = 0
    private var linkRetryCount = 0

    // ── Public API ─────────────────────────────────────────

    fun startHandshake() {
        Timber.d("Starting CarPlay handshake")
        _state.value = SessionState.LINK_SYNC
        authRetries = 0
        linkRetryCount = 0

        val synPacket = IAP2Packet.buildSyn()
        sendPacket(synPacket)
    }

    fun handleData(data: ByteArray) {
        try {
            when (_state.value) {
                SessionState.LINK_SYNC -> handleLinkSync(data)
                SessionState.LINK_ESTABLISHED -> handleLinkEstablished(data)
                SessionState.AUTH_CHALLENGE -> handleAuthChallenge(data)
                SessionState.AUTH_PENDING -> handleAuthResult(data)
                SessionState.AUTHENTICATED,
                SessionState.SESSION_NEGOTIATING -> handleSessionMessage(data)
                SessionState.ACTIVE -> handleActiveSession(data)
                else -> Timber.v("Data received in state: ${_state.value} (${data.size}B)")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error handling data in state ${_state.value}")
            onError?.invoke("Protocol error", e)
        }
    }

    fun openSession(sessionType: Byte) {
        Timber.d("Opening session: 0x${sessionType.toUByte().toString(16)}")
        val packet = IAP2Packet.buildStartSession(sessionType)
        sendPacket(packet)
    }

    fun closeSession() {
        Timber.d("Closing CarPlay session")
        try {
            activeSessions.keys.forEach { sessionType ->
                val stopPacket = IAP2Packet.buildStopSession(sessionType)
                sendPacket(stopPacket)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error sending session stop packets")
        }
        activeSessions.clear()
        authHandler.reset()
        _state.value = SessionState.DISCONNECTED
        onSessionEvent?.invoke(SessionEvent.Disconnected)
    }

    fun isActive() = _state.value == SessionState.ACTIVE
    fun getActiveSessions(): Set<Byte> = activeSessions.keys.toSet()
    fun getState() = _state.value

    // ── State Handlers ─────────────────────────────────────

    private fun handleLinkSync(data: ByteArray) {
        val packet = IAP2Packet.parse(data)
        if (packet == null) {
            Timber.v("Failed to parse link sync response (${data.size}B)")
            return
        }

        Timber.d("Link sync: type=0x${packet.type.toUByte().toString(16)}, isLink=${packet.isLinkPacket}")

        when {
            packet.isLinkSynAck() || packet.isLinkAck() -> {
                Timber.d("Received SYN-ACK/ACK — sending ACK")
                sendPacket(IAP2Packet.buildAck())
                _state.value = SessionState.LINK_ESTABLISHED
                linkRetryCount = 0
                onSessionEvent?.invoke(SessionEvent.LinkEstablished)
            }
            packet.isLinkSyn() -> {
                Timber.d("Received SYN — sending ACK")
                sendPacket(IAP2Packet.buildAck())
                _state.value = SessionState.LINK_ESTABLISHED
                onSessionEvent?.invoke(SessionEvent.LinkEstablished)
            }
            else -> {
                Timber.w("Unexpected packet in LINK_SYNC")
                if (linkRetryCount < MAX_LINK_RETRIES) {
                    linkRetryCount++
                    Timber.d("Retrying SYN ($linkRetryCount/$MAX_LINK_RETRIES)")
                    sendPacket(IAP2Packet.buildSyn())
                } else {
                    Timber.e("Link sync failed after $MAX_LINK_RETRIES retries")
                    _state.value = SessionState.ERROR
                    onSessionEvent?.invoke(SessionEvent.Error("Link sync timeout"))
                }
            }
        }
    }

    private fun handleLinkEstablished(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return

        when {
            packet.isAuthChallenge() -> {
                Timber.d("Received auth challenge")
                _state.value = SessionState.AUTH_CHALLENGE
                handleAuthChallengeInternal(packet)
            }
            packet.isDeviceInfoResponse() -> {
                Timber.d("Received device info response")
            }
            else -> {
                Timber.v("Unexpected packet in LINK_ESTABLISHED: 0x${packet.type.toUByte().toString(16)}")
            }
        }
    }

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

        // Send MFi certificate
        val cert = authHandler.handleCertificateRequest()
        sendPacket(IAP2Packet.build(
            IAP2Constants.SESSION_CONTROL,
            IAP2Constants.CTRL_AUTH_CERTIFICATE.toByte(),
            cert
        ))

        // Send auth response
        val response = authHandler.handleChallenge(packet.payload)
        if (response.isNotEmpty()) {
            sendPacket(response)
            _state.value = SessionState.AUTH_PENDING
            Timber.d("Auth response sent, waiting for result")
        } else {
            handleAuthError("Failed to generate auth response")
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
                sendPacket(IAP2Packet.buildDeviceInfoRequest())
                startSessionNegotiation()
            }
            packet.isAuthFailed() -> {
                authRetries++
                if (authRetries < MAX_AUTH_RETRIES) {
                    Timber.w("Auth failed, retry $authRetries/$MAX_AUTH_RETRIES")
                    _state.value = SessionState.LINK_SYNC
                    startHandshake()
                } else {
                    handleAuthError("Auth failed after $MAX_AUTH_RETRIES retries (MFi chip may be required)")
                }
            }
            else -> {
                Timber.v("Unexpected packet in AUTH_PENDING: 0x${packet.type.toUByte().toString(16)}")
            }
        }
    }

    private fun handleSessionMessage(data: ByteArray) {
        val packet = IAP2Packet.parse(data) ?: return

        when {
            packet.isSessionAck() -> {
                val sessionId = packet.control
                Timber.d("Session ACK: 0x${sessionId.toUByte().toString(16)}")
                activeSessions[sessionId] = true
                checkAllSessionsReady()
            }
            packet.isSessionOpened() -> {
                val sessionId = packet.control
                Timber.d("Session opened: 0x${sessionId.toUByte().toString(16)}")
                activeSessions[sessionId] = true
                checkAllSessionsReady()
            }
            else -> {
                Timber.v("Session message: type=0x${packet.type.toUByte().toString(16)}")
            }
        }
    }

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

        sendPacket(IAP2Packet.buildCarPlayInfo())
        onSessionEvent?.invoke(SessionEvent.SessionNegotiating)
    }

    private fun checkAllSessionsReady() {
        val requiredSessions = setOf(
            IAP2Constants.SESSION_CONTROL,
            IAP2Constants.SESSION_SCREEN,
            IAP2Constants.SESSION_AUDIO_OUT
        )

        if (activeSessions.keys.containsAll(requiredSessions)) {
            Timber.d("All required sessions active! (${activeSessions.size} total)")
            _state.value = SessionState.ACTIVE
            onSessionEvent?.invoke(SessionEvent.SessionActive(activeSessions.keys.toList()))
        } else {
            val missing = requiredSessions - activeSessions.keys
            Timber.v("Waiting for: ${missing.map { "0x${it.toUByte().toString(16)}" }}")
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
        try {
            onDataSend?.invoke(data)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send packet")
            onError?.invoke("Send failed", e)
        }
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
