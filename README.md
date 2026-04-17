# 🚗 CarPlay Android - Apple CarPlay สำหรับ Android

แอพ Android ที่ปลอมตัวเป็น iPhone เพื่อส่งสัญญาณ Apple CarPlay ไปยังจอ NissanConnect ของ Nissan Almera 2025

## 📱 Architecture

```
┌─────────────────────────────────────────────────┐
│                  Android Phone                   │
│                                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────────┐  │
│  │ Dashboard │  │  Maps    │  │   Music      │  │
│  │ (Home)    │  │ (Google) │  │ (Spotify)    │  │
│  └────┬─────┘  └────┬─────┘  └──────┬───────┘  │
│       │              │               │           │
│  ┌────┴──────────────┴───────────────┴────────┐  │
│  │           CarPlay Service                  │  │
│  │  ┌──────────┐ ┌──────────┐ ┌───────────┐  │  │
│  │  │ Session  │ │  Video   │ │   Audio   │  │  │
│  │  │ Manager  │ │ Encoder  │ │  Encoder  │  │  │
│  │  └────┬─────┘ └────┬─────┘ └─────┬─────┘  │  │
│  │       │            │             │          │  │
│  │  ┌────┴────────────┴─────────────┴───────┐  │  │
│  │  │         iAP2 Protocol Layer           │  │  │
│  │  │    (MFi Auth + Packet Builder)        │  │  │
│  │  └─────────────────┬─────────────────────┘  │  │
│  └────────────────────┼────────────────────────┘  │
│                       │                           │
│  ┌────────────────────┴────────────────────────┐  │
│  │            USB Transport                    │  │
│  │         (Host Mode + Bulk Transfer)         │  │
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
│   ├── IAP2Constants.kt      # Protocol constants (packet types, codes)
│   ├── IAP2Packet.kt         # Packet builder & parser
│   ├── MFiAuthHandler.kt     # MFi authentication emulation
│   └── CarPlaySessionManager.kt  # Session lifecycle manager
├── usb/
│   ├── UsbTransport.kt       # USB Host communication
│   └── UsbReceiver.kt        # USB attach/detach broadcast
├── media/
│   ├── VideoEncoder.kt       # H.264 video encoder (screen capture)
│   ├── AudioEncoder.kt       # AAC audio encoder (microphone)
│   └── ScreenCaptureService.kt  # Screen capture via MediaProjection
├── service/
│   └── CarPlayService.kt     # Main foreground service
├── ui/
│   ├── MainActivity.kt       # Main activity (CarPlay dashboard)
│   ├── dashboard/
│   │   └── DashboardFragment.kt  # Home screen with app grid
│   ├── navigation/
│   │   └── MapsFragment.kt   # Google Maps integration
│   ├── music/
│   │   └── MusicFragment.kt  # Music player UI
│   └── phone/
│       └── PhoneFragment.kt  # Phone dialer & contacts
└── utils/
```

## 🛠️ วิธี Build

### Requirements
- Android Studio Hedgehog (2023.1.1) หรือใหม่กว่า
- Android SDK 34 (Android 14)
- Kotlin 1.9.22
- USB Debugging เปิดใช้บนมือถือ

### Steps

```bash
# 1. Clone project
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

### Google Maps API Key
เพิ่ม Maps API key ใน `AndroidManifest.xml`:
```xml
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="YOUR_API_KEY_HERE" />
```

## 🔌 วิธีใช้

1. **เปิด USB Debugging** บนมือถือ Android
2. **เสียบ USB** จากมือถือเข้ากับพอร์ต USB ของ Nissan Almera
3. **เปิดแอพ CarPlay Android**
4. กด **Connect** — แอพจะจับมือกับ head unit ผ่าน iAP2 protocol
5. ถ้า **Authentication ผ่าน** → จอรถจะแสดงหน้า Android screen
6. ใช้ Maps, Music, Phone ได้เลย

## ⚙️ Protocol Flow

```
Android Phone                     NissanConnect Head Unit
     │                                      │
     │──── SYN ────────────────────────────→│  1. Link Sync
     │←─── ACK ─────────────────────────────│
     │                                      │
     │←─── Auth Challenge ──────────────────│  2. MFi Auth
     │──── Certificate ────────────────────→│
     │──── Auth Response ──────────────────→│
     │←─── Auth Success ────────────────────│
     │                                      │
     │──── Start Session (Control) ────────→│  3. Session Setup
     │──── Start Session (Screen) ─────────→│
     │──── Start Session (Audio) ──────────→│
     │──── Start Session (Touch) ──────────→│
     │←─── Session ACK ─────────────────────│
     │                                      │
     │←→ H.264 Video Stream ───────────────→│  4. Active Session
     │←→ AAC Audio Stream ─────────────────→│
     │←→ Touch Events ─────────────────────→│
     │                                      │
```

## ⚠️ ข้อจำกัด

- **MFi Auth**: Apple ใช้ hardware authentication chip จริงๆ แอพนี้ใช้ protocol emulation ซึ่งอาจไม่ผ่าน auth กับ head unit ทุกรุ่น
- **Video Latency**: Screen capture + H.264 encode + USB transfer มี delay ~100-200ms
- **Audio Quality**: ขึ้นกับ USB bandwidth และ codec performance
- **Risk**: การ reverse engineer protocol อาจ void warranty

## 📋 TODO

- [ ] เพิ่ม MFi real authentication flow
- [ ] แก้ touch input mapping ให้ตรงกับจอ NissanConnect
- [ ] เพิ่ม Spotify SDK integration
- [ ] เพิ่ม Bluetooth fallback (A2DP audio)
- [ ] ปรับ H.264 encoder ให้ latency ต่ำลง
- [ ] เพิ่ม Thai language UI
- [ ] เพิ่ม error recovery / auto-reconnect
- [ ] ทดสอบกับ Nissan Almera 2025 จริง

## 📄 License

สำหรับการใช้งานส่วนตัวเท่านั้น ไม่ใช่เชิงพาณิชย์
