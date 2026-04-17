# Handoff: CarPlay Android Project

## Project Overview
- **Repo**: https://github.com/dmz2001TH/carplay-android
- **Goal**: Android app that pretends to be iPhone, sends CarPlay signal to Nissan Almera 2025 head unit
- **Current Status**: v3.0.0 released, UI complete, protocol layer functional but needs real car testing
- **Latest Release**: https://github.com/dmz2001TH/carplay-android/releases/tag/v3.0.0

## What Has Been Done

### Phase 1 — UI Modernize + App Launcher
- Modern dark CarPlay theme (Apple style)
- App tiles with brand colors (YouTube red, Maps blue, YT Music orange)
- Now Playing widget with progress bar + inline controls
- Modernized phone dialer
- App launcher: YouTube, YouTube Music, Google Maps, Spotify, Phone — all open via Intent
- Google Maps WebView (no API key needed)
- Music hub with MediaSession observation + playback controls

### Phase 2 — OkcarOS Research + Protocol
- Studied OkcarOS (github.com/okcar-os/android) — 414 stars, REAL working CarPlay on Android
- Key finding: MFi chip is in HEAD UNIT, not phone. Head units don't verify MFi cert cryptographically
- Cheap dongles ($15-30) work by just responding correctly with 64-byte format-correct response
- Created `UsbGadgetConfigurator.kt` — configures Android USB gadget as Apple iPhone (VID=0x05AC, PID=0x12A8)
- Updated `MFiAuthHandler.kt` — simplified to just send format-correct response
- Added hex debug logging every TX/RX byte to `/sdcard/carplay_debug.log`

### Phase 3 — Build & Deploy
- Built successfully: APK 8.7MB debug, 7MB release
- JDK 17 + Android SDK 34 installed on server at `/opt/jdk` and `/opt/android-sdk`
- Environment variables: `JAVA_HOME=/opt/jdk`, `ANDROID_HOME=/opt/android-sdk`
- Released v3.0.0 on GitHub

## Build Environment (on server)

```bash
export JAVA_HOME=/opt/jdk
export ANDROID_HOME=/opt/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH

cd /root/.openclaw/workspace/carplay-android
./gradlew assembleDebug --no-daemon
# APK: app/build/outputs/apk/debug/app-debug.apk
# or:  app/build/outputs/apk/release/app-release.apk
```

## Key Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/carplay/android/usb/UsbGadgetConfigurator.kt` | Configure USB as iPhone (root required) |
| `app/src/main/java/com/carplay/android/usb/UsbTransport.kt` | USB Host + Accessory transport |
| `app/src/main/java/com/carplay/android/protocol/IAP2Packet.kt` | iAP2 packet builder/parser |
| `app/src/main/java/com/carplay/android/protocol/CarPlaySessionManager.kt` | Session state machine |
| `app/src/main/java/com/carplay/android/protocol/MFiAuthHandler.kt` | MFi auth bypass |
| `app/src/main/java/com/carplay/android/service/CarPlayService.kt` | Main foreground service |
| `app/src/main/java/com/carplay/android/ui/MainActivity.kt` | Main activity |
| `app/src/main/java/com/carplay/android/ui/dashboard/DashboardFragment.kt` | App launcher grid |
| `app/src/main/java/com/carplay/android/ui/music/MusicFragment.kt` | Music hub |
| `app/src/main/java/com/carplay/android/ui/navigation/MapsFragment.kt` | Google Maps WebView |

## OkcarOS Key Findings (reference: okcar-os/android on GitHub)

### USB Gadget Config (init.qcom.okcar.rc)
```
VID=0x05AC, PID=0x12A8 (Apple)
Product="iPhone", Manufacturer="Apple Inc."
Serial="d9ccb8ebdb3e6d6db237d513894b43390e0afec1"

Configs:
  b.1: PTP (Image)
  b.2: iPod USB Interface (audio_source + HID)
  b.3: PTP + Apple Mobile Device (okcar_image + okcar_mobile)
  b.4: PTP + Apple Mobile Device + USB Ethernet (NCM)
```

### Architecture
- Kernel custom functions: `okcar_mobile.gs0` (iAP2), `okcar_usb_ethernet.gs0` (NCM), `okcar_image.gs0` (video)
- User-space: `pccall` (IP routing), `autoconn` (connection detection)
- Touch: `/dev/hidg0` HID gadget
- Video/audio: over NCM network interface (virtual `usb0`)
- MFi bypass: kernel-level, no userspace cert verification by head unit

## What Still Needs Work

1. **Real car testing** — needs rooted Android phone + Nissan Almera 2025 head unit
2. **Kernel module** — OkcarOS approach needs custom kernel with okcar gadget functions; without it, need to use standard ACM/NCM gadget functions
3. **NCM video pipeline** — currently uses screen capture → H.264 → USB bulk; should be screen capture → H.264 → NCM network → head unit
4. **Audio over NCM** — audio should go over NCM network, not USB bulk
5. **Touch via HID** — `/dev/hidg0` needs proper HID report descriptor for touch events
6. **Connection detection** — `autoconn` service that detects when head unit connects

## How to Continue

1. Test on real rooted phone with `adb install app-release.apk`
2. Check `/sdcard/carplay_debug.log` for protocol flow
3. If MFi auth fails: need to study OkcarOS kernel driver more carefully
4. If connection works but no video: fix NCM pipeline
5. If video works but no touch: set up HID gadget properly

## Git History
```
1a3476a Fix: targetSdk 33, minSdk 24, explicit signing, cleartextTraffic
df30f90 Fix build errors - BUILD SUCCESSFUL (8.6MB APK)
4e21401 Phase 3: OkcarOS-based USB Gadget + iAP2 bypass + Debug logging
c54b158 Modern CarPlay UI + YouTube/YT Music/Google Maps integration
```

## Security Note
⚠️ GitHub token was used during development — **revoke immediately** at https://github.com/settings/tokens
