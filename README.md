# Xiaomi RC Bridge

**Make Xiaomi BLE remotes (Mi TV Stick / Mi Box remote) work on Android TV boxes where they pair but no button does anything — no root required.**

On some Android TV boxes (e.g. the Skyworth **TVPLUS BOX**, Android 14) the Xiaomi Bluetooth remote pairs, shows as connected, and then… nothing. This app finds the remote, pairs it, and forwards its key presses to the system: D-pad, OK, Home, Back, Menu, Volume ±, Power, plus the **Netflix** and **Prime Video** buttons.

---

## Contents

- [Root cause](#root-cause)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
- [Pairing the remote](#pairing-the-remote)
- [Key mapping](#key-mapping)
- [Custom key mapping](#custom-key-mapping)
- [Start on boot](#start-on-boot)
- [Power-button long press → power menu](#power-button-long-press--power-menu)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [Building from source](#building-from-source)
- [Technical notes](#technical-notes)

---

## Root cause

Verified with an HCI snoop log:

- The remote (Realtek chip, VID `0x2717` / PID `0x32b9`) exposes **28 HID Report characteristics**.
- Android's BLE HID host (HOGP) tracks at most **20 reports per device** (`BTA_HH_LE_RPT_MAX`).
- The **Consumer Control input report (Report ID 1, handle `0x86`)** — the one that carries *every* button — falls past that limit and is **never subscribed**. The remote sends keys; the TV never listens.

Pairing from the TV's Settings menu often fails too, so the app ships its own pairing tool.

## How it works

```
 ┌──────────────┐   local adb (127.0.0.1:5555)    ┌──────────────────────────────┐
 │ Xiaomi RC    │ ──────────────────────────────▶ │ Daemon (app_process, shell)  │
 │ Bridge (app) │   starts supervisor script      │  • own GATT connection       │
 └──────────────┘                                 │  • subscribe to Report ID 1  │
        ▲ at boot (LOCKED_BOOT_COMPLETED)         │  • bit → inject KeyEvent     │
        │                                         └──────────────┬───────────────┘
   BootReceiver                                                  │ BLE notify
                                                         ┌───────┴───────┐
                                                         │  Xiaomi RC    │
                                                         └───────────────┘
```

1. The app connects to the TV's **own adb daemon** (network debugging, `127.0.0.1:5555`) using a small built-in adb client.
2. Through it, it launches the `Daemon` class straight out of its APK with `app_process` as the **shell user**. Shell has `BLUETOOTH_PRIVILEGED` and `INJECT_EVENTS` — no root needed.
3. The daemon opens its own GATT connection to the remote, subscribes to the Consumer Control report that the system skipped, and translates the 24-bit key bitmap into Android key events (with auto-repeat).
4. A supervisor script (`/data/local/tmp/xrcd.sh`) restarts the daemon within 3 s if it ever dies.

## Requirements

- Android TV / Google TV, **Android 12+** (API 31+). Tested on a TVPLUS BOX running Android 14.
- **Developer options** enabled with **network debugging (ADB over network, port 5555)** on.
  Many boxes have this on by default. The app relies on the TV's own adb, so **do not turn developer options off** — the bridge cannot start without it.
- A Xiaomi BLE remote (tested with the 1st-gen Mi TV Stick remote; any remote whose name contains "Xiaomi", "Mi RC" or "Mi Remote" is recognized).

## Installation

1. Download the latest `xiaomi-rc-bridge-vX.Y.apk` from [Releases](../../releases).
2. Install it on the TV — from a computer:
   ```sh
   adb connect <TV-IP>:5555
   adb install -r xiaomi-rc-bridge-v0.4.apk
   ```
   or via a USB stick / "Send Files to TV".
3. Open **Xiaomi RC Bridge** on the TV.
4. On first launch the TV shows **"Allow USB debugging?"** → tick **"Always allow from this computer"** and press **Allow**, then **Refresh**.
5. The status line should turn green: **"● Bridge running"**.

> ⚠️ If you "Force stop" the app, Android will not deliver boot broadcasts to it. Just open the app once to fix that.

## Pairing the remote

No separate "Bluetooth Scanner" app needed:

1. In the app, press **Pair remote**.
2. Hold the remote close to the TV and press and hold **Home + Menu (☰)** together; release when the light starts blinking.
   (If that doesn't work, try **Home + Back**.)
3. The app BLE-scans for up to 60 s. When it finds the remote it removes any stale bond, bonds over LE, and restarts the bridge.
4. Once you see **"✓ Remote paired"**, the buttons work.

Log: `/data/local/tmp/xrc-pair.log`

To run it manually from a computer:

```sh
adb shell 'CLASSPATH=$(pm path com.kaan.xrc | cut -d: -f2) app_process / com.kaan.xrc.Pairer 60'
# for a specific MAC address:
adb shell 'CLASSPATH=$(pm path com.kaan.xrc | cut -d: -f2) app_process / com.kaan.xrc.Pairer 60 AA:BB:CC:DD:EE:FF'
```

## Key mapping

Report ID 1 is a 24-bit bitmap. Default mapping:

| Bit | Remote button | Android action |
|----:|---------------|----------------|
| 0 | OK | `DPAD_CENTER` |
| 1–4 | Up / Down / Left / Right | `DPAD_UP/DOWN/LEFT/RIGHT` (repeats) |
| 5 | Power | `POWER` (long press → power menu) |
| 6 | Microphone | `SEARCH` (opens search) |
| 7 / 8 | Volume + / − | `VOLUME_UP` / `VOLUME_DOWN` (repeats) |
| 9 | Menu (☰) | `MENU` |
| 10 | Netflix | launches `com.netflix.ninja` |
| 13 | Prime Video | launches `com.amazon.amazonvideo.livingroom` |
| 17 | Voice | `SEARCH` |
| 18 | Home | `HOME` |
| 19 | Back | `BACK` |

D-pad and volume keys repeat after 400 ms, then every 80 ms while held.

## Custom key mapping

Create `/data/local/tmp/xrc-keymap.txt` (one `bit=KEYCODE` or `bit=app:package.name` per line):

```text
# Menu button opens Settings
9=SETTINGS
# Netflix button opens YouTube
10=app:com.google.android.youtube.tv
# Prime button becomes play/pause
13=MEDIA_PLAY_PAUSE
```

```sh
adb push xrc-keymap.txt /data/local/tmp/
```

Then press **Start / Restart** in the app. Key names are Android `KeyEvent.KEYCODE_*` names (the `KEYCODE_` prefix is optional).

## Start on boot

- The app is **direct-boot aware**: as soon as `LOCKED_BOOT_COMPLETED` arrives (~25 s after power-on) it starts the daemon if adb is up, retries every 1.5 s otherwise, and falls back to JobScheduler. `BOOT_COMPLETED` and app updates trigger it as well (the start command is idempotent).
- The adb key lives in device-protected storage so it is usable before unlock.
- The consumer report handle is cached in `/data/local/tmp/xrc-handle`; keys are live ~10 ms after the remote connects.

## Power-button long press → power menu

On some boxes a long press on Power shuts the device down immediately. To get the restart / power-off menu instead:

```sh
adb shell settings put global power_button_long_press 1
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| "Cannot connect to adb (127.0.0.1:5555)" | Enable network debugging in Developer options. Some devices need `adb tcpip 5555` from a computer once. |
| The "Allow USB debugging?" dialog never appears | Use "Revoke USB debugging authorizations" in Developer options, then reopen the app. |
| Bridge running but buttons do nothing | Re-pair with **Pair remote**; check the remote's batteries. |
| Doesn't start at boot | Open the app once (after a Force stop Android withholds boot broadcasts). |

Logs:

```sh
adb shell tail -50 /data/local/tmp/xrcd.log      # daemon
adb shell cat /data/local/tmp/xrc-pair.log       # pairing
adb logcat -s XRC                                # boot receiver
```

Uninstall: just uninstall the app — the supervisor exits once the APK is gone. Optionally clean up with `adb shell rm /data/local/tmp/xrc*`.

## Known limitations

- **The microphone does not work yet.** On a mic press the remote requests a voice session via Report ID 5, but the Xiaomi/Realtek voice-start command is undocumented. Even with audio decoded (likely Report IDs 6/7/8, 120 bytes), the shell user cannot create a virtual microphone, so it would have to be forwarded via the Google TV Remote protocol. For now the mic button opens search.
- Shizuku would not add anything: the daemon already runs with shell privileges.
- One remote at a time.

## Building from source

No Gradle — just the Android SDK (build-tools 36, platform 35) and a JDK:

```sh
./build.sh          # → build/xrc.apk
```

`build.sh` runs `aapt2 link`, `javac`, `d8`, `zipalign` and `apksigner`. It creates a `debug.keystore` if none exists (not included in the repo). An APK signed with your own key cannot be installed over the release APK — uninstall first.

### Source files

| File | Role |
|---|---|
| `Daemon.java` | Bridge running as shell: GATT connection, report subscription, key injection, key map |
| `Pairer.java` | BLE scan + pairing tool |
| `AdbClient.java` | Minimal adb protocol client (CNXN/AUTH/OPEN, RSA key) |
| `Bridge.java` | Supervisor script and start/stop/status/pair commands |
| `BootReceiver.java`, `StartJob.java` | Start on boot |
| `MainActivity.java` | TV UI |
| `VoiceProbe.java` | Diagnostic: logs all HID/vendor notifications (microphone research) |

## Technical notes

- Bluetooth inside `app_process` is set up via reflection: `BluetoothFrameworkInitializer.setBluetoothServiceManager(...)` + `BluetoothAdapter.createAdapter(AttributionSource "com.android.shell")`.
- Key injection: `InputManagerGlobal.getInstance().injectInputEvent(event, 0)`.
- Remote GATT: HID service (`0x1812`), Xiaomi vendor service `d1ff…3c17` (a001 notify / a002 write), and the Realtek OTA service `d0ff…` (**never written to**).
- The report map declares IDs 1 (consumer + key bitmap), 2 (keyboard + LED), 6/7/8 (vendor `0xff00`, 120 bytes); there are also private reports outside the map: IDs 3, 4, 5, 9, 10 and features `0xe0–0xee`.
- Single-instance lock: abstract `LocalServerSocket("xiaomi-rc-bridge")`.

## License

MIT
