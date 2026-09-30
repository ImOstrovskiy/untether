# Decisions

## D0. Name: Untether

The project started as "Pixel Hotspot Toggle". Nothing in it is Pixel-specific
beyond what it was tested on, so it is called **Untether**; identifiers are
`io.github.imostrovskiy.untether`. The name was checked for collisions with
existing hotspot/tethering products.

## D1. macOS client is written in Go, not Swift

- Menu bar: an `NSStatusItem` with an `NSPopover` whose content is a
  `WKWebView` showing `macos/ui/index.html` (embedded with `go:embed`); JS talks
  to Go through a script message handler, Go pushes state with
  `evaluateJavaScript`. This replaced a plain `fyne.io/systray` menu when the UI
  grew tabs, meters and live charts. The bundle has `LSUIElement=true`.
- BLE central: `tinygo.org/x/bluetooth` (CoreBluetooth underneath). LE Secure
  Connections pairing is triggered by macOS on first access to an encrypted
  characteristic.
- CBOR: `github.com/fxamacker/cbor/v2`; HMAC: stdlib.
- Keychain: `github.com/keybase/go-keychain` (native API; `go-keyring` shells
  out to `security`, which leaks the item ACL to any process). Builds are
  signed with a local self-signed identity (`scripts/make-signing-identity.sh`)
  so the Keychain ACL survives rebuilds.
- Wi-Fi join / SSID / sleep notifications: small cgo + Objective-C shim over
  CoreWLAN, CoreLocation, NSWorkspace.
- Launch at login: LaunchAgent plist instead of `SMAppService`.
- Logs: `log/slog` to `~/Library/Logs/PixelHotspot/` instead of `os.Logger`.
- Bundle must carry `NSBluetoothAlwaysUsageDescription`, otherwise TCC kills
  the process on first CoreBluetooth call. Ad-hoc signed (`codesign -s -`).
- Builds with Command Line Tools only; full Xcode is not required.

## D2. Hotspot control: variant A, but thinner than delta

delta (supershadoe/delta) was studied at commit `7709261`. It has no external
API that returns status: the Tasker integration is an exported, unauthenticated
broadcast receiver (`START_SOFT_AP` / `STOP_SOFT_AP`, off by default behind an
"insecure receiver" flag) with no result, so variant B is out. We do our own controller.

delta talks to hidden AIDL interfaces (`ITetheringConnector`, `IWifiManager`)
through `compileOnly` AOSP stubs, wrapping the system binders in
`ShizukuBinderWrapper` and passing `callerPkg = "com.android.shell"`.

Since API 36 the typed `TetheringManager` API is public
(`startTethering(TetheringRequest, …)`, `TetheringRequest.Builder
#setSoftApConfiguration`, `SoftApConfiguration.Builder`,
`TetheringEventCallback#onTetheredInterfacesChanged`). So instead of stubs:

- build a `TetheringManager` with its hidden constructor
  `(Context, Supplier<IBinder>)`, where the supplier returns the Shizuku-wrapped
  `tethering` binder and the context reports `getOpPackageName() =
  "com.android.shell"`, `getAttributionTag() = null`. All binder calls then run
  as uid 2000, which holds `TETHER_PRIVILEGED` and `NETWORK_SETTINGS`
  (checked in AOSP `TetheringService#hasTetherChangePermission`).
- start: public `startTethering` with a request that carries our
  `SoftApConfiguration` (SSID + passphrase). The system config is not touched;
  the SSID/passphrase apply to this tethering session only.
- stop: hidden `stopTethering(int)` via reflection. Unlike
  `stopTethering(TetheringRequest)` it also stops a hotspot that was started
  from quick settings.
- clients: hidden `TetheringEventCallback#onClientsChanged` received through a
  `java.lang.reflect.Proxy` of the public interface; `TetheredClient` read
  by reflection.
- softAP state: `WIFI_AP_STATE_CHANGED` broadcast (only needs
  `ACCESS_WIFI_STATE`, see AOSP `SoftApManager#updateApState`) gives
  enabling / enabled / disabling / disabled / failed.
- hidden API access: `org.lsposed.hiddenapibypass`.

Borrowed from delta: the Shizuku binder-wrapping approach, the
`com.android.shell` caller package and the Shizuku state handling
(binder received/dead, permission listener). See `THIRD_PARTY_NOTICES.md`.

Fallback `cmd wifi start-softap` is implemented as requested, but AOSP's own
help text says it "doesn't activate internet tethering": it brings up an AP
without upstream. It only runs if the TetheringManager path throws.

## D3. Pairing over BLE instead of QR

The Go app cannot scan a QR code without camera code, and typing an
80-character string is not acceptable. The phone opens a 60 s pairing window
and the Mac reads the pairing record over an encrypted (bonded) link. User
presence is required on both sides (button + bonding confirmation). The
service UUID is fixed. The Mac does not pin a peripheral identifier: it
connects to whichever phone advertises the service (add pinning if two phones
running the app ever meet).
See `protocol/PROTOCOL.md`.

## D4. Small things

- No `HotspotController` interface: there is one implementation,
  `ShizukuHotspotController`, with the API from the task
  (`start()`, `stop()`, `state`, `clients`).
- CBOR on Android is a ~60-line encoder for the few types we send; the Mac
  decodes with `fxamacker/cbor`. A golden-bytes test guards both sides.
- `state` notifications carry the full value when it fits the MTU, otherwise
  an empty value that tells the Mac to read.
- Characteristics use `PERMISSION_*_ENCRYPTED`, not `_MITM`: commands are
  authenticated by HMAC anyway, and macOS may fall back to Just Works.

## D5. Android UI: Jetpack Compose, Material 3 only

Dynamic color (Material You), Material Symbols as vector drawables, a large
collapsing top app bar, cards of list items, segmented buttons for the data
SIM, simple dialogs with radio options for settings, an adaptive launcher icon
with a monochrome layer. Strings in English and Ukrainian; the app
language can be chosen per app in Android settings.

## D6. Faster Mac join: stable BSSID, fixed channel, cached network

Since Android 13 the default `SoftApConfiguration` randomizes the BSSID every
session, and automatic channel selection moves the AP between channels (seen:
36 then 40). Both make macOS treat each session as a new network and scan for
it. The phone now writes MAC randomization "persistent" and 5 GHz channel 36
into the system hotspot config; the Mac keeps the `CWNetwork` from the last
join on disk and associates to it directly, falling back to a scan once if it
fails. Measured: 4–5 s from click to internet instead of 9–30 s.

## D7. Watchdogs

The phone re-sends `state` every minute and, on the same tick, reopens the GATT
server or restarts advertising if either is gone. The Mac drops a link that has
been silent for 150 s, disconnects on any GATT timeout (the phone app restarted
under a live link), and resets its Bluetooth adapter after five short-lived
sessions in a row.
