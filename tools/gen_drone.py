#!/usr/bin/env python3
"""Camera Drone item: a small quadcopter model + a 64x64 texture. Pure stdlib.
Central body, 4 arms in a + layout with rotor discs, an under-slung gimbal camera,
skids, and front(green)/rear(red) nav LEDs."""
import zlib, struct, os, math, json

ASSETS = r"E:\Minecraft\dev\photo-mode-mod\src\main\resources\assets\realcamera"
TEX = os.path.join(ASSETS, "textures", "item", "drone.png")
MODEL = os.path.join(ASSETS, "models", "item", "drone.json")
ITEM = os.path.join(ASSETS, "items", "drone.json")

T, N = 16, 128  # 8x8 grid of 16px tiles

def write_png(path, w, h, px):
    def ch(tag, d):
        return struct.pack(">I", len(d)) + tag + d + struct.pack(">I", zlib.crc32(tag + d) & 0xffffffff)
    raw = bytearray()
    for row in px:
        raw.append(0)
        for p in row:
            raw += bytes(p)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + ch(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
                + ch(b"IDAT", zlib.compress(bytes(raw), 9)) + ch(b"IEND", b""))

def hx(s):
    s = s.lstrip("#"); return [int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255]

def shade(c, f):
    return [max(0, min(255, round(c[i] * f))) for i in range(3)] + [255]

PAL = dict(body="#2b2e33", body2="#1e2024", arm="#242629", skid="#16171a",
           rotor="#3a3d42", lens="#14161c", glass="#0a1220", ledg="#3adf6a",
           ledr="#e0402e", top="#33363c", trim="#8a9099")
p = {k: hx(v) for k, v in PAL.items()}

def tile_solid(c):
    return [[list(c) for _ in range(T)] for _ in range(T)]

def tile_rotor():
    g = tile_solid(p["rotor"])
    cx = cy = T / 2
    for y in range(T):
        for x in range(T):
            d = math.hypot(x + .5 - cx, y + .5 - cy)
            a = math.atan2(y - cy, x - cx)
            if d < 7:
                g[y][x] = shade(p["rotor"], 1.0 + 0.35 * math.sin(a * 6) * (d / 7))
            if d > 7:
                g[y][x] = [0, 0, 0, 0]
    g[int(cy)][int(cx)] = list(p["body2"])
    return g

def tile_lens():
    g = tile_solid(p["body2"])
    cx = cy = T / 2
    for y in range(T):
        for x in range(T):
            d = math.hypot(x + .5 - cx, y + .5 - cy)
            if d <= 6.5: g[y][x] = list(shade(p["lens"], 1.3))
            if d <= 5.0: g[y][x] = list(p["glass"])
            if d <= 5.6 and d > 5.0: g[y][x] = list(p["trim"])
    g[5][5] = shade(p["glass"], 3.0)
    return g

SLOT = {
    "body": (0, 0), "body2": (1, 0), "arm": (2, 0), "skid": (3, 0), "top": (4, 0),
    "trim": (5, 0), "ledg": (6, 0), "ledr": (7, 0),
    "rotor": (0, 1), "lens": (1, 1), "glass": (2, 1),
}
TILES = {n: tile_solid(p[n]) for n in ("body", "body2", "arm", "skid", "top", "trim", "ledg", "ledr", "glass")}
TILES["rotor"] = tile_rotor()
TILES["lens"] = tile_lens()

img = [[list(p["body"]) for _ in range(N)] for _ in range(N)]
for tn, (c, r) in SLOT.items():
    tl = TILES[tn]
    for y in range(T):
        for x in range(T):
            if tl[y][x][3]:
                img[r * T + y][c * T + x] = tl[y][x]
write_png(TEX, N, N, img)
print("wrote drone.png")

# ---------------- model ----------------
def uv(name):
    c, r = SLOT[name]
    return [c * 2 + 0.2, r * 2 + 0.2, c * 2 + 1.8, r * 2 + 1.8]

def box(frm, to, faces, rot=None):
    e = {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to],
         "faces": {f: {"uv": uv(t), "texture": "#0"} for f, t in faces.items()}}
    if rot: e["rotation"] = rot
    return e

def all6(t): return {k: t for k in ("north", "south", "east", "west", "up", "down")}

