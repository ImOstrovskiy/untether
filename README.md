# Untether

Instant-Hotspot-style button in the macOS menu bar for an Android phone.
Click it and the Mac asks the phone over Bluetooth LE to turn on its Wi-Fi
hotspot, waits for it to come up, and joins it, in about 4–5 seconds. The
popover shows the phone's battery and temperature, mobile network and signal,
the devices on the hotspot, and lets you pick the data SIM, reconnect mobile
data or ring the phone. The phone can ring the Mac back.

No root: the phone app controls tethering through [Shizuku](https://shizuku.rikka.app/).
Developed and tested on a Pixel 9 with Android 17.

| Path | What |
|------|------|
| `android/` | Kotlin + Jetpack Compose (Material 3): foreground service, BLE GATT server, hotspot control via Shizuku |
| `macos/` | Go menu bar app: BLE central, Keychain, Wi-Fi join; popover UI in `macos/ui/` |
| `protocol/PROTOCOL.md` | BLE protocol: UUIDs, formats, crypto, state machine |
| `protocol/testdata/` | Golden vectors shared by the Android and Go tests |
| `docs/DECISIONS.md` | Design decisions |

## Requirements

- Android 17 (API 37) or newer with Shizuku installed and started
  (wireless debugging or adb).
- Mac with macOS 14+ (Apple Silicon), Command Line Tools, Go 1.26.
- Android SDK platform 37.2 and a JDK for building the phone app.

## Build and install

Phone:

```bash
cd android && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Mac (run the script once, so rebuilt apps keep their Keychain access):

```bash
macos/scripts/make-signing-identity.sh
```

```bash
cd macos && make install && open /Applications/Untether.app
```

## Setup

1. On the phone open **Untether**, grant the permissions, allow it in Shizuku
   and allow it to ignore battery optimization. The service starts and stays in
   the notification shade.
2. On the Mac allow Bluetooth and Location when asked (Location is only used to
   read the current Wi-Fi name).
3. Pair once: on the phone tap **Pair** in the Mac section, then in the Mac
   popover **Settings → Pair with phone…**. Confirm the Bluetooth code on both
   devices.

The app writes its own SSID and password into the phone's hotspot settings, so
quick settings on the phone start the same network the Mac knows. The hotspot
uses a stable BSSID on 5 GHz channel 36, which lets the Mac rejoin without a
Wi-Fi scan.

## Security

- Every characteristic needs an encrypted, bonded BLE link (LE Secure
  Connections with numeric comparison).
- Commands carry `HMAC-SHA256(secret, op ‖ nonce ‖ arg)` over a one-time nonce
  from the phone: without the 32-byte secret nothing can be switched, and a
  recorded command cannot be replayed.
- The secret and hotspot password are generated on the phone, kept encrypted
  with an Android Keystore key, handed to the Mac only inside a 60 s pairing
  window the user opens, and stored in the macOS Keychain.

## Checking it

```bash
cd android && ./gradlew :app:testDebugUnitTest   # protocol vectors, HMAC, CBOR
cd macos && make test                             # same vectors from Go
adb logcat -s Untether                            # phone side
tail -f ~/Library/Logs/Untether/untether.log     # Mac side
```

Doze: `adb shell dumpsys deviceidle force-idle`, then toggle from the Mac;
`adb shell dumpsys deviceidle unforce` to leave.

## Limitations

- Auto-off on sleep / leaving the network needs Location permission on the Mac.
- The Mac connects to the first phone that advertises the service.
- Switching the data SIM needs two active SIMs.

## License

BSD-3-Clause, see `LICENSE`. Third-party notices: `THIRD_PARTY_NOTICES.md`.
