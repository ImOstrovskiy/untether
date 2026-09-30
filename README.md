# Pixel Hotspot Toggle

Instant-Hotspot-style button in the macOS menu bar for a Google Pixel.
Click it and the Mac asks the phone over Bluetooth LE to turn on its Wi-Fi
hotspot, waits for it to come up, and joins it. The menu shows the phone's
hotspot state, battery, cellular network, signal and hotspot clients.

No root: the phone app controls tethering through [Shizuku](https://shizuku.rikka.app/).

| Path | What |
|------|------|
| `android/` | Kotlin app: foreground service, BLE GATT server, hotspot control via Shizuku |
| `macos/` | Go menu bar app: BLE central, Keychain, Wi-Fi join |
| `protocol/PROTOCOL.md` | BLE protocol v1: UUIDs, formats, crypto, state machine |
| `protocol/testdata/` | Golden vectors shared by the Android and Go tests |
| `docs/DECISIONS.md` | Design decisions |

## Requirements

- Pixel on Android 17 (API 37) or newer, with Shizuku installed and started
  (wireless debugging or adb).
- Mac with macOS 14+ (Apple Silicon), Command Line Tools, Go 1.26.
- Android SDK platform 37.2 and a JDK for building the phone app.

## Build and install

Phone:

```bash
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Mac:

```bash
cd macos && make install
open /Applications/PixelHotspot.app
```

## Setup

1. On the phone open **Pixel Hotspot**, grant the Bluetooth/notification/phone
   permissions, tap **Grant Shizuku permission** and **Disable battery
   optimization**. The service starts and stays in the notification shade.
2. On the Mac allow Bluetooth and Location when asked (Location is only used to
   read the current Wi-Fi name).
3. Pair once: tap **Pair Mac (60 s)** on the phone, then **Settings → Pair with
   Phone…** in the Mac menu. Confirm the Bluetooth pairing code on both
   devices.

From then on the Mac stays connected while the phone is in range. **Turn
Hotspot On** starts the hotspot with the app's own SSID and password (shown in
the phone app) and joins it; **Turn Hotspot Off** stops it.

## Security

- Every characteristic needs an encrypted, bonded BLE link.
- Commands carry `HMAC-SHA256(secret, op ‖ nonce)` over a one-time nonce from
  the phone: without the 32-byte secret nothing can be switched, and a
  recorded command cannot be replayed.
- The secret and hotspot password are generated on the phone, kept encrypted
  with an Android Keystore key, handed to the Mac only inside a 60 s pairing
  window the user opens, and stored in the macOS Keychain.

## Checking it

```bash
cd android && ./gradlew :app:testDebugUnitTest   # protocol vectors, HMAC, CBOR
cd macos && make test                             # same vectors from Go
adb logcat -s PixelHotspot                        # phone side
tail -f ~/Library/Logs/PixelHotspot/pixel-hotspot.log
```

Doze: `adb shell dumpsys deviceidle force-idle`, then toggle from the Mac;
`adb shell dumpsys deviceidle unforce` to leave.

## Limitations

- The hotspot SSID/password apply to hotspots started from the Mac. A hotspot
  started from the phone's quick settings uses the system's own settings, which
  the Mac does not know.
- Auto-off on sleep / leaving the network needs Location permission on the Mac.
- The Mac connects to the first phone that advertises the service.

## License

BSD-3-Clause, see `LICENSE`. Third-party notices: `THIRD_PARTY_NOTICES.md`.
