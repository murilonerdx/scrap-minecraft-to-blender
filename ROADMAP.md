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
  Blender scene: the scene geometry + **every nearby entity animating** (mobs: limbs + world path;
  **boats/minecarts/dropped items/item frames: world path**) + sun + sky + an **animated POV camera**
  that follows your eye, so you can render exactly what you saw.
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
- ✅ **NLA stacking** — multi-clip files (animation library, takes) import with each clip laid out as
  its own **NLA strip/track** for non-linear blending/reordering; the writer de-duplicates colliding
  clip names so each is a distinct Action, and a **Stack clips as NLA** button re-applies it.
- **Event timeline** — during a cinematic, **block breaks/placements** (`BlockEvent`) and **sounds**
  (`PlaySoundEvent`: footsteps, hits, music) are recorded as timestamped events, exported as
  `events.json` + `events.csv` and served at `/events`. The add-on drops a **timeline marker** per
  event so you can sync VFX and audio to them.
- ✅ **Sound → Speakers** — recorded sound events are also exported as positioned `Speaker_<sound>`
  nodes (deduped per spot, sound id/time/gain in extras); the add-on builds a Blender **Speaker** object
  at each for spatial audio in the VSE.
- **Multi-camera rig** — `scene`/`snapshot`/cinematic export **preset render cameras** (four 3/4 orbit
  views + a top-down) alongside the in-game POV camera, so you can switch angles in Blender without
  re-framing. They're extra nodes, so they never disturb the rig or the animated POV camera.
- **Render passes ready** — on import the add-on gives every object a unique **Object Index** and turns
  on the **Z/depth, mist, normal and object-index** compositor passes, so the scene is set up for
  advanced compositing out of the box.
- **Day/night timelapse** — a cinematic samples the **sun (direction/color/intensity) and sky colour**
  every keyframe; the add-on keyframes the Blender **Sun lamp and World background** over the recording
  (`/sun` + `sun.json`), so the lighting sweeps from day to dusk to night as it did in-game.
