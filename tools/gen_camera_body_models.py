#!/usr/bin/env python3
"""Camera item models — clean, chunky, Minecraft-native.

    python tools/gen_camera_body_models.py

Emits, all off one small flat-shaded atlas (`camera_body_lens.png`, texture "#1"):
  * camera_body            - bare survival body: OPEN lens mount showing a sensor.
  * camera_body_<lens_id>  - the same body wearing that lens (picked by the
                             custom_model_data string CameraBodyMenu writes).
  * creative_camera        - the body permanently wearing the 24-70 zoom + a
                             small purple accent so it reads as the creative tool.
Every model here is generated from scratch by this script.
"""
import json
import os
import struct
import zlib

A = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "realcamera")
MI = os.path.join(A, "models", "item")
IT = os.path.join(A, "items")
TEX = os.path.join(A, "textures", "item")

CX, CY, ZFRONT = 8.1, 8.0, 11.0          # lens axis + body front face
MOUNT = (4.5, 4.9, 11.7, 11.5)           # x0, y0, x1, y1 of the lens-mount hole in the front

ATEX = 128
TILE = 16

DISPLAY = {
    "gui": {"rotation": [12, 40, 0], "translation": [0, 0.5, 0], "scale": [0.62, 0.62, 0.62]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, 0, -5], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_righthand": {"rotation": [-50, 180, 0], "translation": [0.5, 1.1, 1.2], "scale": [0.62, 0.62, 0.62]},
    "thirdperson_lefthand": {"rotation": [-50, 180, 0], "translation": [0.5, 1.1, 1.2], "scale": [0.62, 0.62, 0.62]},
    "firstperson_righthand": {"rotation": [12, 180, 0], "translation": [0.4, 2.6, 1.4], "scale": [0.5, 0.5, 0.5]},
    "firstperson_lefthand": {"rotation": [12, 180, 0], "translation": [0.4, 2.6, 1.4], "scale": [0.5, 0.5, 0.5]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 13, 0], "scale": [1.0, 1.0, 1.0]},
}


