package main

import (
	"bytes"
	"encoding/hex"
	"os"
	"strings"
	"testing"

	"github.com/fxamacker/cbor/v2"
)

// Golden vectors in protocol/testdata are shared with the Android tests.
func golden(t *testing.T, name string) []byte {
	t.Helper()
	s, err := os.ReadFile("../protocol/testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	b, err := hex.DecodeString(strings.TrimSpace(string(s)))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestSignMatchesGolden(t *testing.T) {
	key := make([]byte, 32)
	nonce := make([]byte, 16)
	for i := range key {
		key[i] = byte(i)
	}
	for i := range nonce {
		nonce[i] = byte(0xa0 + i)
	}
	if got, want := sign(key, opOn, nonce), golden(t, "command_on.hex"); !bytes.Equal(got, want) {
		t.Fatalf("sign = %x, want %x", got, want)
	}
}

func TestStateGolden(t *testing.T) {
	var st State
	if err := cbor.Unmarshal(golden(t, "state_on.hex"), &st); err != nil {
		t.Fatal(err)
	}
	if st.HS != hsOn || st.SSID != "Pixel-1A2B" || st.NCL != 2 || len(st.Clients) != 2 ||
		st.Clients[0].Name != "macbook" || st.Clients[0].IP != "10.42.0.2" || st.Clients[1].Name != "" ||
		st.Bat != 87 || !st.Chg || st.Net != 3 || st.Sig != 4 || st.Shz != shzOK || st.Err != nil {
		t.Fatalf("decoded %+v", st)
	}
	// Re-encoding gives the same bytes: the phone's encoder and this struct agree on layout.
	b, err := cbor.Marshal(st)
	if err != nil || !bytes.Equal(b, golden(t, "state_on.hex")) {
		t.Fatalf("re-encoded %x, err %v", b, err)
	}
}

func TestStateIgnoresUnknownKeys(t *testing.T) {
	b, _ := cbor.Marshal(map[string]any{"v": 1, "hs": 4, "err": 5, "future": "x"})
	var st State
	if err := cbor.Unmarshal(b, &st); err != nil || st.HS != hsError || st.Err == nil || *st.Err != 5 {
		t.Fatalf("got %+v, %v", st, err)
	}
}
