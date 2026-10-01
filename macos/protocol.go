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
	opSetDataSim byte = 0x07 // arg: subscription id, int32 big-endian
	opReconnect  byte = 0x08 // mobile data off and on
	opStopFind   byte = 0x09 // stop the phone's find-my-Mac request
	opSetNR      byte = 0x0A // arg: 1 allows 5G on the data SIM, 0 keeps it on LTE
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
	MAC  string `cbor:"mac" json:"mac"`
	IP   string `cbor:"ip,omitempty" json:"ip,omitempty"`
	Name string `cbor:"n,omitempty" json:"n,omitempty"`
}

// State is the value of the `state` characteristic. Field order matches the phone's encoder.
type State struct {
	V       int      `cbor:"v" json:"v"`
	HS      int      `cbor:"hs" json:"hs"`
	Err     *int     `cbor:"err,omitempty" json:"err,omitempty"`
	SSID    string   `cbor:"ssid,omitempty" json:"ssid,omitempty"`
	Clients []Client `cbor:"cl" json:"cl"`
	NCL     int      `cbor:"ncl" json:"ncl"`
	Bat     int      `cbor:"bat" json:"bat"`
	Chg     bool     `cbor:"chg" json:"chg"`
	Net     int      `cbor:"net" json:"net"`
	Sig     int      `cbor:"sig" json:"sig"`
	Shz     int      `cbor:"shz" json:"shz"`
	// Optional, sent only when set.
	Operator string `cbor:"op,omitempty" json:"op,omitempty"`
	RSRP     *int   `cbor:"rsrp,omitempty" json:"rsrp,omitempty"`
	SNR      *int   `cbor:"snr,omitempty" json:"snr,omitempty"`
	Blocked  int    `cbor:"blk,omitempty" json:"blk,omitempty"`
	BatMin   int    `cbor:"bmin,omitempty" json:"bmin,omitempty"`
	Ringing  bool   `cbor:"ring,omitempty" json:"ring,omitempty"`
	Temp     *int   `cbor:"temp,omitempty" json:"temp,omitempty"`
	Sims     []Sim  `cbor:"sims,omitempty" json:"sims,omitempty"`
	DataSim  *int   `cbor:"dsim,omitempty" json:"dsim,omitempty"`
	FindMac  bool   `cbor:"fmac,omitempty" json:"fmac,omitempty"`
	WiFi     *int   `cbor:"wifi,omitempty" json:"wifi,omitempty"` // 0–4 while the phone's own internet is Wi-Fi
	NR       *int   `cbor:"nr,omitempty" json:"nr,omitempty"`     // 1: the data SIM may use 5G, 0: kept on LTE
	Bye      bool   `cbor:"bye,omitempty" json:"-"`               // Untether was stopped on the phone
}

type Sim struct {
	ID   int    `cbor:"id" json:"id"`
	Name string `cbor:"n" json:"n"`
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
