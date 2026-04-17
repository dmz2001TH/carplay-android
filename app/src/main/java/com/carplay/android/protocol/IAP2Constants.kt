package com.carplay.android.protocol

/**
 * Apple CarPlay / iAP2 Protocol Constants
 *
 * Based on reverse-engineered Apple iPod Accessory Protocol 2 (iAP2)
 * and CarPlay specification.
 *
 * References:
 * - Apple iAP2 spec (accessory communication)
 * - node-carplay, CPDCarPlayIndigo (open source reverse engineering)
 * - libusb CarPlay implementations
 */
object IAP2Constants {

    // ── iAP2 Packet Structure ──────────────────────────────
    // iAP2 Link packet: [SyncByte1 0xFF][SyncByte2 0x5A][Length 2B BE][MsgType][Payload][Checksum]
    // iAP2 Control packet: [PacketStart 0x40][SessionID][PayloadLen 2B BE][Payload...][Checksum]
    const val LINK_SYNC_BYTE_1: Byte = 0xFF.toByte()
    const val LINK_SYNC_BYTE_2: Byte = 0x5A
    const val CONTROL_PACKET_START: Byte = 0x40.toByte()
    const val PACKET_HEADER_SIZE = 5

    // ── Link Layer Messages ────────────────────────────────
    const val LINK_MSG_SYN: Byte = 0xFF.toByte()       // Link synchronization request
    const val LINK_MSG_SYN_ACK: Byte = 0x06.toByte()   // Link sync acknowledgment
    const val LINK_MSG_ACK: Byte = 0x06.toByte()       // Acknowledgment
    const val LINK_MSG_EAK: Byte = 0x05.toByte()       // Extended acknowledge
    const val LINK_MSG_RST: Byte = 0x52.toByte()       // Reset

    // ── Session Types (iAP2 session IDs) ──────────────────
    const val SESSION_CONTROL: Byte = 0x00.toByte()    // Control channel
    const val SESSION_MEDIA: Byte = 0x10.toByte()      // Media metadata
    const val SESSION_SCREEN: Byte = 0x20.toByte()     // Video stream
    const val SESSION_TOUCH: Byte = 0x30.toByte()      // Touch input
    const val SESSION_AUDIO_IN: Byte = 0x40.toByte()   // Microphone input
    const val SESSION_AUDIO_OUT: Byte = 0x50.toByte()  // Speaker output
    const val SESSION_PHONE: Byte = 0x60.toByte()      // Telephony
    const val SESSION_INFO: Byte = 0x70.toByte()       // Device info

    // ── iAP2 Link Sync Payload ────────────────────────────
    // Real iAP2 SYN contains: maxRecvSize, version, etc.
    const val IAP2_LINK_VERSION: Byte = 0x02            // iAP2 version
    const val IAP2_MAX_PACKET_SIZE = 4096               // Max packet size
    const val IAP2_MAX_RECEIVED_SIZE = 65535            // Max receive buffer

    // ── Control Messages ──────────────────────────────────
    const val CTRL_AUTH_CHALLENGE: Byte = 0xAA.toByte()
    const val CTRL_AUTH_RESPONSE: Byte = 0xAB.toByte()
    const val CTRL_AUTH_SUCCESS: Byte = 0xAC.toByte()
    const val CTRL_AUTH_FAILED: Byte = 0xAD.toByte()
    const val CTRL_AUTH_CERTIFICATE: Byte = 0xAE.toByte()

    const val CTRL_DEVICE_INFO_REQUEST: Byte = 0x20.toByte()
    const val CTRL_DEVICE_INFO_RESPONSE: Byte = 0x21.toByte()
    const val CTRL_START_SESSION: Byte = 0x40.toByte()
    const val CTRL_STOP_SESSION: Byte = 0x41.toByte()
    const val CTRL_SESSION_ACK: Byte = 0x42.toByte()
    const val CTRL_SESSION_OPENED: Byte = 0x43.toByte()

    // ── CarPlay Specific ──────────────────────────────────
    const val CARPLAY_INIT: Byte = 0x01.toByte()
    const val CARPLAY_INFO: Byte = 0x02.toByte()
    const val CARPLAY_READY: Byte = 0x03.toByte()
    const val CARPLAY_START: Byte = 0x04.toByte()
    const val CARPLAY_STOP: Byte = 0x05.toByte()

    // ── Video Stream ──────────────────────────────────────
    const val VIDEO_CODEC_H264: Byte = 0x01.toByte()
    const val VIDEO_CODEC_HEVC: Byte = 0x02.toByte()
    const val VIDEO_KEYFRAME_REQUEST: Byte = 0x10.toByte()
    const val VIDEO_FRAME: Byte = 0x20.toByte()

    // Default CarPlay resolution (NissanConnect 8" display)
    const val VIDEO_WIDTH = 800
    const val VIDEO_HEIGHT = 480
    const val VIDEO_FPS = 30
    const val VIDEO_BITRATE = 2_000_000  // 2 Mbps

    // ── Audio Stream ──────────────────────────────────────
    const val AUDIO_CODEC_AAC_LC: Byte = 0x01.toByte()
    const val AUDIO_CODEC_PCM: Byte = 0x02.toByte()
    const val AUDIO_SAMPLE_RATE = 44100
    const val AUDIO_CHANNELS = 2
    const val AUDIO_BITRATE = 128_000

    // ── Touch Events ──────────────────────────────────────
    const val TOUCH_DOWN: Byte = 0x01.toByte()
    const val TOUCH_UP: Byte = 0x02.toByte()
    const val TOUCH_MOVE: Byte = 0x03.toByte()

    // ── Device Info (emulates iPhone) ─────────────────────
    const val DEVICE_NAME = "iPhone"
    const val DEVICE_MODEL = "iPhone15,2"          // iPhone 14 Pro
    const val DEVICE_OS_VERSION = "17.4.1"
    const val DEVICE_CARRIER = "AIS"
    const val DEVICE_LANGUAGE = "th-TH"
    const val DEVICE_SCREEN_SCALE = 2

    // ── MFi (Authentication) ──────────────────────────────
    const val MFI_VERSION: Byte = 0x01.toByte()
    const val MFI_CHALLENGE_LEN = 32
    const val MFI_RESPONSE_LEN = 64
    const val MFI_CERTIFICATE_LEN = 625

    // MFi Protocol Version (we emulate MFi auth chip behavior)
    const val MFI_PROTOCOL_VERSION = 2              // iAP2

    // ── USB Accessory (AOA) Protocol Constants ────────────
    const val AOA_ACCESSORY_VENDOR_ID = 0x18D1      // Google AOA VID
    const val AOA_ACCESSORY_PRODUCT_ID_BASE = 0x2D00
    const val AOA_ACCESSORY_PRODUCT_ID_AUDIO = 0x2D01
    const val AOA_ACCESSORY_PRODUCT_ID_ADB = 0x2D02
    // Apple CarPlay uses proprietary USB interface, not AOA
    // But we support AOA as fallback for Android Auto compatibility

    // ── Apple USB Interface Identifiers ───────────────────
    const val APPLE_USB_VID = 0x05AC                 // Apple Vendor ID
    const val APPLE_CARPLAY_PID_RANGE_START = 0x1290
    const val APPLE_CARPLAY_PID_RANGE_END = 0x12AF
}
