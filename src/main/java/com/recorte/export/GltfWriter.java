package com.recorte.export;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an {@link Ir.Model} as a self-contained binary glTF ({@code .glb}) with a skinned mesh:
 * one node per bone, a skin with inverse-bind matrices, and every material's texture (PNG) embedded in
 * the binary chunk. Primitives are grouped by {@link Ir.Primitive#group} into separate meshes, so the
 * body and the accessories come into Blender as distinct objects sharing one armature.
 */
public final class GltfWriter {
    private GltfWriter() {}

    private static final int FLOAT = 5126;
    private static final int UNSIGNED_INT = 5125;
    private static final int UNSIGNED_SHORT = 5123;
    private static final int ARRAY_BUFFER = 34962;
    private static final int ELEMENT_ARRAY_BUFFER = 34963;
    private static final int NEAREST = 9728;
    private static final int CLAMP_TO_EDGE = 33071;

    public static void write(Ir.Model model, Path glbPath) throws IOException {
        write(model, null, glbPath);
    }

    public static void write(Ir.Model model, Ir.Animation animation, Path glbPath) throws IOException {
        Bin bin = new Bin();

        JsonObject root = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "Recorte Player Model Exporter");
        root.add("asset", asset);

        // --- bone nodes ---------------------------------------------------------------------------
        JsonArray nodes = new JsonArray();
        for (int i = 0; i < model.bones.size(); i++) {
            Ir.Bone b = model.bones.get(i);
            JsonObject node = new JsonObject();
            node.addProperty("name", b.name);
            node.add("matrix", matrixArray(b.localTransform));
            JsonArray children = new JsonArray();
            for (int j = 0; j < model.bones.size(); j++) {
                if (model.bones.get(j).parentIndex == i) children.add(j);
            }
            if (children.size() > 0) node.add("children", children);
            nodes.add(node);
        }

        // --- skin: joints + inverse bind matrices -------------------------------------------------
        bin.align4();
        int ibmStart = bin.length();
        for (Ir.Bone b : model.bones) {
            bin.matrix(new Matrix4f(b.globalBind).invert());
        }
        int ibmView = bin.bufferView(ibmStart, bin.length() - ibmStart, null);
        int ibmAccessor = bin.accessor(ibmView, FLOAT, model.bones.size(), "MAT4", null, null);

        JsonArray joints = new JsonArray();
        for (int i = 0; i < model.bones.size(); i++) joints.add(i);
        JsonObject skin = new JsonObject();
        skin.add("joints", joints);
        skin.addProperty("inverseBindMatrices", ibmAccessor);
        skin.addProperty("skeleton", 0);
        JsonArray skins = new JsonArray();
        skins.add(skin);
        root.add("skins", skins);

        // --- primitives, bucketed into one mesh per group (Player / Accessories) ------------------
        Map<String, JsonArray> groups = new LinkedHashMap<>();
        for (Ir.Primitive prim : model.primitives) {
            if (prim.vertices.isEmpty()) continue;
            groups.computeIfAbsent(prim.group, g -> new JsonArray()).add(buildPrimitive(bin, prim));
        }

        JsonArray meshes = new JsonArray();
        List<Integer> meshNodes = new ArrayList<>();
        for (Map.Entry<String, JsonArray> e : groups.entrySet()) {
            JsonObject mesh = new JsonObject();
            mesh.add("primitives", e.getValue());
            mesh.addProperty("name", e.getKey());
            meshes.add(mesh);
            JsonObject node = new JsonObject();
            node.addProperty("name", e.getKey());
            node.addProperty("mesh", meshes.size() - 1);
            node.addProperty("skin", 0);
            nodes.add(node);
            meshNodes.add(nodes.size() - 1);
        }
        root.add("meshes", meshes);
        root.add("nodes", nodes);

        JsonArray sceneNodes = new JsonArray();
        for (int i = 0; i < model.bones.size(); i++) {   // every armature root (snapshot has one per mob)
            if (model.bones.get(i).parentIndex < 0) sceneNodes.add(i);
        }
        for (int idx : meshNodes) sceneNodes.add(idx);
        JsonObject scene = new JsonObject();
        scene.add("nodes", sceneNodes);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        root.add("scenes", scenes);
        root.addProperty("scene", 0);

        // --- embedded textures (one image per material that has a PNG) ----------------------------
        JsonArray images = new JsonArray();
        JsonArray textures = new JsonArray();
        Map<Integer, Integer> materialToTexture = new HashMap<>();
        for (int mi = 0; mi < model.materials.size(); mi++) {
            Ir.Material m = model.materials.get(mi);
            if (m.png == null) continue;
            bin.align4();
            int imgStart = bin.length();
            bin.bytes(m.png);
            int imgView = bin.bufferView(imgStart, m.png.length, null);

            JsonObject image = new JsonObject();
            image.addProperty("bufferView", imgView);
            image.addProperty("mimeType", "image/png");
            images.add(image);

            JsonObject texture = new JsonObject();
            texture.addProperty("source", images.size() - 1);
            texture.addProperty("sampler", 0);
            textures.add(texture);
            materialToTexture.put(mi, textures.size() - 1);
        }
        if (textures.size() > 0) {
            JsonObject sampler = new JsonObject();
            sampler.addProperty("magFilter", NEAREST);
            sampler.addProperty("minFilter", NEAREST);
            sampler.addProperty("wrapS", CLAMP_TO_EDGE);
            sampler.addProperty("wrapT", CLAMP_TO_EDGE);
            JsonArray samplers = new JsonArray();
            samplers.add(sampler);
            root.add("samplers", samplers);
            root.add("images", images);
            root.add("textures", textures);
        }

        JsonArray materials = new JsonArray();
        boolean anyEmissive = false;
        for (int mi = 0; mi < model.materials.size(); mi++) {
            Ir.Material m = model.materials.get(mi);
            JsonObject material = new JsonObject();
            material.addProperty("name", m.name);
            JsonObject pbr = new JsonObject();
            Integer tex = materialToTexture.get(mi);
            if (tex != null) {
                JsonObject baseColorTexture = new JsonObject();
                baseColorTexture.addProperty("index", tex);
                pbr.add("baseColorTexture", baseColorTexture);
            }
            pbr.addProperty("metallicFactor", 0);
            pbr.addProperty("roughnessFactor", 1);
            material.add("pbrMetallicRoughness", pbr);
            if (m.emissive && tex != null) {
                JsonObject emissiveTexture = new JsonObject();
                emissiveTexture.addProperty("index", tex);
                material.add("emissiveTexture", emissiveTexture);
                JsonArray ef = new JsonArray();
                ef.add(1); ef.add(1); ef.add(1);
                material.add("emissiveFactor", ef);
                JsonObject strength = new JsonObject();
                strength.addProperty("emissiveStrength", 4.0);
                JsonObject ext = new JsonObject();
                ext.add("KHR_materials_emissive_strength", strength);
                material.add("extensions", ext);
                anyEmissive = true;
            }
            material.addProperty("alphaMode", "MASK");
            material.addProperty("alphaCutoff", 0.5);
            material.addProperty("doubleSided", true);
            materials.add(material);
        }
        root.add("materials", materials);
        if (anyEmissive) {
            JsonArray used = new JsonArray();
            used.add("KHR_materials_emissive_strength");
            root.add("extensionsUsed", used);
        }

        // --- animation (optional) -----------------------------------------------------------------
        if (animation != null && !animation.times.isEmpty()) {
            writeAnimation(root, bin, animation);
        }

        // --- buffer + bufferViews + accessors -----------------------------------------------------
        byte[] binData = bin.data.toByteArray();
        JsonObject buffer = new JsonObject();
        buffer.addProperty("byteLength", binData.length);
        JsonArray buffers = new JsonArray();
        buffers.add(buffer);
        root.add("buffers", buffers);
        root.add("bufferViews", bin.bufferViews);
        root.add("accessors", bin.accessors);

        byte[] json = new Gson().toJson(root).getBytes(StandardCharsets.UTF_8);
        Files.write(glbPath, assembleGlb(json, binData));
    }

    /** Writes one primitive's vertex data into the bin and returns its glTF primitive object. */
    private static JsonObject buildPrimitive(Bin bin, Ir.Primitive prim) {
        int n = prim.vertices.size();

        bin.align4();
        int pStart = bin.length();
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Ir.Vertex v : prim.vertices) {
            bin.f(v.px); bin.f(v.py); bin.f(v.pz);
            min[0] = Math.min(min[0], v.px); max[0] = Math.max(max[0], v.px);
            min[1] = Math.min(min[1], v.py); max[1] = Math.max(max[1], v.py);
            min[2] = Math.min(min[2], v.pz); max[2] = Math.max(max[2], v.pz);
        }
        int posAcc = bin.accessor(bin.bufferView(pStart, bin.length() - pStart, ARRAY_BUFFER),
                FLOAT, n, "VEC3", min, max);

        bin.align4();
        int nStart = bin.length();
        for (Ir.Vertex v : prim.vertices) { bin.f(v.nx); bin.f(v.ny); bin.f(v.nz); }
        int nrmAcc = bin.accessor(bin.bufferView(nStart, bin.length() - nStart, ARRAY_BUFFER),
                FLOAT, n, "VEC3", null, null);

        bin.align4();
        int tStart = bin.length();
        for (Ir.Vertex v : prim.vertices) { bin.f(v.u); bin.f(v.v); }
        int uvAcc = bin.accessor(bin.bufferView(tStart, bin.length() - tStart, ARRAY_BUFFER),
                FLOAT, n, "VEC2", null, null);

        bin.align4();
        int jStart = bin.length();
        for (Ir.Vertex v : prim.vertices) { bin.u16(v.joint); bin.u16(0); bin.u16(0); bin.u16(0); }
        int jntAcc = bin.accessor(bin.bufferView(jStart, bin.length() - jStart, ARRAY_BUFFER),
                UNSIGNED_SHORT, n, "VEC4", null, null);

        bin.align4();
        int wStart = bin.length();
        for (int i = 0; i < n; i++) { bin.f(1f); bin.f(0f); bin.f(0f); bin.f(0f); }
        int wgtAcc = bin.accessor(bin.bufferView(wStart, bin.length() - wStart, ARRAY_BUFFER),
                FLOAT, n, "VEC4", null, null);

        boolean colored = false;
        for (Ir.Vertex v : prim.vertices) {
            if (!v.isWhite()) { colored = true; break; }
        }
        Integer colorAcc = null;
        if (colored) {
            bin.align4();
            int cStart = bin.length();
            for (Ir.Vertex v : prim.vertices) { bin.f(v.r); bin.f(v.g); bin.f(v.b); bin.f(v.a); }
            colorAcc = bin.accessor(bin.bufferView(cStart, bin.length() - cStart, ARRAY_BUFFER),
                    FLOAT, n, "VEC4", null, null);
        }

        bin.align4();
        int iStart = bin.length();
        for (int idx : prim.indices) bin.u32(idx);
        int idxAcc = bin.accessor(bin.bufferView(iStart, bin.length() - iStart, ELEMENT_ARRAY_BUFFER),
                UNSIGNED_INT, prim.indices.size(), "SCALAR", null, null);

        JsonObject attributes = new JsonObject();
        attributes.addProperty("POSITION", posAcc);
        attributes.addProperty("NORMAL", nrmAcc);
        attributes.addProperty("TEXCOORD_0", uvAcc);
        attributes.addProperty("JOINTS_0", jntAcc);
        attributes.addProperty("WEIGHTS_0", wgtAcc);
        if (colorAcc != null) attributes.addProperty("COLOR_0", colorAcc);

        JsonObject primitive = new JsonObject();
        primitive.add("attributes", attributes);
        primitive.addProperty("indices", idxAcc);
        primitive.addProperty("material", prim.materialIndex);
        return primitive;
    }

    private static void writeAnimation(JsonObject root, Bin bin, Ir.Animation anim) {
        int n = anim.times.size();
        bin.align4();
        int tStart = bin.length();
        float tmin = Float.MAX_VALUE, tmax = -Float.MAX_VALUE;
        for (float t : anim.times) {
            bin.f(t);
            tmin = Math.min(tmin, t);
            tmax = Math.max(tmax, t);
        }
        int timeAcc = bin.accessor(bin.bufferView(tStart, bin.length() - tStart, null),
                FLOAT, n, "SCALAR", new float[]{tmin}, new float[]{tmax});

        JsonArray samplers = new JsonArray();
        JsonArray channels = new JsonArray();
        for (Map.Entry<Integer, java.util.List<float[]>> e : anim.translations.entrySet()) {
            int bone = e.getKey();
            java.util.List<float[]> trans = e.getValue();
            java.util.List<float[]> rots = anim.rotations.get(bone);

            bin.align4();
            int s = bin.length();
            for (float[] v : trans) { bin.f(v[0]); bin.f(v[1]); bin.f(v[2]); }
            int transAcc = bin.accessor(bin.bufferView(s, bin.length() - s, null), FLOAT, trans.size(), "VEC3", null, null);

            bin.align4();
            s = bin.length();
            for (float[] q : rots) { bin.f(q[0]); bin.f(q[1]); bin.f(q[2]); bin.f(q[3]); }
            int rotAcc = bin.accessor(bin.bufferView(s, bin.length() - s, null), FLOAT, rots.size(), "VEC4", null, null);

            int sT = samplers.size();
            samplers.add(sampler(timeAcc, transAcc));
            channels.add(channel(sT, bone, "translation"));
            int sR = samplers.size();
            samplers.add(sampler(timeAcc, rotAcc));
            channels.add(channel(sR, bone, "rotation"));
        }

        JsonObject animation = new JsonObject();
        animation.add("samplers", samplers);
        animation.add("channels", channels);
        animation.addProperty("name", "recording");
        JsonArray animations = new JsonArray();
        animations.add(animation);
        root.add("animations", animations);
    }

    private static JsonObject sampler(int input, int output) {
        JsonObject s = new JsonObject();
        s.addProperty("input", input);
        s.addProperty("output", output);
        s.addProperty("interpolation", "LINEAR");
        return s;
    }

    private static JsonObject channel(int sampler, int node, String path) {
        JsonObject c = new JsonObject();
        c.addProperty("sampler", sampler);
        JsonObject target = new JsonObject();
        target.addProperty("node", node);
        target.addProperty("path", path);
        c.add("target", target);
        return c;
    }

    private static byte[] assembleGlb(byte[] json, byte[] binData) {
        int jsonPad = (4 - (json.length % 4)) % 4;
        int binPad = (4 - (binData.length % 4)) % 4;
        int total = 12 + (8 + json.length + jsonPad) + (8 + binData.length + binPad);

        ByteBuffer bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0x46546C67);
        bb.putInt(2);
        bb.putInt(total);

        bb.putInt(json.length + jsonPad);
        bb.putInt(0x4E4F534A);
        bb.put(json);
        for (int i = 0; i < jsonPad; i++) bb.put((byte) 0x20);

        bb.putInt(binData.length + binPad);
        bb.putInt(0x004E4942);
        bb.put(binData);
        for (int i = 0; i < binPad; i++) bb.put((byte) 0x00);

        return bb.array();
    }

    private static JsonArray matrixArray(Matrix4f m) {
        float[] f = new float[16];
        m.get(f);
        JsonArray a = new JsonArray();
        for (float v : f) a.add(v);
        return a;
    }

    private static final class Bin {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        final JsonArray bufferViews = new JsonArray();
        final JsonArray accessors = new JsonArray();

        int length() { return data.size(); }

        void align4() { while (data.size() % 4 != 0) data.write(0); }

        void f(float value) { u32(Float.floatToIntBits(value)); }

        void u32(int v) {
            data.write(v & 0xFF);
            data.write((v >>> 8) & 0xFF);
            data.write((v >>> 16) & 0xFF);
            data.write((v >>> 24) & 0xFF);
        }

        void u16(int v) {
            data.write(v & 0xFF);
            data.write((v >>> 8) & 0xFF);
        }

        void bytes(byte[] b) { data.writeBytes(b); }

        void matrix(Matrix4f m) {
            float[] f = new float[16];
            m.get(f);
            for (float v : f) f(v);
        }

        int bufferView(int byteOffset, int byteLength, Integer target) {
            JsonObject bv = new JsonObject();
            bv.addProperty("buffer", 0);
            bv.addProperty("byteOffset", byteOffset);
            bv.addProperty("byteLength", byteLength);
            if (target != null) bv.addProperty("target", target);
            bufferViews.add(bv);
            return bufferViews.size() - 1;
        }

        int accessor(int bufferView, int componentType, int count, String type, float[] min, float[] max) {
            JsonObject a = new JsonObject();
            a.addProperty("bufferView", bufferView);
            a.addProperty("componentType", componentType);
            a.addProperty("count", count);
            a.addProperty("type", type);
            if (min != null) { JsonArray j = new JsonArray(); for (float v : min) j.add(v); a.add("min", j); }
            if (max != null) { JsonArray j = new JsonArray(); for (float v : max) j.add(v); a.add("max", j); }
            accessors.add(a);
            return accessors.size() - 1;
        }
    }
}
