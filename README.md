# Prft Light Ctrl (`app.prft.lightctrl`)

Cleanroom native Android Bluetooth Low Energy (BLE) studio lighting controller written in Kotlin and Jetpack Compose, engineered for Android 12–16+ (`API 31+`) phones, tablets, and foldables.

## Supported Hardware

- **Profoto B10X Pro / Plus / B10X** (`ID 143` / `144` — 500 Ws / 250 Ws flash + 3250 lm bi-color `3000–6500 K` continuous light)
- **Profoto A2** (`ID 36` — 100 Ws flash + LED modeling light)
- **Profoto Connect Pro** (`ID 161..166` — 100-channel Air 2 / AirX 6-group `A–F` studio remote, incl. vB5 `LE Coded PHY` Air Range mode)
- **Profoto Connect** (`ID 150..154` — buttonless AirTTL / Manual / Off remote, studio group standby, Air modeling toggle, and master TTL EV trim)

## Key Features (`v1.4.0`)

- **Monochrome Black & White Studio UI:** Matte charcoal surface hierarchy (`#0D0D0F`–`#222226`) with high-contrast `#FFFFFF` studio controls and zero OLED halation.
- **4-Tab Organized Studio Console (`Groups / Lights / LED / Devices`):** Non-scrolling 6-channel vertical group fader bank (`A–F`), group-filtered fixture cards, dedicated continuous/modeling `LED` console (direct fixtures + Air modeling remotes), persistent equipment nicknames with hardware model permanently visible, and unobtrusive `About` sheet.
- **Dual-UUID + Dual-PHY (`LE 1M` + `LE Coded PHY`) Native BLE Engine:** Simultaneous scanning of SIG `0xFDBB` (including `[0, productId]` Service Data mount identification and raw AD fallback), legacy `1d944db4-58a0-4177-84da-e29bf5c4630d` GATT services, OS-connected/bonded GATT hydration, `MTU 255` negotiation, `"Profoto Client"` session authentication, and out-of-band `TestFlash` priority dispatch.
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
