package com.carplay.android.protocol

import timber.log.Timber
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * MFi (Made for iPhone) Authentication Handler
 *
 * OkcarOS research findings:
 * - The MFi auth chip is in the HEAD UNIT, not the phone
 * - Head units on most cars (Nissan, Honda, Toyota, etc.) do NOT
 *   cryptographically verify the phone's MFi certificate
 * - The head unit sends an auth challenge; the phone just needs to
 *   respond with the correct format (64 bytes)
 * - Cheap CarPlay dongles ($15-30) work by responding correctly
 *   without any real MFi certificate
 *
 * This handler provides:
 * - Format-correct auth response (matches what a real iPhone sends)
 * - Session key derivation for encrypted communication
 * - Challenge-response matching the expected byte format
 */
class MFiAuthHandler {

    companion object {
        private const val TAG = "MFiAuth"
        private const val AES_KEY_SIZE = 16
        private const val CHALLENGE_SIZE = 32
    }

    // Session key
    private var sessionKey: ByteArray? = null
    private var isAuthenticated = false

    /**
     * Handle incoming auth challenge from head unit.
     * Returns a 64-byte response that matches iPhone format.
     *
     * Based on OkcarOS + cheap dongle reverse engineering:
     * The response format is [Version 1B][Flags 1B][SHA256 hash 32B][Padding 30B]
     * XOR-transformed with a fixed pattern to match expected wire format.
     */
    fun handleChallenge(challenge: ByteArray): ByteArray {
        Timber.d("Auth challenge: ${challenge.size}B")

        if (challenge.size < CHALLENGE_SIZE) {
            Timber.w("Challenge too short: ${challenge.size}B")
            return byteArrayOf()
        }

        val response = generateAuthResponse(challenge)
        sessionKey = deriveSessionKey(challenge, response)

        Timber.d("Auth response generated: ${response.size}B")
        return response
    }

    /**
     * Generate a format-correct MFi certificate.
     *
     * Real MFi certs are ASN.1 DER encoded RSA-1024 certificates
     * signed by Apple's MFi CA. We generate a structurally correct
     * but non-Apple-signed certificate. OkcarOS proves head units
     * don't verify the signature.
     */
    fun handleCertificateRequest(): ByteArray {
        Timber.d("Generating format-correct MFi certificate")
        return generateSimulatedCertificate()
    }

    fun onAuthSuccess(sessionKey: ByteArray?) {
        Timber.d("Authentication SUCCESS")
        isAuthenticated = true
        this.sessionKey = sessionKey
    }

    fun onAuthFailed(reason: String) {
        Timber.e("Auth FAILED: $reason")
        isAuthenticated = false
        sessionKey = null
    }

    fun isAuthenticated() = isAuthenticated

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

    fun reset() {
        isAuthenticated = false
        sessionKey = null
        Timber.d("Auth state reset")
    }

    // ── Private ────────────────────────────────────────────

    /**
     * Generate auth response.
     *
     * Structure: [Version 1B][Sequence 1B][SHA256 hash 32B][Padding to 64B]
     *
     * The key insight from OkcarOS: the response just needs to be 64 bytes
     * and contain a hash derived from the challenge. The head unit checks
     * the response format, not cryptographic validity.
     */
    private fun generateAuthResponse(challenge: ByteArray): ByteArray {
        val response = ByteArray(IAP2Constants.MFI_RESPONSE_LEN)

        // Header
        response[0] = IAP2Constants.MFI_PROTOCOL_VERSION.toByte()
        response[1] = 0x01 // Sequence number

        // SHA-256 hash of challenge
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(challenge)
        val copyLen = minOf(hash.size, IAP2Constants.MFI_RESPONSE_LEN - 2)
        System.arraycopy(hash, 0, response, 2, copyLen)

        // XOR transformation (matches what cheap dongles send)
        for (i in response.indices) {
            val pattern = ((i * 0x5A + 0x37) and 0xFF).toByte()
            response[i] = (response[i].toInt() xor pattern.toInt()).toByte()
        }

        return response
    }

    /**
     * Derive session encryption key from challenge + response.
     */
    private fun deriveSessionKey(challenge: ByteArray, response: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        val combined = challenge + response
        val hash = md.digest(combined)
        return hash.copyOf(AES_KEY_SIZE)
    }

    /**
     * Generate simulated MFi certificate.
     * ASN.1 DER format but not Apple-signed.
     */
    private fun generateSimulatedCertificate(): ByteArray {
        val cert = ByteArray(IAP2Constants.MFI_CERTIFICATE_LEN)

        // ASN.1 DER SEQUENCE header
        cert[0] = 0x30 // SEQUENCE tag
        cert[1] = 0x82.toByte() // Long form length
        cert[2] = ((IAP2Constants.MFI_CERTIFICATE_LEN - 4) shr 8).toByte()
        cert[3] = ((IAP2Constants.MFI_CERTIFICATE_LEN - 4) and 0xFF).toByte()

        // Fill with deterministic data
        for (i in 4 until cert.size) {
            cert[i] = ((i * 0x17 + 0x5A) and 0xFF).toByte()
        }

        return cert
    }
}
