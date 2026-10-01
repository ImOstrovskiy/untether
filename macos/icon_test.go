package main

import (
	"bytes"
	"image/png"
	"testing"
)

func TestSignalIcon(t *testing.T) {
	alphaAt := func(b []byte, x, y float64) uint32 { // x, y in 24-unit glyph coordinates
		img, err := png.Decode(bytes.NewReader(b))
		if err != nil {
			t.Fatal(err)
		}
		_, _, _, a := img.At(int(x*1.5), int(y*1.5)).RGBA()
		return a >> 8
	}
	if w := pngWidth(t, hotspotIcon(iconOn, -1)); w != 36 {
		t.Errorf("mark alone: width %d, want 36", w)
	}
	b := hotspotIcon(iconOn, 2)
	if w := pngWidth(t, b); w != 54 {
		t.Errorf("with bars: width %d, want 54", w)
	}
	if a := alphaAt(b, 23.6, 17); a < 200 {
		t.Errorf("lit bar alpha %d", a)
	}
	if a := alphaAt(b, 33.8, 17); a < 40 || a > 120 {
		t.Errorf("unlit bar alpha %d, want dimmed", a)
	}
	for _, c := range []struct {
		st    *State
		bars  int
		label string
	}{
		{nil, -1, ""},
		{&State{Net: 2, Sig: 3}, 3, "LTE"},
		{&State{Net: 4, Sig: 9}, 4, "5G"},
		{&State{Net: 0, Sig: 0}, 0, ""},
	} {
		if bars, label := menuSignal(c.st, false); bars != c.bars || label != c.label {
			t.Errorf("menuSignal(%+v) = %d %q, want %d %q", c.st, bars, label, c.bars, c.label)
		}
	}
	if bars, _ := menuSignal(&State{Net: 2, Sig: 3}, true); bars != -1 {
		t.Error("hidden signal still shows bars")
	}
}

func pngWidth(t *testing.T, b []byte) int {
	img, err := png.Decode(bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	return img.Bounds().Dx()
}
