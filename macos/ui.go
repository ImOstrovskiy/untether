package main

/*
#cgo CFLAGS: -fobjc-arc
#cgo LDFLAGS: -framework AppKit -framework WebKit
#include <stdlib.h>
void phtRun(const char *html);
void phtEval(const char *js);
void phtSetIcon(const void *png, int len, const char *title);
void phtShowPopover(void);
void phtFindSound(int on);
void phtQuit(void);
void phtSetQuickConnect(int on);
char *phtLanguage(void);
*/
import "C"

import (
	_ "embed"
	"encoding/binary"
	"encoding/json"
	"log/slog"
	"runtime"
	"strconv"
	"strings"
	"unsafe"
)

//go:embed ui/index.html
var indexHTML string

//go:embed ui/icons.js
var iconsJS string

// version is set at build time: go build -ldflags "-X main.version=1.2.3".
var version = "dev"

// Cocoa needs the main thread; keep the main goroutine on it.
func init() { runtime.LockOSThread() }

var (
	uiStarted = func() {}
	uiHandler = func(action, arg string) {}
)

// runUI creates the status item and popover and runs the Cocoa loop. Never returns.
func runUI(started func(), handler func(action, arg string)) {
	uiStarted, uiHandler = started, handler
	html := strings.Replace(indexHTML, `<script src="icons.js"></script>`, "<script>"+iconsJS+"</script>", 1)
	C.phtRun(C.CString(html))
}

//export goUIStarted
func goUIStarted() { go uiStarted() }

//export goUIMessage
func goUIMessage(action, arg *C.char) {
	a, v := C.GoString(action), C.GoString(arg)
	go uiHandler(a, v)
}

func uiEval(js string) {
	cs := C.CString(js)
	defer C.free(unsafe.Pointer(cs))
	C.phtEval(cs)
}

func uiSetIcon(png []byte, title string) {
	ct := C.CString(title)
	defer C.free(unsafe.Pointer(ct))
	C.phtSetIcon(unsafe.Pointer(&png[0]), C.int(len(png)), ct)
}
func uiShow() { C.phtShowPopover() }
func uiSetQuickConnect(on bool) {
	v := 0
	if on {
		v = 1
	}
	C.phtSetQuickConnect(C.int(v))
}
func uiQuit() { C.phtQuit() }

func uiFindSound(on bool) {
	v := C.int(0)
	if on {
		v = 1
	}
	C.phtFindSound(v)
}

// systemLanguage returns "uk" or "en" from the first preferred macOS language.
func systemLanguage() string {
	s := C.phtLanguage()
	defer C.free(unsafe.Pointer(s))
	lang := C.GoString(s)
	for _, l := range []string{"uk"} {
		if strings.HasPrefix(lang, l) {
			return l
		}
	}
	return "en"
}

// uiState is what ui/index.html renders. Notes starting with "@" are translation keys.
type uiState struct {
	Lang     string  `json:"lang"`
	Version  string  `json:"version"`
	Paired   bool    `json:"paired"`
	Busy     string  `json:"busy"`
	Note     string  `json:"note"`
	JoinTook float64 `json:"joinTook,omitempty"`
	Settings struct {
		Login        bool `json:"login"`
		AutoOff      bool `json:"autoOff"`
		QuickConnect bool `json:"quickConnect"`
	} `json:"settings"`
	Phone *State `json:"phone"`
}

func (a *app) render() {
	a.renderMu.Lock()
	defer a.renderMu.Unlock()
	a.mu.Lock()
	st := uiState{
		Lang: a.lang, Version: version, Paired: a.creds != nil, Busy: a.busy,
		Note: a.note, JoinTook: a.joinTook, Phone: a.state,
	}
	st.Settings.AutoOff = a.cfg.AutoOff
	st.Settings.QuickConnect = a.cfg.QuickConnect
	icon := menuIcon(a.state, a.busy)
	a.mu.Unlock()
	st.Settings.Login = launchAtLogin()

	b, err := json.Marshal(st)
	if err != nil {
		slog.Error("ui state", "err", err)
		return
	}
	uiEval("window.render && render(" + string(b) + ")")
	if icon != a.lastIcon {
		uiSetIcon(hotspotIcon(icon), icon.label)
		a.lastIcon = icon
	}
}

func iconFor(st *State, busy string) int {
	switch {
	case st == nil:
		return iconDisconnected
	case busy != "" || st.HS == hsStarting || st.HS == hsStopping:
		return iconBusy
	case st.HS == hsError || st.Shz != shzOK:
		return iconError
	case st.HS == hsOn:
		return iconOn
	default:
		return iconOff
	}
}

// handleAction runs a button press from the popover.
func (a *app) handleAction(action, arg string) {
	switch action {
	case "ready":
		a.render()
	case "toggle":
		a.toggle()
	case "quickToggle": // a click on the menu bar icon with quick connect on
		a.setNote("")
		a.toggle()
		a.mu.Lock()
		failed := a.note != ""
		a.mu.Unlock()
		if failed { // only a problem needs the window
			uiShow()
		}
	case "netMode":
		if m, err := strconv.Atoi(arg); err == nil && m >= 0 && m <= 4 {
			a.send(opSetNetMode, []byte{byte(m)}, "")
		}
	case "ring":
		a.send(opRing, nil, "")
	case "reconnectData":
		a.send(opReconnect, nil, "")
	case "dataSim":
		id, err := strconv.Atoi(arg)
		if err != nil {
			return
		}
		a.send(opSetDataSim, binary.BigEndian.AppendUint32(nil, uint32(int32(id))), "")
	case "block":
		a.blockMAC(arg)
	case "unblockAll":
		a.send(opUnblockAll, nil, "")
	case "stopFindMac":
		uiFindSound(false)
		a.send(opStopFind, nil, "")
	case "login":
		if err := setLaunchAtLogin(!launchAtLogin()); err != nil {
			a.setNote("Launch at login: " + err.Error())
		}
		a.render()
	case "autoOff", "quickConnect":
		a.mu.Lock()
		if action == "autoOff" {
			a.cfg.AutoOff = !a.cfg.AutoOff
		} else {
			a.cfg.QuickConnect = !a.cfg.QuickConnect
			uiSetQuickConnect(a.cfg.QuickConnect)
		}
		err := a.cfg.save()
		a.mu.Unlock()
		if err != nil {
			a.setNote("Settings: " + err.Error())
		}
		a.render()
	case "pair":
		a.pair()
	case "forget":
		if err := forgetPairing(); err != nil {
			a.setNote("Keychain: " + err.Error())
		}
		a.mu.Lock()
		a.creds = nil
		a.mu.Unlock()
		a.render()
	case "dismiss":
		a.setNote("")
	case "quit":
		uiQuit()
	}
}
