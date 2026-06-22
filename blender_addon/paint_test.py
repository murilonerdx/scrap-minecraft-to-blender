"""Headless verification of the block PAINTER core. Run: blender --background --python paint_test.py
Paints blocks into per-block-type objects (like the modal painter does on each click), then voxelizes
exactly like Send-to-Minecraft and checks the mod's BuildStructure contract."""
import bpy
import sys
import traceback

ADDON = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\blender_addon\recorte_import.py"


def fail(m):
    print("FAIL:", m)
    sys.exit(2)


ns = {"__name__": "paint_test"}
with open(ADDON, encoding="utf-8") as f:
    exec(compile(f.read(), ADDON, "exec"), ns)
ns["register"]()
print("ADDON", ns["bl_info"]["version"])

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()
ctx = bpy.context
paint = ns["_paint_block"]

# paint a 5x5 floor of deepslate + a stone pillar + sculk accents (what the user does by clicking)
for x in range(5):
    for y in range(5):
        paint(ctx, x, y, 0, "minecraft:deepslate")
for z in range(4):
    paint(ctx, 2, 2, z + 1, "minecraft:stone")
paint(ctx, 0, 0, 1, "minecraft:sculk")
paint(ctx, 4, 4, 1, "minecraft:sculk")
paint(ctx, 2, 2, 2, "minecraft:stone")   # paint over an existing cell -> must NOT duplicate/crash

objs = [o for o in bpy.data.objects if o.name.startswith("paint_")]
print("painted into %d block-type objects: %s" % (len(objs), sorted(o.name for o in objs)))
if len(objs) != 3:
    fail("expected 3 block-type objects (deepslate/stone/sculk), got %d" % len(objs))
for o in objs:
    if "mc_block" not in o:
        fail(o.name + " has no mc_block")

# 'Paint at cursor' operator
bpy.context.scene.recorte_block = "minecraft:crying_obsidian"
bpy.context.scene.cursor.location = (10, 10, 0)
bpy.ops.recorte.add_block()
if bpy.data.objects.get("paint_crying_obsidian") is None:
    fail("add_block (paint at cursor) did not create the obsidian object")

# voxelize everything exactly like Send-to-Minecraft
palette, idx, blocks, bounds = [], {}, [], [10 ** 9, 10 ** 9, 10 ** 9]
for o in [o for o in bpy.data.objects if o.type == "MESH"]:
    try:
        ns["_voxelize_object"](ctx, o, False, palette, idx, blocks, bounds)
    except Exception:
        traceback.print_exc()
        fail("_voxelize_object raised on " + o.name)
print("VOXELIZED -> %d blocks, %d types %s" % (len(blocks), len(palette), palette))
if len(blocks) < 25:
    fail("expected at least the 25-block floor, got %d" % len(blocks))

bx, by, bz = bounds
rows = [[x - bx, y - by, z - bz, i] for x, y, z, i in blocks]
bad = sum(1 for r in rows if len(r) < 4 or not (0 <= r[3] < len(palette)))
if bad:
    fail("%d invalid rows for the mod parser" % bad)
print("0 invalid rows; palette types:", len(palette))
print("ALL PAINT CHECKS PASSED")
