# CarPlay Android — Handoff Prompt

## Context
สานต่อโปรเจค carplay-android (https://github.com/dmz2001TH/carplay-android) — แอพ Android ที่ปลอมตัวเป็น iPhone เพื่อส่ง Apple CarPlay ไปยังจอ NissanConnect ของ Nissan Almera 2025 ผ่าน USB

## สิ่งที่ทำแล้ว (ทั้งหมด)

### Phase 1 (ก่อนหน้า)
- ✅ iAP2 Protocol layer — packet format `[0xFF][0x5A]` link sync + control packets + checksum
- ✅ USB Transport — Host bulk transfer + AOA accessory mode
- ✅ Video pipeline — MediaProjection → ImageReader → NV21 → H.264 MediaCodec → USB
- ✅ Audio pipeline — AudioRecord → AAC-LC MediaCodec → USB
- ✅ Session state machine — DISCONNECTED → LINK_SYNC → AUTH → SESSION_NEGOTIATING → ACTIVE
- ✅ Build ผ่าน — APK 9.1 MB, Android SDK 34, Kotlin 1.9.22, Gradle 8.5

### Phase 2 (ทำใน session นี้)
**commit `381c10f` — Core improvements:**
- ✅ `ReconnectManager.kt` — exponential backoff auto-reconnect (1s→30s, max 10 retries)
- ✅ `TouchDispatcher.kt` — head unit touch→Android input mapping (800x480→phone display), AccessibilityService dispatch + input injection fallback, configurable calibration
- ✅ `VideoEncoder.kt` — CBR bitrate + KEY_LATENCY=0 + no B-frames สำหรับ latency ต่ำ
- ✅ `AudioEncoder.kt` — แยก capture/encode thread, แก้ timestamp calculation
- ✅ `CarPlayService.kt` — integrate ReconnectManager + TouchDispatcher + heartbeat keepalive (5s interval, 15s timeout)
- ✅ `values-th/strings.xml` — Thai language UI ครบ
- ✅ `strings.xml` — ย้าย hardcoded strings ไป resources

**commit `cac1023` — Crash fix #1 (แอพเด้งทันทีที่เปิด):**
- ✅ `ScreenCaptureService` ประกาศเป็น `<service>` ใน manifest แต่ไม่ได้ extends `Service` → ลบออก
- ✅ `Timber.plant()` ไม่เคยถูกเรียก → สร้าง `CarPlayApplication.kt` + register ใน manifest
- ✅ `uses-feature usb.host required=true` → `false`
- ✅ `buildConfig true` เพิ่มใน build.gradle

**commit `d869270` — Runtime permissions:**
- ✅ `MainActivity.kt` — request ทุก permission ตอนเปิดแอพ (RECORD_AUDIO, location, bluetooth, notifications)
- ✅ `POST_NOTIFICATIONS` เพิ่มใน manifest (Android 13+)

**commit `e942a48` — Crash fix #2 (Android 14 / iQOO 12 5G):**
- ✅ `startForeground()` เปลี่ยนเป็น `connectedDevice` type อย่างเดียวตอนเริ่ม (ไม่ใส่ `mediaProjection` จนกว่าจะได้ grant)
- ✅ `setMediaProjection()` อัพเดท foreground service type แบบ dynamic
- ✅ ลบ `android:screenOrientation="landscape"` จาก manifest

**GitHub Release:**
- ✅ v1.0-alpha — APK 9.3 MB attach อยู่ที่ https://github.com/dmz2001TH/carplay-android/releases/tag/v1.0-alpha

## Build Environment
```bash
# JDK 17 + Android SDK 34 (ติดตั้งที่ /opt แล้ว)
export JAVA_HOME=$(ls -d /opt/jdk-17*/)
export ANDROID_HOME=/opt/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
cd carplay-android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk (~9.3MB)
```

## ปัญหาหลักที่ยังแก้ไม่ได้
**MFi Authentication** — `MFiAuthHandler.kt` ใช้ software emulation (SHA-256 + XOR) แทน RSA-1024 + AES-128 จาก Apple MFi hardware chip head unit จริงจะ reject certificate ปลอม นี่คือ blocker หลัก

ตัวเลือก:
1. หา MFi auth chip (NXP SE050 หรือ Apple MFi IC) — ต้องมี Apple MFi License
2. ดูจาก open-source projects ที่ reverse MFi auth เช่น node-carplay, CPDCarPlayIndigo
3. ทำ demo mode ที่ไม่ต้องผ่าน auth (ถ้า head unit มี bypass)

## งานที่ควรทำต่อ
1. ทดสอบกับ iQOO 12 5G (Android 14) — แก้ crash แล้ว ยังไม่ได้ confirm ว่าเปิดได้
2. Spotify SDK integration
3. Bluetooth A2DP fallback (audio)
4. แก้ layout strings บางส่วนยัง hardcode ภาษาอังกฤษ (dashboard card labels)
5. Touch calibration UI (หน้าตั้งค่าสำหรับปรับ mapping)
6. Error logging / crash report (Firebase Crashlytics หรือ equivalent)
7. ทดสอบกับ Nissan Almera 2025 จริง (ต้องแก้ MFi ก่อน)
8. Thai language UI — strings.xml ครบแล้ว แต่ layout บางส่วนยัง hardcode

## Project Structure
```
app/src/main/java/com/carplay/android/
├── CarPlayApplication.kt          # App class (Timber init)
├── protocol/
│   ├── IAP2Constants.kt           # Protocol constants
│   ├── IAP2Packet.kt              # Packet builder & parser
│   ├── MFiAuthHandler.kt          # MFi auth emulation (BLOCKER)
│   └── CarPlaySessionManager.kt   # Session state machine
├── usb/
│   ├── UsbTransport.kt            # USB Host + AOA transport
│   └── UsbReceiver.kt             # USB attach/detach broadcast
├── media/
│   ├── VideoEncoder.kt            # H.264 encoder (low-latency)
│   ├── AudioEncoder.kt            # AAC-LC encoder (dual-thread)
│   └── ScreenCaptureService.kt    # MediaProjection → VideoEncoder
├── service/
│   ├── CarPlayService.kt          # Main foreground service
│   ├── ReconnectManager.kt        # Auto-reconnect with backoff
│   └── TouchDispatcher.kt         # Touch input mapping
├── ui/
│   ├── MainActivity.kt            # Main activity + permissions
│   ├── dashboard/DashboardFragment.kt
│   ├── navigation/MapsFragment.kt  # OpenStreetMap
│   ├── music/MusicFragment.kt
│   └── phone/PhoneFragment.kt
└── utils/
```

## ข้อมูลสำคัญ
- ทดสอบบน: iQOO 12 5G (Android 14 / FuntouchOS)
- จอ NissanConnect: 800×480
- GitHub token: ผู้ใช้จะส่งให้ตอน push (ไม่ได้เก็บไว้ใน repo)
- AndroidManifest.xml สำคัญมาก — หลาย crash มาจาก manifest ผิด
