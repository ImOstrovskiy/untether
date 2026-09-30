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

// hotspotIcon draws a 36×36 template image (18 pt @2x): a dot with two arcs above it.
// Solid parts show the state; the rest is faint. Error adds a slash.
func hotspotIcon(state int) []byte {
	const size, ss = 36, 4 // ss×ss supersampling for anti-aliasing
	faint, solid := 0.3, 1.0
	dotA, arc1A, arc2A := faint, faint, faint
	switch state {
	case iconOff:
		dotA = solid
	case iconBusy:
		dotA, arc1A = solid, solid
	case iconOn, iconError:
		dotA, arc1A, arc2A = solid, solid, solid
	}
	cx, cy := 18.0, 27.0
	img := image.NewNRGBA(image.Rect(0, 0, size, size))
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			var sum float64
			for sy := 0; sy < ss; sy++ {
				for sx := 0; sx < ss; sx++ {
					px := float64(x) + (float64(sx)+0.5)/ss
					py := float64(y) + (float64(sy)+0.5)/ss
					dx, dy := px-cx, py-cy
					r := math.Hypot(dx, dy)
					up := dy < 0 && math.Abs(dx) < -dy*1.1 // within ~48° of vertical
					a := 0.0
					switch {
					case r < 3.5:
						a = dotA
					case up && math.Abs(r-10) < 2:
						a = arc1A
					case up && math.Abs(r-17) < 2:
						a = arc2A
					}
					if state == iconError && math.Abs(px-py)/math.Sqrt2 < 1.6 && px > 4 && px < 32 {
						a = solid
					}
					sum += a
				}
			}
			img.SetNRGBA(x, y, color.NRGBA{A: uint8(255 * sum / (ss * ss))})
		}
	}
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}
