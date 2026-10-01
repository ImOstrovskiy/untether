package main

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"math"
)

// Menu bar icon states.
const (
	iconDisconnected = iota
	iconOff
	iconBusy
	iconOn
	iconError
)

// A shape is a signed distance: <= 0 on the ink, in 24-unit glyph coordinates.
type shape func(x, y float64) float64

func segment(ax, ay, bx, by, hw float64) shape {
	return func(x, y float64) float64 {
		dx, dy := bx-ax, by-ay
		t := math.Max(0, math.Min(1, ((x-ax)*dx+(y-ay)*dy)/(dx*dx+dy*dy)))
		return math.Hypot(x-(ax+t*dx), y-(ay+t*dy)) - hw
	}
}

// arc from a0 to a1 degrees, clockwise on screen (y grows down), stroke half-width hw, round caps.
func arc(cx, cy, r, a0, a1, hw float64) shape {
	rad := func(a float64) (float64, float64) {
		return cx + r*math.Cos(a*math.Pi/180), cy + r*math.Sin(a*math.Pi/180)
	}
	x0, y0 := rad(a0)
	x1, y1 := rad(a1)
	return func(x, y float64) float64 {
		a := math.Mod(math.Atan2(y-cy, x-cx)*180/math.Pi+360, 360)
		if a >= a0 && a <= a1 {
			return math.Abs(math.Hypot(x-cx, y-cy)-r) - hw
		}
		return math.Min(math.Hypot(x-x0, y-y0), math.Hypot(x-x1, y-y1)) - hw
	}
}

// roundedBox spans x0..x1, y0..y1 with corner radius r.
func roundedBox(x0, y0, x1, y1, r float64) shape {
	cx, cy, hx, hy := (x0+x1)/2, (y0+y1)/2, (x1-x0)/2-r, (y1-y0)/2-r
	return func(x, y float64) float64 {
		qx, qy := math.Abs(x-cx)-hx, math.Abs(y-cy)-hy
		return math.Hypot(math.Max(qx, 0), math.Max(qy, 0)) + math.Min(math.Max(qx, qy), 0) - r
	}
}

func disc(cx, cy, r float64) shape {
	return func(x, y float64) float64 { return math.Hypot(x-cx, y-cy) - r }
}

func ring(cx, cy, r, hw float64) shape {
	return func(x, y float64) float64 { return math.Abs(math.Hypot(x-cx, y-cy)-r) - hw }
}

// The Untether mark: a hook (the U) and the dot that got off it, centered on y = 12 like the bars.
var hook = []shape{
	segment(16, 4.5, 16, 14.5, 1),
	arc(11, 14.5, 5, 0, 180, 1),
	segment(6, 14.5, 6, 13.5, 1),
}

// Signal glyphs are 85 % as tall as the mark, which matches the macOS Wi-Fi icon (y = 3.5 to 20.5),
// centered in the image like the mark and the label (see phtSetIcon). Full size looked too big.
const (
	glyphScale          = 0.85
	glyphTop, glyphBase = 12 - 8.5*glyphScale, 12 + 8.5*glyphScale
)

// hotspotIcon draws the template image (18 pt high @2x) for k: the mark for a state, then the
// phone's signal bars, unlit bars dimmed. At least one of the two.
func hotspotIcon(k iconKey) []byte {
	var parts, dim []shape
	width, left, alpha := 0.0, 0.5, 1.0
	lit := func(on bool, s shape) {
		if on {
			parts = append(parts, s)
		} else {
			dim = append(dim, s)
		}
	}
	if k.mark {
		parts = append(parts, hook...)
		switch k.state {
		case iconDisconnected:
			parts, alpha = append(parts, ring(6, 8.6, 1.5, 1)), 0.35
		case iconOff:
			parts = append(parts, ring(6, 8.6, 1.5, 1))
		case iconBusy:
			parts = append(parts, disc(6, 8.6, 1.9))
		case iconOn:
			parts = append(parts, disc(6, 8.6, 1.9), arc(6, 8.6, 4.3, 215, 305, 1))
		case iconError:
			parts = append(parts, segment(4.4, 7, 7.6, 10.2, 1), segment(7.6, 7, 4.4, 10.2, 1))
		}
		width, left = 24, 22.3 // the gap after the hook matches the one before the label
	}
	if k.bars >= 0 {
		// After the iPhone status bar: bars 0.23 of the glyph height wide with 0.21 gaps, rising from
		// 30 % to full height in equal steps.
		const h = glyphBase - glyphTop
		const w, gap = 0.23 * h, 0.21 * h
		for i := range 4 {
			x := left + (w+gap)*float64(i)
			lit(i < k.bars, roundedBox(x, glyphBase-h*(0.3+0.7*float64(i)/3), x+w, glyphBase, 1.1*glyphScale))
		}
		width = left + 4*w + 3*gap + 0.5
	}
	return rasterize(parts, dim, width, alpha)
}

// rasterize draws shapes into a template PNG 36 px (18 pt @2x) high and width glyph units wide;
// dim shapes get 30% of the ink.
func rasterize(parts, dim []shape, width, alpha float64) []byte {
	const size, ss = 36, 4 // ss×ss supersampling
	scale := 24.0 / size
	w := int(math.Ceil(width / scale))
	inked := func(shapes []shape, x, y float64) bool {
		for _, p := range shapes {
			if p(x, y) <= 0 {
				return true
			}
		}
		return false
	}
	img := image.NewNRGBA(image.Rect(0, 0, w, size))
	for py := 0; py < size; py++ {
		for px := 0; px < w; px++ {
			ink := 0.0
			for sy := 0; sy < ss; sy++ {
				for sx := 0; sx < ss; sx++ {
					x := (float64(px) + (float64(sx)+0.5)/ss) * scale
					y := (float64(py) + (float64(sy)+0.5)/ss) * scale
					if inked(parts, x, y) {
						ink++
					} else if inked(dim, x, y) {
						ink += 0.3
					}
				}
			}
			img.SetNRGBA(px, py, color.NRGBA{A: uint8(255 * alpha * ink / (ss * ss))})
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}

// Menu bar styles, config.MenuIcon.
const (
	menuBoth   = ""          // the mark and the phone's signal
	menuSignal = "signal"    // the signal alone
	menuWhenOn = "connected" // the mark; the signal alone while the hotspot is on
	menuMark   = "icon"      // the mark alone
)

// iconKey is everything the status item shows.
type iconKey struct {
	state, bars int // bars < 0: none
	mark        bool
	label       string
}

// menuIcon is what the status item shows in a menu bar style. The mark stays whenever it has
// something to say: no phone, a command running, an error.
func menuIcon(st *State, busy, style string) iconKey {
	k := iconKey{state: iconFor(st, busy), bars: -1, mark: true}
	if st == nil || style == menuMark || (style == menuWhenOn && st.HS != hsOn) {
		return k
	}
	switch {
	case st.WiFi != nil: // the phone itself is on Wi-Fi, and the hotspot shares it
		k.bars, k.label = min(max(*st.WiFi, 0), 4), "Wi-Fi"
	default:
		k.bars = min(max(st.Sig, 0), 4)
		if st.Net >= 1 && st.Net <= 4 {
			k.label = [...]string{"3G", "LTE", "5G", "5G"}[st.Net-1]
		}
	}
	k.mark = style == menuBoth || (k.state != iconOn && k.state != iconOff)
	return k
}
