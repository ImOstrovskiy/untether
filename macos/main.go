// Untether: menu bar app that turns on a Pixel's Wi-Fi hotspot over BLE and joins it.
package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"tinygo.org/x/bluetooth"
)

type config struct {
	AutoOff  bool   `json:"auto_off"`  // turn the hotspot off when the Mac sleeps or leaves its Wi-Fi
	MenuIcon string `json:"menu_icon"` // menu bar style: menuBoth, menuSignal, menuWhenOn, menuMark
}

type app struct {
	phone *Phone

	renderMu sync.Mutex // render is called from many goroutines

	mu        sync.Mutex
	cfg       config
	creds     *Pairing
	state     *State // nil while the phone is not connected
	busy      string // "Turning on…" while a command runs
	note      string // last problem, shown in the menu
	onHotspot bool   // the Mac has been seen on the hotspot's Wi-Fi since it was turned on

	lang     string  // UI language: en, uk
	joinTook float64 // seconds from click to joined, last time
	finding  bool    // the phone asked this Mac to play a sound
	lastIcon iconKey // status item icon currently shown
}

func main() {
	setupLog()
	a := &app{cfg: loadConfig(), lastIcon: iconKey{state: -1}}
	creds, err := loadPairing()
	if err != nil {
		slog.Warn("keychain", "err", err)
	}
	a.creds = creds
	a.phone = NewPhone(bluetooth.DefaultAdapter, a.getCreds, a.onPaired, a.onState)
	a.lang = systemLanguage()
	runUI(a.start, a.handleAction)
}

// start runs once the status item exists.
func (a *app) start() {
	a.render()
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

// --- actions ---

func (a *app) toggle() {
	a.mu.Lock()
	st, creds, busy := a.state, a.creds, a.busy
	a.mu.Unlock()
	switch {
	case busy != "":
	case creds == nil:
		a.setNote("@notPaired")
	case st == nil:
		a.setNote("@notConnected")
	case st.Shz != shzOK:
		a.setNote(shizukuText(st.Shz))
	case st.HS == hsOn || st.HS == hsStarting:
		a.turnOff(creds)
	case st.batteryGuarded():
		a.setNote(fmt.Sprintf("Phone battery is below %d%%: its battery guard refuses to start the hotspot", st.BatMin))
	default:
		a.turnOn(creds)
	}
}

// send runs a command that needs no follow-up; note is shown on success when not empty.
func (a *app) send(op byte, arg []byte, note string) {
	creds := a.getCreds()
	if creds == nil {
		a.setNote("Not paired")
		return
	}
	if err := a.phone.Command(op, creds.Key, arg); err != nil {
		a.setNote(err.Error())
		return
	}
	a.setNote(note)
}

func (a *app) blockMAC(s string) {
	mac, err := net.ParseMAC(s)
	if err != nil || len(mac) != 6 {
		a.setNote("Bad client address " + s)
		return
	}
	a.send(opBlock, mac, "")
	slog.Info("blocked client", "mac", s)
}

func (a *app) turnOn(creds *Pairing) {
	a.setBusy("@turningOn")
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
	a.mu.Lock()
	ssid := ""
	if a.state != nil {
		ssid = a.state.SSID
	}
	a.mu.Unlock()
	if ssid != "" && ssid != creds.SSID {
		// The phone switched to Android's own hotspot settings, or they changed: the stored password is stale.
		a.setNote("@networkChanged")
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
	took := time.Since(start).Round(100 * time.Millisecond)
	a.mu.Lock()
	a.joinTook = took.Seconds()
	a.mu.Unlock()
	a.render()
	slog.Info("hotspot on and joined", "took", took)
}

func (a *app) turnOff(creds *Pairing) {
	a.setBusy("@turningOff")
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
	a.setNote("@pairWait")
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
	find := st != nil && st.FindMac
	changed := find != a.finding
	a.finding = find
	a.mu.Unlock()
	if changed {
		uiFindSound(find)
		if find {
			uiShow()
		}
	}
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

func shizukuText(shz int) string {
	if shz == shzNoPermission {
		return "@shizukuPerm"
	}
	return "@shizukuDown"
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
	return filepath.Join(dir, "Untether", "config.json")
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
	dir := filepath.Join(home, "Library", "Logs", "Untether")
	var w io.Writer = os.Stderr
	if err := os.MkdirAll(dir, 0o755); err == nil {
		if f, err := os.OpenFile(filepath.Join(dir, "untether.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); err == nil {
			w = io.MultiWriter(os.Stderr, f)
		}
	}
	slog.SetDefault(slog.New(slog.NewTextHandler(w, nil)))
}
