#!/usr/bin/env python3
"""Draws AppIcon.icns: the Untether mark on the Android launcher's green, in Apple's macOS icon
grid (an 824 px rounded square with a soft shadow on a 1024 canvas). Needs Pillow and iconutil.

    python3 macos/scripts/make-icon.py   # writes macos/AppIcon.icns
"""
import os
import subprocess
import tempfile

from PIL import Image, ImageDraw, ImageFilter

GREEN, INK = (0x3D, 0xDC, 0x97, 255), (0x0D, 0x1A, 0x14, 255)
SS = 4  # supersampling
N = 1024 * SS
UNIT = 22 * SS  # one unit of the 24-unit glyph (see icon.go), in pixels


def p(x, y):
    """Glyph coordinates, mark centered on (11, 12), to canvas pixels."""
    return N / 2 + (x - 11) * UNIT, N / 2 + (y - 12) * UNIT


def stroke(d, a, b, w):
    d.line([p(*a), p(*b)], fill=INK, width=w)
    for x, y in (a, b):
        cx, cy = p(x, y)
        d.ellipse([cx - w / 2, cy - w / 2, cx + w / 2, cy + w / 2], fill=INK)


def icon():
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    box = [100 * SS, 100 * SS, 924 * SS, 924 * SS]
    shadow = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([box[0], box[1] + 12 * SS, box[2], box[3] + 12 * SS], radius=185 * SS, fill=(0, 0, 0, 80))
    img.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(14 * SS)))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(box, radius=185 * SS, fill=GREEN)
    w = 2 * UNIT
    stroke(d, (16, 4.5), (16, 14.5), w)  # stem
    cx, cy = p(11, 14.5)
    r = 5 * UNIT + w / 2
    d.arc([cx - r, cy - r, cx + r, cy + r], 0, 180, fill=INK, width=w)  # the bend of the hook
    stroke(d, (6, 14.5), (6, 13.5), w)  # its short tip
    dx, dy = p(6, 8.6)
    r = 1.9 * UNIT
    d.ellipse([dx - r, dy - r, dx + r, dy + r], fill=INK)  # the dot that got off
    return img.resize((1024, 1024), Image.LANCZOS)


def main():
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "AppIcon.icns")
    big = icon()
    with tempfile.TemporaryDirectory() as tmp:
        iconset = os.path.join(tmp, "AppIcon.iconset")
        os.mkdir(iconset)
        for size in (16, 32, 128, 256, 512):
            big.resize((size, size), Image.LANCZOS).save(f"{iconset}/icon_{size}x{size}.png")
            big.resize((size * 2, size * 2), Image.LANCZOS).save(f"{iconset}/icon_{size}x{size}@2x.png")
        subprocess.run(["iconutil", "-c", "icns", iconset, "-o", out], check=True)
    print(os.path.normpath(out))


if __name__ == "__main__":
    main()
