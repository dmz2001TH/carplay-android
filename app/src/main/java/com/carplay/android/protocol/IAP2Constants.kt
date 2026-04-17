package com.carplay.android.protocol

/**
 * Apple CarPlay / iAP2 Protocol Constants
 *
 * Reverse engineered from Apple iPod Accessory Protocol 2 (iAP2)
 * Used for communicating with CarPlay-compatible head units
 */
object IAP2Constants {

    // ── iAP2 Packet Structure ──────────────────────────────────
    // [Start Byte][Length (2 bytes)][Packet Type][Payload...][Checksum]

    const val START_BYTE: Byte = 0x55.toByte()          // Packet start marker
    const val PACKET_HEADER_SIZE = 5                      // Start(1) + Length(2) + Type(1) + Control(1)

    // ── Link Layer ────────────────────────────────────────────
    const val LINK_PACKET_SYNC: Byte = 0xFF.toByte()
    const val LINK_PACKET_ACK: Byte = 0x06.toByte()
    const val LINK_PACKET_SYN: Byte = 0x02.toByte()

    // ── Session Types ─────────────────────────────────────────
    const val SESSION_CONTROL: Byte = 0x00.toByte()
    const val SESSION_MEDIA: Byte = 0x10.toByte()
    const val SESSION_SCREEN: Byte = 0x20.toByte()
    const val SESSION_TOUCH: Byte = 0x30.toByte()
    const val SESSION_AUDIO_IN: Byte = 0x40.toByte()     // Microphone
    const val SESSION_AUDIO_OUT: Byte = 0x50.toByte()    // Speaker output
    const val SESSION_PHONE: Byte = 0x60.toByte()
    const val SESSION_INFO: Byte = 0x70.toByte()

    // ── Control Messages ──────────────────────────────────────
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

    // ── CarPlay Specific ──────────────────────────────────────
    const val CARPLAY_INIT: Byte = 0x01.toByte()
    const val CARPLAY_INFO: Byte = 0x02.toByte()
    const val CARPLAY_READY: Byte = 0x03.toByte()
    const val CARPLAY_START: Byte = 0x04.toByte()
    const val CARPLAY_STOP: Byte = 0x05.toByte()

    // ── Video Stream ──────────────────────────────────────────
    const val VIDEO_CODEC_H264: Byte = 0x01.toByte()
    const val VIDEO_CODEC_HEVC: Byte = 0x02.toByte()
    const val VIDEO_KEYFRAME_REQUEST: Byte = 0x10.toByte()
    const val VIDEO_FRAME: Byte = 0x20.toByte()

    // Default CarPlay resolution (NissanConnect 8")
    const val VIDEO_WIDTH = 800
    const val VIDEO_HEIGHT = 480
    const val VIDEO_FPS = 30
    const val VIDEO_BITRATE = 2_000_000  // 2 Mbps

    // ── Audio Stream ──────────────────────────────────────────
    const val AUDIO_CODEC_AAC_LC: Byte = 0x01.toByte()
    const val AUDIO_CODEC_PCM: Byte = 0x02.toByte()
    const val AUDIO_SAMPLE_RATE = 44100
    const val AUDIO_CHANNELS = 2
    const val AUDIO_BITRATE = 128_000

    // ── Touch Events ──────────────────────────────────────────
    const val TOUCH_DOWN: Byte = 0x01.toByte()
    const val TOUCH_UP: Byte = 0x02.toByte()
    const val TOUCH_MOVE: Byte = 0x03.toByte()

    // ── Device Info ───────────────────────────────────────────
    const val DEVICE_NAME = "iPhone"
    const val DEVICE_MODEL = "iPhone15,2"          // iPhone 14 Pro
    const val DEVICE_OS_VERSION = "17.4.1"
    const val DEVICE_CARRIER = "AIS"
    const val DEVICE_LANGUAGE = "th-TH"
    const val DEVICE_SCREEN_SCALE = 2

    // ── MFi (Authentication) ──────────────────────────────────
    // Apple MFi auth challenge/response is the hardest part
    // These are the known protocol steps:
    const val MFI_VERSION: Byte = 0x01.toByte()
    const val MFI_CHALLENGE_LEN = 32
    const val MFI_RESPONSE_LEN = 64
    const val MFI_CERTIFICATE_LEN = 625   // Typical MFi cert size

    // MFi Protocol Version (we emulate MFi auth chip behavior)
    const val MFI_PROTOCOL_VERSION = 2    // iAP2
}
