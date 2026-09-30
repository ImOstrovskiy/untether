// Pixel Hotspot: menu bar app that turns on a Pixel's Wi-Fi hotspot over BLE and joins it.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"fyne.io/systray"
	"tinygo.org/x/bluetooth"
)

const maxClientItems = 8

type config struct {
	AutoOff bool `json:"auto_off"` // turn the hotspot off when the Mac sleeps or leaves its Wi-Fi
}

type app struct {
	phone *Phone
	icons [5][]byte

	renderMu sync.Mutex // render is called from many goroutines

	mu        sync.Mutex
	cfg       config
	creds     *Pairing
	state     *State // nil while the phone is not connected
	busy      string // "Turning on…" while a command runs
	note      string // last problem, shown in the menu
	onHotspot bool   // the Mac has been seen on the hotspot's Wi-Fi since it was turned on

	mHotspot, mPhone, mClients, mShizuku, mNote, mToggle *systray.MenuItem
	mPair, mForget, mLogin, mAutoOff, mQuit            *systray.MenuItem
	mClientList                                        []*systray.MenuItem
}

func main() {
	setupLog()
	a := &app{cfg: loadConfig()}
	for i := range a.icons {
		a.icons[i] = hotspotIcon(i)
	}
	creds, err := loadPairing()
	if err != nil {
		slog.Warn("keychain", "err", err)
	}
	a.creds = creds
	a.phone = NewPhone(bluetooth.DefaultAdapter, a.getCreds, a.onPaired, a.onState)
	systray.Run(a.onReady, func() {})
}

func (a *app) onReady() {
	systray.SetTooltip("Pixel Hotspot")
	a.mHotspot = systray.AddMenuItem("", "")
	a.mHotspot.Disable()
	a.mPhone = systray.AddMenuItem("", "")
	a.mPhone.Disable()
	a.mClients = systray.AddMenuItem("", "")
	for range maxClientItems {
		item := a.mClients.AddSubMenuItem("", "")
		item.Disable()
		a.mClientList = append(a.mClientList, item)
	}
	a.mShizuku = systray.AddMenuItem("", "")
	a.mShizuku.Disable()
	a.mNote = systray.AddMenuItem("", "Click to dismiss")
	systray.AddSeparator()
	a.mToggle = systray.AddMenuItem("Turn Hotspot On", "")
	systray.AddSeparator()
	settings := systray.AddMenuItem("Settings", "")
	a.mPair = settings.AddSubMenuItem("Pair with Phone…", "Open Pixel Hotspot on the phone and tap Pair Mac first")
	a.mForget = settings.AddSubMenuItem("Forget Phone", "")
	a.mLogin = settings.AddSubMenuItemCheckbox("Launch at Login", "", launchAtLogin())
	a.mAutoOff = settings.AddSubMenuItemCheckbox("Turn Hotspot Off on Sleep or When Leaving It", "Needs Location permission", a.cfg.AutoOff)
	a.mQuit = systray.AddMenuItem("Quit", "")
	a.render()

	go a.clicks()
	go func() {
		for {
			err := bluetooth.DefaultAdapter.Enable()
			if err == nil {
				break
			}
			a.setNote("Bluetooth: " + err.Error())
			time.Sleep(5 * time.Second)
		}
		a.setNote("")
		a.phone.Run()
	}()
	requestLocation()
	observeSleep(a.onWillSleep)
	go a.watchWiFi()
}

func (a *app) clicks() {
	for {
		select {
		case <-a.mToggle.ClickedCh:
			go a.toggle()
		case <-a.mPair.ClickedCh:
			go a.pair()
		case <-a.mForget.ClickedCh:
			if err := forgetPairing(); err != nil {
				a.setNote("Keychain: " + err.Error())
			}
			a.mu.Lock()
			a.creds = nil
			a.mu.Unlock()
			a.render()
		case <-a.mLogin.ClickedCh:
			if err := setLaunchAtLogin(!launchAtLogin()); err != nil {
				a.setNote("Launch at login: " + err.Error())
			}
			a.render()
		case <-a.mAutoOff.ClickedCh:
			a.mu.Lock()
			a.cfg.AutoOff = !a.cfg.AutoOff
			err := a.cfg.save()
			a.mu.Unlock()
			if err != nil {
				a.setNote("Settings: " + err.Error())
			}
			a.render()
		case <-a.mNote.ClickedCh:
			a.setNote("")
		case <-a.mQuit.ClickedCh:
			systray.Quit()
		}
	}
}

// --- actions ---

func (a *app) toggle() {
	a.mu.Lock()
	st, creds, busy := a.state, a.creds, a.busy
	a.mu.Unlock()
	switch {
	case busy != "":
	case creds == nil:
		a.setNote("Not paired")
	case st == nil:
		a.setNote("Phone is not connected")
	case st.Shz != shzOK:
		a.setNote(shizukuText(st.Shz))
	case st.HS == hsOn || st.HS == hsStarting:
		a.turnOff(creds)
	default:
		a.turnOn(creds)
	}
}

