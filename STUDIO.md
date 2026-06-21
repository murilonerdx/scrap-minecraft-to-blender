# 🎬 Recorte Animation Studio — 20-feature roadmap

Turning Recorte from an exporter into a **Minecraft → Blender animation studio**: place cameras, light
rigs, record takes, capture VFX, and import a render-ready scene. Implemented one per loop iteration,
each verified by `gradlew gltfSelfTest` before it ships. ✅ = done.

| # | Feature | What it gives you |
|---|---------|-------------------|
| 1 | **Point-light rig** ✅ | torches/lanterns/glowstone/sea-lanterns in a scene export as Blender **point lights**, colored by the block — the scene is lit by real lamps, not just emission. |
| 2 | **Placeable cameras** ✅ | `/recorte cam add <name>` drops a named camera at your eye (`cam clear` / `cam list` too); every scene/snapshot/region/cinematic export carries them as named Blender cameras to switch between. |
| 3 | **Depth of field** ✅ | the POV + placed cameras carry a **focus distance** (to whatever you're looking at) + f-stop in their node extras; the add-on turns on Blender DOF so backgrounds blur cinematically. |
| 4 | **Camera path from waypoints** ✅ | `/recorte cam path <seconds>` builds a smooth **flythrough** (Catmull-Rom position + slerped rotation) sweeping the POV camera through your placed cameras, exported as an animated camera over the scene. |
| 5 | **Camera shake / handheld** ✅ | `/recorte cam shake <0-10>` layers a bounded procedural shake (position + rotation) onto the cinematic POV and camera-path animation for a hand-held feel. |
| 6 | **Ridden-entity parenting** ✅ | in a cinematic, a rider on a boat/horse/minecart has its root bone **re-parented onto the mount** (keyed relative), so they move as one and editing the mount in Blender moves the rider. |
| 7 | **Motion trails (onion skin)** ✅ | `/recorte ghost add` snaps a faded **ghost** of the entity at its current pose/position (move it, snap, repeat); `ghost export` writes them all in one glTF, fading oldest→newest — an animation reference. |
| 8 | **Particle / VFX point cloud** ✅ | `scene`/`snapshot` capture every live particle (fire/smoke/portal/redstone…) as a glTF **POINTS** cloud (`Particles` object) — each point carries its colour as `COLOR_0`, ready to feed **Geometry Nodes** (instance a billboard/volume on each point) for render-quality VFX. |
| 9 | **Beams & lasers** ✅ | an active **beacon's beam** is captured by `scene`/`snapshot` as a tall **emissive cross** of billboard quads (bright core + faint outer glow) in a `Beams` object, coloured by the stained glass above it — real glowing light shafts in Blender. (Next: end-gateway beams.) |
| 10 | **Weather plane** ✅ | when it's raining/snowing, `scene`/`snapshot` fill the scene volume with a **precipitation point cloud** (`Weather` object) — rain (blue-grey) or snow (white, by biome), density from the rain strength; instance a streak/flake per point in Geometry Nodes and animate it falling. |
| 11 | **Sky dome + clouds** ✅ | `scene`/`snapshot` enclose the scene in a vertex-coloured **sky dome** (gradient from the live sky colour at the zenith to a hazy horizon, normals inward) plus a procedural **`Clouds`** layer (soft self-lit, at the dimension's cloud height) — a visible, renderable sky, not just a World background. |
| 12 | **Sound emitters → Speakers** ✅ | every sound played during a **cinematic** recording is exported as a positioned `Speaker_<sound>` node (deduped per spot, earliest time, with sound id/time/gain in extras); the add-on turns each into a Blender **Speaker** object for spatial audio in the VSE. |
| 13 | **Takes** ✅ | `/recorte take start [name]` … `stop` records the same subject repeatedly on one shared rig; each take is a named clip. `take export` writes them all as one multi-clip glTF (one Blender Action per take, sharing the rig origin) to compare and keep the best. |
| 14 | **NLA stacking** ✅ | multi-clip files (animation library, takes) import with each clip laid out as its own **NLA strip/track** for non-linear blending/reordering (single recordings still activate for editing); the writer de-duplicates colliding clip names so each is a distinct Action, and a **Stack clips as NLA** button re-applies it to manual imports. |
| 15 | **Time remap / slow-mo** ✅ | `/recorte slowmo <1-16>` records every clip (recordings, takes, cinematics) **N× more densely** and tags it `timeScale=N`; the writer stretches all keyframe times ×N, so the action plays back N× slower at 30 fps — smooth slow motion straight out of the game. |
| 16 | **Retarget rig** ✅ | `/recorte export retarget` exports the player with **Mixamo-compatible humanoid bone names** (Hips / Spine / Head / Left·RightArm / Left·RightUpLeg) so the armature retargets to/from other humanoid rigs in Blender (Rokoko, Auto-Rig Pro, Rigify) in one click; every export also carries the humanoid label + original MC name in node `extras`. |
| 17 | **Named shot markers** ✅ | `/recorte shot <name>` during a cinematic tags a cut point at the current time; it exports on the event timeline and the add-on drops a clean named marker (`🎬 Intro`), plus a `shots.csv` (name/time/frame) for your editor / shot board. |
| 18 | **Export presets** ✅ | `/recorte preset save\|load\|list <name>` stores studio settings (default radius, slow-mo, camera shake, fps, DOF) as named JSON files under `recorte_exports/presets/`; loading one re-applies the look (slow-mo + shake take effect immediately, the radius becomes the default). |
| 19 | **In-game preview** ✅ | the control panel (key `G`) draws a live **top-down thumbnail** of the scene/snapshot footprint — surface map colours, height-shaded, with red entity dots and the player at centre + an `r<radius> · <n> ent · rain` readout — updating as you change the radius, so you see what you'll capture before you export. |
| 20 | **Studio scene template** | the add-on sets up a render-ready Blender scene (camera, color management, output) on import. |

*Every feature compiles, passes the headless glTF self-test, and updates the docs in the same commit.*
