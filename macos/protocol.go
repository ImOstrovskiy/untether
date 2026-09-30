package main

import (
	"crypto/hmac"
	"crypto/sha256"

	"tinygo.org/x/bluetooth"
)

// BLE protocol v1, see protocol/PROTOCOL.md.
var (
	serviceUUID = mustUUID("13f60001-33cc-47ff-8e19-0a93d1157c5b")
	nonceUUID   = mustUUID("13f60002-33cc-47ff-8e19-0a93d1157c5b")
	commandUUID = mustUUID("13f60003-33cc-47ff-8e19-0a93d1157c5b")
	stateUUID   = mustUUID("13f60004-33cc-47ff-8e19-0a93d1157c5b")
	pairingUUID = mustUUID("13f60005-33cc-47ff-8e19-0a93d1157c5b")
)

const (
	opOn         byte = 0x01
	opOff        byte = 0x02
	opStatus     byte = 0x03
	opRing       byte = 0x04
	opBlock      byte = 0x05 // arg: client MAC, 6 bytes
	opUnblockAll byte = 0x06
)

// Hotspot states (State.HS).
const (
	hsOff = iota
	hsStarting
	hsOn
	hsStopping
	hsError
)

// Shizuku states (State.Shz).
const (
	shzOK = iota
	shzNotRunning
	shzNoPermission
)

var (
	hotspotNames = []string{"off", "starting", "on", "stopping", "error"}
	netNames     = []string{"no network", "3G", "LTE", "5G NSA", "5G SA"}
)

type Client struct {
	MAC  string `cbor:"mac"`
	IP   string `cbor:"ip,omitempty"`
	Name string `cbor:"n,omitempty"`
}

// State is the value of the `state` characteristic. Field order matches the phone's encoder.
type State struct {
	V       int      `cbor:"v"`
	HS      int      `cbor:"hs"`
	Err     *int     `cbor:"err,omitempty"`
	SSID    string   `cbor:"ssid,omitempty"`
	Clients []Client `cbor:"cl"`
	NCL     int      `cbor:"ncl"`
	Bat     int      `cbor:"bat"`
	Chg     bool     `cbor:"chg"`
	Net     int      `cbor:"net"`
	Sig     int      `cbor:"sig"`
	Shz     int      `cbor:"shz"`
	// Optional, sent only when set.
	Operator string `cbor:"op,omitempty"`
	RSRP     *int   `cbor:"rsrp,omitempty"`
	SNR      *int   `cbor:"snr,omitempty"`
	Blocked  int    `cbor:"blk,omitempty"`
	BatMin   int    `cbor:"bmin,omitempty"`
	Ringing  bool   `cbor:"ring,omitempty"`
}

// batteryGuarded reports whether the phone will refuse to start the hotspot.
func (s *State) batteryGuarded() bool { return s.BatMin > 0 && !s.Chg && s.Bat < s.BatMin }

// Pairing is the value of the `pairing` characteristic, kept in the Keychain.
type Pairing struct {
	Key  []byte `cbor:"k" json:"k"`
	SSID string `cbor:"s" json:"s"`
	Pass string `cbor:"p" json:"p"`
}

// sign builds a command frame: op ‖ nonce ‖ arg ‖ HMAC-SHA256(key, op ‖ nonce ‖ arg).
func sign(key []byte, op byte, nonce, arg []byte) []byte {
	msg := append(append([]byte{op}, nonce...), arg...)
	m := hmac.New(sha256.New, key)
	m.Write(msg)
	return m.Sum(msg)
}

func mustUUID(s string) bluetooth.UUID {
	u, err := bluetooth.ParseUUID(s)
	if err != nil {
		panic(err)
	}
	return u
}

func pick(names []string, i int) string {
	if i < 0 || i >= len(names) {
		return "?"
	}
	return names[i]
}
