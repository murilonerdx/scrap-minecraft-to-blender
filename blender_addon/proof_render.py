"""Visual proof of the ALGORITHMIC cosmic-horror generators, COLOURED by Minecraft block so structures
read (the eye's iris/pupil, the maze, etc.). Run: blender --background --python proof_render.py"""
import bpy
import os
import traceback
import mathutils

ADDON = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\blender_addon\recorte_import.py"
BLEND = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\build\horror_proof.blend"
PNG = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\build\horror_proof.png"
os.makedirs(os.path.dirname(BLEND), exist_ok=True)

# rough block -> colour so the gray clay turns into something readable
COL = {
    "bone_block": (0.85, 0.82, 0.7), "warped_wart_block": (0.0, 0.5, 0.5),
    "black_concrete": (0.02, 0.02, 0.03), "smooth_stone": (0.55, 0.55, 0.58),
    "polished_blackstone": (0.12, 0.12, 0.15), "basalt": (0.18, 0.18, 0.2),
    "sculk": (0.03, 0.06, 0.1), "deepslate": (0.22, 0.22, 0.25), "cobweb": (0.9, 0.9, 0.92),
}


def colour_for(obj):
    blk = str(obj.get("mc_block", "")).split("[")[0].replace("minecraft:", "")
    for key, c in COL.items():
        if key in blk:
            return (c[0], c[1], c[2], 1.0)
    return (0.4, 0.4, 0.43, 1.0)


ns = {"__name__": "recorte_proof"}
with open(ADDON, encoding="utf-8") as f:
    exec(compile(f.read(), ADDON, "exec"), ns)
ns["register"]()
print("ADDON", ns["bl_info"]["version"])

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()
scene = bpy.context.scene


def put(loc):
    scene.cursor.location = loc


# the one-click example scene (the recipe the user reproduces)
put((0, 0, 0))
bpy.ops.recorte.example()

meshes = [o for o in bpy.data.objects if o.type == "MESH"]
for o in meshes:
    m = bpy.data.materials.new(o.name)
    m.use_nodes = True
    bsdf = m.node_tree.nodes.get("Principled BSDF")
    if bsdf:
        bsdf.inputs["Base Color"].default_value = colour_for(o)
    o.data.materials.append(m)
print("SCENE built: %d mesh objects" % len(meshes))

cam_data = bpy.data.cameras.new("cam")
cam = bpy.data.objects.new("cam", cam_data)
scene.collection.objects.link(cam)
cam.location = (40.0, -52.0, 60.0)   # higher, looking down into the maze
cam.rotation_euler = (mathutils.Vector((12.0, 12.0, 2.0)) - cam.location).to_track_quat("-Z", "Y").to_euler()
scene.camera = cam
sun_data = bpy.data.lights.new("sun", "SUN")
sun_data.energy = 3.5
sun = bpy.data.objects.new("sun", sun_data)
sun.rotation_euler = (0.5, 0.3, 0.6)
scene.collection.objects.link(sun)
if scene.world is None:
    scene.world = bpy.data.worlds.new("w")
scene.world.use_nodes = True
bg = scene.world.node_tree.nodes.get("Background")
if bg:
    bg.inputs[0].default_value = (0.02, 0.02, 0.03, 1.0)

bpy.ops.wm.save_as_mainfile(filepath=BLEND)
print("SAVED", BLEND)
try:
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 24
    scene.render.resolution_x = 1200
    scene.render.resolution_y = 720
    scene.render.filepath = PNG
    bpy.ops.render.render(write_still=True)
    print("RENDERED", PNG)
except Exception:
    traceback.print_exc()
print("PROOF DONE")
