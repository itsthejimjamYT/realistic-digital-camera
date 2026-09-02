#!/usr/bin/env python3
"""Camera Workbench block assets + all recipe / loot-table JSON for the gear system.

    python tools/gen_workbench_and_recipes.py
"""
import json
import os
import struct
import zlib

RES = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources")
A = os.path.join(RES, "assets", "realcamera")
D = os.path.join(RES, "data", "realcamera")


def wj(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(obj, f, indent=2)


# ---------------------------------------------------------------- block texture
def png16(path, pixels):
    S = 16
    raw = bytearray()
    for y in range(S):
        raw.append(0)
        for x in range(S):
            raw += bytes(pixels[y * S + x])

    def ch(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(ch(b"IHDR", struct.pack(">IIBBBBB", S, S, 8, 6, 0, 0, 0)))
        f.write(ch(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(ch(b"IEND", b""))


def _bt(name, px):
    png16(os.path.join(A, "textures", "block", "camera_workbench_" + name + ".png"), px)


def _px(base):
    return [[base[0], base[1], base[2], 255] for _ in range(16 * 16)]


def _set(px, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        px[y * 16 + x] = [c[0], c[1], c[2], 255]


def _hline(px, x0, x1, y, c):
    for x in range(x0, x1):
        _set(px, x, y, c)


def _rect(px, x0, x1, y0, y1, c):
    for y in range(y0, y1):
        for x in range(x0, x1):
            _set(px, x, y, c)


def workbench_textures():
    STEEL = (58, 60, 66)
    STEEL_HI = (92, 95, 103)
    STEEL_LO = (34, 35, 40)
    RIVET = (108, 112, 120)
    BLACK = (24, 24, 28)

    # ---- side: a brushed-steel cabinet with two drawer seams + handles ----
    s = _px(STEEL)
    for y in range(16):
        for x in range(16):
            if (x * 7 + y * 3) % 11 == 0:                 # faint vertical brushing
                _set(s, x, y, STEEL_HI if (x + y) % 2 else STEEL)
    _hline(s, 0, 16, 0, STEEL_HI)
    _hline(s, 0, 16, 15, STEEL_LO)
    _rect(s, 0, 1, 0, 16, STEEL_LO)
    _rect(s, 15, 16, 0, 16, STEEL_LO)
    for sy in (5, 10):                                    # drawer seams
        _hline(s, 1, 15, sy, STEEL_LO)
        _hline(s, 1, 15, sy + 1, STEEL_HI)
    for hy in (2, 7, 12):                                 # recessed handles
        _rect(s, 5, 11, hy, hy + 2, STEEL_LO)
        _hline(s, 5, 11, hy, BLACK)
    for ry in (3, 8, 13):                                 # rivets
        _set(s, 2, ry, RIVET)
        _set(s, 13, ry, RIVET)
    _bt("side", s)

    # ---- top: a black 7x5 crafting grid, matching the menu layout ----
    LATTICE = (46, 48, 55)      # raised divider metal between cells
    CELLDK = (12, 13, 16)       # recessed black cell you drop an item in
    BRACKET = (202, 202, 196)   # off-white alignment brackets (match the camera body)
    t = _px(LATTICE)
    # 7 columns x 5 rows over the inner 14x14 area (frame is the outer 1px)
    xb = [1 + round(i * 14 / 7) for i in range(8)]        # [1,3,5,7,9,11,13,15]
    yb = [1 + round(j * 14 / 5) for j in range(6)]        # [1,4,7,9,12,15]
    for r in range(5):
        for c in range(7):
            _rect(t, xb[c] + 1, xb[c + 1], yb[r] + 1, yb[r + 1], CELLDK)
            _hline(t, xb[c] + 1, xb[c + 1], yb[r] + 1, (7, 8, 10))   # top inner shadow
    # steel bench-edge frame (ties the top to the sides)
    _hline(t, 0, 16, 0, STEEL_HI)
    _hline(t, 0, 16, 15, STEEL_LO)
    _rect(t, 0, 1, 0, 16, STEEL_HI)
    _rect(t, 15, 16, 0, 16, STEEL_LO)
    # small L-brackets in each inner corner, pointing inward
    for bx, by, sx, sy in ((1, 1, 1, 1), (14, 1, -1, 1), (1, 14, 1, -1), (14, 14, -1, -1)):
        _set(t, bx, by, BRACKET)
        _set(t, bx + sx, by, BRACKET)
        _set(t, bx, by + sy, BRACKET)
    _bt("top", t)

    # ---- bottom: plain steel ----
    b = _px((50, 52, 58))
    _rect(b, 0, 16, 0, 1, STEEL_LO)
    _rect(b, 0, 16, 15, 16, STEEL_LO)
    _rect(b, 0, 1, 0, 16, STEEL_LO)
    _rect(b, 15, 16, 0, 16, STEEL_LO)
    _bt("bottom", b)


# ---------------------------------------------------------------- block json
def block_assets():
    workbench_textures()
    tex = {
        "particle": "realcamera:block/camera_workbench_side",
        "down": "realcamera:block/camera_workbench_bottom",
        "up": "realcamera:block/camera_workbench_top",
        "north": "realcamera:block/camera_workbench_side",
        "south": "realcamera:block/camera_workbench_side",
        "east": "realcamera:block/camera_workbench_side",
        "west": "realcamera:block/camera_workbench_side",
    }
    wj(os.path.join(A, "blockstates", "camera_workbench.json"),
       {"variants": {"": {"model": "realcamera:block/camera_workbench"}}})
    wj(os.path.join(A, "models", "block", "camera_workbench.json"),
       {"parent": "minecraft:block/cube", "textures": tex})
    wj(os.path.join(A, "models", "item", "camera_workbench.json"),
       {"parent": "realcamera:block/camera_workbench"})
    wj(os.path.join(A, "items", "camera_workbench.json"),
       {"model": {"type": "minecraft:model", "model": "realcamera:block/camera_workbench"}})
    wj(os.path.join(D, "loot_table", "blocks", "camera_workbench.json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": "realcamera:camera_workbench"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }],
    })
    tripod_assets()


def tripod_texture():
    """One 16x16 atlas. Rows 0-4 = brushed gunmetal (the stand). Rows 4-10 carry the
    camera swatches picked by the mounted-camera models: black body, grey lens barrel,
    blue front element, off-white barrel. Rows 10-14 = a longer white strip for the big
    tele barrel sides."""
    STEEL = (60, 63, 70)
    px = _px(STEEL)
    for i in range(16 * 16):
        if (i * 5) % 13 == 0:
            px[i] = [72, 75, 83, 255]
    _rect(px, 0, 16, 0, 1, (96, 99, 107))        # bright top edge
    _rect(px, 0, 16, 13, 16, (30, 32, 37))       # dark foot band
    # --- camera swatches (rows 4..10) ---
    _rect(px, 0, 6, 4, 10, (26, 27, 31))         # body: near-black
    _rect(px, 0, 6, 4, 5, (46, 48, 55))          #   top highlight
    _rect(px, 6, 10, 4, 10, (54, 57, 64))        # lens barrel: grey
    _rect(px, 6, 7, 4, 10, (70, 73, 82))         #   barrel highlight rib
    _rect(px, 10, 13, 4, 10, (58, 104, 148))     # front element: blue glass
    _rect(px, 11, 12, 5, 8, (190, 226, 246))     #   glint
    _rect(px, 13, 16, 4, 10, (228, 228, 224))    # white barrel swatch
    # --- long off-white strip (rows 10..13) for the tele barrel ---
    _rect(px, 0, 16, 10, 13, (226, 226, 222))
    for x in range(1, 16, 3):
        _rect(px, x, x + 1, 10, 13, (204, 205, 200))   # faint ribs
    _bt2("tripod", px)


def _bt2(name, px):
    png16(os.path.join(A, "textures", "block", name + ".png"), px)


def _box(a, b, uv, faces=None, rot=None):
    faces = faces or ("north", "south", "east", "west", "up", "down")
    e = {"from": a, "to": b,
         "faces": {f: {"uv": uv, "texture": "#0"} for f in faces}}
    if rot:
        e["rotation"] = rot
    return e


def tripod_assets():
    tripod_texture()
    T = "realcamera:block/tripod"
    METAL = [3, 0, 6, 13]   # a tall gunmetal strip for stand parts

    # --- stand: three legs fanned out on the diagonal so no leg points square-on.
    #     Each leg is ONE solid box tilted 22.5deg (pivot near the top) plugging into a
    #     slim centre column — no overlapping leg geometry and nothing coplanar, so no
    #     z-fighting / moire. The pivot sits low and the bases are pulled in so that,
    #     once the block-entity renderer spins the whole stand 45deg, every foot lands
    #     inside the 1x1 block footprint (feet still reach the ground: y=0). ---
    def leg(cx, cz, axis, angle):
        w = 2.2
        return _box([cx - w / 2.0, -1.32, cz - w / 2.0], [cx + w / 2.0, 18.0, cz + w / 2.0], METAL,
                    rot={"origin": [cx, 16.0, cz], "axis": axis, "angle": angle})

    stand = [
        leg(6.8, 8.2, "z", -22.5),                               # near-left leg  (foot kicks -X)
        leg(9.2, 8.2, "z",  22.5),                               # near-right leg (foot kicks +X)
        leg(8.0, 9.2, "x", -22.5),                               # far leg, under the lens (foot kicks +Z)
        _box([6.25, 17.0, 6.25], [9.75, 22.0, 9.75], METAL),    # slim centre column (legs plug into it)
        _box([6.1, 21.6, 6.1], [9.9, 23.6, 9.9], [0, 0, 4, 3]),  # small mount plate
        _box([7.2, 23.4, 7.2], [8.8, 24.2, 8.8], [2, 0, 4, 2]),  # centre screw disc
    ]

    def cam(barrel_uv, blen):
        """A blocky camera body + lens on the mount plate (~y26.5). blen = barrel length."""
        z0 = 11.0
        return [
            _box([4.4, 26.5, 5.0], [11.6, 30.8, 11.0], [0, 4, 6, 10]),                 # body
            _box([5.0, 30.8, 6.2], [7.6, 31.6, 8.8], [0, 4, 3, 7]),                    # viewfinder hump
            _box([6.5, 27.3, z0], [9.5, 30.3, z0 + blen], barrel_uv),                  # lens barrel (+Z)
            _box([6.9, 27.7, z0 + blen], [9.1, 29.9, z0 + blen + 0.6], [10, 4, 13, 9]),  # front element
        ]

    bare_cam = cam([6, 4, 10, 9], 2.4)
    dark_cam = cam([6, 4, 10, 9], 3.8)
    white_cam = cam([0, 10, 12, 13], 5.0)

    def model(elems):
        return {"parent": "minecraft:block/block",
                "textures": {"0": T, "particle": T}, "elements": elems}

    # model default: the camera's lens points +Z (SOUTH). y-rotation to turn it toward
    # each facing (y:90 sends +Z -> -X): south 0, west 90, north 180, east 270.
    yrot = {"south": 0, "west": 90, "north": 180, "east": 270}
    cam_model = {"none": T + "_cam", "dark": T + "_cam_dark", "white": T + "_cam_white"}
    # Every combo needs an entry, but nothing is ever chunk-rendered (getRenderShape is
    # INVISIBLE — the block-entity renderer draws the stand). The UPPER half + all but the
    # default LOWER combo just map to the bare stand.
    variants = {}
    for half in ("lower", "upper"):
        for mt in ("false", "true"):
            for b in ("none", "dark", "white"):
                m = T if (mt == "false" or half == "upper") else cam_model[b]
                for fac, yr in yrot.items():
                    v = {"model": m}
                    if yr:
                        v["y"] = yr
                    variants["half=%s,mounted=%s,barrel=%s,facing=%s" % (half, mt, b, fac)] = v
    wj(os.path.join(A, "blockstates", "tripod.json"), {"variants": variants})

    wj(os.path.join(A, "models", "block", "tripod.json"), model(stand))
    wj(os.path.join(A, "models", "block", "tripod_cam.json"), model(stand + bare_cam))
    wj(os.path.join(A, "models", "block", "tripod_cam_dark.json"), model(stand + dark_cam))
    wj(os.path.join(A, "models", "block", "tripod_cam_white.json"), model(stand + white_cam))
    wj(os.path.join(A, "models", "item", "tripod.json"), {"parent": "realcamera:block/tripod"})
    wj(os.path.join(A, "items", "tripod.json"),
       {"model": {"type": "minecraft:model", "model": "realcamera:block/tripod"}})
    wj(os.path.join(D, "loot_table", "blocks", "tripod.json"), {
        "type": "minecraft:block",
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "minecraft:item", "name": "realcamera:tripod"}],
            "conditions": [
                {"condition": "minecraft:survives_explosion"},
                # two-tall block: only the LOWER half carries the drop, so breaking
                # the pair (and the updateShape cascade that clears the other half)
                # yields exactly one tripod, never two.
                {"condition": "minecraft:block_state_property",
                 "block": "realcamera:tripod",
                 "properties": {"half": "lower"}},
            ],
        }],
    })


