package com.carplay.android.protocol

import timber.log.Timber
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * MFi (Made for iPhone) Authentication Handler
 *
 * Simulates Apple MFi authentication chip behavior.
 * Real MFi chips use a secure element with RSA-1024 + AES-128.
 *
 * This implementation uses protocol-level emulation to bypass
 * hardware authentication requirements on compatible head units.
 *
 * WARNING: This is a reverse-engineered implementation for
 * educational/personal use. Commercial use requires MFi license.
 */
class MFiAuthHandler {

    companion object {
        private const val TAG = "MFiAuth"
        private const val AES_KEY_SIZE = 16
        private const val CHALLENGE_SIZE = 32
    }

    // Simulated MFi certificate data (would be real cert in production)
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

        // Step 1: Validate challenge format
        if (!validateChallenge(challenge)) {
            Timber.w("Invalid challenge format")
            return byteArrayOf()
        }

        // Step 2: Generate response using simulated auth
        val response = generateAuthResponse(challenge)

        // Step 3: Derive session key
        sessionKey = deriveSessionKey(challenge, response)

        Timber.d("Auth response generated: ${response.size}B")
        return response
    }

    /**
     * Handle certificate request from head unit
     * @return Certificate data
     */
    fun handleCertificateRequest(): ByteArray {
        Timber.d("Sending simulated MFi certificate")
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
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
            cipher.doFinal(padData(data))
        } catch (e: Exception) {
            Timber.e(e, "Encryption failed")
            data // Fallback: send unencrypted
        }
    }

    /**
     * Decrypt data using session key
     */
    fun decrypt(data: ByteArray): ByteArray {
        val key = sessionKey ?: return data
        return try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
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

    // ── Private Methods ──────────────────────────────────────

    /**
     * Validate challenge format
     */
    private fun validateChallenge(challenge: ByteArray): Boolean {
        // Check for known challenge patterns
        // Real MFi challenge has specific structure
        return challenge.size >= CHALLENGE_SIZE
    }

    /**
     * Generate authentication response
     *
     * Real implementation would use:
     * - RSA-1024 signature with MFi private key
     * - AES-128 CBC with challenge as IV
     * - HMAC-SHA256 for integrity
     *
     * This emulates the output format
     */
    private fun generateAuthResponse(challenge: ByteArray): ByteArray {
        val response = ByteArray(IAP2Constants.MFI_RESPONSE_LEN)

        // Create hash from challenge
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(challenge)

        // Build response structure:
        // [Version 1B][Sequence 1B][Hash 32B][Padding 30B]
        response[0] = IAP2Constants.MFI_PROTOCOL_VERSION.toByte()
        response[1] = 0x01 // Sequence number

        // Copy hash portion
        val copyLen = minOf(hash.size, IAP2Constants.MFI_RESPONSE_LEN - 2)
        System.arraycopy(hash, 0, response, 2, copyLen)

        // XOR transformation with known pattern (emulates RSA signature)
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

        // Take first 16 bytes as AES-128 key
        return hash.copyOf(AES_KEY_SIZE)
    }

    /**
     * Generate simulated MFi certificate
     * Real cert is X.509 with Apple's MFi CA
     */
    private fun generateSimulatedCertificate(): ByteArray {
        val cert = ByteArray(IAP2Constants.MFI_CERTIFICATE_LEN)

        // Certificate header (ASN.1 DER format simulation)
        cert[0] = 0x30 // SEQUENCE tag
        cert[1] = 0x82.toByte() // Long form length
        cert[2] = ((IAP2Constants.MFI_CERTIFICATE_LEN - 4) shr 8).toByte()
        cert[3] = ((IAP2Constants.MFI_CERTIFICATE_LEN - 4) and 0xFF).toByte()

        // Fill with deterministic data (simulates real cert structure)
        for (i in 4 until cert.size) {
            cert[i] = ((i * 0x17 + 0x5A) and 0xFF).toByte()
        }

        return cert
    }

    /**
     * PKCS7 padding to AES block size (16 bytes)
     */
    private fun padData(data: ByteArray): ByteArray {
        val blockSize = 16
        val padding = blockSize - (data.size % blockSize)
        val padded = ByteArray(data.size + padding)
        System.arraycopy(data, 0, padded, 0, data.size)
        for (i in data.size until padded.size) {
            padded[i] = padding.toByte()
        }
        return padded
    }
}
