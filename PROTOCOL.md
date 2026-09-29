# Sony MDR-v2 protocol notes (WH-1000XM5 / XM6)

Reverse-engineered from the open implementations below, plus a live XM6 capture.
This is the reference the app's `SonyMdrV2.kt` is built from.

Sources:

- [mos9527/SonyHeadphonesClient](https://github.com/mos9527/SonyHeadphonesClient) — `libmdr`, the most complete
  implementation (C++). `libmdr/include/mdr/ProtocolV2T1.hpp` and `ProtocolV2T2.hpp` are the authoritative
  byte layouts.
- [ruimartins23/xm6-control](https://github.com/ruimartins23/xm6-control) — Swift, contains a **live WH-1000XM6
  capture** confirming the multipoint/peripheral traffic.
- [Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) — `SonyProtocolImplV2.java`; libmdr was in
  turn written from this.

## 1. Framing

```
SOF(0x3E) | TYPE SEQ LEN(4, big-endian) PAYLOAD(N) CHECKSUM(1) | EOF(0x3C)
```

`TYPE` is a *frame type*, and it selects which command table the payload is interpreted
against. The body and the checksum byte are escaped: any literal `0x3E`, `0x3C` or `0x3D`
inside them is replaced by `0x3D` followed by the byte with bit 4 cleared
(`0x2E`, `0x2C`, `0x2D`).

`CHECKSUM` = sum of all body bytes, mod 256.

### Frame types

| Frame type | libmdr name | Meaning |
|---|---|---|
| `0x0C` | `DATA_MDR` (table 1) | Main feature commands |
| `0x0E` | `DATA_MDR_NO2` (table 2) | Peripheral / extended commands |

ACK frames use frame type `0x01` with an empty payload.

### Sequencing

The sequence number is a **single bit shared across both tables** — not per-table. Every
non-ACK frame received from the headphones must be answered with an ACK carrying the
flipped sequence bit, or the device drops the channel. The sender toggles its own bit
after each write.

This is why a table mismatch is *silent*: the frame is well-formed, gets ACKed, and is
then simply ignored because the command byte means something else in that table.

## 2. The two command tables (the key insight)

The same command byte means **different things** in each table. Sending a table-2
command over a table-1 frame does nothing on real hardware.

| Command byte | Table 1 (0x0C) | Table 2 (0x0E) |
|---|---|---|
| `0x22`–`0x29` | Power (battery, power-off, auto-power-off) | Power (same) |
| `0x30`–`0x35` | UPDT capability/status | **PERI** capability/status |
| `0x36`–`0x39` | UPDT param | **PERI** param (multipoint) |
| `0x3C`/`0x3D` | *not defined* | **PERI** extended param (source switch) |
| `0x40`–`0x48` | LEA (LE Audio) | **Voice guidance** |
| `0x50`–`0x5B` | **EQ** (EQEBB) | Safe listening |
| `0x60`–`0x69` | **NCASM** (ANC/ambient) | LEA |
| `0x70`–`0x79` | SENSE | **Party mode** |
| `0x86`–`0x89` | OPT | — |
| `0x98`/`0x99` | Alert (incl. `ALERT_SET_PARAM`) | — |
| `0xA0`–`0xA9` | Play | — |
| `0xD6`–`0xD9` | **General setting** (touch panel) | — |
| `0xE6`–`0xE9` | **Audio params** (DSEE, BGM, upmix) | — |
| `0xF6`–`0xF9` | **System params** (speak-to-chat, pause-when-taken-off) | System (same) |
| `0xFA`–`0xFD` | **System ext params** (speak-to-chat config) | System ext (same) |

## 3. Multipoint (peripheral family, table 2 only)

### Device list

Request — `PERI_GET_PARAM`:

```
36 02        # get param, type 0x02 = pairing device mgmt + class-of-device
```

Reply — `PERI_RET_PARAM` / `PERI_NTFY_PARAM`:

```
37 02 <count> <entries…> <playbackStatus>
```

Each entry:

| Field | Size | Notes |
|---|---|---|
| BD address | 17 B | ASCII, uppercase-with-colons, e.g. `AA:BB:CC:DD:EE:FF` |
| connectedStatus | 1 B | non-zero = connected |
| class of device | 3 B | **only for subtype 0x02**; absent for subtype 0x00 |
| name length | 1 B | |
| friendly name | n B | UTF-8 |

The trailing `playbackStatus` byte is the key: an entry is the **active playback
source** when its `connectedStatus` equals that byte.

Subtype `0x00` (classic BT pairing management) is the same layout minus the 3-byte
class-of-device.

### Source switch

Request — `PERI_SET_EXT_PARAM` (`0x3C`):

```
3C 01 <17-byte BD address ASCII>
```

Reply — `PERI_NTFY_EXT_PARAM` (`0x3D`):

```
3D 01 <result> <17-byte target BD address>
```

`result` `0x00` = success.

### Playback lock ("Fix Playback")

Request — `PERI_SET_PARAM` (`0x38`):

```
38 01 <value>
```

Reply — `PERI_NTFY_PARAM` (`0x39`), then `PERI_GET_PARAM`/`PERI_RET_PARAM`:

```
39 01 <value> <result>
36 01
37 01 <value>
```

**The value is inverted, and getting it wrong is silent.** `value` is
*source switch control **enabled***, which is the negation of
*playback fixed* — Sony's own app calls the locked state "Fixing playback
device", the reference client notes it is "the negation of source switch
control". So:

| `value` | meaning |
|---|---|
| `0x00` | **locked** to this device |
| `0x01` | unlocked |

Sending `0x01` to lock does not fail. The headset accepts it, replies
`result=0x00` (success), and locks *nothing* — so a naive "did it return OK"
check passes while the feature is inverted. The readback has to be inverted
with it, or the UI and the device agree on the wrong answer.

`result` arrives on the `0x39` notify at offset 3 — **not** on `0x3D`, which
in practice is not sent for this command. `0x00` success, `1` failed,
`2` call in progress, `3` no audio connection, `4` voice assistant has
priority. `3` is the one to expect if you toggle with nothing playing.

### Music hand-over

`36 03` queries / `39 03 …` reports whether music hand-over is enabled.

### Why the first attempt failed

The earlier implementation sent the right payload (`36 02`) on the right frame type,
but had **no capability probe and no diagnostics**, so a silent no-op was
indistinguishable from a broken button. It also had no `PERI_GET_CAPABILITY` (`30 02`)
probe to confirm the peripheral family exists at all, and no hex logging to see whether
the device replied. Both are now in place, so the logcat transcript immediately shows
whether the XM6 answers on table 2.

## 4. Feature bytes actually verified for XM6

### Power off — `24 03 01`
`POWER_SET_STATUS` / `POWER_OFF` / `USER_POWER_OFF`. Immediately shuts down.

### Auto power off — `26 05` (get) / `28 05 <mode> 00` (set)
Subtype `0x05` = auto power-off with wearing detection. Values:

| Value | Meaning |
|---|---|
| `0x00` / `0x04` / `0x01` / `0x02` / `0x03` | 5 / 15 / 30 / 60 / 180 min |
| `0x10` | when taken off |
| `0x11` | never (disabled) |

**XM6 firmware only accepts `0x10` and `0x11`.** Writes of the timed values are answered
with a NTFY re-announcing the *old* value. The app therefore only offers the two
wearing-detection options.

### Speak-to-chat — `F6 0C` (get) / `F8 0C <v> 01` (set)
Enable byte is **inverted**: `0x00` = enabled, `0x01` = disabled.
Config (sensitivity, timeout) rides the ext family: `FA 0C` / `FC 0C <sens> <timeout>`.

### Pause when taken off — `F6 01` (get) / `F8 01 <v>` (set)
GET and SET ride *different* families, asymmetric in the reference source; the value is
inverted the same way.

### DSEE Extreme (upscaling) — `E6 01` / `E8 01 <v>`
Audio param `0x01`, inverted enable byte.

### BGM / upmix — `E8 09 <v> <roomSize>` / `E8 04 <v>`
Inverted enable. `0x09` carries a room-size byte; the two are mutually exclusive.

### ANC / ambient (NCASM) — `68 15 …`
Subtype `0x15` is correct for the XM6: its coordinator does not declare ANC-2/wind
hardware, so `0x17` must not be used.

### EQ — `56 …` / `58 …`
The XM6 only answers subtype `0x04` (`PRESET_EQ_AND_ERRORCODE`); the classic `0x00`
query is ignored. Ten bands, values stored with a `+6` offset, range −6…+6.
Verified write: `58 04 a0 0a 09 08 07 06 05 04 03 06 06 06` echoes back byte-for-byte.

## 5. Useful gotchas

- **Table selection is not cosmetic.** `0x66` is "read ambient sound" on table 1 and
  "read LE Audio" on table 2. Getting it wrong looks exactly like an unsupported feature.
- **Several features share one command family**, disambiguated only by a subtype byte
  at `payload[1]` — e.g. `F6`/`F8` carries pause-when-taken-off (`0x01`), button modes
  (`0x03`), voice assistant (`0x04`), auto volume (`0x0A`), speak-to-chat (`0x0C`),
  quick access (`0x0D`).
- **Some enable bytes are inverted** (speak-to-chat, pause-when-taken-off, DSEE, BGM,
  upmix). Sending `0x01` to "enable" actually disables.
- **A device that answers nothing is the normal failure mode** for a wrong table or an
  unsupported subtype — it does not error. Hence the hex logging in the app.
- Multipoint device lists are only populated once multipoint is enabled in the Sony app
  and a second device is actually connected.
