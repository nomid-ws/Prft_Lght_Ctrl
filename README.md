# Prft Light Ctrl (`app.prft.lightctrl`)

Cleanroom native Android Bluetooth Low Energy (BLE) studio lighting controller written in Kotlin and Jetpack Compose, engineered for Android 12–16+ (`API 31+`) phones, tablets, and foldables.

## Supported Hardware

- **Profoto B10X Pro / Plus / B10X** (`ID 143` / `144` — 500 Ws / 250 Ws flash + 3250 lm bi-color `3000–6500 K` continuous light)
- **Profoto A2** (`ID 36` — 100 Ws flash + LED modeling light)
- **Profoto Connect Pro** (`ID 161..166` — 100-channel Air 2 / AirX 6-group `A–F` studio remote, incl. vB5 `LE Coded PHY` Air Range mode)
- **Profoto Connect** (`ID 150..154` — buttonless AirTTL / Manual / Off remote, studio group standby, Air modeling toggle, and master TTL EV trim)

## Key Features (`v1.5.1`)

- **Studio Charcoal UI & Adaptive Portrait + Landscape Console:** Matte charcoal surface hierarchy (`#0D0D0F`–`#222226`) with Portrait `ThumbZoneBottomDock` and Landscape/Tablet `NavigationRail` + single-screen 6-fader console.
- **4-Tab Studio Workflow (`Groups / Lights / LED / Devices`):** Non-scrolling 6-channel group fader bank (`A–F`, full `0.1–10.0` stop precision floor), Named Group Roles (`Key`, `Fill`, `Rim`, etc.), Dual Power Readout (`Ws` & fractional `1/1..1/512` stops alongside `1–10` scale), Saved Studio Scenes with JSON clipboard import/export, and `FIRE` CTA + foreground hardware Volume Key trigger.
- **Full Remote & Monolight Parity:** `Connect Pro` software `Auto (TTL)` / `Manual` toggle (`op=144`), Per-Group TTL EV ratio biases (`Groups A–C`, `op=148`), Remote Settings sheet (Sync Mode `1st/2nd/X-Sync/HSS/Global Shutter`, AF-Assist, RF Power `P1–P4`, Auto-Off Sleep, Display Brightness, Battery Chemistry, Control Sounds, fast `1–100` Channel Picker), Monolight Zoom (`op=161..163`) & Ready Signal (`op=217`), and Pinned Kit auto-reconnect.
- **Samsung One UI + Pixel Hardened Native BLE Engine:** Hardware-filtered `1M` scanning across SIG `0xFDBB` Service Data/UUID, `AUTH_SERVICE_UUID` (`bbd75314-...`), and legacy `1d944db4-...` (`neverForLocation`-compliant on One UI 7+), async `onScanFailed` self-healing fallback, OS-connected/bonded GATT hydration, `MTU 255` negotiation, `"Profoto Client"` session authentication, and out-of-band `FIRE` priority dispatch.
- **Hard Bootloader / DFU Quarantine:** Compile-time and runtime blocklists prevent any read/write access to firmware-update GATT UUIDs (`f7bf3564-...`) or DFU opcodes (`46`, `142`, `154`).
- **Zero Trackers / 100% Offline:** No `INTERNET` permission, no analytics, no proprietary SDKs, and no simulator code in release builds.

## Project Structure

- `:core:protocol` — Pure JVM PUP wire frame codec (`counter % 240` + `uint16_le` framing, `AllSettingsV2` TLV parser, hardware catalog).
- `:core:ble` — Android `BluetoothGatt` 10-stage connection state machine & foreground `connectedDevice` service.
- `:core:domain` — Multi-light `StudioOrchestrator`, shared AirX channel collision guard, and 6-group `A–F` fader state engine.
- `:app` — Responsive Jetpack Compose studio UI for portrait, landscape, tablet, and foldable postures.
- [`docs/PROFOTO_BLE_PROTOCOL_SPEC.md`](docs/PROFOTO_BLE_PROTOCOL_SPEC.md) — Cleanroom Profoto BLE GATT & PUP wire protocol specification.

## Build & Test

```bash
./gradlew :core:protocol:test :core:ble:testDebugUnitTest :core:domain:testDebugUnitTest
./gradlew :app:assembleRelease
```

## License & Disclaimer

Licensed under the **GNU General Public License v3.0 or later (`GPL-3.0-or-later`)** — see [`LICENSE`](LICENSE).

*Prft Light Ctrl is an independent, cleanroom interoperability project and is not affiliated with, endorsed by, or sponsored by Profoto AB. All product names are trademarks of their respective owners.*
