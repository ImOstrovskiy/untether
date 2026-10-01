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
		bars  int
		mark  bool
		width int
	}{{-1, true, 36}, {2, true, 64}, {2, false, 31}} {
		if w := decode(t, hotspotIcon(iconOn, c.bars, c.mark)).Bounds().Dx(); w != c.width {
			t.Errorf("bars %d, mark %v: width %d, want %d", c.bars, c.mark, w, c.width)
		}
	}
	img := decode(t, hotspotIcon(iconOn, 2, true))
	if a := alphaAt(img, 24, 15.5); a < 200 {
		t.Errorf("lit bar alpha %d", a)
	}
	if a := alphaAt(img, 40, 15.5); a < 40 || a > 120 {
		t.Errorf("unlit bar alpha %d, want dimmed", a)
	}
}

func TestMenuIcon(t *testing.T) {
	lte := &State{Net: 2, Sig: 3, HS: hsOff, Shz: shzOK}
	on := &State{Net: 4, Sig: 9, HS: hsOn, Shz: shzOK}
	broken := &State{Net: 2, Sig: 3, HS: hsOff, Shz: shzOK + 1}
	for _, c := range []struct {
		st    *State
		style string
		want  iconKey
	}{
		{nil, menuBoth, iconKey{iconDisconnected, -1, true, ""}},
		{lte, menuBoth, iconKey{iconOff, 3, true, "LTE"}},
		{lte, menuSignal, iconKey{iconOff, 3, false, "LTE"}},
		{broken, menuSignal, iconKey{iconError, 3, true, "LTE"}},
		{lte, menuWhenOn, iconKey{iconOff, -1, true, ""}},
		{on, menuWhenOn, iconKey{iconOn, 4, false, "5G"}},
		{on, menuMark, iconKey{iconOn, -1, true, ""}},
	} {
		if got := menuIcon(c.st, "", c.style); got != c.want {
			t.Errorf("menuIcon(%+v, %q) = %+v, want %+v", c.st, c.style, got, c.want)
		}
	}
}
