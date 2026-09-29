# Sony ANC Auto-Switch

> Automatically toggles ANC on your Sony WH-1000XM6/XM5 headphones when media plays or pauses — with a built-in **visual EQ** and **per-device profiles**.

**Media plays** → 🎧 **Noise Cancelling** | **Pause** → 🌬 **Ambient mode** | **Nothing playing** → 🔊 **Off**

![v1.17](https://img.shields.io/badge/version-1.17-blue)

---

## Features

- **🎵 Auto ANC** — Switches NC/Ambient/Off based on media playback (Spotify, YouTube Music, Podcast Addict, etc.)
- **⚙️ Customisable automations** — Decide what happens on playback start/stop and connect/disconnect.
  Triggers: playback starts, playback stops, headphones connect, headphones disconnect. Actions: do
  nothing, set noise control, set ambient level, set volume, set EQ preset. Rules evaluate in order and
  the first match wins — the default play → NC / stop → ambient behaviour is just an editable starting point.
- **📱 Multipoint control** — See paired devices, which is the active audio source, and tap to switch
- **🔊 Media volume + voice guidance volume** — read back and set through the device
- **⏻ Power off** — Shut the headphones down from the app
- **🎛️ Visual EQ** — Drag the frequency response curve (10 bands, 31Hz–16kHz, -6..+6 dB)
  - 8 presets: Off, Heavy, Clear, Hard, Soft, Custom, User 1, User 2
  - Write custom bands to Custom, User 1, or User 2 slots
  - EQ state readback from headphones — sliders show the actual current curve
- **🎧 Multi-model support** — Auto-detects XM5 vs XM6 and uses the correct protocol (7-byte vs 9-byte payloads + correct UUID)
- **📁 Per-device settings** — Each headphone remembers its own ambient level, voice passthrough, and auto-ambient settings
- **🔒 Playback lock ("Fix Playback")** — Pin audio to this phone so a second paired device can't steal it
- **📶 Multipoint control** — List paired devices, switch playback between them, connect/disconnect, and unpair
- **⏸️ Pause auto-ANC** — Toggle in the notification to temporarily stop media reactions (manual buttons still work)
- **⚡ Quick mode buttons** — Tap NC/Ambient/Off directly from the Dashboard tab
- **🔋 Battery + mode display** — See battery level and current ANC mode in the headphone card
- **🔄 Auto-reconnect** — Reconnects with exponential backoff if Bluetooth drops

### Dashboard / Settings / EQ tabs

| Tab | Content |
|---|---|
| **Dashboard** | Headphone card (model, battery, mode), Start/Stop, quick NC/Ambient/Off |
| **Settings** | Device selector, ambient level slider, voice passthrough, auto-ambient, playback lock, pairing mode, multipoint device list |
| **EQ** | 8 preset buttons + visual frequency response curve with draggable band dots |

---

## How it works

1. A foreground service connects to your headphones via **Bluetooth RFCOMM** using the reverse-engineered Sony MDR protocol
2. It monitors active media sessions via `MediaSessionManager` / `AudioManager`
3. When media plays → sends the ANC-on payload
4. When paused → sends the ambient-sound payload
5. If the Bluetooth disconnects → automatic reconnection with exponential backoff
6. EQ commands use the `0x58` / `0x59` protocol family with 10 band values (±6 dB, +6 offset)

## Requirements

- **Android 8.0+** (API 26)
- A **paired** Sony WH-1000XM6, XM5 (or compatible)
- Bluetooth enabled

## Building

### With Android Studio

1. `File → Open…` → select the project directory
2. Let Gradle sync
3. `Build → Build Bundle(s) / APK(s) → Build APK(s)`

### With command line

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Installing

1. Pair your headphones via **Settings → Bluetooth**
2. Install the APK
3. Open the app → it shows your headphones in the list
4. Tap **▶ Start**
5. Grant requested permissions (Bluetooth, Notifications)
6. A persistent notification appears — the service is running

The **BuildConfig** includes the current commit hash as `BUILD_HASH` — visible at the bottom of the Dashboard tab.

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` | Connect to paired headphones (Android 12+) |
| `BLUETOOTH_SCAN` | Scan for paired devices (Android 12+) |
| `ACCESS_FINE_LOCATION` | Legacy Bluetooth scan (Android 6–10) |
| `FOREGROUND_SERVICE` | Keep service alive in background |
| `POST_NOTIFICATIONS` | Show service notification (Android 13+) |

## Protocol

The service implements the Sony MDR (Music Data Relay) protocol over Bluetooth RFCOMM:

- **Transport:** Bluetooth Classic RFCOMM
- **Service UUIDs:** `956c7b26-…` (XM6 v2) or `96cc203e-…` (XM5 v1)
- **Frame:** `SOF(0x3E) | escaped(body) | EOF(0x3C)`
- **ANC payload (XM6):** `68 19 01 <enable> <mode> <av> <level> <na> <naSens>` (9 bytes)
- **ANC payload (XM5):** `68 18 01 <totalEffect> <mode> <av> <level>` (7 bytes)
- **EQ preset:** `58 00 <presetID> 00` + re-query `56 00`
- **Custom EQ:** `58 00 <profileID> <count> <bands…>` + re-query
- **EOF/ACK:** Stop-and-wait, alternating seq bit per message

Protocol reverse-engineering credits: [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) and [xm6-control](https://github.com/MaxKotelnikov/xm6-control).

## File structure

```
app/src/main/java/com/fencewatcher/sonyanc/
├── BluetoothAncService.kt   # Foreground RFCOMM service — connection, handshake, ANC, frame parsing
├── MediaPlaybackMonitor.kt  # Media session for playback detection
├── HeadphoneProfile.kt       # XM5/XM6 protocol profiles — UUID, payload format, handshake
├── SonyAncProtocol.kt        # MDR frame builder: SOF/EOF/escaped/checksum
├── EQPreset.kt               # EQ preset enum (Off/Heavy/Clear/Hard/Soft/Custom/User1-5)
├── EQGraphView.kt            # Custom Canvas view: frequency response curve with draggable dots
└── MainActivity.kt           # ˣ-tab UI, device picker, settings, EQ tab
```

## Credits

- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgebridge) — original Sony protocol RE
- [Plutoberth/SonyHeadphonesClient](https://github.com/Plutoberth/SonyHeadphonesClient) — protocol documentation
- [MaxKotelnikov/xm-control](https://github.com/MaxKotelnikov/xm6-control) — macOS reference implementation with XM5 separations