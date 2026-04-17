package com.carplay.android.protocol

import timber.log.Timber

/**
 * iAP2 Packet Builder & Parser
 *
 * Builds and parses iAP2 protocol packets for CarPlay communication.
 * Format: [StartByte 0x55] [Length 2B] [PacketType] [ControlByte] [Payload...] [Checksum]
 */
class IAP2Packet {

    companion object {
        private const val TAG = "IAP2Packet"

        /**
         * Build an iAP2 packet
         */
        fun build(type: Byte, control: Byte, payload: ByteArray = byteArrayOf()): ByteArray {
            val length = IAP2Constants.PACKET_HEADER_SIZE + payload.size
            val packet = ByteArray(length)

            packet[0] = IAP2Constants.START_BYTE
            packet[1] = ((length shr 8) and 0xFF).toByte()
            packet[2] = (length and 0xFF).toByte()
            packet[3] = type
            packet[4] = control

            if (payload.isNotEmpty()) {
                System.arraycopy(payload, 0, packet, 5, payload.size)
            }

            // Calculate checksum
            val checksum = calculateChecksum(packet, packet.size - 1)
            packet[packet.size - 1] = if (packet.size > payload.size + 5) {
                packet[packet.size - 1]  // already has checksum slot
            } else {
                checksum
            }

            // Recalculate with proper size including checksum
            val finalPacket = ByteArray(length + 1)
            System.arraycopy(packet, 0, finalPacket, 0, length)
            finalPacket[length] = calculateChecksum(finalPacket, length)

            Timber.d("Built packet: type=0x${type.toUByte().toString(16)}, " +
                    "ctrl=0x${control.toUByte().toString(16)}, " +
                    "payload=${payload.size}B, total=${finalPacket.size}B")
            return finalPacket
        }

        /**
         * Build an iAP2 packet with string payload
         */
        fun buildWithString(type: Byte, control: Byte, text: String): ByteArray {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            return build(type, control, textBytes)
        }

        /**
         * Parse received iAP2 data into a packet
         */
        fun parse(data: ByteArray): ParsedPacket? {
            if (data.size < IAP2Constants.PACKET_HEADER_SIZE) {
                Timber.w("Data too short: ${data.size}B")
                return null
            }

            if (data[0] != IAP2Constants.START_BYTE) {
                Timber.w("Invalid start byte: 0x${data[0].toUByte().toString(16)}")
                return null
            }

            val length = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)

            if (length > data.size) {
                Timber.w("Incomplete packet: expected=$length, got=${data.size}")
                return null
            }

            val type = data[3]
            val control = data[4]
            val payloadSize = length - IAP2Constants.PACKET_HEADER_SIZE
            val payload = if (payloadSize > 0) {
                data.copyOfRange(5, 5 + payloadSize)
            } else {
                byteArrayOf()
            }

            // Verify checksum
            val expectedChecksum = calculateChecksum(data, length)
            val actualChecksum = if (data.size > length) data[length] else 0.toByte()
            if (expectedChecksum != actualChecksum) {
                Timber.w("Checksum mismatch: expected=0x${expectedChecksum.toUByte().toString(16)}, " +
                        "actual=0x${actualChecksum.toUByte().toString(16)}")
            }

            return ParsedPacket(type, control, payload, length)
        }

        /**
         * Build SYN packet (link layer synchronization)
         */
        fun buildSyn(): ByteArray {
            return byteArrayOf(IAP2Constants.LINK_PACKET_SYN, 0x00, 0x02)
        }

        /**
         * Build ACK packet (link layer acknowledgment)
         */
        fun buildAck(): ByteArray {
            return byteArrayOf(IAP2Constants.LINK_PACKET_ACK, 0x00, 0x02)
        }

