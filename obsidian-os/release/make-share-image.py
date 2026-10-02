#!/usr/bin/env python3
"""Draws the 1200x630 image that forums and chat apps show beside a link to the site.

    make-share-image.py --out share.png

There is no image library on either server, so this writes the PNG itself: a PNG is a header, a
zlib stream of rows, and a checksum, which is little enough code to be worth it rather than
installing something on a box with 908 MB of memory.

The picture is the site's own mark, the ellipse, centred on black, at the size every social site
and chat app expects. No text: the title and description come from the page's Open Graph tags and
are drawn by whoever is showing the card, in their own font, so putting words in the image as well
would only repeat them in the wrong typeface.
"""
import argparse
import struct
import zlib

WIDTH, HEIGHT = 1200, 630
BACKGROUND = (0, 0, 0)
MARK = (216, 216, 216)          # the same grey the site draws its mark in
# The mark is an ellipse outline, the proportions taken from the site's own favicon: rx 8, ry 10
# on a 32 unit square. Scaled up here so it sits comfortably in the middle of the card.
SCALE = 19.0
RX, RY = 8.0 * SCALE, 10.0 * SCALE
STROKE = 1.4 * SCALE / 2.0      # half width, measured either side of the true edge


def ellipse_coverage(x, y, cx, cy, rx, ry, half):
    """How much of this pixel the outline covers, from 0 to 1.

    Sampled rather than solved: the distance field of an ellipse has no closed form, so each pixel
    is tested on a 3x3 grid and the fraction of samples inside the band is the coverage. That is
    what keeps the curve smooth instead of stepped.
    """
    hits = 0
    for sy in (-0.333, 0.0, 0.333):
        for sx in (-0.333, 0.0, 0.333):
            px, py = x + sx, y + sy
            # Normalised radius: 1.0 is exactly on the ellipse.
            r = ((px - cx) / rx) ** 2 + ((py - cy) / ry) ** 2
            if r <= 0.0:
                continue
            r = r ** 0.5
            # Convert the normalised distance back to roughly pixel distance from the edge, using
            # the local radius in the direction of this sample.
            local = (((px - cx) ** 2 + (py - cy) ** 2) ** 0.5) or 1.0
            edge = local / r if r else 0.0
            if abs(local - edge) <= half:
                hits += 1
    return hits / 9.0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    cx, cy = WIDTH / 2.0, HEIGHT / 2.0
    rows = []
    for y in range(HEIGHT):
        row = bytearray()
        row.append(0)  # PNG filter type 0, no filtering
        for x in range(WIDTH):
            a = ellipse_coverage(x + 0.5, y + 0.5, cx, cy, RX, RY, STROKE)
            if a <= 0.0:
                row += bytes(BACKGROUND)
            else:
                row += bytes(round(BACKGROUND[i] + (MARK[i] - BACKGROUND[i]) * a) for i in range(3))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(kind, payload):
        return (struct.pack(">I", len(payload)) + kind + payload
                + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 2, 0, 0, 0))  # 8 bit truecolour
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    with open(args.out, "wb") as out:
        out.write(png)
    print("%s: %dx%d, %d bytes" % (args.out, WIDTH, HEIGHT, len(png)))


main()
