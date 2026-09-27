# Sony ANC Auto-Switch

Automatically toggles Sony WH-1000XM6/XM5 Noise Cancelling when you play or pause media.

**Play media → ANC ON** | **Pause → Ambient mode** | **Stays silent and disconnected → Off**

## How it works

1. A foreground service connects to your headphones via **Bluetooth RFCOMM** using the reverse-engineered Sony MDR protocol (UUID `956c7b26-d49a-4ba8-b03f-b17d393cb6e2` — same as Gadgetbridge).
2. It monitors **all active media sessions** via `MediaSessionManager` (Spotify, YouTube Music, Podcast Addict, etc.).
3. When **any** media starts playing → sends the ANC-on payload.
4. When **all** media is paused/stopped → sends the ambient-sound payload.
5. If the Bluetooth disconnects (walked out of range, headphones turned off) → automatic reconnection with exponential backoff.

## Requirements

- **Android 8.0+** (API 26)
- A **paired** Sony WH-1000XM6, XM5 (or any XM series with the v2 ANC command)
- Bluetooth enabled
- Location permission (legacy requirement for Bluetooth scanning on Android 10 and below)

## Building

### With Android Studio (recommended)

1. Open **Android Studio**
2. `File → Open…` → select the `SonyAncAutoSwitch/` directory
3. Let Gradle sync (it will suggest installing the SDK components)
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
5. Install the APK on your phone

### With command-line Gradle

```bash
# Ensure ANDROID_HOME is set to your SDK location
export ANDROID_HOME=~/Android/Sdk

# Build debug APK
cd SonyAncAutoSwitch
./gradlew assembleDebug

# APK at: app/build/outputs/apk/debug/app-debug.apk
```

> Note: You'll need the Gradle wrapper. If it's missing, run `gradle wrapper` in the project root (requires Gradle installed).

## Installing

1. Pair your WH-1000XM6/XM5 with your phone via **Settings → Bluetooth** (if not already paired)
2. Install the APK
3. Open the app → it should show your headphones in the list
4. Tap **Start Service**
5. Grant any requested permissions (Bluetooth, Notifications)
6. A persistent notification appears → the service is running

## Testing

- Open Spotify → play a song → the notification should show "▶ Playing"
- Pause → the notification shows "⏸ Paused"
- The app should silently switch ANC/Ambient in the background

## Protocol

Based on the reverse-engineering work from [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) and the [sony-mx5-desktop-toggle](https://github.com/MamaJo3/sony-mx5-desktop-toggle) project.

- **Transport:** Bluetooth Classic RFCOMM
- **Service UUID:** `956c7b26-d49a-4ba8-b03f-b17d393cb6e2`
- **Frame:** `SOF(0x3E) | escaped(body) | EOF(0x3C)`
- **ANC payload (v2):** `68 17 01 <on/off> <nc/ambient> <wind> <voice> <level>`
- **Seq bit:** alternates `0→1→0→1…` per message, resets on reconnect

See [PROTOCOL.md](https://github.com/MamaJo3/sony-mx5-desktop-toggle/blob/main/docs/PROTOCOL.md) for full details.

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` | Connect to paired headphones (Android 12+) |
| `BLUETOOTH_SCAN` | Scan for paired devices (Android 12+) |
| `ACCESS_FINE_LOCATION` | Legacy Bluetooth scan (Android 6–10) |
| `FOREGROUND_SERVICE` | Keep service alive in background |
| `POST_NOTIFICATIONS` | Show service notification (Android 13+) |

## File structure

```
SonyAncAutoSwitch/
├── build.gradle.kts
├── settings.gradle.kts
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/fencewatcher/sonyanc/
│       │   ├── SonyAncProtocol.kt      # MDR frame builder + escape
│       │   ├── MediaPlaybackMonitor.kt  # Media session watcher
│       │   ├── BluetoothAncService.kt   # Foreground RFCOMM service
│       │   └── MainActivity.kt         # Device picker + controls
│       └── res/
│           ├── drawable/ic_headphones.xml
│           ├── layout/activity_main.xml
│           └── values/...
└── README.md
```

## XM6 notes

The payloads were confirmed working on XM5 (v2 firmware). The XM6 uses the same protocol — no known breaking changes as of writing. If the ANC behaves unexpectedly, check `adb logcat -s BTAncSvc` for debug output.

## Credits

- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — original Sony protocol RE
- [Plutoberth/SonyHeadphonesClient](https://github.com/Plutoberth/SonyHeadphonesClient) — protocol documentation
- [MamaJo3/sony-mx5-desktop-toggle](https://github.com/MamaJo3/sony-mx5-desktop-toggle) — working reference implementation