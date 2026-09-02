# Realistic Digital Camera

A Fabric mod that adds a working in-game camera with a full **manual photo mode** —
aperture, shutter speed, ISO, focus point, real depth of field, lenses, filters and a
tripod. Built for taking screenshots that look like photographs.

Craft a camera body at the **Camera Workbench**, fit one of eleven lenses (14 mm up to
200–600 mm) and an optional filter, then shoot handheld or from a tripod. Photos are
written to `.minecraft/photos/`.

## Features

- **Manual exposure** — aperture, shutter speed, ISO and exposure compensation, with a
  live light meter and RGB histogram.
- **Depth of field** — physically based, with a picked focus point and a real focal
  plane. Works with vanilla rendering and with shader packs.
- **Lenses & filters** — 6 primes and 5 zooms that each set the usable zoom range
  (primes lock it); ND, polarizer and mist filters. Zooms with a variable maximum
  aperture behave like the real thing as you zoom in.
- **Film looks** — a set of built-in grades plus three editable custom recipes:
  film-sim base, dynamic range, highlight/shadow tone, colour, split-toning, grain and
  fade.
- **Tripod** — a placeable stand for locked-off shots; the mounted camera shows the
  lens that's installed and points the way you were facing when you set it down.
- **Capture** — long exposure, exposure bracketing, composition grids, aspect-ratio
  framing guides, and output up to 4K with supersampling.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Download the mod `.jar` from the [Releases](../../releases) page.
3. Drop it, along with [Fabric API](https://modrinth.com/mod/fabric-api), into your
   `mods/` folder.
4. Optional: add **Mod Menu** + **Cloth Config** for the in-game settings screen, and
   **JEI** to browse the Camera Workbench recipes.

## Controls

Right-click a camera item to enter photo mode. Then:

| Key | Action |
|---|---|
| `Tab` | open / close the settings panel |
| Scroll | zoom |
| `F` | pick the focus point |
| Left click | take the photo |
| Right click | exit |

## Building

Needs JDK 25.

```
./gradlew build
```

The jar is written to `build/libs/`.

## License

[MIT](LICENSE).
