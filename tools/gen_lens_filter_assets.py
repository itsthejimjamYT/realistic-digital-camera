#!/usr/bin/env python3
"""Generate flat item art + model/item JSON for the lens and filter items.

Lenses are drawn in *side profile* (a barrel with a mount, rings and a front
element) so they read as distinct objects at inventory size, and the long
telephotos get a white super-telephoto barrel. Filters
are drawn as screw-in circular filters (knurled metal ring + glass).

Pure stdlib PNG writer (zlib + struct, RGBA, 32x32). Run from anywhere:
    python tools/gen_lens_filter_assets.py
"""
import json
import math
import os
import struct
import zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "realcamera")
TEX = os.path.join(ROOT, "textures", "item")
MODEL = os.path.join(ROOT, "models", "item")
ITEMDEF = os.path.join(ROOT, "items")
S = 32  # texture size


def _png(path, px):
    raw = bytearray()
    for y in range(S):
        raw.append(0)
        for x in range(S):
            raw += bytes(px[y * S + x])
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", S, S, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def canvas():
    return [[0, 0, 0, 0] for _ in range(S * S)]


def over(px, x, y, rgba):
    if not (0 <= x < S and 0 <= y < S):
        return
    r, g, b, a = rgba
    if a <= 0:
        return
    dr, dg, db, da = px[y * S + x]
    ia = 255 - a
    px[y * S + x] = [
        (r * a + dr * ia) // 255,
        (g * a + dg * ia) // 255,
        (b * a + db * ia) // 255,
        max(da, a),
    ]


def rect(px, x0, x1, y0, y1, rgba):
    for y in range(int(round(y0)), int(round(y1))):
        for x in range(int(round(x0)), int(round(x1))):
            over(px, x, y, rgba)


def disc(px, cx, cy, rad, rgba, r_in=0.0):
    for y in range(S):
        for x in range(S):
            d = ((x + 0.5 - cx) ** 2 + (y + 0.5 - cy) ** 2) ** 0.5
            if r_in <= d <= rad:
                edge = min(rad - d, d - r_in, 1.0)
                a = int(rgba[3] * max(0.0, min(1.0, edge + 0.35)))
                over(px, x, y, (rgba[0], rgba[1], rgba[2], a))


def ring(px, cx, cy, rad, w, rgba):
    disc(px, cx, cy, rad, rgba, rad - w)


def ellipse(px, cx, cy, rx, ry, rgba, r_in=0.0):
    for y in range(S):
        for x in range(S):
            dx = (x + 0.5 - cx) / max(rx, 0.01)
            dy = (y + 0.5 - cy) / max(ry, 0.01)
            d = (dx * dx + dy * dy) ** 0.5
            if r_in <= d <= 1.0:
                a = int(rgba[3] * max(0.0, min(1.0, (1.0 - d) * rx + 0.4)))
                over(px, x, y, (rgba[0], rgba[1], rgba[2], a))


GLASS_DARK = (18, 22, 30, 255)
GLASS_COAT = (48, 44, 96, 255)      # purple lens-coating reflection
GLASS_CYAN = (96, 158, 196, 210)
MOUNT = (154, 156, 162, 255)
MOUNT_HI = (188, 190, 196, 255)
MOUNT_LO = (108, 110, 118, 255)
GOLD = (216, 178, 96, 255)
ORANGE = (232, 120, 40, 255)        # maker-ring accent
SCALE = (222, 222, 216, 255)


def _cyl(px, x0, x1, cy, hh, base, hi, lo):
    """A short cylinder section x0..x1: top highlight, bottom shade."""
    x0, x1 = int(round(x0)), int(round(x1))
    rect(px, x0, x1, cy - hh, cy + hh, base)
    rect(px, x0, x1, cy - hh, cy - hh + 2, hi)
    rect(px, x0, x1, cy + hh - 1, cy + hh, lo)


