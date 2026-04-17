package com.carplay.android.protocol

import timber.log.Timber
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * MFi (Made for iPhone) Authentication Handler
 *
 * Simulates Apple MFi authentication chip behavior.
 *
 * ⚠️ IMPORTANT LIMITATION:
 * Real Apple MFi authentication uses a hardware secure element (auth chip)
 * with RSA-1024 certificates signed by Apple's MFi CA. This software
 * implementation CANNOT replicate that — it will be REJECTED by real
 * CarPlay head units that properly validate MFi certificates.
 *
 * This handler provides:
 * - Protocol-level handshake simulation (for development/testing)
 * - Session key derivation (for encrypted communication)
 * - Certificate structure matching (format only, not cryptographically valid)
 *
 * For production use, you need:
 * 1. Apple MFi License ($$$ and approval process)
 * 2. MFi authentication IC (e.g., NXP SE050, Apple MFi auth chip)
 * 3. Apple-issued certificates
 */
class MFiAuthHandler {

    companion object {
        private const val TAG = "MFiAuth"
        private const val AES_KEY_SIZE = 16
        private const val CHALLENGE_SIZE = 32
    }

    // Simulated MFi certificate (format-correct but not Apple-signed)
    private val simulatedCertificate = generateSimulatedCertificate()

    // Session key derived during auth
    private var sessionKey: ByteArray? = null
    private var isAuthenticated = false

    /**
     * Handle incoming authentication challenge from head unit
     * @return Auth response packet data
     */
    fun handleChallenge(challenge: ByteArray): ByteArray {
        Timber.d("Received auth challenge: ${challenge.size}B")

        if (challenge.size < CHALLENGE_SIZE) {
            Timber.w("Challenge too short: ${challenge.size}B")
            return byteArrayOf()
        }

        if (!validateChallenge(challenge)) {
            Timber.w("Invalid challenge format")
            return byteArrayOf()
        }

        val response = generateAuthResponse(challenge)
        sessionKey = deriveSessionKey(challenge, response)

        Timber.d("Auth response generated: ${response.size}B")
        return response
    }

    /**
     * Handle certificate request from head unit
     * @return Certificate data
     */
    fun handleCertificateRequest(): ByteArray {
        Timber.d("Sending simulated MFi certificate (${simulatedCertificate.size}B)")
        return simulatedCertificate
    }

    /**
     * Handle auth success notification
     */
    fun onAuthSuccess(sessionKey: ByteArray?) {
        Timber.d("Authentication SUCCESS")
        isAuthenticated = true
        this.sessionKey = sessionKey
    }

    /**
     * Handle auth failure notification
     */
    fun onAuthFailed(reason: String) {
        Timber.e("Authentication FAILED: $reason")
        isAuthenticated = false
        sessionKey = null
    }

    /**
     * Check if currently authenticated
     */
    fun isAuthenticated() = isAuthenticated

    /**
     * Encrypt data using session key (for post-auth communication)
     */
    fun encrypt(data: ByteArray): ByteArray {
        val key = sessionKey ?: return data
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            Timber.e(e, "Encryption failed")
            data
        }
    }

    /**
     * Decrypt data using session key
     */
    fun decrypt(data: ByteArray): ByteArray {
        val key = sessionKey ?: return data
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            cipher.doFinal(data)
        } catch (e: Exception) {
            Timber.e(e, "Decryption failed")
            data
        }
    }

    /**
     * Reset auth state
     */
    fun reset() {
        isAuthenticated = false
        sessionKey = null
        Timber.d("Auth state reset")
    }

    // ── Private Methods ────────────────────────────────────

    private fun validateChallenge(challenge: ByteArray): Boolean {
        return challenge.size >= CHALLENGE_SIZE
    }

    /**
     * Generate authentication response
     *
     * Structure: [Version 1B][Sequence 1B][SHA256 hash 32B][Padding to 64B]
     * Then XOR-transformed to simulate RSA signature format
     */
    private fun generateAuthResponse(challenge: ByteArray): ByteArray {
        val response = ByteArray(IAP2Constants.MFI_RESPONSE_LEN)

        // Create hash from challenge
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(challenge)

        // Build response structure
        response[0] = IAP2Constants.MFI_PROTOCOL_VERSION.toByte()
        response[1] = 0x01 // Sequence number

        // Copy hash portion
        val copyLen = minOf(hash.size, IAP2Constants.MFI_RESPONSE_LEN - 2)
        System.arraycopy(hash, 0, response, 2, copyLen)

        // XOR transformation (emulates RSA signature structure)
        for (i in response.indices) {
            val pattern = ((i * 0x5A + 0x37) and 0xFF).toByte()
            response[i] = (response[i].toInt() xor pattern.toInt()).toByte()
        }

        return response
    }

    /**
     * Derive session encryption key from challenge + response
     */
    private fun deriveSessionKey(challenge: ByteArray, response: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val combined = challenge + response
        val hash = md.digest(combined)
        return hash.copyOf(AES_KEY_SIZE)
    }

    /**
     * Generate simulated MFi certificate
     * Format matches ASN.1 DER structure but is NOT Apple-signed
     */
    private fun generateSimulatedCertificate(): ByteArray {
        val cert = ByteArray(IAP2Constants.MFI_CERTIFICATE_LEN)

        // Certificate header (ASN.1 DER SEQUENCE tag)
        cert[0] = 0x30 // SEQUENCE
        cert[1] = 0x82.toByte() // Long form length
        cert[2] = ((IAP2Constants.MFI_CERTIFICATE_LEN - 4) shr 8).toByte()
        cert[3] = ((IAP2Constants.MFI_CERTIFICATE_LEN - 4) and 0xFF).toByte()

        // Fill with deterministic data (simulates cert structure)
        for (i in 4 until cert.size) {
            cert[i] = ((i * 0x17 + 0x5A) and 0xFF).toByte()
        }

        return cert
    }
}
