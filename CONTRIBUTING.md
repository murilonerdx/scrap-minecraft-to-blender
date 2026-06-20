# Contributing

Thanks for your interest in **scrap-minecraft-to-blender** (Recorte)! This guide gets you building and
explains how the exporter is structured so you can add features.

## Dev setup

Requirements: Git and any JDK to launch Gradle (the build provisions its own **JDK 17** toolchain).

```bash
git clone https://github.com/murilonerdx/scrap-minecraft-to-blender.git
cd scrap-minecraft-to-blender
./gradlew build          # -> build/libs/recorte-0.1.0.jar
./gradlew runClient      # a dev Minecraft client
```

- Target: **Minecraft 1.20.1 / Forge 47.x**, official (Mojang) mappings.
- IDE: `./gradlew genIntellijRuns` or `genEclipseRuns`.
- Mixin mods (Oculus/Embeddium) don't load in `runClient` (mappings mismatch) — to test against a real
  modpack, drop the built jar into that instance's `mods` folder.

## Architecture in 60 seconds

Everything funnels through one **intermediate representation** (`export/Ir.java`): a list of **bones**,
**materials**, and **mesh primitives** (vertices with position/normal/uv/color, each bound to one bone).
Producers fill the IR; writers serialize it.

```
Producers (fill the IR)                         Writers (serialize)
- ModelExtractor      player & mobs  (bones)     - GltfWriter  -> .glb (skinned, multi-object)
- BakedModelExtractor items & blocks             - ObjWriter   -> .obj/.mtl
- SceneExtractor      world blocks
- LayerCapturer       real render (armor/Curios/GeckoLib) via a recording VertexConsumer
Exporter orchestrates them; TextureExporter reads textures back from the GPU.
```

Two tricks worth knowing before you touch the producers:
- **Reflection by field _type_** (`ReflectUtil`), never by name — names are obfuscated in the shipped
  game, so we match the unique `List`/`Map`/`Vector3f`/array field instead. This avoids an access transformer.
- **Coordinate conversion** lives in `Convert`. Geometry read straight from `ModelPart` cubes is in
  Minecraft units (÷16 needed); geometry from the **render pipeline** already arrives ÷16 — applying the
  scale twice shrinks everything 256×. Use `Convert.matrix()` vs `Convert.matrixCaptured()` accordingly.

## Adding a new export type

1. Write a producer that returns an `Ir.Model` (or fills one). Reuse the cube/quad extraction patterns.
2. Add an `exportX(...)` method to `Exporter` and a `writeAll(ir, dir, name)` call.
3. Register a subcommand in `client/InputHandler` under `/recorte export`.
4. Anything self-illuminating? set `Ir.Material.emissive`. Tinted? pass vertex colors to `Ir.Vertex`.

## Testing an export without Blender

`validate_export.py <export_dir>` parses the `.glb` (header, chunks, bones, vertex/triangle counts,
bounding boxes) and the textures — quick sanity check that geometry and rigging came out right.

## Style & PRs

- Match the surrounding code (4-space indent, clear names, small focused classes). Keep `Ir` the only
  thing producers and writers share.
- Run `./gradlew build` before opening a PR.
- One feature per PR; describe what you exported and (ideally) attach a Blender screenshot.

By contributing you agree your work is released under the [MIT License](LICENSE).