func (a *app) turnOn(creds *Pairing) {
	a.setBusy("Turning On…")
	defer a.setBusy("")
	start := time.Now()
	if err := a.phone.Command(opOn, creds.Key, nil); err != nil {
		a.setNote(err.Error())
		return
	}
	if err := a.waitFor(hsOn, 15*time.Second); err != nil {
		a.setNote(err.Error())
		return
	}
	// The new AP needs a few seconds to show up in scans; macOS auto-join may also beat us to it.
	var err error
	for range 8 {
		if currentSSID() == creds.SSID {
			err = nil
			break
		}
		if _, err = joinWiFi(creds.SSID, creds.Pass); err == nil {
			break
		}
		time.Sleep(time.Second)
	}
	if err != nil {
		a.setNote("Wi-Fi: " + err.Error())
		return
	}
	slog.Info("hotspot on and joined", "took", time.Since(start).Round(100*time.Millisecond))
}

func (a *app) turnOff(creds *Pairing) {
	a.setBusy("Turning Off…")
	defer a.setBusy("")
	if err := a.phone.Command(opOff, creds.Key, nil); err != nil {
		a.setNote(err.Error())
		return
	}
	if currentSSID() == creds.SSID {
		leaveWiFi()
	}
	a.mu.Lock()
	a.onHotspot = false
	a.mu.Unlock()
}

// waitFor polls the phone state until it reaches hs, fails or times out.
func (a *app) waitFor(hs int, timeout time.Duration) error {
	for deadline := time.Now().Add(timeout); time.Now().Before(deadline); time.Sleep(200 * time.Millisecond) {
		a.mu.Lock()
		st := a.state
		a.mu.Unlock()
		switch {
		case st == nil:
			return errNotConnected
		case st.HS == hs:
			return nil
		case st.HS == hsError:
			code := -1
			if st.Err != nil {
				code = *st.Err
			}
			return fmt.Errorf("phone could not start the hotspot (code %d)", code)
		}
	}
	return fmt.Errorf("hotspot did not reach %q in %s", hotspotNames[hs], timeout)
}

func (a *app) pair() {
	a.setNote("On the phone: Pixel Hotspot → Pair Mac")
	if err := a.phone.Pair(); err != nil {
		a.setNote(err.Error())
		return
	}
	a.setNote("")
}

func (a *app) onPaired(p Pairing) {
	if err := savePairing(p); err != nil {
		a.setNote("Keychain: " + err.Error())
	}
	a.mu.Lock()
	a.creds = &p
	a.mu.Unlock()
	slog.Info("paired", "ssid", p.SSID)
	a.render()
}

func (a *app) onState(st *State) {
	a.mu.Lock()
	a.state = st
	a.mu.Unlock()
	a.render()
}

// onWillSleep runs on the sleep notification queue; sleep waits for it (up to 30 s).
func (a *app) onWillSleep() {
	a.mu.Lock()
	st, creds, use := a.state, a.creds, a.cfg.AutoOff && a.onHotspot
	a.mu.Unlock()
	if use && st != nil && creds != nil && st.HS == hsOn {
		slog.Info("sleep: turning hotspot off")
		if err := a.phone.Command(opOff, creds.Key, nil); err != nil {
			slog.Warn("sleep: off failed", "err", err)
		}
	}
}

// watchWiFi turns the hotspot off after the Mac leaves its network (option AutoOff).
// Without Location permission the SSID is unreadable and this does nothing.
func (a *app) watchWiFi() {
	misses := 0
	for range time.Tick(5 * time.Second) {
		a.mu.Lock()
		st, creds, auto := a.state, a.creds, a.cfg.AutoOff
		a.mu.Unlock()
		if creds == nil || st == nil || st.HS != hsOn {
			misses = 0
			a.setOnHotspot(false)
			continue
		}
		if currentSSID() == creds.SSID {
			misses = 0
			a.setOnHotspot(true)
			continue
		}
		a.mu.Lock()
		was := a.onHotspot
		a.mu.Unlock()
		if misses++; auto && was && misses >= 2 {
			slog.Info("Mac left the hotspot: turning it off")
			a.setOnHotspot(false)
			go a.turnOff(creds)
		}
	}
}

// --- UI ---

