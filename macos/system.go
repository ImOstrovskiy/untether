package main

/*
#cgo CFLAGS: -fobjc-arc
#cgo LDFLAGS: -framework CoreWLAN -framework CoreLocation -framework IOKit -framework Foundation
#include <stdlib.h>
void phtRequestLocation(void);
char *phtCurrentSSID(void);
char *phtJoin(const char *ssid, const char *pass);
void phtLeave(void);
void phtObserveSleep(void);
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

func joinWiFi(ssid, pass string) error {
	cs, cp := C.CString(ssid), C.CString(pass)
	defer C.free(unsafe.Pointer(cs))
	defer C.free(unsafe.Pointer(cp))
	if e := C.phtJoin(cs, cp); e != nil {
		defer C.free(unsafe.Pointer(e))
		return errors.New(C.GoString(e))
	}
	return nil
}

func leaveWiFi() { C.phtLeave() }

var onWillSleep = func() {}

func observeSleep(f func()) {
	onWillSleep = f
	C.phtObserveSleep()
}

//export goWillSleep
func goWillSleep() { onWillSleep() }

// Launch at login through a LaunchAgent (SMAppService needs an Objective-C/Swift host app).

const agentLabel = "io.github.imostrovskiy.pixelhotspot"

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
