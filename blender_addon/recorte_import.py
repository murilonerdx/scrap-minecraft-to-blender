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


# Bypass any system/Blender proxy — localhost must not be routed through a proxy.
_opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def _fetch(port, path):
    url = "http://127.0.0.1:%d/%s" % (port, path)
    with _opener.open(url, timeout=5) as r:
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

        _activate_animations(new_objs)
        _setup_render_passes(new_objs)
        n_events = _apply_events(port)

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

        msg = "Imported latest Recorte export (%d objects)" % len(new_objs)
        if n_events:
            msg += " — %d timeline marker(s) added" % n_events
        self.report({"INFO"}, msg)
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


def _apply_pixel_art(objs):
    for obj in objs:
        for slot in getattr(obj, "material_slots", []):
            mat = slot.material
            if mat and mat.use_nodes:
                for node in mat.node_tree.nodes:
                    if node.type == "TEX_IMAGE":
                        node.interpolation = "Closest"


def _activate_animations(objs):
    """glTF stashes animations in the NLA; pull them onto the active Action so the tracks are
    editable in the Dope Sheet / Graph Editor, and fit the scene frame range to them. Returns the
    number of objects that ended up with an active animation."""
    end = 1
    n_act = 0
    for obj in objs:
        ad = getattr(obj, "animation_data", None)
        if not ad:
            continue
        act = ad.action
        if act is None and ad.nla_tracks:
            for tr in list(ad.nla_tracks):
                for st in tr.strips:
                    if st.action and act is None:
                        act = st.action
                ad.nla_tracks.remove(tr)
            if act:
                ad.action = act
        if act:
            n_act += 1
            try:
                end = max(end, int(round(act.frame_range[1])))
            except Exception:  # noqa: BLE001
                pass
    scene = bpy.context.scene
    if scene:
        # the in-game recorder samples at ~30 fps (render-frame interpolated) — match it so
        # playback is smooth and keyframes land on whole frames
        scene.render.fps = 30
        if end > 1:
            scene.frame_start = 0
            scene.frame_end = end
    return n_act


def _setup_render_passes(objs):
    """Give each imported object a unique Object Index and turn on the common compositor passes
    (Z/depth, mist, normal, object-index) so the scene is ready for advanced compositing (#7)."""
    try:
        vl = bpy.context.view_layer
        for attr in ("use_pass_z", "use_pass_mist", "use_pass_normal", "use_pass_object_index"):
            if hasattr(vl, attr):
                setattr(vl, attr, True)
    except Exception:  # noqa: BLE001
        pass
    idx = 1
    for obj in objs:
        if getattr(obj, "type", None) == "MESH":
            try:
                obj.pass_index = idx
                idx += 1
            except Exception:  # noqa: BLE001
                pass


def _apply_events(port):
    """Fetch the latest recording's timeline events (block breaks/placements) and drop Blender
    timeline markers at the matching frames, so VFX/sound can be timed to them. Returns the count."""
    try:
        import json as _json
        data = _json.loads(_fetch(port, "events").decode("utf-8"))
    except Exception:  # noqa: BLE001
        return 0
    evs = data.get("events") or []
    scene = bpy.context.scene
    if scene is None:
        return 0
    # clear our own previous markers so repeated imports don't pile up
    for m in list(scene.timeline_markers):
        if m.name.startswith(("break:", "place:", "sound:")):
            scene.timeline_markers.remove(m)
    if not evs:
        return 0
    fps = float(data.get("fps") or scene.render.fps or 30)
    for e in evs:
        frame = int(round(float(e.get("time", 0.0)) * fps))
        scene.timeline_markers.new(str(e.get("name", "event")), frame=frame)
    return len(evs)


class RECORTE_OT_activate_anim(bpy.types.Operator):
    """Pull glTF animations out of the NLA onto the active Action for the selected (or all) objects,
    so the keyframes show up in the Timeline / Dope Sheet ready to edit. Use this after a manual
    File > Import (the 'Import latest' button already does it automatically)."""
    bl_idname = "recorte.activate_anim"
    bl_label = "Show animation keys"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        objs = list(context.selected_objects) or list(bpy.data.objects)
        n = _activate_animations(objs)
        if n:
            self.report({"INFO"}, "Animation keys activated on %d object(s) — open the Timeline / "
                                  "Dope Sheet to edit. Playback set to 20 fps." % n)
        else:
            self.report({"WARNING"}, "No animations found on those objects. Select the armature "
                                     "(or nothing, to scan all) and try again.")
        return {"FINISHED"}


class RECORTE_OT_live(bpy.types.Operator):
    """Auto-reimport the live export whenever the game pushes a new one (real-time link)."""
    bl_idname = "recorte.live"
    bl_label = "Start Live link"

    _timer = None
    _last_gen = None
    _objs = None

    def modal(self, context, event):
        if not context.scene.recorte_live:
            self.cancel(context)
            return {"CANCELLED"}
        if event.type == "TIMER":
            port = context.scene.recorte_port
            try:
                gen = _fetch(port, "gen").decode("utf-8").strip()
            except Exception:  # noqa: BLE001
                return {"PASS_THROUGH"}
            if gen != self._last_gen:
                self._last_gen = gen
                self._reimport(context, port)
        return {"PASS_THROUGH"}

    def _reimport(self, context, port):
        for o in list(self._objs or []):
            try:
                bpy.data.objects.remove(o, do_unlink=True)
            except Exception:  # noqa: BLE001
                pass
        self._objs = []
        try:
            data = _fetch(port, "latest")
        except Exception:  # noqa: BLE001
            return
        path = os.path.join(tempfile.gettempdir(), "recorte_live.glb")
        with open(path, "wb") as f:
            f.write(data)
        before = set(bpy.data.objects)
        try:
            bpy.ops.import_scene.gltf(filepath=path)
        except Exception:  # noqa: BLE001
            return
        self._objs = [o for o in bpy.data.objects if o not in before]
        _apply_pixel_art(self._objs)
        _activate_animations(self._objs)
        _apply_events(port)

    def execute(self, context):
        context.scene.recorte_live = True
        self._objs = []
        self._last_gen = None
        self._timer = context.window_manager.event_timer_add(1.0, window=context.window)
        context.window_manager.modal_handler_add(self)
        self.report({"INFO"}, "Live link started — turn on 'Live link' in-game (/recorte live)")
        return {"RUNNING_MODAL"}

    def cancel(self, context):
        if self._timer is not None:
            context.window_manager.event_timer_remove(self._timer)
            self._timer = None


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
        layout.operator("recorte.activate_anim", icon="ACTION")
        layout.separator()
        layout.label(text="Real-time link:")
        if context.scene.recorte_live:
            layout.prop(context.scene, "recorte_live", text="Live link: ON", toggle=True)
        else:
            layout.operator("recorte.live", icon="PLAY")
        layout.label(text="In-game: /recorte live (or panel button)")


classes = (RECORTE_OT_import_latest, RECORTE_OT_ping, RECORTE_OT_activate_anim,
           RECORTE_OT_live, RECORTE_PT_panel)


def register():
    bpy.types.Scene.recorte_port = bpy.props.IntProperty(
        name="Port", default=DEFAULT_PORT, min=1, max=65535)
    bpy.types.Scene.recorte_live = bpy.props.BoolProperty(name="Live link", default=False)
    for c in classes:
        bpy.utils.register_class(c)


def unregister():
    for c in reversed(classes):
        bpy.utils.unregister_class(c)
    del bpy.types.Scene.recorte_port
    del bpy.types.Scene.recorte_live


if __name__ == "__main__":
    register()
