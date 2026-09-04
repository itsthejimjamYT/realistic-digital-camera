# Realistic Digital Camera

A Fabric mod that adds a **fully functional digital camera** to Minecraft (CURRENTLY ONLY VERSION 26.2)
Pick an exposure mode, dial in aperture / shutter / ISO (or let the
camera do it), fit a lens and a filter, choose your focus point, and take a shot that
renders real optical depth of field. Every photo is written out as a PNG on your PC.

## The camera

- **Exposure modes** — **P** (program / auto), **A** (aperture priority), **S** (shutter
  priority) and **M** (full manual), plus an Auto ISO option. A live light meter and RGB
  histogram show what you're metering.
- **Exposure controls** — aperture, shutter speed (1/8000 s to 30 s), ISO, and exposure
  compensation, all in real third-stop steps.
- **Lenses** — 6 primes and 5 zooms, 14 mm to 200–600 mm. Each lens sets the usable zoom
  range; primes lock it. Zooms with a variable maximum aperture lose light as you zoom
  in, like the real thing. Long teles carry a tripod foot.
- **Filters** — ND (long exposures in daylight), circular polarizer, and mist / diffusion.
- **Depth of field** — physically based, driven by focal length and aperture, focused on
  a point you pick from the scene. Works with vanilla rendering and with shader packs.
- **Film looks** — a set of built-in color grades plus three editable custom recipes:
  film-sim base, dynamic range, highlight / shadow tone, color, split-toning, grain and
  a matte fade.
- **Tripod** — a placeable stand for locked-off shots. The mounted camera shows whatever
  lens is fitted and points the way you were facing when you set it down.
- **Composition aids** — aspect-ratio framing guides, rule-of-thirds / golden / centre
  grids, a focus-peaking and clipping-warning overlay.
- **Output** — up to 8K, with up to 4× supersampling, long exposure, and exposure bracketing.

## Crafting

The **camera body** and the **Camera Workbench** itself are crafted at a normal crafting
table. **Lenses, filters, the tripod and the camera drone** are crafted at the **Camera
Workbench**. Install [JEI](https://modrinth.com/mod/jei) to browse the recipes in-game —
its **+** button lays one straight into the bench from your inventory.

### Camera body &amp; workbench &nbsp;·&nbsp; *crafting table*

| Camera Body | Camera Workbench |
| :---: | :---: |
| ![Camera Body recipe](Docs/CameraBodyCraftingRecipe.png) | ![Camera Workbench recipe](Docs/CameraWorkbenchCraftingRecipe.png) |

### Tripod &amp; drone &nbsp;·&nbsp; *Camera Workbench*

| Tripod | Camera Drone |
| :---: | :---: |
| ![Tripod recipe](Docs/TripodCraftingRecipe.png) | ![Drone recipe](Docs/DroneCraftingRecipe.png) |

### Filters &nbsp;·&nbsp; *Camera Workbench*

| ND | Polarizer | Mist |
| :---: | :---: | :---: |
| ![ND filter recipe](Docs/NDFilterCraftingRecipe.png) | ![Polarizer recipe](Docs/PolarizerFilterCraftingRecipe.png) | ![Mist filter recipe](Docs/MistFilterCraftingRecipe.png) |

### Lenses &nbsp;·&nbsp; *Camera Workbench*

<details>
<summary><b>Primes</b> — 14, 24, 35, 50, 85, 135&nbsp;mm</summary>

| 14&nbsp;mm | 24&nbsp;mm | 35&nbsp;mm |
| :---: | :---: | :---: |
| ![14mm recipe](Docs/14mmLensCraftingRecipe.png) | ![24mm recipe](Docs/24mmLensCraftingRecipe.png) | ![35mm recipe](Docs/35mmLensCraftingRecipe.png) |

| 50&nbsp;mm | 85&nbsp;mm | 135&nbsp;mm |
| :---: | :---: | :---: |
| ![50mm recipe](Docs/50mmLensCraftingRecipe.png) | ![85mm recipe](Docs/85mmLensCraftingRecipe.png) | ![135mm recipe](Docs/135mmLensCraftingRecipe.png) |

</details>

<details>
<summary><b>Zooms</b> — 16–35, 24–70, 70–200, 100–400, 200–600&nbsp;mm</summary>

| 16–35&nbsp;mm | 24–70&nbsp;mm | 70–200&nbsp;mm |
| :---: | :---: | :---: |
| ![16-35mm recipe](Docs/16-35mmLensCraftingRecipe.png) | ![24-70mm recipe](Docs/24-70mmLensCraftingRecipe.png) | ![70-200mm recipe](Docs/70-200mmLensCraftingRecipe.png) |

| 100–400&nbsp;mm | 200–600&nbsp;mm | &nbsp; |
| :---: | :---: | :---: |
| ![100-400mm recipe](Docs/100-400mmLensCraftingRecipe.png) | ![200-600mm recipe](Docs/200-600mmLensCraftingRecipe.png) | &nbsp; |

</details>

## Where your photos go

Photos are saved as timestamped `.png` files in a **`photos`** folder inside your
Minecraft game directory:

```
C:\Users\<you>\AppData\Roaming\.minecraft\photos\
```

If you run a custom launcher (Modrinth App, Prism, MultiMC, …), it's the `photos` folder
inside that instance / profile's game folder instead.

**Bracketing** saves each exposure as its own separate file (`..._BRACKET_1of3_...`,
etc.) — the mod does **not** merge them. Combine them yourself in an HDR / photo editor
(Lightroom, Photoshop, Darktable, …).

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Download the mod `.jar` from the [Releases](../../releases) page.
3. Drop it, along with [Fabric API](https://modrinth.com/mod/fabric-api), into your
   `mods/` folder.
4. Optional: **Mod Menu** + **Cloth Config** for the in-game settings screen, and **JEI**
   for the Camera Workbench recipes.

## Controls

Right-click a camera to pick it up. Then:

| Key | Action |
|---|---|
| `Tab` | open / close the settings panel |
| Scroll | zoom |
| `F` | pick the focus point |
| Left click | take the photo |
| Right click | put the camera down |

## Building

Needs JDK 25.

```
./gradlew build
```

The jar is written to `build/libs/`.

## License

[MIT](LICENSE).
