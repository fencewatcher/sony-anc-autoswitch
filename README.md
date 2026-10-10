# Sony ANC Auto-Switch

> Automatically toggles ANC on your Sony WH-1000XM6 headphones when media plays or pauses — with a built-in **visual EQ**, **per-device profiles**, and a live readout of what the headphones are actually doing.

**Media plays** → 🎧 **Noise Cancelling** | **Pause** → 🌬 **Ambient mode** | **Nothing playing** → 🔊 **Off**

![v1.94](https://img.shields.io/badge/version-1.94-blue)

---

## Features

### Behaviour

- **🎵 Auto ANC** — Switches NC/Ambient/Off based on media playback (Spotify, YouTube Music, podcast apps, …)
- **⚙️ Customisable automations** — Rules with triggers (playback starts, playback stops, headphones connect, headphones disconnect, headphones on/off head) and actions (nothing, set noise control, set ambient level, set volume, set EQ preset). Rules evaluate in order, first match wins.
- **📁 Per-device settings** — Each headphone remembers its own ambient level, voice passthrough and auto-ambient settings
- **🔒 Playback lock ("Fix Playback")** — Pin audio to this phone so a second paired device can't steal it
- **⏸️ Pause auto-switching** — Stop routines reacting to play/pause, from the Home tab or the notification; both stay in sync
- **🔄 Auto-reconnect** — Exponential backoff when Bluetooth drops, and a **Waiting** state once the headphones look absent: the service stays alive and picks them up the moment they return, instead of reading as "stopped"
- **🧹 Clean stop** — stopping the service clears battery and mode readouts instead of leaving stale values on the card; impossible battery readings are ignored as protocol noise
- **⚡ Retry now** — Skip the reconnect backoff with one tap on the Home card or the notification while the headphones are away

### Readouts — verified on hardware

- **👕 Wear detection** — Asks the headphones whether they're on your head, rather than inferring it. Green when worn, yellow when off, and drives the on/off-head automation triggers.
- **🎧 Active codec** — Reads back whether LDAC or AAC is actually running.
- **🔊 Media volume + voice guidance volume** — read back and set through the device
- **🔋 Battery + mode display** — Battery level and current ANC mode in the headphone card

### Controls

- **🎛️ Visual EQ** — Drag the frequency response curve (10 bands, 31 Hz–16 kHz, ±6 dB)
  - 8 presets: Off, Heavy, Clear, Hard, Soft, Custom, User 1, User 2
  - Write custom bands to Custom, User 1 or User 2
  - EQ state read back from the headphones — the curve always shows what's actually set; editing is enabled only while an editable slot (Custom/User) is selected, and the status label holds steady through a write instead of flickering between values
- **📶 Multipoint** — List paired devices with connected/active indicators (connected first), switch the active source, lock playback, unpair
- **🖐️ Quick Access** — Choose what a double- or triple-press of the [NC/AMB] button launches
- **⏻ Power off** — Shut the headphones down from the app
- **⚡ Quick mode buttons** — Tap NC/Ambient/Off directly from the Home tab

### Audio tab

Ambient level, voice passthrough, auto-ambient, speak-to-chat, pause when taken off, DSEE, BGM, upmix, auto power off, voice guidance, plus a readout of which upscaling variant the headset has selected.

### Tabs

| Tab | Content |
|---|---|
| **Home** | Headphone card (model, battery, mode, wear indicator, codec), Start/Stop, quick NC/Ambient/Off, pause auto-switching, power off |
| **Audio** | EQ presets + draggable curve, ambient, voice passthrough, speak-to-chat, DSEE, BGM, upmix, voice guidance |
| **Devices** | Headphone picker, connection mode, multipoint list, playback lock, pairing mode |
| **Routines** | Quick Access shortcuts, then your automation rules |

### Developer

- **🔍 Debug frame log** — Every TX/RX frame with millisecond timestamps, per table (T1/T2). Five taps on the app title. This is what to paste when something misbehaves.

---

## How it works

1. A foreground service connects over **Bluetooth RFCOMM** using the reverse-engineered Sony MDR protocol
2. It monitors media sessions via `MediaSessionManager` / `AudioManager`
3. Media plays → sends the ANC-on payload; paused → ambient payload
4. Bluetooth drops → automatic reconnection with backoff; when the headphones are genuinely away the service enters **Waiting** — still running, still watching, and it reconnects on its own when they come back. A **Retry now** button skips the backoff.
5. State is read back from the headphones with a real stop-and-wait request/response exchange

### Known gaps

- **LE Audio transport switching is not implemented.** The set payload is four
  fields: `isLEAudioType`, `isLEAItem`, a `ConnModeSettingType` and a
  `QualityPriorValue`. An earlier version sent only two bytes, so the frame was
  truncated *and* expressed the wrong idea — the booleans are LE Audio markers,
  not an on/off pair. The enum byte codes were recovered from smali (`PriorMode` is
  0/1/2, `ConnModeSettingType` has a single `SOUND_CONNECTION`), but the wire order
  of the four fields is not visible statically, and guessing it has already cost
  five broken releases. The status query is still issued and the raw reply logged,
  so a capture from the official app can settle the order.

## Requirements

- **Android 8.0+** (API 26)
- A **paired** Sony WH-1000XM6 (or compatible)
- Bluetooth enabled

**WH-1000XM5 is not supported.** Its protocol paths were never verified on real
hardware — connections half-worked and readouts were wrong — so XM5 support was
scrapped in v1.83 rather than shipped broken. The service refuses an XM5 by
device name.

## Building

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and use **Build → Build APK(s)**.

The APK records its commit hash as `BUILD_HASH`, shown at the bottom of the Home tab.

## Installing

1. Pair your headphones in **Settings → Bluetooth**
2. Install the APK
3. Open the app, select your headphones, tap **▶ Start**
4. Grant the requested permissions (Bluetooth, notifications)
5. A persistent notification appears — the service is running

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` | Connect to paired headphones (Android 12+) |
| `BLUETOOTH_SCAN` | Scan for paired devices (Android 12+) |
| `ACCESS_FINE_LOCATION` | Legacy Bluetooth scan (Android 6–10) |
| `FOREGROUND_SERVICE` | Keep the service alive in the background |
| `POST_NOTIFICATIONS` | Show the service notification (Android 13+) |

---

## Protocol

The service implements the Sony **MDR** (Music Data Relay) protocol over Bluetooth Classic RFCOMM.

- **Service UUIDs:** `956c7b26-…` (MDR v2)
- **Frame:** `SOF(0x3E) | dataType | seq | size(4B BE) | payload | checksum | EOF(0x3C)`
- **ANC payload (XM6):** `68 19 01 <enable> <mode> <av> <level> <na> <naSens>` (9 bytes)
- **EQ preset:** `58 00 <presetID> 00`, then re-query `56 00`
- **Custom EQ:** `58 00 <profileID> <count> <bands…>`, then re-query

### Two command tables, not one

This is the single most important thing to know about the protocol.

Every frame carries a **dataType** that selects one of two command tables:

| dataType | Table |
|---|---|
| `0x0C` | **T1** — the main table: ANC, EQ, codec, connection mode, Quick Access |
| `0x0E` | **T2** — peripheral/multipoint **and wear detection** |

**The two tables share command bytes but have completely disjoint type-byte enums.** A type byte that is valid in T1 is meaningless in T2. So `f2 0d` is a *T1* Quick Access query and `f2 00` is a *T2* wear query — the command byte `0xf2` means the same thing, the second byte does not.

Wear detection lives on **T2** and is polled with `f2 00` → `f3 00 <status>`, where status is per-ear (`00` both worn, `02` left not worn, `03` right not worn, `04` both not worn). The XM6 reports all-or-nothing in practice, so only `00` and `04` are observed.

### Stop-and-wait

The link is strictly stop-and-wait: send one frame, wait for its reply, acknowledge with the flipped sequence bit, *then* send the next. Firing a batch of queries without waiting loses everything past the first few replies.

`request()` therefore sends one query and blocks on its reply, retrying once on timeout. Replies are matched to requests by command byte alone, because every MDR command pair satisfies **reply = request + 1** (`0x12→0x13`, `0xE2→0xE3`, `0xF6→0xF7`, `0xF8→0xF9`). An unsolicited notification carries a *notify* command, never `request+1`, so it can never be mistaken for an answer.

T1 and T2 keep **separate sequence counters**, since they are separate channels.

### Verified vs unverified

Struct definitions in third-party headers are not evidence about a given model. Two field positions have been wrong on the XM6 and were caught by reading frames off the real device:

- The LDAC flag in the audio connection-mode reply reported *inactive* while the active-codec read simultaneously reported LDAC. LDAC is therefore derived from the codec.
- Quick Access is a pair of assignable *function* slots (`QUICK_ACCESS1` / `QUICK_ACCESS2`), not a left/right earcup pair.

When something disagrees with the headset, the frame log is the source of truth.

### File structure

```
app/src/main/java/com/fencewatcher/sonyanc/
├── BluetoothAncService.kt   # Foreground RFCOMM service — connection, handshake, read loop, state
├── SonyMdrV2.kt             # MDR v2 payloads: command/type bytes, frame builders, tables T1/T2
├── SonyAncProtocol.kt       # Frame encoder/decoder: SOF/EOF escaping, checksum, framing
├── HeadphoneProfile.kt      # The WH-1000XM6 wire profile — UUID, payloads, handshake
├── MainActivity.kt          # Tab UI, device picker, EQ, settings
├── DebugActivity.kt         # Frame log with timestamps, T1/T2, raw send
├── Automation.kt            # Rules, triggers, actions
├── MediaPlaybackMonitor.kt  # Media session playback detection
├── EQPreset.kt              # EQ preset ids
└── EQGraphView.kt           # Frequency response curve with draggable bands
```

### Credits

- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — original Sony protocol reverse engineering
- [Plutoberth/SonyHeadphonesClient](https://github.com/Plutoberth/SonyHeadphonesClient) — protocol documentation and per-device feature matrices
- [MaxKotelnikov/xm6-control](https://github.com/MaxKotelnikov/xm6-control) — macOS reference implementation
