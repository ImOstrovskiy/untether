package main

/*
#cgo CFLAGS: -fobjc-arc
#cgo LDFLAGS: -framework AppKit -framework WebKit
#include <stdlib.h>
void phtRun(const char *html);
void phtEval(const char *js);
void phtSetIcon(const void *png, int len);
void phtShowPopover(void);
void phtFindSound(int on);
void phtQuit(void);
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

const version = "0.2.0"

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

func uiSetIcon(png []byte) { C.phtSetIcon(unsafe.Pointer(&png[0]), C.int(len(png))) }
func uiShow()              { C.phtShowPopover() }
func uiQuit()              { C.phtQuit() }

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
	NoteOk   bool    `json:"noteOk"`
	JoinTook float64 `json:"joinTook,omitempty"`
	Settings struct {
		Login   bool `json:"login"`
		AutoOff bool `json:"autoOff"`
	} `json:"settings"`
	Phone *State `json:"phone"`
}

func (a *app) render() {
	a.renderMu.Lock()
	defer a.renderMu.Unlock()
	a.mu.Lock()
	st := uiState{
		Lang: a.lang, Version: version, Paired: a.creds != nil, Busy: a.busy,
		Note: a.note, NoteOk: a.noteOk, JoinTook: a.joinTook, Phone: a.state,
	}
	st.Settings.AutoOff = a.cfg.AutoOff
	icon := iconFor(a.state, a.busy)
	a.mu.Unlock()
	st.Settings.Login = launchAtLogin()

	b, err := json.Marshal(st)
	if err != nil {
		slog.Error("ui state", "err", err)
		return
	}
	uiEval("window.render && render(" + string(b) + ")")
	if icon != a.lastIcon {
		uiSetIcon(a.icons[icon])
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
	case "autoOff":
		a.mu.Lock()
		a.cfg.AutoOff = !a.cfg.AutoOff
		err := a.cfg.save()
		a.mu.Unlock()
		if err != nil {
			a.setNote("Settings: " + err.Error())
		}
		a.render()
	case "pair":
		a.pair()
	case "selfTest":
		a.selfTest()
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
