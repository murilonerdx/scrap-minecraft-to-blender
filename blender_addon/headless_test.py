"""Headless verification of the Blender-side cosmic-horror build pipeline. Run with:
   blender --background --python headless_test.py
It exec's the real add-on, voxelizes a cube + a generated module, and writes the same JSON the
'Send to Minecraft' operator would POST — proving the voxelizer and geometry helpers actually run."""
import bpy
import sys
import json
import os
import traceback

ADDON = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\blender_addon\recorte_import.py"
OUT = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\build\voxtest.json"

ns = {"__name__": "recorte_headless"}
with open(ADDON, encoding="utf-8") as f:
    exec(compile(f.read(), ADDON, "exec"), ns)
print("ADDON loaded, version", ns["bl_info"]["version"])

# fresh scene
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()

# 1) voxelize a solid cube
bpy.ops.mesh.primitive_cube_add(size=6, location=(0, 0, 0))
cube = bpy.context.active_object
cube["mc_block"] = "minecraft:obsidian"
palette, block_index, blocks, bounds = [], {}, [], [10 ** 9, 10 ** 9, 10 ** 9]
try:
    ns["_voxelize_object"](bpy.context, cube, True, palette, block_index, blocks, bounds)
except Exception:
    traceback.print_exc()
    sys.exit(2)
print("VOXELIZE cube -> %d blocks, palette=%s" % (len(blocks), palette))
if not blocks:
    print("FAIL: cube produced no blocks")
    sys.exit(3)

# 2) geometry helpers used by the kit
for name, args in (("_corridor", (8,)), ("_arch", ()), ("_stairs", ()), ("_web", (10,)), ("_box", (5, 5, 4))):
    v, fa = ns[name](*args)
    print("HELPER %s -> %d verts %d faces" % (name, len(v), len(fa)))
    if not v or not fa:
        print("FAIL: helper", name, "empty")
        sys.exit(4)

# 3) build the exact JSON the operator POSTs, normalized to the min corner
bx, by, bz = bounds
cells = {}
for x, y, z, i in blocks:
    cells[(x - bx, y - by, z - bz)] = i
rows = [[x, y, z, i] for (x, y, z), i in cells.items()]
payload = {"name": "headless", "palette": palette, "blocks": rows}
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w") as f:
    json.dump(payload, f)
print("WROTE %s with %d blocks (%d types)" % (OUT, len(rows), len(palette)))
print("ALL HEADLESS CHECKS PASSED")
