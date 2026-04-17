package com.carplay.android.protocol

import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * iAP2 Packet Builder & Parser
 *
 * Implements the real iAP2 packet format:
 *
 * Link Sync packet:
 *   [0xFF][0x5A][Length 2B BE][0xFF][Payload...][Checksum]
 *
 * Control/Data packet:
 *   [0x40][SessionID][PayloadLength 2B BE][MessageType 1B][Payload...][Checksum]
 *
 * Checksum = 256 - (sum of all preceding bytes mod 256)
 */
class IAP2Packet {

    companion object {
        private const val TAG = "IAP2Packet"

        // ── Link Layer Packets ─────────────────────────────

        /**
         * Build iAP2 Link Synchronization packet (SYN)
         *
         * Real iAP2 SYN structure:
         * [0xFF][0x5A][Length 2B][0xFF][version][maxPacketSize 2B][recvWindowSize][...][checksum]
         */
        fun buildSyn(): ByteArray {
            val payload = ByteBuffer.allocate(8).apply {
                order(ByteOrder.BIG_ENDIAN)
                put(IAP2Constants.IAP2_LINK_VERSION)                 // iAP2 version
                putShort(IAP2Constants.IAP2_MAX_PACKET_SIZE.toShort()) // Max packet size
                put(IAP2Constants.IAP2_MAX_RECEIVED_SIZE.toByte())    // Receive window
                put(0x00)                                              // Reserved
                put(0x00)                                              // Reserved
                put(0x00)                                              // Reserved
                put(0x00)                                              // Reserved
            }.array()

            return buildLinkPacket(IAP2Constants.LINK_MSG_SYN, payload)
        }

        /**
         * Build iAP2 Link Acknowledgment packet (ACK)
         */
        fun buildAck(): ByteArray {
            return buildLinkPacket(IAP2Constants.LINK_MSG_ACK, byteArrayOf(0x00))
        }

        /**
         * Build an iAP2 Link Layer packet
         * Format: [0xFF][0x5A][Length 2B BE][MsgType][Payload][Checksum]
         */
        private fun buildLinkPacket(msgType: Byte, payload: ByteArray): ByteArray {
            // Length = sync(2) + length(2) + msgType(1) + payload + checksum(1)
            val totalLength = 2 + 2 + 1 + payload.size + 1
            val packet = ByteArray(totalLength)

            packet[0] = IAP2Constants.LINK_SYNC_BYTE_1      // 0xFF
            packet[1] = IAP2Constants.LINK_SYNC_BYTE_2      // 0x5A
            packet[2] = ((totalLength shr 8) and 0xFF).toByte()
            packet[3] = (totalLength and 0xFF).toByte()
            packet[4] = msgType

            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, packet, 5, payload.size)
            }

            // Checksum = 256 - (sum of all preceding bytes mod 256)
            packet[totalLength - 1] = calculateChecksum(packet, totalLength - 1)

