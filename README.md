# scrap-minecraft-to-blender

**🌐 Language:** **English** · [Português](README.pt-BR.md)

[![build](https://github.com/murilonerdx/scrap-minecraft-to-blender/actions/workflows/build.yml/badge.svg)](https://github.com/murilonerdx/scrap-minecraft-to-blender/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen)
![Forge](https://img.shields.io/badge/Forge-47.x-orange)
![Java](https://img.shields.io/badge/Java-17-red)

> **Recorte** — export (almost) anything from Minecraft straight into **Blender**: your character,
> mobs, items, blocks, whole mods, **world scenes** and full **cinematics** — with a **skeleton
> (bones)**, **smooth recorded animations** (editable keyframes), baked **world lighting**, resource-pack
> **PBR**, multiple **cameras**, a **day/night timelapse** and block/sound **timeline markers**, as
> **glTF (`.glb`)** and **OBJ**.

A **client-side** mod for **Minecraft 1.20.1 / Forge**. Press a key (or run a command) in-game and the
files appear ready to import into Blender.

![pipeline](docs/architecture.svg)

> 📸 In-game and Blender screenshots coming soon (see [docs/](docs/)).

---

## ✨ What you can export

| Command | What it does | Bones |
|---|---|:---:|
| `O` (key) or `/recorte export` | the player/mob you're **looking at**, or yourself | ✅ |
| `/recorte export player <name>` | a player by name | ✅ |
| `/recorte export entity <id>` | a **mob** (e.g. `minecraft:zombie`) | ✅ vanilla¹ |
| `/recorte export item <id>` | a 3D **item** model (sword, egg…) | – |
| `/recorte export held` | the item you're **holding** (main hand) — no id needed | – |
| `/recorte export block <id>` | a **block** model | – |
| `/recorte export block` (no id) | the **block/object you're looking at** (chests, machines, signs come with their real state + block entity) | – |
| `/recorte export mod <modid>` | **all** items + blocks **+ entities** of a mod (batch) **+ a `manifest.json` catalog** | ✅ |
| `/recorte export animlib` | a **library of player animations** (idle/walk/run/sneak) on one rig | ✅ |
| `/recorte export retarget` | the player with **Mixamo-compatible humanoid bone names** (Hips/Spine/Head/arms/legs) for retargeting | ✅ |
| `/recorte export scene [radius]` | 🎬 the **surroundings** (a diorama of your build/terrain) | – |
| `/recorte export snapshot [radius]` | 🎬 the **moment**: scene + every nearby **entity** (mobs rigged; item frames, paintings, boats, minecarts, dropped items… captured) | ✅ |
| `/recorte export region <from> <to>` | a precise **box** between two corners (a whole build), framed and lit | – |

¹ Vanilla mobs (`HumanoidModel`/`HierarchicalModel`) export **with bones**. **GeckoLib** mobs fall back
to a static capture (but they still export!).

### 🎥 Record live animation & cinematics

| Command / key | What it does |
|---|---|
| `R` (key) or `/recorte record start` … `stop` | record **one mob/player**: limbs **and** world path → a keyframed glTF animation |
| `/recorte record scene start [radius]` … `stop` | 🎬 **cinematic**: the whole moment — scene + every nearby entity animating (mobs rigged; boats/minecarts/items by world path; **riders parented to their mount**) + **animated POV camera** + sun + sky |
| `/recorte live` | real-time link: the mod auto-exports ~1×/s and the Blender add-on re-imports as you play |
| `/recorte cam add <name>` (· `clear` · `list`) | 🎥 drop a **named camera** at your eye; every scene/snapshot/cinematic carries your placed cameras into Blender |
| `/recorte cam path <seconds>` | 🎬 a smooth **flythrough** sweeping the camera through your placed cameras — an animated camera over the scene |
| `/recorte cam shake <0-10>` | 🤳 layer a hand-held **camera shake** onto recorded/flythrough camera animation (0 = off) |
| `/recorte ghost add` (· `clear` · `export`) | 👻 **onion-skin**: snap faded ghosts of an entity (move, snap, repeat), then export them all fading oldest→newest |
| `/recorte take start [name]` (· `stop` · `export` · `list` · `clear`) | 🎬 **takes**: record one subject repeatedly on a shared rig; each take is a named clip → `take export` writes them all as one multi-clip glTF (one Action each) to compare and keep the best |
| `/recorte slowmo <1-16>` | 🐢 **slow-mo**: the next recordings/takes/cinematics sample N× denser and stretch their keyframe times ×N → smooth N× slow motion at 30 fps (1 = real time) |
| `/recorte shot <name>` | 🎬 **shot marker**: during a cinematic, name a cut point at the current time → a clean named Blender timeline marker (`🎬 <name>`) + a `shots.csv` |
| `/recorte preset save` (· `load` · `list`) `<name>` | 💾 **presets**: save/load studio settings (radius, slow-mo, shake, fps, DOF) as named JSON files — dial a look in once, reload it next session |

Recordings are sampled on **render frames with interpolation (~30 fps)**, so motion is smooth, not
stepped at the 20 Hz tick. The add-on pulls the clip onto the active Action so the **keyframes show in
the Timeline** ready to edit.

**Automatic extras:**
- 🦴 **Skeleton/armature** ready to animate (player and mobs).
- 🎨 **Per-sprite textures** (small — not the whole atlas).
- 💡 **Emission** — lava, glowstone, torches and lanterns **glow** in Blender.
- 🌿 **Biome tints** (grass/leaves/water) baked as **vertex colors**.
- 🔆 **Baked world lighting** — block + sky light and face shading baked into vertex colors, so the
  scene already looks lit like the game.
- 🧱 **Hidden-face culling** + **block entities** (chests, signs, banners, beds…) rendered into scenes.
- 📷 **Multi-camera** — `scene`/`snapshot`/cinematic export the in-game POV camera **plus** preset orbit
  + top-down render cameras, plus any you placed with `/recorte cam add`.
- 🔭 **Depth of field** — the POV/placed cameras focus on what you're looking at (with an f-stop); the
  add-on enables Blender DOF for cinematic background blur.
- ☀️ **Sun** — a directional light angled & colored by the time of day; cinematics animate a full
  **day/night timelapse** (sun + sky).
- 🧩 **Render passes** — the add-on assigns object IDs and enables Z/normal/mist passes for compositing.
- 🪨 **Resource-pack PBR (LabPBR)** — `_n` normal maps + `_s` specular → glTF metallic-roughness.
- 🎚️ **Timeline markers** — block breaks/placements and sounds become Blender markers (+ `events.csv`).
- 🌊 **Animated textures** — water/lava/fire/portal use the correct frame, the full **frame sequence** is
  exported, and the add-on **auto-builds a looping Image Sequence** per material, so they actually flow
  in Blender as the scene plays.
- 💧 **Fluid surfaces** — water and lava (skipped before, since they have no block model) now export
  their **top surface and exposed sides** (waterfalls, pool edges) at the real height, biome-tinted/
  emissive, as a separate `Fluids` object.
- 🪟 **Real transparency** — glass, stained glass, ice and water export as glTF **BLEND** (genuinely
  see-through), while pixel-art cutouts (leaves, grass) stay crisp MASK.
- 👕 Player armor, held items (both hands) and **Curios/Artifacts accessories** (separate `Accessories`
  object); the **cape/elytra** comes in as its own `Cape` object.
- ✨ **Particle / VFX point cloud** — `scene`/`snapshot` capture every live particle (fire, smoke, portal,
  redstone…) as a glTF **point cloud** (`Particles` object); each point keeps its colour, ready to drive
  **Geometry Nodes** (instance a billboard or volume on every point) for render-quality VFX.
- 🔦 **Beacon beams** — an active beacon's beam exports as a tall **emissive cross** (`Beams` object),
  coloured by the stained glass above it, so glowing light shafts rise through your scene in Blender.
- 🌧️ **Weather** — when it's raining/snowing, the scene volume fills with a **precipitation point cloud**
  (`Weather` object): rain (blue-grey) or snow (white) by biome, density from the storm strength —
  instance a streak/flake per point with Geometry Nodes and animate it falling.
- 🌌 **Sky dome + clouds** — `scene`/`snapshot` wrap the scene in a vertex-coloured **`Sky`** dome
  (gradient from the live sky colour down to a hazy horizon) plus a procedural soft **`Clouds`** layer at
  the dimension's cloud height, so you get a visible, renderable sky — not just a World background.
- 🔊 **Sound → Speakers** — every sound played during a cinematic recording exports as a positioned
  `Speaker` node (deduped per spot, with the sound id + time); the add-on builds a Blender **Speaker**
  object at each, ready for spatial audio in the Video Sequence Editor.

---

## 🚀 Install

1. Have **Minecraft 1.20.1** with **Forge 47.x**.
2. Build or download `recorte-0.1.0.jar` (see **Build** below) and drop it into your instance's `mods` folder.
3. That's it. The mod is client-only; works in single-player and on servers.

## 🔨 Build from source

No prerequisites beyond the repo — the Gradle Wrapper downloads everything (including a **JDK 17** via toolchain).

```bash
# Windows
.\gradlew.bat build
# Linux/macOS
./gradlew build
# Output: build/libs/recorte-0.1.0.jar
```

Run a dev client: `./gradlew runClient`

> Note: mixin mods (Oculus/Embeddium) **won't** load in the dev `runClient` due to a mappings mismatch.
> To test alongside those, install the `.jar` into your real instance.

---

## 🎮 Usage

1. Join a world.
2. Press **`G`** for the **control panel** (buttons for every export + record, a **searchable item/block
   browser** (type to filter, click a result to export), **Looked-at block** / **Held item** buttons, plus
   a live **top-down preview thumbnail** of the scene/snapshot footprint), **`O`** to export the
   looked-at/yourself, **`R`** to record an animation, or run a `/recorte export …` command.
3. Files land in:
   ```
   <instance folder>/recorte_exports/<timestamp>_<name>/
     ├── <name>.glb     ← glTF (best): bones + embedded textures
     ├── <name>.obj/.mtl
     └── *.png          ← textures
   ```

### Examples
```
/recorte export entity minecraft:zombie
/recorte export item minecraft:diamond_sword
/recorte export block minecraft:furnace
/recorte export mod artifacts          # items + blocks + entities of the mod
/recorte export animlib                # idle/walk/run/sneak Actions on your rig
/recorte export scene 12
/recorte export snapshot 16
/recorte record scene start 16         # …act in-game… then:
/recorte record scene stop             # → an animated cinematic with POV camera + markers
```

## 🟦 Importing into Blender

- **glTF (recommended):** `File → Import → glTF 2.0` and pick the `.glb`. Comes with the armature and
  materials wired up.
- For crisp **pixel-art** (no blur): set the texture filter to **Closest** in the material.
- **Emission** (lava/glowstone): exposed via `emissiveFactor`/`emissiveTexture` — visible in EEVEE/Cycles.
- **Tints** (grass/water): imported as a *Color Attribute* (`COLOR_0`), multiplied over the base color.
- Scale: 1 unit = 1 block.

## 🔌 Live link — Blender add-on (one-click import)

While the game is running, the mod serves the latest export over `http://127.0.0.1:25599`. Install the
add-on and import without touching files:

1. Blender → *Edit → Preferences → Add-ons → Install…* → pick `blender_addon/recorte_import.py` → enable it.
2. In the 3D viewport press **N** → **Recorte** tab.
3. Export in-game (key `O`), then click **Import latest from Minecraft**.

**Import latest** does more than import: it sets pixel-art (Closest) filtering, pulls recorded
animations onto the active Action so the **keyframes show in the Timeline**, drops **timeline markers**
for block/sound events, keyframes the **day/night timelapse** (Sun lamp + World background), and turns
on the compositor **render passes**. A **Show animation keys** button re-activates animations on any
manually-imported file. For **multi-clip files** (animation library, takes) the first clip is activated
(keys visible) and the rest are kept as **NLA strips** for non-linear blending — a **Stack clips as
NLA** button pushes them all to the NLA. With **Studio scene** on, it also makes the scene
**render-ready** — active camera,
fps/resolution, faithful color management, sky-dome-safe clipping and EEVEE glow (a **Setup studio
scene** button re-applies it).

**Live mode:** run `/recorte live` (or the panel's *Live link* button) in-game and click **Start Live
link** in the add-on — the mod auto-exports a snapshot (**the world around you + nearby entities**)
every ~2s and Blender re-imports it automatically, so the whole scene updates as you play.

---

## 🧠 How it works

```
com.recorte
├── Recorte / client/*         @Mod, keybinds, GUI panel, /recorte command tree
└── export
    ├── Exporter               orchestrates each mode (player/entity/item/block/scene/snapshot/mod/animlib)
    ├── ModelExtractor         player + mobs → skeleton (ModelPart walk) with geometry
    ├── BakedModelExtractor    items/blocks → geometry from the BakedModel + sprite
    ├── SceneExtractor         world blocks + block entities → diorama (culling, baked light, tint, PBR)
    ├── LayerCapturer          captures the REAL render (armor, Curios, cape, GeckoLib mobs)
    ├── Recorder               records ONE entity's animation over time (render-frame sampled)
    ├── SceneRecorder          cinematic: scene + every mob + POV camera + sun/sky timelapse, animated
    ├── Ir                     intermediate representation (bones, materials, mesh, animation, events)
    ├── GltfWriter / ObjWriter  write .glb (skinned, multi-object, multi-clip) and .obj/.mtl
    ├── TextureExporter        reads textures back from the GPU; LabPBR normal/specular maps
    ├── HttpBridge             localhost server for the Blender add-on (latest/env/events/sun)
    └── Convert / ReflectUtil   axis conversion; reflection BY TYPE (survives obfuscation)
```

**Key ideas**
- **Reflection by field *type*** (not name) → the same code works in dev and in the obfuscated game,
  with no access transformer.
- **Capturing the real render** into a `VertexConsumer` → grabs armor, Curios and GeckoLib without
  knowing each rendering system.
- **A single axis conversion**; captured geometry already arrives divided by 16 from the game (a gotcha
  documented in the code).

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to add a new export type.

---

## 🗺️ Roadmap

Animations, VFX/particles, lighting, a Blender add-on and more in **[ROADMAP.md](ROADMAP.md)**.

## 🤝 Contributing

Contributions welcome — see **[CONTRIBUTING.md](CONTRIBUTING.md)**.

## 📄 License

[MIT](LICENSE) © murilonerdx
