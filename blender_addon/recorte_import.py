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


def _safe(label, fn, *args):
    """Run one import post-step; log and swallow any error so a single failing step never aborts the
    whole import (the mesh is already in the scene by then). Returns the step's result or None."""
    try:
        return fn(*args)
    except Exception as e:  # noqa: BLE001
        print("[Recorte] import step '%s' failed (skipped): %r" % (label, e))
        return None


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
        try:
            bpy.ops.import_scene.gltf(filepath=path)
        except Exception as e:  # noqa: BLE001
            self.report({"ERROR"}, "glTF import failed: %s (the .glb is at %s)" % (e, path))
            return {"CANCELLED"}
        new_objs = [o for o in bpy.data.objects if o not in before]

        # Every step below is best-effort: a failure is logged and skipped so the import still
        # succeeds with the imported mesh, rather than aborting the whole operator.
        _safe("pixel-art", _apply_pixel_art, new_objs)
        _safe("animations", _activate_animations, new_objs)
        _safe("render-passes", _setup_render_passes, new_objs)
        n_events = _safe("events", _apply_events, port) or 0
        _safe("world-sky", _apply_world_sky, context, port)
        n_sun = _safe("sun", _apply_sun, port, new_objs) or 0
        n_anim = _safe("anim-textures", _apply_animated_textures, port) or 0
        _safe("dof", _apply_dof, new_objs)
        n_spk = _safe("speakers", _apply_speakers, new_objs) or 0
        studio = False
        if getattr(context.scene, "recorte_studio_template", False):   # #20: render-ready scene
            studio = _safe("studio-scene", _setup_studio_scene, context, new_objs, port)

        msg = "Imported latest Recorte export (%d objects)" % len(new_objs)
        if n_events:
            msg += " — %d timeline marker(s)" % n_events
        if n_sun:
            msg += " — timelapse sun/sky animated"
        if n_anim:
            msg += " — %d animated texture(s)" % n_anim
        if n_spk:
            msg += " — %d speaker(s)" % n_spk
        if studio:
            msg += " — render-ready studio scene"
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


def _apply_world_sky(context, port):
    """Set the Blender World background to the in-game sky colour (from /env)."""
    import json as _json
    env = _json.loads(_fetch(port, "env").decode("utf-8"))
    sky = env.get("sky")
    if not (sky and len(sky) == 3):
        return
    world = context.scene.world
    if world is None:
        world = bpy.data.worlds.new("Recorte World")
        context.scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    if bg is not None:
        bg.inputs[0].default_value = (sky[0], sky[1], sky[2], 1.0)


def _apply_pixel_art(objs):
    for obj in objs:
        for slot in getattr(obj, "material_slots", []):
            mat = slot.material
            if mat and mat.use_nodes:
                for node in mat.node_tree.nodes:
                    if node.type == "TEX_IMAGE":
                        node.interpolation = "Closest"


def _collect_actions(ad):
    """All distinct Actions reachable from an object's animation_data (active + NLA strips), in order."""
    actions = []
    if ad.action and ad.action not in actions:
        actions.append(ad.action)
    for tr in list(ad.nla_tracks):
        for st in tr.strips:
            if st.action and st.action not in actions:
                actions.append(st.action)
    return actions


def _stack_as_nla(ad, actions):
    """Rebuild a clean NLA: clear the active Action + tracks, then lay out one named track/strip per
    Action so the clips can be blended/reordered non-linearly (#14). Returns the strip count."""
    ad.action = None
    for tr in list(ad.nla_tracks):
        ad.nla_tracks.remove(tr)
    n = 0
    for act in actions:
        try:
            tr = ad.nla_tracks.new()
            tr.name = act.name
            try:
                start = int(round(act.frame_range[0]))
            except Exception:  # noqa: BLE001
                start = 0
            tr.strips.new(act.name, start, act)
            n += 1
        except Exception:  # noqa: BLE001
            pass
    return n


def _stack_nla(objs):
    """Lay every imported clip out as its own NLA strip/track for the given objects (regardless of
    count). Returns (objects_stacked, total_strips)."""
    n_obj = 0
    n_strips = 0
    for obj in objs:
        ad = getattr(obj, "animation_data", None)
        if not ad:
            continue
        actions = _collect_actions(ad)
        if not actions:
            continue
        n_strips += _stack_as_nla(ad, actions)
        n_obj += 1
    return n_obj, n_strips


