# Roadmap — scrap-minecraft-to-blender

Ideas to grow Recorte into a complete **Minecraft → Blender** bridge, focused on **animation, VFX and
cinematics**. ✅ marks what already exists. (Versão em português abaixo / Portuguese version below.)

## ✅ Already works
- Player (body, skin, 2nd layer, **armor + Curios/Artifacts**) with a skeleton.
- Mobs with bones (vanilla) + capture fallback (GeckoLib).
- Items, blocks and a **whole mod** in batch.
- **Scene** (world diorama) and **Snapshot** (scene + rigged mobs) with culling, tint and emission.
- Skinned multi-object glTF + OBJ, per-sprite textures, vertex colors.

## 🦴 Animation
- **Export animation cycles** (idle/walk/attack) as glTF animation: sample `setupAnim` over time and
  write per-bone keyframes → the model **moves** in Blender.
- **Record a live animation**: capture an entity's pose over N real ticks → whatever the mob does
  in-game becomes a baked animation.
- **Block-entity animations** (chest opening, gates, conduit, beacon).
- **Held-item / first-person animation** (swing, use, block).
- **GeckoLib animations** (read the `.animation.json`).

## ✨ VFX / Particles
- **Export particles** (fire, smoke, portal, redstone, potions, totem) as a point cloud / instanced
  mesh with position + color → Geometry/Particle Nodes in Blender.
- **Capture an effect over time** → animated VFX (particle trails).
- **Beacon beam, end gateway, dragon breath, explosions.**
- **Fluid surfaces** (water/lava) with normals/flow and animated texture.

## 💡 Lighting & environment
- Export **sky/fog/biome color + sun angle** → recreate the in-game look in Blender (sun + world).
- **Block light / sky light** baked (vertex color or light probes) to match the game's lighting.
- Skybox, clouds, stars.
- **Camera**: export the player's position/FOV → a Blender camera matching your view.

## 🧱 More to extract
- **Larger regions / schematics** (NBT structures, `.litematic`).
- **Signs (text), paintings, item frames, maps** as textured meshes/planes.
- **Animated textures** (water, lava, fire, portal) exported as image sequences.
- **Resource-pack PBR** (LabPBR: normal/specular) and connected textures.
- **Real transparency** (glass, water) in BLEND mode, not just MASK.

## 🎬 Cinematic / full scene
- **"Cinematic" export**: scene + **rigged** entities + camera + lighting + sky → a Blender scene ready
  to render of a game moment.
- **Timeline**: capture a sequence of frames (mobs walking, water flowing) → a full animation.
- Integration with **replay** mods to export recorded clips.

## 🧰 Workflow & comfort
- **Blender add-on** that imports the export automatically: builds materials (nearest, emission, alpha),
  parents to the armature, fixes scale — one click.
- **In-game GUI** with a **preview** to choose what to export and the options (radius, interiors,
  detail), instead of typing commands.
- **Presets** (low/high detail, with/without interiors, with/without entities).
- Export **all of a mod's mobs** (entities in batch; today only items/blocks).
- An **"open folder"** button after exporting.

## 🏗️ Technical quality
- **Shared atlas** in mod-batch (today each item duplicates its sprite).
- **Merge by material** across objects to reduce draw calls in Blender.
- **Bind pose / T-pose** option vs. current pose.
- Cleaner mob bone names (name by part even in the obfuscated game).

---

Got suggestions? Open an issue. 🙌