func (a *app) render() {
	a.renderMu.Lock()
	defer a.renderMu.Unlock()
	a.mu.Lock()
	st, creds, busy, note, autoOff := a.state, a.creds, a.busy, a.note, a.cfg.AutoOff
	a.mu.Unlock()
	if a.mHotspot == nil {
		return // menu not built yet
	}

	icon := iconDisconnected
	if st != nil {
		switch {
		case busy != "" || st.HS == hsStarting || st.HS == hsStopping:
			icon = iconBusy
		case st.HS == hsError || st.Shz != shzOK:
			icon = iconError
		case st.HS == hsOn:
			icon = iconOn
		default:
			icon = iconOff
		}
	}
	systray.SetTemplateIcon(a.icons[icon], a.icons[icon])

	switch {
	case creds == nil:
		a.mHotspot.SetTitle("Not paired: Settings → Pair with Phone…")
	case st == nil:
		a.mHotspot.SetTitle("Phone not connected")
	default:
		title := "Hotspot: " + pick(hotspotNames, st.HS)
		if st.HS == hsOn && st.SSID != "" {
			title += " · " + st.SSID
		}
		if st.HS == hsError && st.Err != nil {
			title += fmt.Sprintf(" (code %d)", *st.Err)
		}
		a.mHotspot.SetTitle(title)
	}

	show(a.mPhone, st != nil, func() string {
		charging := ""
		if st.Chg {
			charging = " ⚡"
		}
		bars := strings.Repeat("●", min(max(st.Sig, 0), 4)) + strings.Repeat("○", 4-min(max(st.Sig, 0), 4))
		return fmt.Sprintf("Phone: %d%%%s · %s · %s", st.Bat, charging, pick(netNames, st.Net), bars)
	})
	show(a.mClients, st != nil && st.NCL > 0, func() string { return fmt.Sprintf("Clients: %d", st.NCL) })
	for i, item := range a.mClientList {
		show(item, st != nil && i < len(st.Clients), func() string {
			c := st.Clients[i]
			return strings.TrimSpace(fmt.Sprintf("%s %s", firstNonEmpty(c.Name, c.MAC), c.IP))
		})
	}
	show(a.mShizuku, st != nil && st.Shz != shzOK, func() string { return shizukuText(st.Shz) })
	show(a.mNote, note != "", func() string { return "⚠ " + note })

	switch {
	case busy != "":
		a.mToggle.SetTitle(busy)
		a.mToggle.Disable()
	case st != nil && (st.HS == hsOn || st.HS == hsStarting):
		a.mToggle.SetTitle("Turn Hotspot Off")
		a.mToggle.Enable()
	default:
		a.mToggle.SetTitle("Turn Hotspot On")
		if st != nil && creds != nil && st.Shz == shzOK {
			a.mToggle.Enable()
		} else {
			a.mToggle.Disable()
		}
	}
	if creds != nil {
		a.mForget.Enable()
	} else {
		a.mForget.Disable()
	}
	check(a.mLogin, launchAtLogin())
	check(a.mAutoOff, autoOff)
}

func shizukuText(shz int) string {
	if shz == shzNoPermission {
		return "⚠ Shizuku has no permission on the phone: open Pixel Hotspot there"
	}
	return "⚠ Shizuku is not running on the phone: start it there"
}

func show(item *systray.MenuItem, visible bool, title func() string) {
	if !visible {
		item.Hide()
		return
	}
	item.SetTitle(title())
	item.Show()
}

func check(item *systray.MenuItem, on bool) {
	if on {
		item.Check()
	} else {
		item.Uncheck()
	}
}

func firstNonEmpty(s ...string) string {
	for _, v := range s {
		if v != "" {
			return v
		}
	}
	return ""
}

func (a *app) getCreds() *Pairing {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.creds
}

func (a *app) setBusy(s string) {
	a.mu.Lock()
	a.busy = s
	if s != "" {
		a.note = ""
	}
	a.mu.Unlock()
	a.render()
}

func (a *app) setNote(s string) {
	if s != "" {
		slog.Warn(s)
	}
	a.mu.Lock()
	a.note = s
	a.mu.Unlock()
	a.render()
}

func (a *app) setOnHotspot(v bool) {
	a.mu.Lock()
	a.onHotspot = v
	a.mu.Unlock()
}

// --- config and logs ---

func configPath() string {
	dir, _ := os.UserConfigDir()
	return filepath.Join(dir, "PixelHotspot", "config.json")
}

func loadConfig() (c config) {
	if b, err := os.ReadFile(configPath()); err == nil {
		_ = json.Unmarshal(b, &c)
	}
	return c
}

func (c config) save() error {
	b, err := json.Marshal(c)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(configPath()), 0o755); err != nil {
		return err
	}
	return os.WriteFile(configPath(), b, 0o644)
}

// ponytail: the log file is never rotated; add rotation if it ever gets big.
func setupLog() {
	home, _ := os.UserHomeDir()
	dir := filepath.Join(home, "Library", "Logs", "PixelHotspot")
	var w io.Writer = os.Stderr
	if err := os.MkdirAll(dir, 0o755); err == nil {
		if f, err := os.OpenFile(filepath.Join(dir, "pixel-hotspot.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); err == nil {
			w = io.MultiWriter(os.Stderr, f)
		}
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(w, nil)))
}
