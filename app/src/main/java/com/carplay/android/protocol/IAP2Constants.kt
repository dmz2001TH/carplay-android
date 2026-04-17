package com.carplay.android.protocol

/**
 * Apple CarPlay / iAP2 Protocol Constants
 *
 * Based on:
 * - OkcarOS reverse-engineered implementation (414 stars, working on real cars)
 * - node-carplay, CPDCarPlayIndigo (open source reverse engineering)
 * - Apple iAP2 spec fragments
 *
 * Key facts from OkcarOS research:
 * - iAP2 packets start with: FF 5A (link) or 40 (control)
 * - Head unit does NOT verify MFi cert cryptographically on most cars
 * - Android needs: USB VID=0x05AC, PID=0x12A8 to be seen as iPhone
 * - CarPlay uses 2 USB interfaces: iAP2 (control) + NCM (video/audio)
 * - NCM = USB Network Control Model, virtualizes as network interface `usb0`
 * - Touch goes via HID gadget (/dev/hidg0)
 * - Audio can go via audio_source gadget or NCM stream
 */
object IAP2Constants {

    // ── iAP2 Packet Structure ──────────────────────────────
    // Link packet:  [0xFF][0x5A][Length 2B BE][0xFF][Payload...][Checksum]
    // Control packet: [0x40][SessionID][PayloadLen 2B BE][MsgType][Payload...][Checksum]
    const val LINK_SYNC_BYTE_1: Byte = 0xFF.toByte()
    const val LINK_SYNC_BYTE_2: Byte = 0x5A.toByte()
    const val CONTROL_PACKET_START: Byte = 0x40.toByte()
    const val PACKET_HEADER_SIZE = 5

    // ── Link Layer Messages ────────────────────────────────
    const val LINK_MSG_SYN: Byte = 0xFF.toByte()
    const val LINK_MSG_SYN_ACK: Byte = 0x06.toByte()
    const val LINK_MSG_ACK: Byte = 0x06.toByte()
    const val LINK_MSG_EAK: Byte = 0x05.toByte()
    const val LINK_MSG_RST: Byte = 0x52.toByte()

    // ── Session Types (iAP2 session IDs) ──────────────────
    const val SESSION_CONTROL: Byte = 0x00.toByte()
    const val SESSION_MEDIA: Byte = 0x10.toByte()
    const val SESSION_SCREEN: Byte = 0x20.toByte()
    const val SESSION_TOUCH: Byte = 0x30.toByte()
    const val SESSION_AUDIO_IN: Byte = 0x40.toByte()
    const val SESSION_AUDIO_OUT: Byte = 0x50.toByte()
    const val SESSION_PHONE: Byte = 0x60.toByte()
    const val SESSION_INFO: Byte = 0x70.toByte()

    // ── iAP2 Link Sync ─────────────────────────────────────
    const val IAP2_LINK_VERSION: Byte = 0x02
    const val IAP2_MAX_PACKET_SIZE = 4096
    const val IAP2_MAX_RECEIVED_SIZE = 65535

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

    // NissanConnect 8" display defaults
    const val VIDEO_WIDTH = 800
    const val VIDEO_HEIGHT = 480
    const val VIDEO_FPS = 30
    const val VIDEO_BITRATE = 2_000_000

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
    const val DEVICE_MODEL = "iPhone15,2"
    const val DEVICE_OS_VERSION = "17.4.1"
    const val DEVICE_CARRIER = "AIS"
    const val DEVICE_LANGUAGE = "th-TH"
    const val DEVICE_SCREEN_SCALE = 2

    // ── MFi ───────────────────────────────────────────────
    const val MFI_VERSION: Byte = 0x01.toByte()
    const val MFI_CHALLENGE_LEN = 32
    const val MFI_RESPONSE_LEN = 64
    const val MFI_CERTIFICATE_LEN = 625
    const val MFI_PROTOCOL_VERSION = 2

    // ── Apple USB Identifiers (from OkcarOS) ──────────────
    // Confirmed working: VID=0x05AC, PID=0x12A8
    const val APPLE_USB_VID = 0x05AC
    const val APPLE_USB_PID = 0x12A8
    const val APPLE_BCD_DEVICE = 0x1003
    const val APPLE_BCD_USB = 0x0200

    // ── USB Gadget Config (OkcarOS reference) ─────────────
    // ConfigFS base path
    const val USB_GADGET_PATH = "/config/usb_gadget/g1"
    const val USB_UDC_PATH = "$USB_GADGET_PATH/UDC"

    // ── Debug ──────────────────────────────────────────────
    const val DEBUG_LOG_PATH = "/sdcard/carplay_debug.log"
}
