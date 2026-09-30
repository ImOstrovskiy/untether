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
	opOn     byte = 0x01
	opOff    byte = 0x02
	opStatus byte = 0x03
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
}

// Pairing is the value of the `pairing` characteristic, kept in the Keychain.
type Pairing struct {
	Key  []byte `cbor:"k" json:"k"`
	SSID string `cbor:"s" json:"s"`
	Pass string `cbor:"p" json:"p"`
}

// sign builds a command frame: op ‖ nonce ‖ HMAC-SHA256(key, op ‖ nonce).
func sign(key []byte, op byte, nonce []byte) []byte {
	msg := append([]byte{op}, nonce...)
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