els = []
# central body
els.append(box([5.5, 7.0, 5.5], [10.5, 9.2, 10.5],
               {"up": "top", "down": "body2", "north": "body", "south": "body", "east": "body", "west": "body"}))
els.append(box([6.2, 6.4, 6.2], [9.8, 7.0, 9.8], all6("body2")))          # lower shell
els.append(box([7.4, 9.2, 7.4], [8.6, 9.9, 8.6], all6("trim")))           # GPS puck
# arms (+ layout) + motor + rotor at each end
ARMS = [(1, 0, "ledr"), (-1, 0, "ledg"), (0, 1, "ledg"), (0, -1, "ledg")]
for dx, dz, led in ARMS:
    ex, ez = 8 + dx * 5.0, 8 + dz * 5.0
    x0, z0 = min(8 + dx * 2.2, ex), min(8 + dz * 2.2, ez)
    x1, z1 = max(8 + dx * 2.2, ex), max(8 + dz * 2.2, ez)
    els.append(box([x0 - 0.55 if dz else x0, 7.6, z0 - 0.55 if dx else z0],
                   [x1 + 0.55 if dz else x1, 8.4, z1 + 0.55 if dx else z1], all6("arm")))
    els.append(box([ex - 0.9, 8.2, ez - 0.9], [ex + 0.9, 9.0, ez + 0.9], all6("body2")))   # motor
    els.append(box([ex - 2.6, 9.0, ez - 2.6], [ex + 2.6, 9.35, ez + 2.6],
                   {"up": "rotor", "down": "rotor", "north": "body2", "south": "body2",
                    "east": "body2", "west": "body2"}))                                     # rotor disc
    els.append(box([ex - 0.35, 8.6, ez - 0.35], [ex + 0.35, 8.95, ez + 0.35], all6(led)))  # nav LED
# skids
for dx in (-1, 1):
    els.append(box([8 + dx * 3.2 - 0.4, 4.6, 6.4], [8 + dx * 3.2 + 0.4, 6.6, 9.6], all6("skid")))
    els.append(box([8 + dx * 3.2 - 0.4, 4.6, 5.6], [8 + dx * 3.2 + 0.4, 5.2, 10.6], all6("skid")))
# under-slung gimbal camera (points +Z / forward-down)
els.append(box([7.1, 5.2, 8.6], [8.9, 7.0, 10.0], all6("body")))
els.append(box([7.3, 5.4, 10.0], [8.7, 6.8, 10.6],
               {"south": "lens", "north": "body2", "east": "body2", "west": "body2",
                "up": "body2", "down": "body2"}))
els.append(box([7.5, 5.6, 10.55], [8.5, 6.6, 10.75], all6("glass")))

DISPLAY = {
    "gui": {"rotation": [40, 35, 0], "translation": [0, 0.5, 0], "scale": [0.66, 0.66, 0.66]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
    "fixed": {"rotation": [20, 0, 0], "translation": [0, 0, -5], "scale": [0.9, 0.9, 0.9]},
    "thirdperson_righthand": {"rotation": [30, 160, 0], "translation": [0, 3.2, 0.8], "scale": [0.5, 0.5, 0.5]},
    "thirdperson_lefthand": {"rotation": [30, 200, 0], "translation": [0, 3.2, 0.8], "scale": [0.5, 0.5, 0.5]},
    "firstperson_righthand": {"rotation": [25, 170, 0], "translation": [0.6, 2.4, 1.6], "scale": [0.42, 0.42, 0.42]},
    "firstperson_lefthand": {"rotation": [25, 190, 0], "translation": [0.6, 2.4, 1.6], "scale": [0.42, 0.42, 0.42]},
    "head": {"rotation": [0, 0, 0], "translation": [0, 13, 0], "scale": [1.0, 1.0, 1.0]},
}

with open(MODEL, "w") as f:
    json.dump({"gui_light": "side", "textures": {"0": "realcamera:item/drone", "particle": "realcamera:item/drone"},
               "elements": els, "display": DISPLAY}, f, indent=2)
with open(ITEM, "w") as f:
    json.dump({"model": {"type": "minecraft:model", "model": "realcamera:item/drone"}}, f, indent=2)
print("wrote drone model -", len(els), "elements")