def _activate_animations(objs):
    """Make imported keyframes visible. Always pull the **first** clip onto the active Action so its keys
    show in the Dope Sheet / Timeline (this is what most people expect after a recording). For multi-clip
    files (animation library, takes) the OTHER clips are left as NLA strips so the non-linear stack is
    still there — use **Stack clips as NLA** to push the first one down too. Fits the scene frame range
    and sets 30 fps. Returns the object count that got an active animation."""
    end = 1
    n = 0
    animated = []
    for obj in objs:
        ad = getattr(obj, "animation_data", None)
        if not ad:
            continue
        actions = _collect_actions(ad)
        if not actions:
            continue
        first = actions[0]
        # drop only the NLA track that holds the first clip (so it isn't applied twice), keep the rest
        for tr in list(ad.nla_tracks):
            try:
                if any(st.action == first for st in tr.strips):
                    ad.nla_tracks.remove(tr)
            except Exception:  # noqa: BLE001
                pass
        try:
            ad.action = first   # active Action -> keyframes are visible in the Timeline
            animated.append(obj)
            n += 1
        except Exception:  # noqa: BLE001
            pass
        for act in actions:
            try:
                end = max(end, int(round(act.frame_range[1])))
            except Exception:  # noqa: BLE001
                pass
    # IMPORTANT: the Dope Sheet / Timeline only shows keys for the ACTIVE object. The glTF importer
    # leaves the mesh selected, not the armature that holds the action, so the keys look "missing".
    # Select the animated object(s) and make the first one active so the keyframes actually show.
    if animated:
        try:
            for o in list(bpy.context.selected_objects):
                o.select_set(False)
        except Exception:  # noqa: BLE001
            pass
        for o in animated:
            try:
                o.select_set(True)
            except Exception:  # noqa: BLE001
                pass
        try:
            bpy.context.view_layer.objects.active = animated[0]
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
    return n


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
    """Fetch the latest recording's timeline events (block breaks/placements, sounds and named **shots**)
    and drop Blender timeline markers at the matching frames, so VFX/sound and cuts can be timed to them.
    Named shots (#17) become clean markers like '🎬 Intro'. Returns the count."""
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
        if m.name.startswith(("break:", "place:", "sound:", "🎬 ")):
            scene.timeline_markers.remove(m)
    if not evs:
        return 0
    fps = float(data.get("fps") or scene.render.fps or 30)
    for e in evs:
        frame = int(round(float(e.get("time", 0.0)) * fps))
        name = str(e.get("name", "event"))
        if name.startswith("shot:"):          # named cut point -> a clean, prominent marker
            name = "🎬 " + name[len("shot:"):]
        scene.timeline_markers.new(name, frame=frame)
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


def _setup_studio_scene(context, objs, port):
    """Studio scene template (#20): make the imported scene **render-ready** in one step — active camera,
    fps + render resolution (from the game's /studio settings when reachable), faithful colour management
    for Minecraft's flat colours, a large camera clip end for the sky dome, and EEVEE glow so emissive
    beams/particles/lava bloom. Everything is defensive (Blender API varies by version). Returns True."""
    scene = context.scene
    if scene is None:
        return False
    fps, w, h = 30, 1920, 1080
    try:
        import json as _json
        cfg = _json.loads(_fetch(port, "studio").decode("utf-8"))
        fps = int(cfg.get("fps") or 30)
        w = int(cfg.get("width") or 1920)
        h = int(cfg.get("height") or 1080)
    except Exception:  # noqa: BLE001
        pass
    try:
        scene.render.fps = fps
        scene.render.resolution_x = w
        scene.render.resolution_y = h
        scene.render.resolution_percentage = 100
        scene.render.image_settings.file_format = "PNG"
    except Exception:  # noqa: BLE001
        pass
    # faithful colour management for Minecraft's flat pixel-art colours
    try:
        scene.view_settings.view_transform = "Standard"
        scene.view_settings.look = "None"
    except Exception:  # noqa: BLE001
        pass
    # active camera = the imported POV camera (or any imported camera); clip far enough for the sky dome
    try:
        cams = [o for o in objs if getattr(o, "type", None) == "CAMERA"]
        pov = next((o for o in cams if o.name.startswith("Camera")), None) or (cams[0] if cams else None)
        if pov is not None:
            scene.camera = pov
            try:
                pov.data.clip_end = max(pov.data.clip_end, 4000.0)
            except Exception:  # noqa: BLE001
                pass
    except Exception:  # noqa: BLE001
        pass
    # EEVEE so it previews fast; glow + AO make beams/particles/lava read right
    try:
        scene.render.engine = "BLENDER_EEVEE"
    except Exception:  # noqa: BLE001
        try:
            scene.render.engine = "BLENDER_EEVEE_NEXT"
        except Exception:  # noqa: BLE001
            pass
    for attr in ("use_bloom", "use_gtao"):
        try:
            setattr(scene.eevee, attr, True)
        except Exception:  # noqa: BLE001
            pass
    return True


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


