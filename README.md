# 🚗 CarPlay Android - Apple CarPlay สำหรับ Android

แอพ Android ที่ปลอมตัวเป็น iPhone เพื่อส่งสัญญาณ Apple CarPlay ไปยังจอ NissanConnect ของ Nissan Almera 2025

## ⚠️ สถานะโปรเจค

**Phase 1 เสร็จแล้ว** — Protocol layer, USB transport, video/audio pipeline ทำงานครบวงจร

**ข้อจำกัดสำคัญ:** Apple MFi authentication ต้องใช้ hardware chip จริง (ราคา ~$2-3/ตัว) + Apple MFi License ซอฟต์แวร์อย่างเดียวไม่สามารถ bypass ได้กับ head unit ที่ตรวจ certificate อย่างเข้มงวด

## 📱 Architecture

```
┌─────────────────────────────────────────────────┐
│                  Android Phone                   │
│                                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │ Dashboard │  │  Maps    │  │   Music      │  │
│  │ (Home)    │  │ (OSM)    │  │ (Media3)     │  │
│  └────┬─────┘  └────┬─────┘  └──────┬───────┘  │
│       │              │               │           │
│  ┌────┴──────────────┴───────────────┴────────┐  │
│  │           CarPlay Service                  │  │
│  │  ┌──────────┐ ┌──────────┐ ┌───────────┐  │  │
│  │  │ Session  │ │  Video   │ │   Audio   │  │  │
│  │  │ Manager  │ │ Encoder  │ │  Encoder  │  │  │
│  │  │ (iAP2)   │ │ (H.264)  │ │ (AAC-LC)  │  │  │
│  │  └────┬─────┘ └────┬─────┘ └─────┬─────┘  │  │
│  │       │            │             │          │  │
│  │  ┌────┴────────────┴─────────────┴───────┐  │  │
│  │  │         iAP2 Protocol Layer           │  │  │
│  │  │  (Link Sync + MFi Auth + Sessions)    │  │  │
│  │  └─────────────────┬─────────────────────┘  │  │
│  └────────────────────┼────────────────────────┘  │
│                       │                           │
│  ┌────────────────────┴────────────────────────┐  │
│  │            USB Transport                    │  │
│  │      (Host Bulk Transfer + AOA Mode)        │  │
│  └────────────────────┬────────────────────────┘  │
└───────────────────────┼───────────────────────────┘
                        │ USB Cable
                        │
┌───────────────────────┴───────────────────────────┐
│           NissanConnect Head Unit                 │
│            (Nissan Almera 2025)                   │
│                                                   │
│    ┌─────────┐ ┌─────────┐ ┌─────────────────┐   │
│    │ Screen  │ │ Audio   │ │  Touch Input    │   │
│    │ Display │ │ Output  │ │  (to phone)     │   │
│    └─────────┘ └─────────┘ └─────────────────┘   │
└───────────────────────────────────────────────────┘
```

## 📂 Project Structure

```
app/src/main/java/com/carplay/android/
├── protocol/
│   ├── IAP2Constants.kt          # Protocol constants (link, session, control codes)
│   ├── IAP2Packet.kt             # Packet builder & parser (link + control layers)
│   ├── MFiAuthHandler.kt         # MFi authentication emulation
│   └── CarPlaySessionManager.kt  # Full session state machine
├── usb/
│   ├── UsbTransport.kt           # USB Host + Accessory (AOA) transport
│   └── UsbReceiver.kt            # USB attach/detach broadcast
├── media/
│   ├── VideoEncoder.kt           # H.264 hardware encoder (MediaCodec)
│   ├── AudioEncoder.kt           # AAC-LC encoder (MediaCodec + AudioRecord)
│   └── ScreenCaptureService.kt   # Screen capture via MediaProjection → VideoEncoder
├── service/
│   └── CarPlayService.kt         # Main foreground service (wires everything)
├── ui/
│   ├── MainActivity.kt           # Main activity (dashboard + controls)
│   ├── dashboard/                # Home screen
│   ├── navigation/               # Maps (OpenStreetMap)
│   ├── music/                    # Music player
│   └── phone/                    # Phone dialer
└── utils/
```

## 🔧 What Was Fixed (Phase 1)