            Timber.d("Built link packet: type=0x${msgType.toUByte().toString(16)}, " +
                    "len=$totalLength, payload=${payload.size}B")
            return packet
        }

        // ── Control Packets ────────────────────────────────

        /**
         * Build an iAP2 Control/Data packet
         * Format: [0x40][SessionID][PayloadLen 2B BE][MsgType][Payload][Checksum]
         */
        fun build(sessionId: Byte, msgType: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
            // Total = start(1) + session(1) + length(2) + msgType(1) + payload + checksum(1)
            val totalLength = 5 + payload.size + 1
            val packet = ByteArray(totalLength)

            packet[0] = IAP2Constants.CONTROL_PACKET_START   // 0x40
            packet[1] = sessionId
            // Payload length includes msgType byte
            packet[2] = (((1 + payload.size) shr 8) and 0xFF).toByte()
            packet[3] = ((1 + payload.size) and 0xFF).toByte()
            packet[4] = msgType

            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, packet, 5, payload.size)
            }

            packet[totalLength - 1] = calculateChecksum(packet, totalLength - 1)

            Timber.v("Built control packet: session=0x${sessionId.toUByte().toString(16)}, " +
                    "msg=0x${msgType.toUByte().toString(16)}, " +
                    "payload=${payload.size}B, total=$totalLength")
            return packet
        }

        /**
         * Build packet with string payload
         */
        fun buildWithString(sessionId: Byte, msgType: Byte, text: String): ByteArray {
            return build(sessionId, msgType, text.toByteArray(Charsets.UTF_8))
        }

        // ── Parse ──────────────────────────────────────────

        /**
         * Parse received iAP2 data into a packet
         */
        fun parse(data: ByteArray): ParsedPacket? {
            if (data.size < 5) {
                Timber.w("Data too short: ${data.size}B")
                return null
            }

            // Check if it's a link packet (starts with 0xFF 0x5A)
            if (data[0] == IAP2Constants.LINK_SYNC_BYTE_1 &&
                data[1] == IAP2Constants.LINK_SYNC_BYTE_2) {
                return parseLinkPacket(data)
            }

            // Check if it's a control packet (starts with 0x40)
            if (data[0] == IAP2Constants.CONTROL_PACKET_START) {
                return parseControlPacket(data)
            }

            Timber.w("Unknown packet type: 0x${data[0].toUByte().toString(16)}")
            return null
        }

        /**
         * Parse link layer packet
         */
        private fun parseLinkPacket(data: ByteArray): ParsedPacket? {
            if (data.size < 6) return null

            val length = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            if (length > data.size) {
                Timber.w("Incomplete link packet: expected=$length, got=${data.size}")
                return null
            }

            val msgType = data[4]
            val payloadSize = length - 6 // minus sync(2) + len(2) + msgType(1) + checksum(1)
            val payload = if (payloadSize > 0) {
                data.copyOfRange(5, 5 + payloadSize)
            } else {
                byteArrayOf()
            }

            return ParsedPacket(
                type = msgType,
                control = msgType,
                payload = payload,
                totalLength = length,
                isLinkPacket = true
            )
        }

        /**
         * Parse control/data packet
         */
        private fun parseControlPacket(data: ByteArray): ParsedPacket? {
            if (data.size < 6) return null

            val sessionId = data[1]
            val payloadLen = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val totalLen = 5 + payloadLen + 1 // header + payload + checksum

            if (totalLen > data.size) {
                Timber.w("Incomplete control packet: expected=$totalLen, got=${data.size}")
                return null
            }

            val msgType = data[4]
            val actualPayloadSize = payloadLen - 1 // minus msgType
            val payload = if (actualPayloadSize > 0) {
                data.copyOfRange(5, 5 + actualPayloadSize)
            } else {
                byteArrayOf()
            }

            // Verify checksum
            val expectedChecksum = calculateChecksum(data, totalLen - 1)
            val actualChecksum = data[totalLen - 1]
            if (expectedChecksum != actualChecksum) {
                Timber.w("Checksum mismatch: expected=0x${expectedChecksum.toUByte().toString(16)}, " +
                        "actual=0x${actualChecksum.toUByte().toString(16)}")
            }

            return ParsedPacket(
                type = msgType,
                control = sessionId,
                payload = payload,
                totalLength = totalLen,
                isLinkPacket = false
            )
        }

        // ── Authentication Packets ─────────────────────────

        /**
         * Build authentication challenge response
         * Uses simulated MFi auth — real implementation needs hardware chip
         */
        fun buildAuthResponse(challenge: ByteArray): ByteArray {
            val response = ByteArray(IAP2Constants.MFI_RESPONSE_LEN)

            // Copy challenge data as base
            challenge.copyInto(response, 0, 0, minOf(challenge.size, response.size))

            // Apply transformation (simulated — real MFi uses RSA-1024 + AES-128)
            for (i in response.indices) {
                response[i] = (response[i].toInt() xor 0xA5.toByte().toInt()).toByte()
            }

            return build(IAP2Constants.SESSION_CONTROL,
                IAP2Constants.CTRL_AUTH_RESPONSE.toByte(), response)
        }

        /**
         * Build device info request
         */
        fun buildDeviceInfoRequest(): ByteArray {
            return build(IAP2Constants.SESSION_CONTROL,
                IAP2Constants.CTRL_DEVICE_INFO_REQUEST.toByte())
        }

        /**
         * Build start session request
         */
        fun buildStartSession(sessionType: Byte): ByteArray {
            val payload = byteArrayOf(sessionType, IAP2Constants.CARPLAY_INIT)
            return build(IAP2Constants.SESSION_CONTROL,
                IAP2Constants.CTRL_START_SESSION.toByte(), payload)
        }

        /**
         * Build stop session
         */
        fun buildStopSession(sessionType: Byte): ByteArray {
            return build(IAP2Constants.SESSION_CONTROL,
                IAP2Constants.CTRL_STOP_SESSION.toByte(), byteArrayOf(sessionType))
        }

        /**
         * Build CarPlay info message with device capabilities
         */
        fun buildCarPlayInfo(): ByteArray {
            val payload = mutableListOf<Byte>()

            // Device name parameter
            payload.add(0x01) // Parameter ID: device name
            val nameBytes = IAP2Constants.DEVICE_NAME.toByteArray(Charsets.UTF_8)
            payload.add(nameBytes.size.toByte())
            payload.addAll(nameBytes.toList())

            // Screen resolution parameter
            payload.add(0x02) // Parameter ID: screen info
            payload.add(0x04) // Length
            payload.add(((IAP2Constants.VIDEO_WIDTH shr 8) and 0xFF).toByte())
            payload.add((IAP2Constants.VIDEO_WIDTH and 0xFF).toByte())
            payload.add(((IAP2Constants.VIDEO_HEIGHT shr 8) and 0xFF).toByte())
            payload.add((IAP2Constants.VIDEO_HEIGHT and 0xFF).toByte())

            // Scale factor
            payload.add(0x03) // Parameter ID: scale
            payload.add(0x01) // Length
            payload.add(IAP2Constants.DEVICE_SCREEN_SCALE.toByte())

            // Device model
            payload.add(0x04) // Parameter ID: model
            val modelBytes = IAP2Constants.DEVICE_MODEL.toByteArray(Charsets.UTF_8)
            payload.add(modelBytes.size.toByte())
            payload.addAll(modelBytes.toList())

            // OS version
            payload.add(0x05) // Parameter ID: OS version
            val osBytes = IAP2Constants.DEVICE_OS_VERSION.toByteArray(Charsets.UTF_8)
            payload.add(osBytes.size.toByte())
            payload.addAll(osBytes.toList())

            return build(IAP2Constants.SESSION_CONTROL,
                IAP2Constants.CARPLAY_INFO.toByte(), payload.toByteArray())
        }

        // ── Checksum ───────────────────────────────────────

        /**
         * Calculate iAP2 checksum
         * Checksum = 256 - (sum of all bytes mod 256)
         */
        private fun calculateChecksum(data: ByteArray, upToIndex: Int): Byte {
            var sum = 0
            for (i in 0 until upToIndex) {
                sum += data[i].toInt() and 0xFF
            }
            return ((256 - (sum % 256)) and 0xFF).toByte()
        }
    }

    // ── Data Classes ───────────────────────────────────────

    /**
     * Parsed iAP2 packet
     */
    data class ParsedPacket(
        val type: Byte,
        val control: Byte,
        val payload: ByteArray,
        val totalLength: Int,
        val isLinkPacket: Boolean = false
    ) {
        // Link layer checks
        fun isLinkSyn() = isLinkPacket && type == IAP2Constants.LINK_MSG_SYN
        fun isLinkAck() = isLinkPacket && type == IAP2Constants.LINK_MSG_ACK
        fun isLinkSynAck() = isLinkPacket && type == IAP2Constants.LINK_MSG_SYN_ACK
        fun isLinkRst() = isLinkPacket && type == IAP2Constants.LINK_MSG_RST

        // Auth checks (using control byte as message type for control packets)
        fun isAuthChallenge() = !isLinkPacket && type == IAP2Constants.CTRL_AUTH_CHALLENGE
        fun isAuthSuccess() = !isLinkPacket && type == IAP2Constants.CTRL_AUTH_SUCCESS
        fun isAuthFailed() = !isLinkPacket && type == IAP2Constants.CTRL_AUTH_FAILED
        fun isAuthCertificate() = !isLinkPacket && type == IAP2Constants.CTRL_AUTH_CERTIFICATE

        // Session checks
        fun isSessionAck() = !isLinkPacket && type == IAP2Constants.CTRL_SESSION_ACK
        fun isSessionOpened() = !isLinkPacket && type == IAP2Constants.CTRL_SESSION_OPENED
        fun isDeviceInfoResponse() = !isLinkPacket && type == IAP2Constants.CTRL_DEVICE_INFO_RESPONSE

        // Data checks
        fun isTouchInput() = !isLinkPacket && control == IAP2Constants.SESSION_TOUCH
        fun isVideoData() = !isLinkPacket && control == IAP2Constants.SESSION_SCREEN
        fun isAudioData() = !isLinkPacket && control == IAP2Constants.SESSION_AUDIO_OUT

        /**
         * Extract touch data from a touch packet
         */
        fun getTouchData(): TouchData? {
            if (!isTouchInput() || payload.size < 5) return null
            val action = payload[0]
            val x = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
            val y = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
            return TouchData(action, x, y)
        }

        /**
         * Get session type from session control packet
         */
        fun getSessionType(): Byte? {
            if (payload.isNotEmpty()) return payload[0]
            return null
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedPacket) return false
            return type == other.type && control == other.control &&
                    payload.contentEquals(other.payload) && isLinkPacket == other.isLinkPacket
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + control.hashCode()
            result = 31 * result + payload.contentHashCode()
            result = 31 * result + isLinkPacket.hashCode()
            return result
        }
    }

    /**
     * Touch input data from head unit
     */
    data class TouchData(
        val action: Byte,
        val x: Int,
        val y: Int
    )
}
