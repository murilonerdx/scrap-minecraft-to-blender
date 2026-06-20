# scrap-minecraft-to-blender

**🌐 Language:** **English** · [Português](README.pt-BR.md)

[![build](https://github.com/murilonerdx/scrap-minecraft-to-blender/actions/workflows/build.yml/badge.svg)](https://github.com/murilonerdx/scrap-minecraft-to-blender/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen)
![Forge](https://img.shields.io/badge/Forge-47.x-orange)
![Java](https://img.shields.io/badge/Java-17-red)

> **Recorte** — export (almost) anything from Minecraft straight into **Blender**: your character,
> mobs, items, blocks, whole mods, and even **world scenes** — with a **skeleton (bones)**, textures,
> emission and biome tints, as **glTF (`.glb`)** and **OBJ**.

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
| `/recorte export block <id>` | a **block** model | – |
| `/recorte export mod <modid>` | **all** items + blocks of a mod (batch) | – |
| `/recorte export scene [radius]` | 🎬 the **surroundings** (a diorama of your build/terrain) | – |
| `/recorte export snapshot [radius]` | 🎬 the **moment**: scene + every nearby mob (**rigged**) | ✅ |

¹ Vanilla mobs (`HumanoidModel`/`HierarchicalModel`) export **with bones**. **GeckoLib** mobs fall back
to a static capture (but they still export!).

**Automatic extras:**
- 🦴 **Skeleton/armature** ready to animate (player and mobs).
- 🎨 **Per-sprite textures** (small — not the whole atlas).
- 💡 **Emission** — lava, glowstone, torches and lanterns **glow** in Blender.
- 🌿 **Biome tints** (grass/leaves/water) baked as **vertex colors**.
- 🧱 **Hidden-face culling** in scenes (interiors aren't exported).
- 📷 **Camera** — `scene`/`snapshot` include a glTF camera framed to your in-game view.
- 👕 Player armor, held item and **Curios/Artifacts accessories** (as a separate `Accessories` object).

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
2. Press **`O`** (rebindable under *Options → Controls → Recorte*) or run a `/recorte export …` command.
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
/recorte export mod artifacts
/recorte export scene 12
/recorte export snapshot 16
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

> This is *phase A* of the real-time link. Phase B will stream bone transforms live (the mob mirrors the
> game in Blender in real time).

---

## 🧠 How it works

```
com.recorte
├── Recorte / client/*         @Mod, keybind, /recorte export command tree
└── export
    ├── Exporter               orchestrates each mode (player/entity/item/block/scene/snapshot/mod)
    ├── ModelExtractor         player + mobs → skeleton (ModelPart walk) with geometry
    ├── BakedModelExtractor    items/blocks → geometry from the BakedModel + sprite
    ├── SceneExtractor         world blocks → diorama (culling, tint, emission)
    ├── LayerCapturer          captures the REAL render (armor, Curios, GeckoLib mobs)
    ├── Ir                     intermediate representation (bones, materials, mesh, color)
    ├── GltfWriter / ObjWriter  write .glb (skinned, multi-object) and .obj/.mtl
    ├── TextureExporter        reads textures back from the GPU → PNG
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