# ---------------------------------------------------------------- atlas --------
def _png(path, px, S):
    raw = bytearray()
    for y in range(S):
        raw.append(0)
        for x in range(S):
            raw += bytes(px[y * S + x])

    def ch(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(ch(b"IHDR", struct.pack(">IIBBBBB", S, S, 8, 6, 0, 0, 0)))
        f.write(ch(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(ch(b"IEND", b""))


# name -> (col, row) in the 8x8 grid of 16px tiles
SLOT = {
    "black": (0, 0), "white": (1, 0), "red": (2, 0), "gold": (3, 0),
    "mount": (4, 0), "glass": (5, 0), "smooth": (6, 0), "hood": (7, 0),
    "orange": (0, 1), "body": (1, 1), "lgrey": (2, 1), "grey": (3, 1),
    "dark": (4, 1), "screen": (5, 1), "sensor": (6, 1), "grip": (7, 1),
    "vf": (0, 2), "dialtop": (1, 2), "throat": (2, 2), "purple": (3, 2),
}

COLOR = {
    "black": (40, 40, 45), "white": (223, 222, 216), "red": (200, 58, 48),
    "gold": (212, 174, 92), "mount": (150, 152, 160), "glass": (60, 122, 176),
    "smooth": (20, 20, 24), "hood": (28, 28, 33), "orange": (226, 122, 40),
    "body": (34, 35, 40), "lgrey": (34, 35, 40), "grey": (78, 80, 88),
    "dark": (22, 23, 27), "screen": (26, 32, 44), "sensor": (34, 58, 64),
    "grip": (42, 44, 50), "vf": (58, 120, 182), "dialtop": (100, 103, 112),
    "throat": (10, 10, 13), "purple": (150, 92, 200),
}
RIBBED = {"black", "white", "mount"}          # subtle horizontal machining lines


def build_atlas():
    px = [[0, 0, 0, 0] for _ in range(ATEX * ATEX)]

    def put(x, y, c):
        px[y * ATEX + x] = [c[0], c[1], c[2], 255]

    for name, (c, r) in SLOT.items():
        base = COLOR[name]
        rib = name in RIBBED
        for yy in range(TILE):
            for xx in range(TILE):
                col = list(base)
                if rib and yy % 3 == 0:
                    col = [max(0, v - 18) for v in col]
                if yy < 2:
                    col = [min(255, v + 20) for v in col]        # top light
                elif yy > TILE - 3:
                    col = [max(0, v - 24) for v in col]           # bottom shade
                put(c * TILE + xx, r * TILE + yy, col)

    def tile_xy(name):
        c, r = SLOT[name]
        return c * TILE, r * TILE

    # grip: vertical knurl
    gx, gy = tile_xy("grip")
    for xx in range(TILE):
        if xx % 3 == 0:
            for yy in range(TILE):
                put(gx + xx, gy + yy, [max(0, v - 12) for v in COLOR["grip"]])
    # screen: bright diagonal glint
    sx, sy = tile_xy("screen")
    for yy in range(TILE):
        for xx in range(TILE):
            if 0 <= xx - yy + 3 < 3:
                put(sx + xx, sy + yy, (120, 150, 205))
    # sensor: horizontal sheen band + a faint micro-lens shimmer
    ex, ey = tile_xy("sensor")
    for xx in range(TILE):
        put(ex + xx, ey + 5, (70, 96, 118))
        put(ex + xx, ey + 6, (58, 82, 100))
    put(ex + 10, ey + 9, (128, 72, 120))
    put(ex + 5, ey + 3, (96, 120, 150))
    # dialtop: a dark index notch
    dx, dy = tile_xy("dialtop")
    for yy in range(2, TILE - 2):
        put(dx + TILE // 2, dy + yy, (60, 61, 66))
    # vf window: inner glint
    vx, vy = tile_xy("vf")
    for yy in range(4, 12):
        for xx in range(4, 12):
            put(vx + xx, vy + yy, (110, 168, 220))
    # glass (front element): coated deep-blue with a bright diagonal reflection + glint
    lx, ly = tile_xy("glass")
    for yy in range(TILE):
        for xx in range(TILE):
            d = xx - yy
            if -1 <= d + 4 < 3:
                put(lx + xx, ly + yy, (150, 205, 240))          # sweep reflection
            elif (xx - 5) ** 2 + (yy - 5) ** 2 <= 3:
                put(lx + xx, ly + yy, (225, 240, 250))          # hot glint
            elif xx < 2 or yy < 2 or xx > 13 or yy > 13:
                put(lx + xx, ly + yy, (30, 60, 95))             # dark coated rim

    _png(os.path.join(TEX, "camera_body_lens.png"), px, ATEX)


# ---------------------------------------------------------------- geometry -----
def uv(c, r):
    return [round(c * 2 + 0.1, 2), round(r * 2 + 0.1, 2),
            round(c * 2 + 1.9, 2), round(r * 2 + 1.9, 2)]


TILE_UV = {n: uv(c, r) for n, (c, r) in SLOT.items()}
FACES6 = ("north", "south", "east", "west", "up", "down")


def _all(t):
    return {f: t for f in FACES6}


def box(a, b, tile=None, facemap=None, rot=None):
    fm = facemap or _all(tile)
    e = {"from": [round(v, 3) for v in a], "to": [round(v, 3) for v in b],
         "faces": {f: {"uv": TILE_UV[t], "texture": "#1"} for f, t in fm.items()}}
    if rot:
        e["rotation"] = rot
    return e


def knob(cx, cz, y0, r, h, side="grey", top="dialtop"):
    """Round-ish knob: square base + a 45deg copy scaled to 0.71x (its corners land ON
    the base edges -> an octagon, never an 8-point star) + a small textured cap."""
    rr = r * 0.71
    s = _all(side)
    s["down"] = "dark"
    cap = _all(side)
    cap["up"], cap["down"] = top, "dark"
    return [
        box([cx - r, y0, cz - r], [cx + r, y0 + h * 0.66, cz + r], facemap=s),
        box([cx - rr, y0 + 0.01, cz - rr], [cx + rr, y0 + h * 0.66 - 0.01, cz + rr], facemap=s,
            rot={"origin": [cx, y0, cz], "axis": "y", "angle": 45}),
        box([cx - r * 0.6, y0 + h * 0.66, cz - r * 0.6],
            [cx + r * 0.6, y0 + h, cz + r * 0.6], facemap=cap),
    ]


def body_elements():
    """All-black, extra-wide body. Centred finder + hot shoe. Everything else lives on
    the grip side: a big forward-protruding grip with a stepped hand contour, the
    shutter on top of that protrusion, two dials on the shoulder behind it, and a rear
    control wheel to one side of the screen. The far side is left completely clean."""
    els = []
    # core: NO front (+Z) face — the lens-mount hole must be a real opening, so the
    # front is built as four panels around MOUNT and the mount / sensor fills the gap.
    shell = _all("body")
    shell["down"] = "dark"
    del shell["south"]
    els.append(box([0.8, 4.3, 4.7], [15.2, 12.3, 11.0], facemap=shell))        # core (wide)
    mx0, my0, mx1, my1 = MOUNT
    for a, c, b, d in ((0.8, 4.3, mx0, 12.3), (mx1, 4.3, 15.2, 12.3),
                       (mx0, my1, mx1, 12.3), (mx0, 4.3, mx1, my0)):
        els.append(box([a, c, 10.95], [b, d, 11.05], "body"))                  # front panel around the hole
    els.append(box([0.9, 4.0, 5.0], [15.1, 4.32, 10.8], "dark"))               # base plate
    els.append(box([0.75, 4.25, 10.96], [mx0, 7.8, 11.2], "dark"))            # front band, left of the mount
    els.append(box([mx1, 4.25, 10.96], [15.25, 7.8, 11.2], "dark"))           # front band, right of the mount
    els.append(box([1.1, 12.3, 4.9], [15.0, 13.05, 10.7], "body"))            # top plate

    # centred finder + hot shoe
    finder = _all("body")
    finder["north"] = "smooth"
    els.append(box([6.0, 13.05, 4.9], [10.2, 15.0, 9.6], facemap=finder))      # finder bump
    els.append(box([6.5, 12.8, 4.55], [9.7, 14.3, 4.92], "smooth"))          # eyecup collar
    els.append(box([6.9, 13.0, 4.15], [9.3, 14.05, 4.58], "smooth"))         # eyecup
    hs = _all("grey")
    hs["down"] = "dark"
    els.append(box([7.2, 15.0, 6.2], [9.0, 15.45, 8.4], facemap=hs))          # hot shoe (centred)

    # --- grip side: a big protruding grip; its shoulder carries the shutter ---
    els.append(box([0.4, 4.1, 7.0], [3.8, 12.3, 12.4], "grip"))               # grip mass (meets the deck)
    els.append(box([0.9, 4.3, 12.2], [4.0, 13.0, 13.9], "grip"))             # forward shoulder (shutter sits here)
    els.append(box([1.3, 5.0, 13.7], [3.7, 11.2, 14.7], "grip"))             # hand-contour tip (goes out more)
    els.append(box([0.5, 9.0, 4.2], [2.7, 12.1, 5.7], "grip"))              # rear thumb rest (upper right)

    # shutter — flush on the grip shoulder (y13.0 = shoulder top), grey, no gap
    els.append(box([1.5, 13.0, 12.4], [3.6, 13.6, 13.8], "grey"))

    # two dials on the shoulder, behind the shutter
    els += knob(3.0, 6.6, 13.05, 0.82, 0.95)                                   # front dial
    els += knob(2.5, 8.9, 13.05, 1.0, 1.1)                                     # exposure-comp dial

    # screen — mostly on the far side; the right third is left for the rear wheel + thumb
    els.append(box([4.7, 4.7, 4.4], [14.9, 11.5, 4.74], "dark"))            # screen frame
    els.append(box([5.2, 5.1, 4.3], [14.4, 11.2, 4.58], "screen"))         # rear screen

    # rear control wheel — lower right, clear below the thumb rest
    els.append(box([1.4, 5.6, 3.95], [3.9, 8.1, 4.72], "grey"))             # wheel
    els.append(box([2.1, 6.2, 3.78], [3.2, 7.5, 4.0], "dialtop"))          # hub
    return els


def bare_front():
    """No lens -> a real open mount: dark chamber walls set back into the body for
    depth, the sensor sitting at the far wall with the same screen-glint look as the
    rear display, a silver mount bevel at the lip, and gold contact fingers."""
    mx0, my0, mx1, my1 = MOUNT
    dz = ZFRONT
    back = dz - 3.0                   # far wall — 3 units deep, real depth
    els = []
    # chamber: four dark walls from the lip back to the wall (you see INTO this)
    t = 0.5
    els.append(box([mx0, my0, back], [mx0 + t, my1, dz], "throat"))            # left wall
    els.append(box([mx1 - t, my0, back], [mx1, my1, dz], "throat"))           # right wall
    els.append(box([mx0, my1 - t, back], [mx1, my1, dz], "throat"))           # top wall
    els.append(box([mx0, my0, back], [mx1, my0 + t, dz], "throat"))           # bottom wall
    els.append(box([mx0, my0, back], [mx1, my1, back + 0.4], "throat"))       # far wall
    # silver mount bevel at the lip
    b = 0.4
    els.append(box([mx0, my0, dz - 0.55], [mx0 + b, my1, dz + 0.02], "mount"))
    els.append(box([mx1 - b, my0, dz - 0.55], [mx1, my1, dz + 0.02], "mount"))
    els.append(box([mx0, my1 - b, dz - 0.55], [mx1, my1, dz + 0.02], "mount"))
    els.append(box([mx0, my0, dz - 0.55], [mx1, my0 + b, dz + 0.02], "mount"))
    # the sensor against the far wall — wider than tall, screen-glint texture
    els.append(box([CX - 2.4, CY - 1.7, back + 0.35], [CX + 2.4, CY + 1.7, back + 0.7], "screen"))
    # gold contact fingers on the inside of the bottom bevel
    els.append(box([CX - 1.7, my0 + 0.3, dz - 0.7], [CX + 1.7, my0 + 0.7, dz - 0.2], "gold"))
    return els


def _band(els, half, z, thick, tile, proud=0.15):
    """A control ring / collar around the barrel, slightly proud of it."""
    h = half + proud
    els.append(box([CX - h, CY - h, z], [CX + h, CY + h, z + thick], tile))


def _hood(els, z, base_half, steps, depth, flare):
    """A HOLLOW flared lens hood: `steps` rings (each 4 rails, so you can see the glass
    down inside) widening evenly from base_half to base_half + flare over `depth`.
    Returns the front z."""
    dz = depth / steps
    for i in range(steps):
        w = base_half + flare * (i + 1) / steps
        inn = base_half - 0.35 + flare * i / steps          # opening widens with the flare
        thick = dz * (1.06 if i < steps - 1 else 0.6)
        for a, b, c, d in ((-w, -inn, -w, w), (inn, w, -w, w),
                           (-inn, inn, inn, w), (-inn, inn, -w, -inn)):
            els.append(box([CX + a, CY + c, z], [CX + b, CY + d, z + thick], "hood"))
        z += dz
    return z


def lens_elements(spec):
    ln, hf = spec["len"], spec["half"]
    barrel = "white" if spec.get("white") else "black"
    accent = "red" if spec.get("white") else "gold"
    zoom = spec.get("zoom")
    els, z = [], ZFRONT

    mc = max(hf + 0.3, 3.75)                                    # collar always covers the body's mount hole
    els.append(box([CX - mc, CY - mc, z - 0.9], [CX + mc, CY + mc, z + 0.25], "black"))  # black mount flange (was grey and bordered the body on the narrower lenses)
    z += 0.25
    _band(els, hf, z, 0.35, accent, 0.08)                       # maker / colour ring at the mount
    z += 0.35

    nseg = 3 if ln >= 7.0 else 2
    room = 0.7 + (0.9 if zoom else 0.0)                         # focus + (zoom) rings
    seg = max(0.6, (ln - 0.3 - room) / nseg)

    els.append(box([CX - hf, CY - hf, z], [CX + hf, CY + hf, z + seg], barrel)); z += seg
    if zoom:
        _band(els, hf, z, 0.9, "smooth", 0.18); z += 0.9        # ribbed zoom ring, proud
    els.append(box([CX - hf, CY - hf, z], [CX + hf, CY + hf, z + seg], barrel)); z += seg
    _band(els, hf - 0.05, z, 0.7, "smooth", 0.16); z += 0.7     # ribbed focus ring
    if nseg == 3:
        els.append(box([CX - hf, CY - hf, z], [CX + hf, CY + hf, z + seg], barrel)); z += seg

    if zoom:                                                    # AF/MF switch panel on the side
        sy = CY - hf * 0.15
        els.append(box([CX - hf - 0.55, sy - 0.9, ZFRONT + 1.5], [CX - hf + 0.15, sy + 0.9, ZFRONT + 3.5], "smooth"))

    if spec.get("foot"):                                        # tripod collar + foot on the barrel
        cz = ZFRONT + min(ln * 0.30, 4.6)                       # near the rear third, capped
        _band(els, hf, cz - 0.7, 1.4, "smooth", 0.30)                                    # rotating collar
        els.append(box([CX - 1.2, CY - hf - 2.2, cz - 1.0], [CX + 1.2, CY - hf + 0.2, cz + 1.4], "smooth"))  # arm
        els.append(box([CX - 2.1, CY - hf - 3.3, cz - 1.5], [CX + 2.1, CY - hf - 2.1, cz + 1.9], "black"))   # foot plate

    hf2 = hf - 0.25
    els.append(box([CX - hf2, CY - hf2, z], [CX + hf2, CY + hf2, z + 0.4], barrel)); z += 0.4
    if spec.get("white"):
        _band(els, hf2, z, 0.28, accent, 0.12); z += 0.28

    # --- front element + hood (every lens) -----------------------------------
    # The glass sits deep INSIDE the hood: the flared rings run on well past the
    # glass front (~1 unit of overhang) so the hood clearly shades the element
    # rather than sitting flush with it. Rings hug the glass edge so there's no
    # hollow-tube gap along the length.
    wide = spec.get("bulb")
    big = spec.get("white") or ln >= 5.0
    gdepth = 0.75 if wide else (1.85 if big else 1.15)   # flat-pane (glass) depth
    fl = 1.9 if wide else (1.7 if big else 0.9)          # hood flare
    lip = 2.0 if wide else (2.3 if big else 2.2)         # hood length PAST the glass front
    dm = 0.2 if wide else 0.4
    bump = 0.18 if wide else 0.32                        # dome protrusion past the flat pane
    front = z + gdepth
    hood_len = gdepth + lip
    els.append(box([CX - hf2, CY - hf2, z], [CX + hf2, CY + hf2, front], "glass"))           # flat pane
    els.append(box([CX - hf2 + dm, CY - hf2 + dm, front - gdepth * 0.5],
                   [CX + hf2 - dm, CY + hf2 - dm, front + bump], "glass"))                    # domed centre, proud of the pane (no coplanar faces -> no moire)
    dz = hood_len / 3
    for i in range(3):
        w = hf2 + fl * (i + 1) / 3
        inn = hf2 + 0.06 + i * 0.14                                                          # opening flares ring-by-ring (no shared lip plane between rings)
        thick = dz * (1.05 if i < 2 else 0.7)
        zi = z + i * dz
        for a, b, c, e in ((-w, -inn, -w, w), (inn, w, -w, w),
                           (-inn, inn, inn, w), (-inn, inn, -w, -inn)):
            els.append(box([CX + a, CY + c, zi], [CX + b, CY + e, zi + thick], "hood"))
    z += hood_len
    return els


LENSES = {
    "lens_14mm":      dict(len=3.2, half=2.95, hood=True, bulb=True),
    "lens_24mm":      dict(len=3.8, half=2.75, hood=True),
    "lens_35mm":      dict(len=4.2, half=2.70, hood=True),
    "lens_50mm":      dict(len=4.6, half=2.60, hood=True),
    "lens_85mm":      dict(len=5.6, half=2.65, hood=True),
    "lens_135mm":     dict(len=6.8, half=2.60, hood=True),
    "lens_16_35mm":   dict(len=5.2, half=2.85, hood=True, zoom=True),
    "lens_24_70mm":   dict(len=6.4, half=2.90, hood=True, zoom=True),
    "lens_70_200mm":  dict(len=9.2, half=3.10, hood=True, zoom=True, white=True, foot=True),
    "lens_100_400mm": dict(len=11.5, half=3.20, hood=True, zoom=True, white=True, foot=True),
    "lens_200_600mm": dict(len=14.5, half=3.30, hood=True, zoom=True, white=True, foot=True),
}


def write_model(name, extra):
    m = {"gui_light": "side",
         "textures": {"1": "realcamera:item/camera_body_lens",
                      "particle": "realcamera:item/camera_body_lens"},
         "elements": body_elements() + extra,
         "display": DISPLAY}
    json.dump(m, open(os.path.join(MI, name + ".json"), "w"), indent=2)


def main():
    build_atlas()

    write_model("camera_body", bare_front())

    cases = []
    for name, spec in LENSES.items():
        write_model("camera_body_" + name, lens_elements(spec))
        cases.append({"when": name, "model": {"type": "minecraft:model",
                                              "model": "realcamera:item/camera_body_" + name}})
    json.dump({"model": {
        "type": "minecraft:select",
        "property": "minecraft:custom_model_data",
        "index": 0,
        "cases": cases,
        "fallback": {"type": "minecraft:model", "model": "realcamera:item/camera_body"},
    }}, open(os.path.join(IT, "camera_body.json"), "w"), indent=2)

    # creative camera: always wears the 24-70, plus a thin purple accent along the deck
    purple = box([3.2, 13.05, 9.7], [13.2, 13.24, 10.5], "purple")
    write_model("creative_camera", lens_elements(LENSES["lens_24_70mm"]) + [purple])
    json.dump({"model": {"type": "minecraft:model", "model": "realcamera:item/creative_camera"}},
              open(os.path.join(IT, "creative_camera.json"), "w"), indent=2)

    print("wrote camera_body (bare + sensor) +", len(LENSES), "lensed +",
          "creative_camera + atlas")


if __name__ == "__main__":
    main()
