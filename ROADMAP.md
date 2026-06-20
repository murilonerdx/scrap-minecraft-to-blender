# Roadmap — scrap-minecraft-to-blender

Ideas to grow Recorte into a complete **Minecraft → Blender** bridge, focused on **animation, VFX and
cinematics**. ✅ marks what already exists. (Versão em português abaixo / Portuguese version below.)

## ✅ Already works
- Player (body, skin, 2nd layer, **armor + Curios/Artifacts**) with a skeleton.
- Mobs with bones (vanilla) + capture fallback (GeckoLib).
- Items, blocks and a **whole mod** in batch.
- **Scene** (world diorama) and **Snapshot** (scene + rigged mobs) with culling, tint and emission.
- Skinned multi-object glTF + OBJ, per-sprite textures, vertex colors.
- **In-game control panel** (key `G`) — buttons for every export mode + record toggle, with radius and
  id fields. No commands needed.
- **Live animation recording** (key `R`) → keyframed glTF animation, including **world movement** (the
  mob travels its real path, not just limbs in place).
- **Cinematic scene recording** (`/recorte record scene start|stop`) → the whole moment in one animated
  Blender scene: the scene geometry + **every nearby rigged mob animating** (limbs + world path) + sun +
  sky + an **animated POV camera** that follows your eye, so you can render exactly what you saw.
- **Blender add-on + HTTP bridge** (real-time link *phase A*): one-click "Import latest from Minecraft"
  pulls the newest export from the running game (`blender_addon/recorte_import.py`) and sets the World
  background to the live in-game sky color (`/env`).
- **Real-time link *phase B* (live)**: `/recorte live` makes the mod auto-export the target ~1×/s; the
  add-on's "Live link" watches `/gen` and auto-reimports — you play, Blender updates.
- **Camera export** — `scene`/`snapshot` include a glTF camera framed to your in-game view (position
  + FOV), so the diorama opens already lined up with your shot.
- **Sun light** — `scene`/`snapshot` include a directional light (`KHR_lights_punctual`) colored and
  angled by the in-game time of day (warm at dusk, blue at night). Blender imports it as a Sun lamp.
- **Baked world lighting** — `scene`/`snapshot` bake **block + sky light** and Minecraft's per-face
  shading into vertex colours, so the diorama already looks lit like the game (no manual relighting).
- **Smooth 30 fps recording** — both recorders sample on **render frames** with partial-tick
  interpolation (positions/yaw via `Mth.lerp`/`rotLerp`), so motion is smooth, not stepped at 20 Hz.
  The add-on pulls the animation from the NLA onto the active Action and sets the scene to 30 fps, so
  the **keyframes show up in the Timeline / Dope Sheet ready to edit** (plus a "Show animation keys"
  button for manual imports).
- **Event timeline** — during a cinematic, **block breaks/placements** (`BlockEvent`) and **sounds**
  (`PlaySoundEvent`: footsteps, hits, music) are recorded as timestamped events, exported as
  `events.json` + `events.csv` and served at `/events`. The add-on drops a **timeline marker** per
  event so you can sync VFX and audio to them.
- **Multi-camera rig** — `scene`/`snapshot`/cinematic export **preset render cameras** (four 3/4 orbit
  views + a top-down) alongside the in-game POV camera, so you can switch angles in Blender without
  re-framing. They're extra nodes, so they never disturb the rig or the animated POV camera.
- **Render passes ready** — on import the add-on gives every object a unique **Object Index** and turns
  on the **Z/depth, mist, normal and object-index** compositor passes, so the scene is set up for
  advanced compositing out of the box.

## 🔜 Big next steps
- **Real-time link phase C**: true streaming (WebSocket/SSE) of bone transforms so it's frame-accurate,
  not ~1×/s.
- **Particle/VFX capture**: record particle systems (fire, portal, explosions) as animated point clouds.
- **In-game preview**: a render of the model/scene inside the control panel before exporting.
- **Batch a mod's entities**: `export mod` currently does items+blocks; add all the mod's mobs.

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
- ✅ **Block light / sky light** baked into vertex colors (+ face shading) to match the game's lighting.
- Skybox, clouds, stars.
- ✅ **Camera** + ✅ **Sun light** + ✅ **Sky color** (the add-on sets the Blender World from the live
  sky via the `/env` endpoint) → next: proper **fog**, and optional **depth-of-field** on your look target.

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