def lens_tex(path, fmin, fmax):
    """One continuous side-profile barrel: silver mount, a gold or orange maker
    ring, a ribbed rubber focus grip, the barrel flaring into a petal hood, and a
    round coated front element filling the hood mouth. Teles get a white
    super-tele barrel + a tripod foot."""
    px = canvas()
    is_zoom = fmin != fmax
    white = fmax >= 200
    base = (236, 235, 230, 255) if white else (44, 44, 49, 255)
    hi = (253, 252, 249, 255) if white else (80, 80, 86, 255)
    lo = (198, 197, 191, 255) if white else (24, 24, 27, 255)
    rubber = (30, 30, 33, 255)
    rubber_hi = (54, 54, 58, 255)
    rubber_gr = (13, 13, 15, 255)
    badge = ORANGE if white else GOLD

    length = {14: 12, 24: 13, 35: 14, 50: 15, 85: 17, 135: 19}.get(fmax) or \
        {(16, 35): 14, (24, 70): 16, (70, 200): 19, (100, 400): 21, (200, 600): 23}[(fmin, fmax)]
    half = 6 if not white else 7
    flare = 2 if not white else 3
    cy = 16

    total = 4 + length + 4
    bx0 = max(1, (S - total) // 2)
    mount_x = bx0

    # --- rear mount (silver bayonet) + locating pin ---
    rect(px, bx0 - 1, bx0 + 1, cy - 2, cy + 2, MOUNT)
    _cyl(px, bx0, bx0 + 3, cy, half, MOUNT, MOUNT_HI, MOUNT_LO)
    x = bx0 + 3
    rect(px, x, x + 2, cy - half, cy + half, badge)             # maker ring, flush to mount

    # --- one continuous barrel: rear -> ribbed grip -> slightly flared hood ---
    barrel0 = x + 2
    barrel1 = barrel0 + length
    grip0 = barrel0 + max(2, length // 6)
    grip1 = barrel0 + length * 52 // 100
    flare0 = barrel1 - 4
    rim = half + flare
    for xi in range(barrel0, barrel1):
        if xi >= flare0:
            t = (xi - flare0) / 4.0
            hh = half + round(t * flare)
            col = (lo, (lo[0] + 16, lo[1] + 16, lo[2] + 16, 255), lo)   # matte hood
        elif grip0 <= xi < grip1:
            hh, col = half, (rubber, rubber_hi, rubber_gr)
        else:
            hh, col = half, (base, hi, lo)
        _cyl(px, xi, xi + 1, cy, hh, *col)
        if grip0 <= xi < grip1 and (xi - grip0) % 3 == 1:
            rect(px, xi, xi + 1, cy - hh, cy + hh, rubber_gr)
    rect(px, barrel1 - 1, barrel1, cy - rim, cy + rim, lo)      # crisp hood rim
    if is_zoom:                                                 # thin zoom ring behind the grip
        rect(px, grip0 - 1, grip0, cy - half, cy + half, badge)

    # --- round coated front element, sitting just proud of the hood mouth ---
    gx = barrel1 - 2
    ellipse(px, gx, cy, 3.0, half + 1.0, GLASS_DARK)
    ellipse(px, gx, cy, 2.3, half - 0.5, GLASS_COAT)
    ellipse(px, gx, cy, 1.3, half - 2.5, GLASS_CYAN)
    over(px, int(gx - 1), cy - 3, (255, 255, 255, 235))
    over(px, int(gx - 1), cy - 2, (255, 255, 255, 140))

    # --- tripod collar foot on the 200-600 ---
    if fmax >= 500:
        m = (mount_x + barrel1) // 2
        rect(px, m - 3, m + 3, cy + half + 1, cy + half + 3, lo)
        rect(px, m - 4, m + 4, cy + half + 3, cy + half + 6, rubber)

    _png(path, [px[i] for i in range(S * S)])


def _knurl(px, cx, cy, rad, light, dark):
    for k in range(40):
        a = k / 40 * math.tau
        x = cx + math.cos(a) * rad
        y = cy + math.sin(a) * rad
        over(px, int(round(x)), int(round(y)), light if k % 2 == 0 else dark)


def filter_tex(path, name):
    px = canvas()
    cx = cy = S / 2
    if name == "filter_mist":
        rim_out = (196, 198, 205, 255)
        rim_in = (150, 152, 160, 255)
        glass = (236, 239, 245, 205)
        klight, kdark = (222, 224, 230, 255), (150, 152, 160, 255)
    elif name == "filter_polarizer":
        rim_out = (58, 58, 64, 255)
        rim_in = (110, 112, 120, 255)
        glass = (44, 78, 120, 225)
        klight, kdark = (120, 122, 130, 255), (40, 40, 46, 255)
    else:  # neutral density — black anodized ring, smoky glass
        rim_out = (46, 46, 50, 255)
        rim_in = (90, 92, 98, 255)
        glass = (54, 57, 62, 235)
        klight, kdark = (96, 98, 104, 255), (30, 30, 34, 255)

    ring(px, cx, cy, 14.5, 3.2, rim_out)
    ring(px, cx, cy, 12.0, 1.2, rim_in)
    _knurl(px, cx, cy, 13.4, klight, kdark)
    disc(px, cx, cy, 11.0, glass)

    if name == "filter_mist":
        disc(px, cx - 1.5, cy - 1.5, 6.5, (255, 255, 255, 60))
        disc(px, cx - 2.5, cy - 3.0, 2.4, (255, 255, 255, 150))
    elif name == "filter_polarizer":
        # blue-cyan polarised sheen + a bright glint
        disc(px, cx + 1.0, cy + 1.5, 6.0, (70, 150, 200, 90))
        for y in range(S):
            for x in range(S):
                if ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5 <= 10.0 and 0 < int(x - y) % 8 < 3:
                    over(px, x, y, (150, 205, 235, 45))
        disc(px, cx - 3.0, cy - 3.5, 2.0, (220, 240, 255, 170))
    else:
        # faint "ND" cue: a darker centre gradient + a slim highlight arc
        disc(px, cx, cy, 7.5, (36, 38, 42, 120))
        disc(px, cx - 3.0, cy - 3.5, 1.8, (150, 155, 162, 180))

    _png(path, [px[i] for i in range(S * S)])


LENSES = {
    "lens_14mm": (14, 14), "lens_24mm": (24, 24), "lens_35mm": (35, 35),
    "lens_50mm": (50, 50), "lens_85mm": (85, 85), "lens_135mm": (135, 135),
    "lens_16_35mm": (16, 35), "lens_24_70mm": (24, 70), "lens_70_200mm": (70, 200),
    "lens_100_400mm": (100, 400), "lens_200_600mm": (200, 600),
}
FILTERS = ["filter_nd8", "filter_polarizer", "filter_mist"]


def write_defs(name):
    with open(os.path.join(MODEL, name + ".json"), "w") as f:
        json.dump({"parent": "minecraft:item/generated",
                   "textures": {"layer0": "realcamera:item/" + name}}, f, indent=2)
    with open(os.path.join(ITEMDEF, name + ".json"), "w") as f:
        json.dump({"model": {"type": "minecraft:model", "model": "realcamera:item/" + name}}, f, indent=2)


def main():
    for d in (TEX, MODEL, ITEMDEF):
        os.makedirs(d, exist_ok=True)
    for name, (fmin, fmax) in LENSES.items():
        lens_tex(os.path.join(TEX, name + ".png"), fmin, fmax)
        write_defs(name)
        print("lens", name)
    for name in FILTERS:
        filter_tex(os.path.join(TEX, name + ".png"), name)
        write_defs(name)
        print("filter", name)
    print("done:", len(LENSES), "lenses,", len(FILTERS), "filters")


if __name__ == "__main__":
    main()
