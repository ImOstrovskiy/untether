# Pixel Hotspot Toggle

Instant-Hotspot-style button in the macOS menu bar for a Google Pixel.
The Mac asks the phone over Bluetooth LE to turn on its Wi-Fi hotspot
(via [Shizuku](https://shizuku.rikka.app/), no root), then joins it.

Status: work in progress.

| Path | What |
|------|------|
| `android/` | Kotlin app: foreground service, BLE GATT server, hotspot control via Shizuku |
| `macos/` | Go menu bar app: BLE central, Keychain, Wi-Fi join |
| `protocol/PROTOCOL.md` | BLE protocol: UUIDs, formats, crypto, state machine |
| `docs/DECISIONS.md` | Design decisions |

License: BSD-3-Clause, see `LICENSE` and `THIRD_PARTY_NOTICES.md`.