class RECORTE_OT_studio_scene(bpy.types.Operator):
    """Make the current scene render-ready (studio template #20): active camera, fps + resolution,
    faithful colour management, sky-dome-safe camera clipping and EEVEE glow. 'Import latest' does this
    automatically when 'Studio scene' is enabled."""
    bl_idname = "recorte.studio_scene"
    bl_label = "Setup studio scene"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        port = context.scene.recorte_port
        objs = list(context.selected_objects) or list(bpy.data.objects)
        if _setup_studio_scene(context, objs, port):
            self.report({"INFO"}, "Studio scene set up — camera, fps/resolution, colour management and "
                                  "EEVEE glow ready to render.")
        else:
            self.report({"WARNING"}, "No active scene to set up.")
        return {"FINISHED"}


class RECORTE_OT_stack_nla(bpy.types.Operator):
    """Lay every animation clip of the selected (or all) objects out as its own NLA strip/track, so a
    multi-clip file (animation library, takes) becomes a non-linear stack you can blend and reorder in
    the NLA editor. 'Import latest' already stacks multi-clip files automatically."""
    bl_idname = "recorte.stack_nla"
    bl_label = "Stack clips as NLA"
    bl_options = {"REGISTER", "UNDO"}

    def execute(self, context):
        objs = list(context.selected_objects) or list(bpy.data.objects)
        n_obj, n_strips = _stack_nla(objs)
        if n_obj:
            self.report({"INFO"}, "Stacked %d clip(s) as NLA strips on %d object(s) — open the NLA "
                                  "editor to blend them." % (n_strips, n_obj))
        else:
            self.report({"WARNING"}, "No animation clips found to stack.")
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
        _safe("pixel-art", _apply_pixel_art, self._objs)
        _safe("animations", _activate_animations, self._objs)
        _safe("events", _apply_events, port)
        _safe("world-sky", _apply_world_sky, context, port)
        _safe("sun", _apply_sun, port, self._objs)
        _safe("anim-textures", _apply_animated_textures, port)
        _safe("dof", _apply_dof, self._objs)
        _safe("speakers", _apply_speakers, self._objs)
        if getattr(context.scene, "recorte_studio_template", False):
            _safe("studio-scene", _setup_studio_scene, context, self._objs, port)

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
        layout.operator("recorte.stack_nla", icon="NLA")
        layout.prop(context.scene, "recorte_studio_template")
        layout.operator("recorte.studio_scene", icon="SCENE")
        layout.separator()
        layout.label(text="Real-time link:")
        if context.scene.recorte_live:
            layout.prop(context.scene, "recorte_live", text="Live link: ON", toggle=True)
        else:
            layout.operator("recorte.live", icon="PLAY")
        layout.label(text="In-game: /recorte live (or panel button)")


classes = (RECORTE_OT_import_latest, RECORTE_OT_ping, RECORTE_OT_activate_anim,
           RECORTE_OT_stack_nla, RECORTE_OT_studio_scene, RECORTE_OT_live, RECORTE_PT_panel)


def register():
    bpy.types.Scene.recorte_port = bpy.props.IntProperty(
        name="Port", default=DEFAULT_PORT, min=1, max=65535)
    bpy.types.Scene.recorte_live = bpy.props.BoolProperty(name="Live link", default=False)
    bpy.types.Scene.recorte_studio_template = bpy.props.BoolProperty(
        name="Studio scene", description="Set up a render-ready scene (camera, fps, colour, glow) on import",
        default=True)
    for c in classes:
        bpy.utils.register_class(c)


def unregister():
    for c in reversed(classes):
        bpy.utils.unregister_class(c)
    del bpy.types.Scene.recorte_port
    del bpy.types.Scene.recorte_live
    del bpy.types.Scene.recorte_studio_template


if __name__ == "__main__":
    register()