- **Player animation library** — `/recorte export animlib` bakes **idle / walk / run / sneak** as four
  reusable, looping **glTF animations on one rig** (driven through the model's own `setupAnim`), so you
  get ready-made Actions to reuse on the player in Blender. The glTF writer now supports many named
  clips in a single file.
- ✅ **Takes** — `/recorte take start [name]` … `stop` records the same subject repeatedly on one shared
  rig (sampled by the common `PoseSampler`); `take export` writes every take as one multi-clip glTF (one
  Action each, sharing the rig origin) so you can compare and keep the best.
- ✅ **Time remap / slow-mo** — `/recorte slowmo <1-16>` samples the next recordings/takes/cinematics N×
  denser and tags them `timeScale=N`; the writer stretches every keyframe time ×N for smooth N× slow
  motion at 30 fps.
- ✅ **Retarget rig** — `/recorte export retarget` names the player's bones with Mixamo-compatible
  humanoid labels (Hips/Spine/Head/arms/legs) for one-click retargeting in Blender; every export also
  carries the humanoid label + original MC bone name in node `extras`.
- **Resource-pack PBR (LabPBR)** — when the active pack ships them, scene materials export the `_n`
  **normal map** (glTF `normalTexture`) and the `_s` **specular** repacked into a glTF
  **metallic-roughness** texture (LabPBR smoothness→roughness, F0→metalness), so blocks get real bumpy,
  metallic, rough/smooth shading in Blender (no-op on vanilla).
  *(If the bump looks inverted, flip the normal map's green channel — the usual OpenGL/DirectX gotcha.)*
- **Cape / elytra as a separate object** — the cloak/elytra is captured into its own **"Cape"** Blender
  object (correct rendered orientation), so you can rig and animate the cloth on its own.

## 🔜 Big next steps
- **Real-time link phase C**: true streaming (WebSocket/SSE) of bone transforms so it's frame-accurate,
  not ~1×/s.
- **Particle/VFX capture**: record particle systems (fire, portal, explosions) as animated point clouds.
- **In-game preview**: a render of the model/scene inside the control panel before exporting.
- ✅ **Batch a mod's entities**: `export mod` now also exports every one of the mod's entities (rigged
  or captured) into an `entities/` folder, plus a `manifest.json` summarising the batch.
- ✅ **Block entities in scenes**: chests, signs, banners, beds, bells, shulker boxes… are rendered via
  their `BlockEntityRenderer` and folded into `scene`/`snapshot` as a `BlockEntities` object.
- ✅ **All entities in snapshots**: `snapshot`/live now capture **every** entity, not just living ones —
  item frames, paintings, boats, minecarts, dropped items, armor stands… (mobs rigged, the rest captured).

## 🦴 Animation
- **Export animation cycles** (idle/walk/attack) as glTF animation: sample `setupAnim` over time and
  write per-bone keyframes → the model **moves** in Blender.
- **Record a live animation**: capture an entity's pose over N real ticks → whatever the mob does
  in-game becomes a baked animation.
- **Block-entity animations** (chest opening, gates, conduit, beacon).
- **Held-item / first-person animation** (swing, use, block).
- **GeckoLib animations** (read the `.animation.json`).

## ✨ VFX / Particles
- ✅ **Export particles** (fire, smoke, portal, redstone, potions, totem) as a **point cloud** — every
  live particle is captured by `scene`/`snapshot` into a glTF **POINTS** primitive (`Particles` object)
  with per-point position + colour (`COLOR_0`) → instance a billboard/volume on each point in **Geometry
  Nodes**. Next: capture the cloud **over time** for animated trails.
- **Capture an effect over time** → animated VFX (particle trails).
- ✅ **Beacon beam** — an active beacon's beam exports as a tall emissive cross of billboard quads
  (bright core + outer glow), coloured by the stained glass above it, in a `Beams` object. Next:
  **end gateway**, dragon breath, explosions.
- ✅ **Fluid surfaces** (water/lava) — the exposed **top surface AND vertical sides** (waterfalls, pool
  edges) are emitted with the fluid's still sprite at the real fluid height, biome-tinted (water) or
  emissive (lava), face-shaded, reusing the animated-texture frames. Next: per-corner slope + flow dir.

## 💡 Lighting & environment
- Export **sky/fog/biome color + sun angle** → recreate the in-game look in Blender (sun + world).
- ✅ **Block light / sky light** baked into vertex colors (+ face shading) to match the game's lighting.
- ✅ **Weather** — rain/snow exports as a **precipitation point cloud** (`Weather` object) filling the
  scene volume, rain vs snow by biome, density by storm strength → instance + animate in Geometry Nodes.
- ✅ **Sky dome + clouds** — a vertex-coloured gradient `Sky` dome (live sky colour → hazy horizon) +
  a procedural self-lit `Clouds` layer at the cloud height enclose the scene as renderable geometry.
  Next: stars at night, real clouds.png pattern.
- ✅ **Camera** + ✅ **Sun light** + ✅ **Sky color** (the add-on sets the Blender World from the live
  sky via the `/env` endpoint) → next: proper **fog**, and optional **depth-of-field** on your look target.

## 🧱 More to extract
- ✅ **Arbitrary region** — `/recorte export region <from> <to>` exports the exact box between two
  corners (a whole build), not just a radius around you. Next: NBT structures / `.litematic` import.
- **Signs (text), paintings, item frames, maps** as textured meshes/planes.
- ✅ **Animated textures** (water, lava, fire, portal) — the model uses the correct first frame, the full
  **frame sequence** is written (`*_f000.png` … + `animated_textures.json`), and the **add-on now builds a
  looping Image Sequence** per material (frames served over `/anim_textures` + `/anim_frame`), so water/
  lava actually flow in Blender as the scene plays.
- ✅ **Resource-pack PBR** (LabPBR normal + specular→metallic-roughness); next: connected textures.
- ✅ **Real transparency** — translucent blocks (glass, stained glass, ice, water…) export with glTF
  `alphaMode: BLEND` instead of the MASK cutout, so they're actually see-through in Blender.

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
