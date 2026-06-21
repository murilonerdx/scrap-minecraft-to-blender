# 🎬 Recorte Animation Studio — 20-feature roadmap

Turning Recorte from an exporter into a **Minecraft → Blender animation studio**: place cameras, light
rigs, record takes, capture VFX, and import a render-ready scene. Implemented one per loop iteration,
each verified by `gradlew gltfSelfTest` before it ships. ✅ = done.

| # | Feature | What it gives you |
|---|---------|-------------------|
| 1 | **Point-light rig** ✅ | torches/lanterns/glowstone/sea-lanterns in a scene export as Blender **point lights**, colored by the block — the scene is lit by real lamps, not just emission. |
| 2 | **Placeable cameras** ✅ | `/recorte cam add <name>` drops a named camera at your eye (`cam clear` / `cam list` too); every scene/snapshot/region/cinematic export carries them as named Blender cameras to switch between. |
| 3 | **Depth of field** ✅ | the POV + placed cameras carry a **focus distance** (to whatever you're looking at) + f-stop in their node extras; the add-on turns on Blender DOF so backgrounds blur cinematically. |
| 4 | **Camera path from waypoints** | record a smooth bezier flythrough between placed camera points. |
| 5 | **Camera shake / handheld** | optional procedural shake on the POV camera for a handheld feel. |
| 6 | **Ridden-entity parenting** | a rider on a boat/horse/minecart is parented to the mount, so they move as one. |
| 7 | **Motion trails (onion skin)** | N ghost copies of a moving entity along its path, for animation reference. |
| 8 | **Particle / VFX point cloud** | capture active particles (fire/smoke/portal) as an animated point cloud → Geometry Nodes. |
| 9 | **Beams & lasers** | beacon beams / end-gateway beams as animated geometry. |
| 10 | **Weather plane** | rain/snow as a particle plane over the scene. |
| 11 | **Sky dome + clouds** | the in-game sky/clouds as a Blender world dome. |
| 12 | **Sound emitters → Speakers** | sounds become positioned Blender **Speaker** objects for spatial audio in the VSE. |
| 13 | **Takes** | record several takes; each becomes its own clip to compare/keep. |
| 14 | **NLA stacking** | stack the animation library + recorded clips as NLA strips for non-linear editing. |
| 15 | **Time remap / slow-mo** | record at high sample rate + export a time curve for slow motion. |
| 16 | **Retarget rig** | consistent humanoid bone names (root/hips/spine/arms/legs) for Mixamo-style retargeting. |
| 17 | **Named shot markers** | name shots while recording; they become named Blender timeline markers. |
| 18 | **Export presets** | save/load studio settings (radius, fps, passes, DOF) as named presets. |
| 19 | **In-game preview** | a thumbnail of what you'll export, in the control panel. |
| 20 | **Studio scene template** | the add-on sets up a render-ready Blender scene (camera, color management, output) on import. |

*Every feature compiles, passes the headless glTF self-test, and updates the docs in the same commit.*