# ---------------------------------------------------------------- recipes
def shaped_vanilla(name, pattern, key):
    wj(os.path.join(D, "recipe", name + ".json"), {
        "type": "minecraft:crafting_shaped",
        "pattern": pattern,
        "key": {k: v for k, v in key.items()},
        "result": {"id": "realcamera:" + name},
    })


def shaped_bench(name, pattern, key, result=None, count=1):
    obj = {
        "type": "realcamera:camera_workbench",
        "pattern": pattern,
        "key": {k: v for k, v in key.items()},
        "result": "realcamera:" + (result or name),
    }
    if count != 1:
        obj["count"] = count
    wj(os.path.join(D, "recipe", name + ".json"), obj)


def recipes():
    # Everything here is deliberately EARLY-GAME reachable: glass, iron/copper/gold,
    # redstone, quartz, amethyst, lapis, diamond, glowstone. No netherite, no boss
    # drops (nether star / echo shard), no monument loot.
    G = "minecraft:glass"
    P = "minecraft:glass_pane"
    I = "minecraft:iron_ingot"
    N = "minecraft:iron_nugget"

    # --- bootstrap: craftable on a normal table so you can make the bench + body ---
    shaped_vanilla("camera_workbench",
                   ["GGG", "GCG", "III"],
                   {"G": G, "C": "minecraft:crafting_table", "I": I})
    shaped_vanilla("camera_body",
                   ["III", "GOG", "IRI"],
                   {"I": I, "G": G, "O": "minecraft:comparator", "R": "minecraft:redstone"})

    Q = "minecraft:quartz"
    CU = "minecraft:copper_ingot"

    # --- lenses: every recipe fills the whole 7x5 bench (a lens is a LOT of glass) ---
    #   row 0 / row 4 : iron "barrel" housing
    #   cols 1-5, rows 1-3 : glass blocks (the elements)
    #   col 6, rows 1-3 : 3 glass panes (the front element / filter thread)
    #   middle row : quartz "optical glass", one more per focal length, growing left
    #                -> right until it reaches the pane on the longest lens.
    # Primes: quartz starts at col 0 (1..6 by focal length).
    def prime(name, nq):
        shaped_bench(name, [
            "IIIIIII",
            "IGGGGGP",
            "Q" * nq + "G" * (6 - nq) + "P",
            "IGGGGGP",
            "IIIIIII",
        ], {"I": I, "G": G, "P": P, "Q": Q})

    prime("lens_14mm", 1)
    prime("lens_24mm", 2)
    prime("lens_35mm", 3)
    prime("lens_50mm", 4)
    prime("lens_85mm", 5)
    prime("lens_135mm", 6)

    # Zooms: a copper column down the left, quartz then starts at col 1 (1..5).
    def zoom(name, nq):
        shaped_bench(name, [
            "IIIIIII",
            "CGGGGGP",
            "C" + "Q" * nq + "G" * (5 - nq) + "P",
            "CGGGGGP",
            "IIIIIII",
        ], {"I": I, "G": G, "P": P, "Q": Q, "C": CU})

    zoom("lens_16_35mm", 1)
    zoom("lens_24_70mm", 2)
    zoom("lens_70_200mm", 3)
    zoom("lens_100_400mm", 4)
    zoom("lens_200_600mm", 5)

    # --- filters: iron-nugget frame + glass panes ---
    shaped_bench("filter_nd8",
                 [" NNNNN ", "NPPPPPN", "NPXXXPN", "NPPPPPN", " NNNNN "],
                 {"N": N, "P": P, "X": "minecraft:black_dye"})
    shaped_bench("filter_polarizer",
                 [" NNNNN ", "NPPPPPN", "NPLLLPN", "NPPPPPN", " NNNNN "],
                 {"N": N, "P": P, "L": "minecraft:lapis_lazuli"})
    shaped_bench("filter_mist",
                 [" NNNNN ", "NPPPPPN", "NPWWWPN", "NPPPPPN", " NNNNN "],
                 {"N": N, "P": P, "W": "minecraft:white_dye"})

    # No creative_camera recipe — it's a creative-mode / testing item only.

    # --- tripod: Camera Workbench, user's layout (mount block on top, column, spread legs) ---
    shaped_bench("tripod", [
        "   B   ",
        "   I   ",
        "  III  ",
        " I I I ",
        " I I I ",
    ], {"I": I, "B": "minecraft:iron_block"})

    # --- drone (user-designed layout) ---
    shaped_bench("drone",
                 ["   I   ",
                  " IUDUI ",
                  "IIDCDII",
                  " IUDUI ",
                  "   I   "],
                 {"I": I, "U": "minecraft:gold_ingot", "D": "minecraft:diamond",
                  "C": "realcamera:camera_body"})


def main():
    for stale in (os.path.join(D, "recipe", "creative_camera.json"),
                  os.path.join(A, "models", "block", "tripod_camera.json")):
        if os.path.exists(stale):
            os.remove(stale)
    block_assets()
    recipes()
    print("wrote block assets + 17 recipes + loot tables")


if __name__ == "__main__":
    main()
