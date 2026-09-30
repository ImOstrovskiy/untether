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

func disc(cx, cy, r float64) shape {
	return func(x, y float64) float64 { return math.Hypot(x-cx, y-cy) - r }
}

func ring(cx, cy, r, hw float64) shape {
	return func(x, y float64) float64 { return math.Abs(math.Hypot(x-cx, y-cy)-r) - hw }
}

// The Untether mark: a hook (the U) and the dot that got off it.
var hook = []shape{
	segment(16, 3.5, 16, 13.5, 1),
	arc(11, 13.5, 5, 0, 180, 1),
	segment(6, 13.5, 6, 12.5, 1),
}

// hotspotIcon draws the 36×36 template image (18 pt @2x) for a state.
func hotspotIcon(state int) []byte {
	parts := append([]shape{}, hook...)
	alpha := 1.0
	switch state {
	case iconDisconnected:
		parts, alpha = append(parts, ring(6, 7.6, 1.5, 1)), 0.35
	case iconOff:
		parts = append(parts, ring(6, 7.6, 1.5, 1))
	case iconBusy:
		parts = append(parts, disc(6, 7.6, 1.9))
	case iconOn:
		parts = append(parts, disc(6, 7.6, 1.9), arc(6, 7.6, 4.3, 215, 305, 1))
	case iconError:
		parts = append(parts, segment(4.4, 6, 7.6, 9.2, 1), segment(7.6, 6, 4.4, 9.2, 1))
	}
	const size, ss = 36, 4 // ss×ss supersampling
	scale := 24.0 / size
	img := image.NewNRGBA(image.Rect(0, 0, size, size))
	for py := 0; py < size; py++ {
		for px := 0; px < size; px++ {
			hits := 0
			for sy := 0; sy < ss; sy++ {
				for sx := 0; sx < ss; sx++ {
					x := (float64(px) + (float64(sx)+0.5)/ss) * scale
					y := (float64(py) + (float64(sy)+0.5)/ss) * scale
					for _, p := range parts {
						if p(x, y) <= 0 {
							hits++
							break
						}
					}
				}
			}
			img.SetNRGBA(px, py, color.NRGBA{A: uint8(255 * alpha * float64(hits) / (ss * ss))})
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}
