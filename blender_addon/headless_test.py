"""Headless verification of the WHOLE Blender-side cosmic-horror build pipeline. Run with:
   blender --background --python headless_test.py
It registers the real add-on, runs the kit operators (generate / module / concept / corrupt / deform),
voxelizes every resulting mesh exactly like 'Send to Minecraft', and writes the JSON the operator POSTs
— proving the entire creative pipeline runs end-to-end, no GUI, no approval."""
import bpy
import sys
import json
import os
import traceback

ADDON = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\blender_addon\recorte_import.py"
OUT = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\build\voxtest.json"


def fail(msg):
    print("FAIL:", msg)
    sys.exit(2)


ns = {"__name__": "recorte_headless"}
with open(ADDON, encoding="utf-8") as f:
    exec(compile(f.read(), ADDON, "exec"), ns)
print("ADDON loaded, version", ns["bl_info"]["version"])

# register so the operators (bpy.ops.recorte.*) and scene props exist
try:
    ns["register"]()
except Exception:
    traceback.print_exc()
    fail("register() raised")

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()

# --- run the actual kit operators -------------------------------------------------------------------
try:
    print("generate ->", bpy.ops.recorte.generate(grid=2, room=6, wrong=0.5))
    for k in ("CORRIDOR", "ROOM", "PILLAR", "ARCH", "STAIRS", "EYE", "COCOON", "WEB"):
        r = bpy.ops.recorte.module(kind=k, size=6)
        print("module", k, "->", r, "objs now", len([o for o in bpy.data.objects if o.type == "MESH"]))
    print("concept OBSERVATION ->", bpy.ops.recorte.concept(concept="OBSERVATION", n=6))
    bpy.ops.object.select_all(action="SELECT")
    print("deform ->", bpy.ops.recorte.deform(method="TWIST", angle=30.0))
    print("corrupt ->", bpy.ops.recorte.corrupt(amount=0.3))
except Exception:
    traceback.print_exc()
    fail("a kit operator raised")

meshes = [o for o in bpy.data.objects if o.type == "MESH"]
print("TOTAL mesh objects after kit:", len(meshes))
if not meshes:
    fail("kit produced no meshes")

# --- voxelize everything, exactly like the Send-to-Minecraft operator -------------------------------
palette, block_index, blocks, bounds = [], {}, [], [10 ** 9, 10 ** 9, 10 ** 9]
voxelized = 0
for obj in meshes:
    try:
        before = len(blocks)
        ns["_voxelize_object"](bpy.context, obj, False, palette, block_index, blocks, bounds)
        if len(blocks) > before:
            voxelized += 1
    except Exception:
        traceback.print_exc()
        fail("_voxelize_object raised on " + obj.name)
print("VOXELIZED %d/%d objects -> %d blocks, %d block types %s" %
      (voxelized, len(meshes), len(blocks), len(palette), palette))
if not blocks:
    fail("no blocks produced from the kit")

bx, by, bz = bounds
cells = {}
for x, y, z, i in blocks:
    cells[(x - bx, y - by, z - bz)] = i
rows = [[x, y, z, i] for (x, y, z), i in cells.items()]
payload = {"name": "headless_kit", "palette": palette, "blocks": rows}
# validate the BuildStructure contract right here
bad = sum(1 for b in rows if len(b) < 4 or not (0 <= b[3] < len(palette)))
if bad:
    fail("%d invalid rows for the mod parser" % bad)
os.makedirs(os.path.dirname(OUT), exist_ok=True)
with open(OUT, "w") as f:
    json.dump(payload, f)
print("WROTE %s: %d blocks (%d types), 0 invalid rows" % (OUT, len(rows), len(palette)))
print("ALL HEADLESS CHECKS PASSED")
