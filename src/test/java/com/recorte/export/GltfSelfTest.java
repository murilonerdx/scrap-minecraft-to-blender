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

        Ir.Model model = buildRig();

        // 1) single animation (the recording path)
        GltfWriter.write(model, walkClip("recording", 0.5f), outDir.resolve("single.glb"));

        // 2) multi-clip animation library (the riskiest refactor) + a spinning clip that would flip
        //    quaternion signs without the hemisphere fix (the cause of jittery recorded animations)
        List<Ir.Animation> lib = new ArrayList<>();
        lib.add(walkClip("idle", 0.1f));
        lib.add(walkClip("walk", 0.5f));
        lib.add(walkClip("run", 0.9f));
        lib.add(spinClip("spin"));
        for (Ir.Animation a : lib) assertContinuous(a);
        GltfWriter.writeLibrary(model, lib, outDir.resolve("library.glb"));

        // 3) static (no animation) — cameras + sun + PBR textures
        GltfWriter.write(model, outDir.resolve("static.glb"));

        System.out.println("SELFTEST OK -> single.glb, library.glb, static.glb in " + outDir.toAbsolutePath());
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
        m.extraCameras.add(new Ir.Camera(new float[]{5, 2, 0}, new float[]{0, 0.7f, 0, 0.7f}, 0.9f));
        m.extraCameras.add(new Ir.Camera(new float[]{0, 8, 0}, new float[]{-0.7f, 0, 0, 0.7f}, 0.9f));
        m.sun = new Ir.Light(new float[]{-0.3f, -1f, -0.2f}, new float[]{1f, 0.95f, 0.9f}, 4f);
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
