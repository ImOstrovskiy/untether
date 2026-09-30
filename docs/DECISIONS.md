# Decisions

## D1. macOS client is written in Go, not Swift

- Menu bar: `fyne.io/systray`; the binary is wrapped in a `.app` bundle with
  `LSUIElement=true` (no Dock icon).
- BLE central: `tinygo.org/x/bluetooth` (CoreBluetooth underneath). LE Secure
  Connections pairing is triggered by macOS on first access to an encrypted
  characteristic.
- CBOR: `github.com/fxamacker/cbor/v2`; HMAC: stdlib.
- Keychain: `github.com/keybase/go-keychain` (native API; `go-keyring` shells
  out to `security`, which leaks the item ACL to any process).
- Wi-Fi join / SSID / sleep notifications: small cgo + Objective-C shim over
  CoreWLAN, CoreLocation, NSWorkspace.
- Launch at login: LaunchAgent plist instead of `SMAppService`.
- Logs: `log/slog` to `~/Library/Logs/PixelHotspot/` instead of `os.Logger`.
- Bundle must carry `NSBluetoothAlwaysUsageDescription`, otherwise TCC kills
  the process on first CoreBluetooth call. Ad-hoc signed (`codesign -s -`).
- Builds with Command Line Tools only; full Xcode is not required.
