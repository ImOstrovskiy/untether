package main

/*
#cgo CFLAGS: -fobjc-arc
#cgo LDFLAGS: -framework CoreWLAN -framework CoreLocation -framework IOKit -framework Foundation -framework Carbon
#include <stdint.h>
#include <stdlib.h>
void phtRequestLocation(void);
char *phtCurrentSSID(void);
int phtWiFiPowerOn(void);
char *phtJoin(const char *ssid, const char *pass, int *scanned);
void phtLeave(void);
int phtWiFiBytes(uint64_t *in, uint64_t *out);
void phtObserveSleep(void);
void phtObserveURLs(void);
void phtSetHotKey(int on);
*/
import "C"

import (
	"errors"
	"fmt"
	"html"
	"os"
	"path/filepath"
	"unsafe"
)

func requestLocation() { C.phtRequestLocation() }

// currentSSID is "" when not on Wi-Fi or when Location permission is missing.
func currentSSID() string {
	s := C.phtCurrentSSID()
	if s == nil {
		return ""
	}
	defer C.free(unsafe.Pointer(s))
	return C.GoString(s)
}

func wifiPowerOn() bool { return C.phtWiFiPowerOn() != 0 }

// joinWiFi reports whether it had to scan (the cached network from the last join did not work).
func joinWiFi(ssid, pass string) (scanned bool, err error) {
	cs, cp := C.CString(ssid), C.CString(pass)
	defer C.free(unsafe.Pointer(cs))
	defer C.free(unsafe.Pointer(cp))
	var sc C.int
	if e := C.phtJoin(cs, cp, &sc); e != nil {
		defer C.free(unsafe.Pointer(e))
		return sc != 0, errors.New(C.GoString(e))
	}
	return sc != 0, nil
}

func leaveWiFi() { C.phtLeave() }

// wifiBytes returns the Wi-Fi interface's total received and sent bytes.
func wifiBytes() (in, out uint64, ok bool) {
	var ci, co C.uint64_t
	if C.phtWiFiBytes(&ci, &co) != 0 {
		return 0, 0, false
	}
	return uint64(ci), uint64(co), true
}

var (
	onWillSleep = func() {}
	onURL       = func(string) {}
	onHotKey    = func() {}
)

func observeSleep(f func()) {
	onWillSleep = f
	C.phtObserveSleep()
}

// observeURLs must run before the app finishes launching to catch the URL that launched it.
func observeURLs(f func(string)) {
	onURL = f
	C.phtObserveURLs()
}

// setHotKey registers or removes the global shortcut ⌃⌥⌘H.
func setHotKey(on bool, f func()) {
	onHotKey = f
	v := C.int(0)
	if on {
		v = 1
	}
	C.phtSetHotKey(v)
}

// The exported callbacks run on macOS threads; keep them short.

//export goWillSleep
func goWillSleep() { onWillSleep() }

//export goOpenURL
func goOpenURL(url *C.char) {
	u := C.GoString(url)
	go onURL(u)
}

//export goHotKey
func goHotKey() { go onHotKey() }

// Launch at login through a LaunchAgent (SMAppService needs an Objective-C/Swift host app).

const agentLabel = "io.github.imostrovskiy.untether"

func agentPath() string {
	home, _ := os.UserHomeDir()
	return filepath.Join(home, "Library", "LaunchAgents", agentLabel+".plist")
}

func launchAtLogin() bool {
	_, err := os.Stat(agentPath())
	return err == nil
}

func setLaunchAtLogin(on bool) error {
	if !on {
		return os.Remove(agentPath())
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	plist := fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>Label</key><string>%s</string>
	<key>ProgramArguments</key><array><string>%s</string></array>
	<key>RunAtLoad</key><true/>
	<key>ProcessType</key><string>Interactive</string>
</dict>
</plist>
`, agentLabel, html.EscapeString(exe))
	if err := os.MkdirAll(filepath.Dir(agentPath()), 0o755); err != nil {
		return err
	}
	return os.WriteFile(agentPath(), []byte(plist), 0o644)
}
