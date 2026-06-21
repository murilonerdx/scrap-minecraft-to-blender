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
import urllib.parse

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

        n_sun = _apply_sun(port, new_objs)   # day/night timelapse (after the static world is set)
        n_anim = _apply_animated_textures(port)
        _apply_dof(new_objs)
        n_spk = _apply_speakers(new_objs)    # sound emitters → positioned Blender Speakers

        msg = "Imported latest Recorte export (%d objects)" % len(new_objs)
        if n_events:
            msg += " — %d timeline marker(s)" % n_events
        if n_sun:
            msg += " — timelapse sun/sky animated"
        if n_anim:
            msg += " — %d animated texture(s)" % n_anim
        if n_spk:
            msg += " — %d speaker(s)" % n_spk
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


def _apply_sun(port, objs):
    """Day/night timelapse (#1): keyframe the imported Sun lamp (rotation/color/energy) and the World
    background colour over the recording, from the /sun track. Returns the number of keyframes."""
    try:
        import json as _json
        data = _json.loads(_fetch(port, "sun").decode("utf-8"))
    except Exception:  # noqa: BLE001
        return 0
    times = data.get("times") or []
    dirs = data.get("dir") or []
    cols = data.get("color") or []
    inten = data.get("intensity") or []
    sky = data.get("sky") or []
    n = min(len(times), len(dirs))
    if n < 2:
        return 0

    import mathutils
    scene = bpy.context.scene
    fps = float(data.get("fps") or scene.render.fps or 30)

    sun_obj = None
    for o in objs:
        if getattr(o, "type", None) == "LIGHT" and getattr(getattr(o, "data", None), "type", None) == "SUN":
            sun_obj = o
            break
    if sun_obj is not None:
        sun_obj.rotation_mode = "QUATERNION"

    bg = None
    world = scene.world
    if world and world.use_nodes:
        bg = world.node_tree.nodes.get("Background")

    down = mathutils.Vector((0.0, 0.0, -1.0))
    step = max(1, n // 300)   # the sun moves slowly; ~300 keys is plenty, keep the file light
    keys = 0
    for i in range(0, n, step):
        frame = int(round(float(times[i]) * fps))
        if sun_obj is not None:
            d = mathutils.Vector(dirs[i][:3])
            if d.length > 1e-6:
                sun_obj.rotation_quaternion = down.rotation_difference(d.normalized())
                sun_obj.keyframe_insert("rotation_quaternion", frame=frame)
            if i < len(cols):
                sun_obj.data.color = cols[i][:3]
                sun_obj.data.keyframe_insert("color", frame=frame)
            if i < len(inten):
                sun_obj.data.energy = float(inten[i])
                sun_obj.data.keyframe_insert("energy", frame=frame)
            keys += 1
        if bg is not None and i < len(sky):
            c = sky[i]
            bg.inputs[0].default_value = (c[0], c[1], c[2], 1.0)
            bg.inputs[0].keyframe_insert("default_value", frame=frame)
    return keys


def _apply_dof(objs):
    """Turn on depth of field for imported cameras that carry dof_focus/dof_fstop (the glTF importer puts
    node extras onto the camera object's custom properties). Returns how many cameras got DOF."""
    n = 0
    for obj in objs:
        if getattr(obj, "type", None) != "CAMERA":
            continue
        focus = obj.get("dof_focus")
        if focus is None:
            continue
        try:
            obj.data.dof.use_dof = True
            obj.data.dof.focus_distance = float(focus)
            fstop = obj.get("dof_fstop")
            if fstop:
                obj.data.dof.aperture_fstop = float(fstop)
            n += 1
        except Exception:  # noqa: BLE001
            pass
    return n


def _apply_speakers(objs):
    """Turn imported `Speaker_` empty nodes into Blender **Speaker** objects (positioned spatial-audio
    emitters for the VSE). The source .ogg isn't exported, so each Speaker is created without a sound for
    you to assign; its sound id, time and gain ride along as custom properties. Returns the count."""
    n = 0
    for obj in list(objs):
        if not obj.name.startswith("Speaker_"):
            continue
        try:
            spk_data = bpy.data.speakers.new(obj.name)
            spk_obj = bpy.data.objects.new(obj.name, spk_data)
            spk_obj.location = obj.location
            for k in ("sound", "time", "gain"):
                if k in obj.keys():
                    spk_obj[k] = obj[k]
            try:
                spk_data.volume = float(obj.get("gain", 1.0))
            except Exception:  # noqa: BLE001
                pass
            cols = list(obj.users_collection) or [bpy.context.scene.collection]
            for c in cols:
                c.objects.link(spk_obj)
            bpy.data.objects.remove(obj, do_unlink=True)   # replace the placeholder empty
            n += 1
        except Exception:  # noqa: BLE001
            pass
    return n


def _apply_animated_textures(port):
    """Build a looping Image Sequence for each animated-texture material (water/lava/fire/portal),
    downloading the frames from the running game and wiring them into the imported material so they
    animate as the scene plays. Returns the number of materials animated."""
    try:
        import json as _json
        data = _json.loads(_fetch(port, "anim_textures").decode("utf-8"))
    except Exception:  # noqa: BLE001
        return 0
    texs = data.get("textures") or []
    if not texs:
        return 0
    fps = int(data.get("fps") or 20)
    animated = 0
    for t in texs:
        mat_name = t.get("material")
        frames = int(t.get("frames") or 0)
        if not mat_name or frames < 2:
            continue
        folder = os.path.join(tempfile.gettempdir(), "recorte_anim", str(mat_name))
        try:
            os.makedirs(folder, exist_ok=True)
        except Exception:  # noqa: BLE001
            continue
        first = None
        for i in range(frames):
            try:
                raw = _fetch(port, "anim_frame?m=%s&i=%d" % (urllib.parse.quote(str(mat_name)), i))
            except Exception:  # noqa: BLE001
                first = None
                break
            fp = os.path.join(folder, "frame_%04d.png" % (i + 1))
            with open(fp, "wb") as f:
                f.write(raw)
            if first is None:
                first = fp
        if first is None:
            continue
        try:
            img = bpy.data.images.load(first, check_existing=False)
            img.source = "SEQUENCE"
        except Exception:  # noqa: BLE001
            continue
        for mat in bpy.data.materials:
            if not (mat.name == mat_name or mat.name.startswith(str(mat_name) + ".")):
                continue
            if not mat.use_nodes:
                continue
            for node in mat.node_tree.nodes:
                if node.type == "TEX_IMAGE":
                    node.image = img
                    node.interpolation = "Closest"
                    try:
                        node.image_user.frame_duration = frames
                        node.image_user.use_cyclic = True
                        node.image_user.use_auto_refresh = True
                        node.image_user.frame_start = 1
                    except Exception:  # noqa: BLE001
                        pass
                    animated += 1
                    break
    if animated:
        try:
            bpy.context.scene.render.fps = fps
        except Exception:  # noqa: BLE001
            pass
    return animated


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
        _apply_sun(port, self._objs)
        _apply_animated_textures(port)
        _apply_dof(self._objs)

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
