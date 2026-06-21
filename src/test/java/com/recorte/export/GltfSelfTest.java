package com.recorte.export;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless self-test of {@link GltfWriter} — builds a synthetic {@link Ir.Model} exercising the risky
 * paths (multi-clip animation, multi-camera, sun light, normal + metallic-roughness textures, vertex
 * colours, multi-object meshes) and writes {@code .glb} files so they can be validated WITHOUT launching
 * Minecraft. {@code Ir} and {@code GltfWriter} only depend on JOML + Gson, so this runs standalone.
 *
 * <p>Run with: {@code ./gradlew gltfSelfTest} — then {@code python validate_selftest.py build/selftest}.
 */
public final class GltfSelfTest {
    private GltfSelfTest() {}

    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "build/selftest");
        Files.createDirectories(outDir);

        assertAccessoryClears();
        assertCameraShake();
        assertWeatherField();
        assertSkyGeometry();
        assertSpeakers();
        assertNlaUniqueNames();
        assertRetargetMap();
        assertShots();
        assertPresets();
        assertPreviewGrid();

        Ir.Model model = buildRig();

        // 1) single animation (the recording path)
        GltfWriter.write(model, walkClip("recording", 0.5f), outDir.resolve("single.glb"));

        // 2) multi-clip animation library (the riskiest refactor) + a spinning clip that would flip
        //    quaternion signs without the hemisphere fix (the cause of jittery recorded animations)
        List<Ir.Animation> lib = new ArrayList<>();
        Ir.Animation idle = walkClip("idle", 0.1f);
        idle.timeScale = 2f;   // slow-mo (#15): the writer must stretch this clip's times ×2
        lib.add(idle);
        lib.add(walkClip("walk", 0.5f));
        lib.add(walkClip("run", 0.9f));
        lib.add(spinClip("spin"));
        for (Ir.Animation a : lib) assertContinuous(a);
        GltfWriter.writeLibrary(model, lib, outDir.resolve("library.glb"));

        // 3) static (no animation) — cameras + sun + PBR textures
        GltfWriter.write(model, outDir.resolve("static.glb"));

        // 4) particle / VFX point cloud (studio #8) — a POINTS-mode primitive with per-point colour
        GltfWriter.write(buildPointCloud(), outDir.resolve("points.glb"));

        // 5) takes (studio #13) + NLA name de-dup (studio #14) — two clips intentionally share a name;
        //    the writer must make them distinct Actions so they stack cleanly as NLA strips
        List<Ir.Animation> takes = new ArrayList<>();
        takes.add(walkClip("take", 0.2f));
        takes.add(walkClip("take", 0.6f));
        GltfWriter.writeLibrary(model, takes, outDir.resolve("takes.glb"));

        // 6) retarget rig (studio #16) — same rig but bone nodes named with humanoid labels (Mixamo-style)
        model.useRetargetNames = true;
        GltfWriter.write(model, outDir.resolve("retarget.glb"));

        System.out.println("SELFTEST OK -> single.glb, library.glb, static.glb, points.glb, takes.glb, "
                + "retarget.glb in " + outDir.toAbsolutePath());
    }

    /** A minimal particle point cloud: one root bone + a POINTS primitive whose points carry colours.
     *  Verifies the writer emits glTF primitive {@code mode:0} and a COLOR_0 attribute (read into Blender
     *  as loose vertices for Geometry Nodes). */
    private static Ir.Model buildPointCloud() {
        Ir.Model m = new Ir.Model();
        Ir.Bone root = new Ir.Bone("scene", -1, new Matrix4f());
        root.localTransform = new Matrix4f();
        m.addBone(root);
        int mat = m.materialIndex("Particles");
        Ir.Primitive p = m.primitiveForMaterial(mat);
        p.group = "Particles";
        p.mode = Ir.Primitive.POINTS;
        for (int i = 0; i < 16; i++) {
            float a = i / 16f;
            p.addPoint(new Ir.Vertex(a, a * 2f, -a, 0f, 1f, 0f, 0f, 0f, 0, 1f, a, 1f - a, 1f));
        }
        if (p.indices.size() != 16) {
            throw new IllegalStateException("FAIL: point cloud should have 16 point indices, got " + p.indices.size());
        }
        System.out.println("  point-cloud OK: 16-point POINTS primitive built (mode=" + p.mode + ")");
        return m;
    }

    /** Two full turns on Y — the raw quaternion sign flips mid-way; the hemisphere fix must remove it. */
    private static Ir.Animation spinClip(String name) {
        Ir.Animation a = new Ir.Animation();
        a.name = name;
        for (int f = 0; f <= 40; f++) {
            float t = f / 40f;
            a.times.add(t);
            Quaternionf q = new Quaternionf().rotationY((float) (t * 4 * Math.PI));
            a.key(1, new float[]{0, 1, 0}, new float[]{q.x, q.y, q.z, q.w});
        }
        return a;
    }

    private static void assertContinuous(Ir.Animation a) {
        for (var e : a.rotations.entrySet()) {
            List<float[]> rots = e.getValue();
            for (int i = 1; i < rots.size(); i++) {
                float[] p = rots.get(i - 1), q = rots.get(i);
                float dot = p[0] * q[0] + p[1] * q[1] + p[2] * q[2] + p[3] * q[3];
                if (dot < 0f) {
                    throw new IllegalStateException("FAIL: quaternion discontinuity in clip '" + a.name
                            + "' bone " + e.getKey() + " key " + i + " (dot=" + dot + ") — would jitter in Blender");
                }
            }
        }
        System.out.println("  continuity OK: clip '" + a.name + "' has no quaternion sign flips");
    }

    private static Ir.Model buildRig() {
        Ir.Model m = new Ir.Model();
        // skeleton: root(0) -> body(1) -> head(2)
        Ir.Bone root = new Ir.Bone("root", -1, new Matrix4f());
        root.localTransform = new Matrix4f();
        m.addBone(root);
        Ir.Bone body = new Ir.Bone("body", 0, new Matrix4f().translate(0, 1, 0));
        body.localTransform = new Matrix4f().translate(0, 1, 0);
        m.addBone(body);
        Ir.Bone head = new Ir.Bone("head", 1, new Matrix4f().translate(0, 2, 0));
        head.localTransform = new Matrix4f().translate(0, 1, 0);
        m.addBone(head);

        byte[] png = tinyPng();
        int mat0 = m.materialIndex("tex_0");
        m.materials.get(mat0).png = png;
        m.materials.get(mat0).textureFile = "tex_0.png";
        m.materials.get(mat0).translucent = true;   // exercises alphaMode BLEND
        m.materials.get(mat0).alpha = 0.5f;          // exercises ghost fade (baseColorFactor alpha)
        m.materials.get(mat0).emissiveColor = new float[]{0.2f, 0.6f, 1.0f};  // beam glow: textureless emissiveFactor
        int mat1 = m.materialIndex("tex_1");
        Ir.Material pbr = m.materials.get(mat1);
        pbr.png = png;
        pbr.textureFile = "tex_1.png";
        pbr.normalPng = png;
        pbr.normalFile = "tex_1_n.png";
        pbr.mrPng = png;
        pbr.mrFile = "tex_1_mr.png";

        Ir.Primitive p0 = m.primitiveForMaterial(mat0);
        p0.group = "Body";
        quad(p0, 1, 0.5f, 0.7f, 1f);   // coloured → exercises COLOR_0
        Ir.Primitive p1 = m.primitiveForMaterial(mat1);
        p1.group = "Head";
        quad(p1, 2, 1f, 1f, 1f);       // white → no COLOR_0

        m.camera = new Ir.Camera(new float[]{0, 2, 5}, new float[]{0, 0, 0, 1}, 1.0f);
        m.camera.focusDistance = 5f;   // exercises depth-of-field extras
        m.extraCameras.add(new Ir.Camera(new float[]{5, 2, 0}, new float[]{0, 0.7f, 0, 0.7f}, 0.9f));
        m.extraCameras.get(0).name = "cam_hero";   // a placed (named) studio camera
        m.extraCameras.add(new Ir.Camera(new float[]{0, 8, 0}, new float[]{-0.7f, 0, 0, 0.7f}, 0.9f));
        m.sun = new Ir.Light(new float[]{-0.3f, -1f, -0.2f}, new float[]{1f, 0.95f, 0.9f}, 4f);
        m.lights.add(Ir.Light.point(new float[]{1f, 1.5f, 1f}, new float[]{1f, 0.86f, 0.66f}, 50f));
        m.lights.add(Ir.Light.point(new float[]{-1f, 1.5f, -1f}, new float[]{1f, 0.86f, 0.66f}, 50f));
        m.speakers.add(new Ir.Speaker("minecraft:block.note_block.harp", new float[]{2, 1, 3}, 0.5f, 1f));
        RetargetMap.apply(m);   // #16: tag root/body/head with Hips/Spine/Head (carried in node extras)
        return m;
    }

    private static void quad(Ir.Primitive p, int joint, float r, float g, float b) {
        p.addQuad(
                new Ir.Vertex(0, 0, 0, 0, 0, 1, 0, 0, joint, r, g, b, 1f),
                new Ir.Vertex(1, 0, 0, 0, 0, 1, 1, 0, joint, r, g, b, 1f),
                new Ir.Vertex(1, 1, 0, 0, 0, 1, 1, 1, joint, r, g, b, 1f),
                new Ir.Vertex(0, 1, 0, 0, 0, 1, 0, 1, joint, r, g, b, 1f));
    }

    private static Ir.Animation walkClip(String name, float phase) {
        Ir.Animation a = new Ir.Animation();
        a.name = name;
        for (int f = 0; f <= 10; f++) {
            float t = f / 10f;
            a.times.add(t);
            float ang = (float) Math.sin((t + phase) * Math.PI * 2) * 0.5f;
            Quaternionf q = new Quaternionf().rotationX(ang);
            a.key(1, new float[]{0, 1, 0}, new float[]{q.x, q.y, q.z, q.w});
            a.key(2, new float[]{0, 1, 0}, new float[]{q.x, q.y, q.z, q.w});
            a.cameraKey(new float[]{0, 2, 5f - t}, new float[]{0, 0, 0, 1});   // POV camera channel
        }
        return a;
    }

    /** The recurring "accessory inside the player" bug: a box dead-centre in the torso must be pushed
     *  clear of the body, and an accessory already outside must be left untouched. */
    private static void assertAccessoryClears() {
        List<float[]> body = boxVerts(-0.25f, 0f, -0.125f, 0.25f, 0.75f, 0.125f);     // torso
        List<float[]> acc = boxVerts(-0.05f, 0.35f, -0.05f, 0.05f, 0.45f, 0.05f);     // dead-centre inside
        float[] push = AccessoryPush.compute(acc, body);
        float aMinX = -0.05f + push[0], aMaxX = 0.05f + push[0];
        float aMinZ = -0.05f + push[1], aMaxZ = 0.05f + push[1];
        boolean clear = aMinX > 0.25f || aMaxX < -0.25f || aMinZ > 0.125f || aMaxZ < -0.125f;
        if (!clear) {
            throw new IllegalStateException("FAIL: accessory still inside body after push "
                    + push[0] + "," + push[1]);
        }
        float[] none = AccessoryPush.compute(boxVerts(0.4f, 0.35f, -0.05f, 0.5f, 0.45f, 0.05f), body);
        if (none[0] != 0f || none[1] != 0f) {
            throw new IllegalStateException("FAIL: an already-clear accessory was moved");
        }
        System.out.println("  accessory-push OK: centred accessory cleared (dx=" + push[0] + " dz=" + push[1]
                + "), already-clear one untouched");
    }

    /** Camera shake must be a no-op when off, and bounded + non-zero when on. */
    private static void assertCameraShake() {
        CameraShake.amount = 0f;
        float[] off = CameraShake.positionOffset(1.5f);
        if (off[0] != 0f || off[1] != 0f || off[2] != 0f) {
            throw new IllegalStateException("FAIL: shake should be zero when off");
        }
        CameraShake.amount = 4f;
        float bound = 4f * 0.03f * 1.5f + 1e-4f;
        boolean moved = false;
        for (float t = 0f; t < 3f; t += 0.1f) {
            for (float v : CameraShake.positionOffset(t)) {
                if (Math.abs(v) > 1e-4f) moved = true;
                if (Math.abs(v) > bound) {
                    throw new IllegalStateException("FAIL: shake offset out of bounds: " + v);
                }
            }
        }
        CameraShake.amount = 0f;   // reset so the exported GLBs aren't shaken
        if (!moved) {
            throw new IllegalStateException("FAIL: shake on produced no motion");
        }
        System.out.println("  camera-shake OK: zero when off, bounded & non-zero when on");
    }

    /** Weather field (studio #10): points stay inside the volume, scale with intensity, colour by type. */
    private static void assertWeatherField() {
        int radius = 16;
        List<float[]> rain = WeatherField.points(radius, 1.0f, false, 123L);
        if (rain.isEmpty()) {
            throw new IllegalStateException("FAIL: full-intensity weather produced no points");
        }
        float topY = radius + WeatherField.HEADROOM;
        for (float[] p : rain) {
            if (Math.abs(p[0]) > radius + 1e-3f || Math.abs(p[2]) > radius + 1e-3f
                    || p[1] < -radius - 1e-3f || p[1] > topY + 1e-3f) {
                throw new IllegalStateException("FAIL: weather point outside the volume: "
                        + p[0] + "," + p[1] + "," + p[2]);
            }
            for (int c = 3; c < 6; c++) {
                if (p[c] < 0f || p[c] > 1f) {
                    throw new IllegalStateException("FAIL: weather colour out of range: " + p[c]);
                }
            }
        }
        List<float[]> light = WeatherField.points(radius, 0.3f, false, 123L);
        if (!(light.size() < rain.size())) {
            throw new IllegalStateException("FAIL: weather density should scale with intensity ("
                    + light.size() + " !< " + rain.size() + ")");
        }
        // snow reads whiter than rain: its blue channel sits higher on average
        List<float[]> snow = WeatherField.points(radius, 1.0f, true, 123L);
        if (!(avgChannel(snow, 5) > avgChannel(rain, 5))) {
            throw new IllegalStateException("FAIL: snow should be whiter (bluer) than rain");
        }
        System.out.println("  weather-field OK: " + rain.size() + " in-bounds rain pts, scales w/ intensity, "
                + "snow whiter than rain");
    }

    /** Sky geometry (studio #11): dome vertices sit on the sphere with a top→bottom gradient; clouds
     *  are a flat self-lit layer at a single height within bounds. */
    private static void assertSkyGeometry() {
        Ir.Model m = new Ir.Model();
        Ir.Bone root = new Ir.Bone("scene", -1, new Matrix4f());
        root.localTransform = new Matrix4f();
        m.addBone(root);

        float radius = 100f;
        float[] zenith = {0.2f, 0.4f, 0.9f}, horizon = {0.7f, 0.8f, 1.0f};
        SkyGeometry.addDome(m, radius, zenith, horizon, 12, 18, 0);
        Ir.Primitive dome = m.primitives.get(0);
        float top = -1e9f, bot = 1e9f;
        Ir.Vertex topV = null, botV = null;
        for (Ir.Vertex v : dome.vertices) {
            float len = (float) Math.sqrt(v.px * v.px + v.py * v.py + v.pz * v.pz);
            if (Math.abs(len - radius) > 0.5f) {
                throw new IllegalStateException("FAIL: dome vertex off the sphere (len=" + len + " r=" + radius + ")");
            }
            if (v.py > top) { top = v.py; topV = v; }
            if (v.py < bot) { bot = v.py; botV = v; }
        }
        // top of the dome should be the zenith colour, bottom the horizon colour
        if (Math.abs(topV.b - zenith[2]) > 0.02f || Math.abs(botV.b - horizon[2]) > 0.02f) {
            throw new IllegalStateException("FAIL: dome gradient wrong (top.b=" + topV.b + " bot.b=" + botV.b + ")");
        }

        float cloudY = 60f, extent = 100f;
        int cells = SkyGeometry.addClouds(m, extent, cloudY, 12f, new float[]{1f, 1f, 1f}, 42L, 0);
        if (cells <= 0) {
            throw new IllegalStateException("FAIL: clouds produced no cells");
        }
        Ir.Primitive clouds = m.primitives.get(1);
        for (Ir.Vertex v : clouds.vertices) {
            if (Math.abs(v.py - cloudY) > 1e-3f || Math.abs(v.px) > extent + 12f || Math.abs(v.pz) > extent + 12f) {
                throw new IllegalStateException("FAIL: cloud vertex out of layer: "
                        + v.px + "," + v.py + "," + v.pz);
            }
        }
        if (m.materials.get(m.materialIndex("Clouds")).emissiveColor == null) {
            throw new IllegalStateException("FAIL: clouds should be self-lit (emissiveColor)");
        }
        System.out.println("  sky-geometry OK: " + dome.vertices.size() + " dome verts on-sphere w/ gradient, "
                + cells + " self-lit cloud cells in bounds");
    }

    /** Speakers (studio #12): repeated sounds at one spot collapse to a single emitter, keeping the
     *  earliest time; distinct sounds/positions stay separate. */
    private static void assertSpeakers() {
        List<Ir.Event> events = new ArrayList<>();
        events.add(new Ir.Event(1.0f, "sound:minecraft:block.note_block.harp", new float[]{0, 1, 0}));
        events.add(new Ir.Event(0.5f, "sound:minecraft:block.note_block.harp", new float[]{0.2f, 1f, 0.1f})); // same spot, earlier
        events.add(new Ir.Event(2.0f, "sound:minecraft:entity.zombie.ambient", new float[]{10, 2, 10}));      // different sound
        events.add(new Ir.Event(3.0f, "break:minecraft:stone", new float[]{1, 1, 1}));                        // not a sound
        events.add(new Ir.Event(4.0f, "sound:minecraft:weather.rain", null));                                 // no position
        List<Ir.Speaker> sp = Speakers.fromEvents(events);
        if (sp.size() != 2) {
            throw new IllegalStateException("FAIL: expected 2 speakers (deduped), got " + sp.size());
        }
        Ir.Speaker harp = sp.stream().filter(s -> s.sound.contains("harp")).findFirst().orElse(null);
        if (harp == null || Math.abs(harp.time - 0.5f) > 1e-6f) {
            throw new IllegalStateException("FAIL: harp speaker should keep the earliest time 0.5");
        }
        System.out.println("  speakers OK: 5 events -> 2 deduped speakers, earliest time kept");
    }

    /** NLA stacking (studio #14): colliding clip names are made unique (stable order) so each imports
     *  as a distinct Blender Action; blanks become "clip". */
    private static void assertNlaUniqueNames() {
        List<String> in = new ArrayList<>(List.of("walk", "take", "take", "take", "walk", ""));
        List<String> out = NlaStack.uniqueNames(in);
        List<String> expect = List.of("walk", "take", "take_2", "take_3", "walk_2", "clip");
        if (!out.equals(expect)) {
            throw new IllegalStateException("FAIL: NLA unique names " + out + " != " + expect);
        }
        if (out.size() != new java.util.HashSet<>(out).size()) {
            throw new IllegalStateException("FAIL: NLA names not unique: " + out);
        }
        System.out.println("  nla-names OK: collisions de-duplicated, order preserved -> " + out);
    }

    /** Preview grid (studio #19): the footprint maps onto an N×N grid; centre/corners land in the right
     *  cells, out-of-range columns are dropped, and build() samples one colour per cell. */
    private static void assertPreviewGrid() {
        int cx = 100, cz = 200, radius = 16, size = PreviewGrid.SIZE;
        // a column just inside the centre maps to the middle row/col; the NW corner to (0,0)
        int mid = PreviewGrid.index(cx, cz, cx, cz, radius, size);
        if (mid != (size / 2) * size + (size / 2)) {
            throw new IllegalStateException("FAIL: centre cell wrong: " + mid);
        }
        if (PreviewGrid.index(cx - radius, cz - radius, cx, cz, radius, size) != 0) {
            throw new IllegalStateException("FAIL: NW corner should be cell 0");
        }
        if (PreviewGrid.index(cx + radius * 4, cz, cx, cz, radius, size) != -1) {
            throw new IllegalStateException("FAIL: far-out column should be -1");
        }
        // build() must produce size*size cells, sampling each cell's world position
        int[] grid = PreviewGrid.build(cx, cz, radius, size, (wx, wz) -> (wx << 16) | (wz & 0xFFFF));
        if (grid.length != size * size) {
            throw new IllegalStateException("FAIL: grid length " + grid.length + " != " + (size * size));
        }
        // cells increase in world-x left→right and world-z top→bottom
        if (!(decodeX(grid[0]) < decodeX(grid[size - 1]))) {
            throw new IllegalStateException("FAIL: grid columns not west→east");
        }
        System.out.println("  preview-grid OK: " + size + "×" + size + " cells, centre/corner mapping correct");
    }

    private static int decodeX(int packed) {
        return packed >> 16;
    }

    /** Studio presets (#18): a config round-trips through JSON, and apply() pushes to the global holders. */
    private static void assertPresets() {
        StudioConfig c = new StudioConfig();
        c.radius = 24;
        c.slowmo = 4f;
        c.shake = 3f;
        c.fps = 60;
        c.dof = false;
        StudioConfig back = StudioConfig.fromJson(c.toJson());
        if (back.radius != 24 || back.slowmo != 4f || back.shake != 3f || back.fps != 60 || back.dof) {
            throw new IllegalStateException("FAIL: preset JSON round-trip lost data: " + back.describe());
        }
        // apply() must push slow-mo + shake to the live holders and become CURRENT
        SlowMo.set(1f);
        CameraShake.amount = 0f;
        back.apply();
        if (SlowMo.factor() != 4f || CameraShake.amount != 3f || StudioConfig.CURRENT != back) {
            throw new IllegalStateException("FAIL: preset apply() didn't update globals");
        }
        SlowMo.set(1f);                 // reset so exported GLBs aren't slowed
        CameraShake.amount = 0f;
        StudioConfig.CURRENT = new StudioConfig();
        System.out.println("  presets OK: JSON round-trip + apply() pushes slow-mo/shake to globals");
    }

    /** Shot markers (studio #17): only "shot:" events are extracted, prefix stripped, sorted by time. */
    private static void assertShots() {
        List<Ir.Event> events = new ArrayList<>();
        events.add(new Ir.Event(5.0f, "shot:Outro", null));
        events.add(new Ir.Event(1.0f, "shot:Intro", null));
        events.add(new Ir.Event(2.0f, "break:minecraft:stone", new float[]{0, 0, 0}));   // not a shot
        events.add(new Ir.Event(3.0f, "shot:Action", null));
        List<Shots.Shot> shots = Shots.fromEvents(events);
        if (shots.size() != 3) {
            throw new IllegalStateException("FAIL: expected 3 shots, got " + shots.size());
        }
        if (!shots.get(0).name.equals("Intro") || !shots.get(1).name.equals("Action")
                || !shots.get(2).name.equals("Outro")) {
            throw new IllegalStateException("FAIL: shots not in time order / prefix not stripped: "
                    + shots.get(0).name + "," + shots.get(1).name + "," + shots.get(2).name);
        }
        System.out.println("  shots OK: 4 events -> 3 named shots, prefix stripped, time-ordered");
    }

    /** Retarget map (studio #16): Minecraft bone names map to humanoid labels (case/separator-insensitive). */
    private static void assertRetargetMap() {
        check("Hips".equals(RetargetMap.humanoid("root")), "root -> Hips");
        check("Spine".equals(RetargetMap.humanoid("body")), "body -> Spine");
        check("Head".equals(RetargetMap.humanoid("head")), "head -> Head");
        check("RightArm".equals(RetargetMap.humanoid("right_arm")), "right_arm -> RightArm");
        check("LeftArm".equals(RetargetMap.humanoid("leftArm")), "leftArm -> LeftArm (separator-insensitive)");
        check("RightUpLeg".equals(RetargetMap.humanoid("right_leg")), "right_leg -> RightUpLeg");
        check(RetargetMap.humanoid("scene") == null, "unknown bone -> null");
        System.out.println("  retarget-map OK: MC bones -> humanoid labels (Hips/Spine/Head/arms/legs)");
    }

    private static void check(boolean cond, String label) {
        if (!cond) throw new IllegalStateException("FAIL: retarget map " + label);
    }

    private static float avgChannel(List<float[]> pts, int ch) {
        float s = 0f;
        for (float[] p : pts) s += p[ch];
        return s / Math.max(1, pts.size());
    }

    private static List<float[]> boxVerts(float x0, float y0, float z0, float x1, float y1, float z1) {
        List<float[]> v = new ArrayList<>();
        for (float x : new float[]{x0, x1}) {
            for (float y : new float[]{y0, y1}) {
                for (float z : new float[]{z0, z1}) {
                    v.add(new float[]{x, y, z});
                }
            }
        }
        return v;
    }

    /** A minimal valid 1x1 PNG, so embedded image bytes are real PNGs. */
    private static byte[] tinyPng() {
        int[] u = {137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, 1, 0, 0, 0, 1,
                8, 6, 0, 0, 0, 31, 21, 196, 137, 0, 0, 0, 10, 73, 68, 65, 84, 120, 156, 99, 0, 1, 0, 0,
                5, 0, 1, 13, 10, 45, 180, 0, 0, 0, 0, 73, 69, 78, 68, 174, 66, 96, 130};
        byte[] b = new byte[u.length];
        for (int i = 0; i < u.length; i++) b[i] = (byte) u[i];
        return b;
    }
}
