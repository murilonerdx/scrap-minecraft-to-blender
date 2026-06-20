bl_info = {
    "name": "Recorte — Import from Minecraft",
    "author": "murilonerdx",
    "version": (0, 1, 0),
    "blender": (3, 0, 0),
    "location": "View3D > Sidebar (N) > Recorte",
    "description": "One-click import of the latest Recorte export from a running Minecraft instance.",
    "category": "Import-Export",
}

import os
import tempfile
import urllib.request

import bpy

DEFAULT_PORT = 25599


def _fetch(port, path):
    url = "http://127.0.0.1:%d/%s" % (port, path)
    with urllib.request.urlopen(url, timeout=5) as r:
        return r.read()


class RECORTE_OT_import_latest(bpy.types.Operator):
    """Fetch the most recent Recorte export from the running game and import it."""
    bl_idname = "recorte.import_latest"
    bl_label = "Import latest from Minecraft"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        port = context.scene.recorte_port
        try:
            data = _fetch(port, "latest")
        except Exception as e:  # noqa: BLE001
            self.report({"ERROR"}, "Couldn't reach Minecraft on port %d (%s). "
                                   "Is the game running with the Recorte mod?" % (port, e))
            return {"CANCELLED"}

        path = os.path.join(tempfile.gettempdir(), "recorte_latest.glb")
        with open(path, "wb") as f:
            f.write(data)

        before = set(bpy.data.objects)
        bpy.ops.import_scene.gltf(filepath=path)
        new_objs = [o for o in bpy.data.objects if o not in before]

        # Pixel-art friendly: nearest filtering on every imported image texture.
        for obj in new_objs:
            for slot in getattr(obj, "material_slots", []):
                mat = slot.material
                if not mat or not mat.use_nodes:
                    continue
                for node in mat.node_tree.nodes:
                    if node.type == "TEX_IMAGE":
                        node.interpolation = "Closest"

        # Match the in-game sky as the world background.
        try:
            import json as _json
            env = _json.loads(_fetch(port, "env").decode("utf-8"))
            sky = env.get("sky")
            if sky and len(sky) == 3:
                world = context.scene.world
                if world is None:
                    world = bpy.data.worlds.new("Recorte World")
                    context.scene.world = world
                world.use_nodes = True
                bg = world.node_tree.nodes.get("Background")
                if bg is not None:
                    bg.inputs[0].default_value = (sky[0], sky[1], sky[2], 1.0)
        except Exception:  # noqa: BLE001
            pass

        self.report({"INFO"}, "Imported latest Recorte export (%d objects)" % len(new_objs))
        return {"FINISHED"}


class RECORTE_OT_ping(bpy.types.Operator):
    """Check whether the game is reachable."""
    bl_idname = "recorte.ping"
    bl_label = "Test connection"

    def execute(self, context):
        try:
            _fetch(context.scene.recorte_port, "ping")
            self.report({"INFO"}, "Connected to Minecraft ✔")
        except Exception as e:  # noqa: BLE001
            self.report({"ERROR"}, "Not reachable: %s" % e)
        return {"FINISHED"}


class RECORTE_PT_panel(bpy.types.Panel):
    bl_label = "Recorte"
    bl_idname = "RECORTE_PT_panel"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "Recorte"

    def draw(self, context):
        layout = self.layout
        layout.prop(context.scene, "recorte_port")
        layout.operator("recorte.import_latest", icon="IMPORT")
        layout.operator("recorte.ping", icon="PLUGIN")
        layout.label(text="Export in-game (key O), then click Import.")


classes = (RECORTE_OT_import_latest, RECORTE_OT_ping, RECORTE_PT_panel)


def register():
    bpy.types.Scene.recorte_port = bpy.props.IntProperty(
        name="Port", default=DEFAULT_PORT, min=1, max=65535)
    for c in classes:
        bpy.utils.register_class(c)


def unregister():
    for c in reversed(classes):
        bpy.utils.unregister_class(c)
    del bpy.types.Scene.recorte_port


if __name__ == "__main__":
    register()
