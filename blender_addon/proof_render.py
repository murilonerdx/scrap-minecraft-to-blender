"""Visual proof: build the cosmic-horror scene with the real add-on, SAVE a .blend you can open, and
RENDER a PNG you can look at. Run: blender --background --python proof_render.py"""
import bpy
import sys
import os
import traceback
import mathutils

ADDON = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\blender_addon\recorte_import.py"
BLEND = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\build\horror_proof.blend"
PNG = r"C:\Users\T-GAMER\Desktop\recorte-minecraft\build\horror_proof.png"
os.makedirs(os.path.dirname(BLEND), exist_ok=True)

ns = {"__name__": "recorte_proof"}
with open(ADDON, encoding="utf-8") as f:
    exec(compile(f.read(), ADDON, "exec"), ns)
ns["register"]()
print("ADDON", ns["bl_info"]["version"])

bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete()

# build the scene with the actual kit operators
bpy.ops.recorte.generate(grid=3, room=7, wrong=0.5)
for k, loc in (("ARCH", (-14, 0, 0)), ("STAIRS", (-14, 10, 0)), ("EYE", (24, 6, 8)),
               ("COCOON", (24, 16, 4)), ("WEB", (10, -14, 8)), ("PILLAR", (4, 24, 0))):
    bpy.context.scene.cursor.location = loc
    bpy.ops.recorte.module(kind=k, size=7)
bpy.ops.object.select_all(action="SELECT")
bpy.ops.recorte.deform(method="TWIST", angle=18.0)
bpy.ops.recorte.corrupt(amount=0.25)
meshes = [o for o in bpy.data.objects if o.type == "MESH"]
print("SCENE built: %d mesh objects" % len(meshes))

# camera + sun framing the layout
scene = bpy.context.scene
cam_data = bpy.data.cameras.new("proof_cam")
cam = bpy.data.objects.new("proof_cam", cam_data)
scene.collection.objects.link(cam)
cam.location = (55.0, -45.0, 42.0)
target = mathutils.Vector((8.0, 8.0, 4.0))
cam.rotation_euler = (target - cam.location).to_track_quat("-Z", "Y").to_euler()
scene.camera = cam
sun_data = bpy.data.lights.new("sun", "SUN")
sun_data.energy = 3.0
sun = bpy.data.objects.new("sun", sun_data)
sun.rotation_euler = (0.6, 0.2, 0.4)
scene.collection.objects.link(sun)

bpy.ops.wm.save_as_mainfile(filepath=BLEND)
print("SAVED .blend ->", BLEND)

# render a still you can look at (Cycles CPU works headless)
try:
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = 16
except Exception:
    pass
scene.render.resolution_x = 1100
scene.render.resolution_y = 620
scene.render.filepath = PNG
try:
    bpy.ops.render.render(write_still=True)
    print("RENDERED PNG ->", PNG)
except Exception:
    traceback.print_exc()
    print("render failed (open the .blend instead)")
print("PROOF DONE")
