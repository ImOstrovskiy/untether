package main

import (
	"bytes"
	"image"
	"image/png"
	"testing"
)

func decode(t *testing.T, b []byte) image.Image {
	img, err := png.Decode(bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	return img
}

func TestSignalIcon(t *testing.T) {
	alphaAt := func(img image.Image, x, y float64) uint32 { // x, y in 24-unit glyph coordinates
		_, _, _, a := img.At(int(x*1.5), int(y*1.5)).RGBA()
		return a >> 8
	}
	for _, c := range []struct {
		k     iconKey
		width int
	}{
		{iconKey{state: iconOn, bars: -1, mark: true}, 36},
		{iconKey{state: iconOn, bars: 2, mark: true}, 79},
		{iconKey{state: iconOn, bars: 2}, 47},
		{iconKey{state: iconOn, bars: 2, wifi: true}, 37},
	} {
		if w := decode(t, hotspotIcon(c.k)).Bounds().Dx(); w != c.width {
			t.Errorf("%+v: width %d, want %d", c.k, w, c.width)
		}
	}
	img := decode(t, hotspotIcon(iconKey{state: iconOn, bars: 2, mark: true}))
	if a := alphaAt(img, 24.7, 19); a < 200 {
		t.Errorf("lit bar alpha %d", a)
	}
	if a := alphaAt(img, 49.7, 19); a < 40 || a > 120 {
		t.Errorf("unlit bar alpha %d, want dimmed", a)
	}
}

func TestMenuIcon(t *testing.T) {
	lte := &State{Net: 2, Sig: 3, HS: hsOff, Shz: shzOK}
	on := &State{Net: 4, Sig: 9, HS: hsOn, Shz: shzOK}
	broken := &State{Net: 2, Sig: 3, HS: hsOff, Shz: shzOK + 1}
	three := 3
	wifi := &State{Net: 2, Sig: 1, HS: hsOn, Shz: shzOK, WiFi: &three}
	for _, c := range []struct {
		st    *State
		style string
		want  iconKey
	}{
		{nil, menuBoth, iconKey{iconDisconnected, -1, false, true, ""}},
		{lte, menuBoth, iconKey{iconOff, 3, false, true, "LTE"}},
		{lte, menuSignal, iconKey{iconOff, 3, false, false, "LTE"}},
		{broken, menuSignal, iconKey{iconError, 3, false, true, "LTE"}},
		{lte, menuWhenOn, iconKey{iconOff, -1, false, true, ""}},
		{on, menuWhenOn, iconKey{iconOn, 4, false, false, "5G"}},
		{on, menuMark, iconKey{iconOn, -1, false, true, ""}},
		{wifi, menuBoth, iconKey{iconOn, 3, true, true, ""}},
	} {
		if got := menuIcon(c.st, "", c.style); got != c.want {
			t.Errorf("menuIcon(%+v, %q) = %+v, want %+v", c.st, c.style, got, c.want)
		}
	}
}