        /**
         * Build authentication challenge response
         */
        fun buildAuthResponse(challenge: ByteArray): ByteArray {
            // In real MFi, this would use the authentication chip
            // Here we generate a compatible response
            val response = ByteArray(IAP2Constants.MFI_RESPONSE_LEN)

            // Copy challenge data as base
            challenge.copyInto(response)

            // Apply transformation (simplified - real MFi uses AES/RSA)
            for (i in response.indices) {
                response[i] = (response[i].toInt() xor 0xA5.toByte().toInt()).toByte()
            }

            return build(IAP2Constants.CTRL_AUTH_RESPONSE.toByte(), 0x00, response)
        }

        /**
         * Build device info request
         */
        fun buildDeviceInfoRequest(): ByteArray {
            return build(IAP2Constants.CTRL_DEVICE_INFO_REQUEST.toByte(), 0x00)
        }

        /**
         * Build start session request
         */
        fun buildStartSession(sessionType: Byte): ByteArray {
            val payload = byteArrayOf(sessionType, IAP2Constants.CARPLAY_INIT)
            return build(IAP2Constants.CTRL_START_SESSION.toByte(), 0x00, payload)
        }

        /**
         * Build CarPlay info message
         */
        fun buildCarPlayInfo(): ByteArray {
            val payload = ByteArray(0).toMutableList()

            // Device name
            payload.add(0x01) // Parameter ID: device name
            val nameBytes = IAP2Constants.DEVICE_NAME.toByteArray(Charsets.UTF_8)
            payload.add(nameBytes.size.toByte())
            payload.addAll(nameBytes.toList())

            // Screen resolution
            payload.add(0x02) // Parameter ID: screen info
            payload.add(0x04) // Length
            payload.add(((IAP2Constants.VIDEO_WIDTH shr 8) and 0xFF).toByte())
            payload.add((IAP2Constants.VIDEO_WIDTH and 0xFF).toByte())
            payload.add(((IAP2Constants.VIDEO_HEIGHT shr 8) and 0xFF).toByte())
            payload.add((IAP2Constants.VIDEO_HEIGHT and 0xFF).toByte())

            // Scale factor
            payload.add(0x03) // Parameter ID: scale
            payload.add(0x01)
            payload.add(IAP2Constants.DEVICE_SCREEN_SCALE.toByte())

            return build(IAP2Constants.CARPLAY_INFO.toByte(), 0x00, payload.toByteArray())
        }

        /**
         * Calculate packet checksum
         * Sum of all bytes except the checksum byte itself, negated + 1
         */
        private fun calculateChecksum(data: ByteArray, length: Int): Byte {
            var sum = 0
            for (i in 0 until length) {
                sum += data[i].toInt() and 0xFF
            }
            return ((-sum) and 0xFF).toByte()
        }
    }

    /**
     * Parsed packet data class
     */
    data class ParsedPacket(
        val type: Byte,
        val control: Byte,
        val payload: ByteArray,
        val totalLength: Int
    ) {
        fun isAuthChallenge() = control == IAP2Constants.CTRL_AUTH_CHALLENGE
        fun isAuthSuccess() = control == IAP2Constants.CTRL_AUTH_SUCCESS
        fun isAuthFailed() = control == IAP2Constants.CTRL_AUTH_FAILED
        fun isSessionAck() = control == IAP2Constants.CTRL_SESSION_ACK
        fun isSessionOpened() = control == IAP2Constants.CTRL_SESSION_OPENED
        fun isDeviceInfoResponse() = control == IAP2Constants.CTRL_DEVICE_INFO_RESPONSE
        fun isTouchInput() = type == IAP2Constants.SESSION_TOUCH

        fun getTouchData(): TouchData? {
            if (!isTouchInput() || payload.size < 5) return null
            val action = payload[0]
            val x = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
            val y = ((payload[3].toInt() and 0xFF) shl 8) or (payload[4].toInt() and 0xFF)
            return TouchData(action, x, y)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ParsedPacket) return false
            return type == other.type && control == other.control && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + control.hashCode()
            result = 31 * result + payload.contentHashCode()
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