- **iAP2 Protocol** — Rewrote packet format to match real iAP2 (link sync `[0xFF][0x5A]`, control packets with proper checksum)
- **USB Transport** — Added Android Open Accessory (AOA) mode alongside USB Host bulk transfer
- **Video Pipeline** — Connected MediaProjection → ImageReader → NV21 → H.264 MediaCodec → USB (was completely disconnected)
- **Audio Pipeline** — Connected AudioRecord → PCM → AAC-LC MediaCodec → USB (was missing capture source)
- **Session Manager** — Proper state machine: DISCONNECTED → LINK_SYNC → LINK_ESTABLISHED → AUTH → SESSION_NEGOTIATING → ACTIVE
- **ScreenCapture** — Accepts MediaProjection from Activity, feeds VideoEncoder, proper lifecycle

## 🛠️ วิธี Build

### Requirements
- Android Studio Hedgehog (2023.1.1) หรือใหม่กว่า
- Android SDK 34 (Android 14)
- Kotlin 1.9.22
- USB Debugging เปิดใช้บนมือถือ

### Steps

```bash
# 1. Clone project
git clone https://github.com/dmz2001TH/carplay-android.git
cd carplay-android

# 2. เปิดใน Android Studio
# File → Open → เลือกโฟลเดอร์ carplay-android

# 3. Sync Gradle
# Android Studio จะ sync อัตโนมัติ

# 4. Build
./gradlew assembleDebug

# 5. ติดตั้งบนมือถือ
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 🔌 วิธีใช้

1. **เปิด USB Debugging** บนมือถือ Android
2. **เสียบ USB** จากมือถือเข้ากับพอร์ต USB ของ Nissan Almera
3. **เปิดแอพ CarPlay Android**
4. กด **Connect** — เริ่ม iAP2 handshake
5. กด **Screen** — ขอ MediaProjection permission สำหรับ screen capture
6. ถ้า head unit ตอบรับ → จอรถจะแสดง Android screen

## ⚙️ Protocol Flow

```
Android Phone                     NissanConnect Head Unit
     │                                      │
     │──── SYN [0xFF][0x5A] ──────────────→│  1. Link Sync
     │←─── SYN-ACK ────────────────────────│
     │──── ACK ────────────────────────────→│
     │                                      │
     │←─── Auth Challenge ──────────────────│  2. MFi Auth
     │──── MFi Certificate ────────────────→│
     │──── Auth Response ──────────────────→│
     │←─── Auth Success / Failed ───────────│
     │                                      │
     │──── Start Session (Control 0x00) ──→│  3. Sessions
     │──── Start Session (Screen  0x20) ──→│
     │──── Start Session (Audio   0x50) ──→│
     │──── Start Session (Touch   0x30) ──→│
     │←─── Session ACK ─────────────────────│
     │                                      │
     │←── H.264 Video Stream (0x20) ──────→│  4. Active
     │←── AAC Audio Stream  (0x40) ───────→│
     │──→ Touch Events     (0x30) ────────→│
     │                                      │
```

## ⚠️ ข้อจำกัด

| ด้าน | สถานะ | หมายเหตุ |
|---|---|---|
| Protocol Layer | ✅ ทำงานได้ | iAP2 packet format + state machine |
| USB Transport | ✅ ทำงานได้ | Host bulk + AOA accessory mode |
| Video Pipeline | ✅ ทำงานได้ | MediaProjection → H.264 → USB |
| Audio Pipeline | ✅ ทำงานได้ | Mic → AAC-LC → USB |
| MFi Authentication | ⚠️ Emulated | **จะถูก reject โดย head unit จริง** — ต้องใช้ hardware auth chip |
| ใช้กับรถจริง | ❌ ยังไม่ได้ | ต้อง MFi License + real auth chip |

## 📋 TODO

- [ ] หา MFi authentication chip + license (ถ้าต้องการใช้จริง)
- [ ] แก้ touch input mapping ให้ตรงกับจอ NissanConnect
- [ ] เพิ่ม Spotify SDK integration
- [ ] เพิ่ม Bluetooth fallback (A2DP audio)
- [ ] ปรับ H.264 encoder ให้ latency ต่ำลง
- [ ] เพิ่ม Thai language UI
- [ ] เพิ่ม error recovery / auto-reconnect
- [ ] ทดสอบกับ Nissan Almera 2025 จริง

## 📄 License

สำหรับการใช้งานส่วนตัวเท่านั้น ไม่ใช่เชิงพาณิชย์
