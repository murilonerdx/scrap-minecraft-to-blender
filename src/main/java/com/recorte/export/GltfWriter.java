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
        writeInternal(model, java.util.List.of(), glbPath);
    }

    public static void write(Ir.Model model, Ir.Animation animation, Path glbPath) throws IOException {
        writeInternal(model, animation == null ? java.util.List.of() : java.util.List.of(animation), glbPath);
    }

    /** Writes several named animations into one file — a reusable Action library on the rig. */
    public static void writeLibrary(Ir.Model model, java.util.List<Ir.Animation> animations, Path glbPath)
            throws IOException {
        writeInternal(model, animations, glbPath);
    }

    private static void writeInternal(Ir.Model model, java.util.List<Ir.Animation> animations, Path glbPath)
            throws IOException {
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
            // #16 retarget rig: name the bone with its humanoid label when requested (Mixamo-style),
            // and always carry the label in extras so retargeting tools/the add-on can map by it
            boolean retarget = model.useRetargetNames && b.retargetName != null;
            node.addProperty("name", retarget ? b.retargetName : b.name);
            if (b.retargetName != null) {
                JsonObject extras = new JsonObject();
                extras.addProperty("retarget", b.retargetName);
                extras.addProperty("mcBone", b.name);
                node.add("extras", extras);
            }
            // TRS (not a matrix) so the node can be animation-targeted (glTF forbids matrix + animation)
            org.joml.Vector3f tt = b.localTransform.getTranslation(new org.joml.Vector3f());
            org.joml.Quaternionf rr = b.localTransform.getNormalizedRotation(new org.joml.Quaternionf());
            org.joml.Vector3f ss = b.localTransform.getScale(new org.joml.Vector3f());
            node.add("translation", vec3(tt.x, tt.y, tt.z));
            node.add("rotation", vec4(rr.x, rr.y, rr.z, rr.w));
            node.add("scale", vec3(ss.x, ss.y, ss.z));
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

        // cameras: the primary (in-game view, animation-targeted) + any preset angles for renders.
        // Extra camera nodes are appended here / below the sun, so they never shift the bone or mesh
        // node indices the skin and animation rely on.
        JsonArray cameras = new JsonArray();
        int cameraNode = -1;
        List<Integer> extraCameraNodes = new ArrayList<>();
        if (model.camera != null) {
            cameras.add(perspectiveCamera(model.camera.yfovRadians));
            nodes.add(cameraNodeObj("Camera", cameras.size() - 1, model.camera));
            cameraNode = nodes.size() - 1;
        }
        for (int ci = 0; ci < model.extraCameras.size(); ci++) {
            Ir.Camera ec = model.extraCameras.get(ci);
            cameras.add(perspectiveCamera(ec.yfovRadians));
            String camName = ec.name != null ? ec.name : "Camera_" + (ci + 1);
            nodes.add(cameraNodeObj(camName, cameras.size() - 1, ec));
            extraCameraNodes.add(nodes.size() - 1);
        }
        if (cameras.size() > 0) root.add("cameras", cameras);

        // lights via KHR_lights_punctual: the directional sun + any point lights (torches, glowstone…)
        List<Integer> lightNodes = new ArrayList<>();
        JsonArray lights = new JsonArray();
        if (model.sun != null) {
            JsonObject light = new JsonObject();
            light.addProperty("type", "directional");
            light.add("color", vec3(model.sun.color[0], model.sun.color[1], model.sun.color[2]));
            light.addProperty("intensity", model.sun.intensity);
            int li = lights.size();
            lights.add(light);

            org.joml.Vector3f d = new org.joml.Vector3f(
                    model.sun.direction[0], model.sun.direction[1], model.sun.direction[2]);
            if (d.lengthSquared() < 1e-8f) d.set(0, -1, 0);
            float ux = 0, uy = 1, uz = 0;
            if (Math.abs(d.y) > 0.99f) { uy = 0; uz = 1; }   // avoid degenerate up
            org.joml.Quaternionf q = new org.joml.Quaternionf().lookAlong(d.x, d.y, d.z, ux, uy, uz).conjugate();
            JsonObject node = new JsonObject();
            node.addProperty("name", "Sun");
            node.add("rotation", vec4(q.x, q.y, q.z, q.w));
            node.add("extensions", lightRef(li));
            nodes.add(node);
            lightNodes.add(nodes.size() - 1);
        }
        for (int pli = 0; pli < model.lights.size(); pli++) {
            Ir.Light pl = model.lights.get(pli);
            JsonObject light = new JsonObject();
            light.addProperty("type", "point");
            light.add("color", vec3(pl.color[0], pl.color[1], pl.color[2]));
            light.addProperty("intensity", pl.intensity);
            int li = lights.size();
            lights.add(light);

            JsonObject node = new JsonObject();
            node.addProperty("name", "Lamp_" + pli);
            node.add("translation", vec3(pl.position[0], pl.position[1], pl.position[2]));
            node.add("extensions", lightRef(li));
            nodes.add(node);
            lightNodes.add(nodes.size() - 1);
        }
        if (lights.size() > 0) {
            JsonObject lp = new JsonObject();
            lp.add("lights", lights);
            JsonObject rootExt = new JsonObject();
            rootExt.add("KHR_lights_punctual", lp);
            root.add("extensions", rootExt);
        }

        // sound emitters → named, positioned nodes carrying the sound id/time/gain in extras; the add-on
        // turns these into Blender Speaker objects for spatial audio in the VSE.
        List<Integer> speakerNodes = new ArrayList<>();
        for (Ir.Speaker sp : model.speakers) {
            JsonObject node = new JsonObject();
            node.addProperty("name", "Speaker_" + shortName(sp.sound));
            if (sp.position != null) node.add("translation", vec3(sp.position[0], sp.position[1], sp.position[2]));
            JsonObject extras = new JsonObject();
            extras.addProperty("sound", sp.sound);
            extras.addProperty("time", sp.time);
            extras.addProperty("gain", sp.gain);
            node.add("extras", extras);
            nodes.add(node);
            speakerNodes.add(nodes.size() - 1);
        }
        root.add("nodes", nodes);

        JsonArray sceneNodes = new JsonArray();
        for (int i = 0; i < model.bones.size(); i++) {   // every armature root (snapshot has one per mob)
            if (model.bones.get(i).parentIndex < 0) sceneNodes.add(i);
        }
        for (int idx : meshNodes) sceneNodes.add(idx);
        if (cameraNode >= 0) sceneNodes.add(cameraNode);
        for (int idx : extraCameraNodes) sceneNodes.add(idx);
        for (int idx : lightNodes) sceneNodes.add(idx);
        for (int idx : speakerNodes) sceneNodes.add(idx);
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
        // LabPBR normal maps → glTF normalTexture (only materials that found a sibling _n texture)
        Map<Integer, Integer> materialToNormal = new HashMap<>();
        for (int mi = 0; mi < model.materials.size(); mi++) {
            Ir.Material m = model.materials.get(mi);
            if (m.normalPng == null) continue;
            bin.align4();
            int imgStart = bin.length();
            bin.bytes(m.normalPng);
            int imgView = bin.bufferView(imgStart, m.normalPng.length, null);

            JsonObject image = new JsonObject();
            image.addProperty("bufferView", imgView);
            image.addProperty("mimeType", "image/png");
            images.add(image);

            JsonObject texture = new JsonObject();
            texture.addProperty("source", images.size() - 1);
            texture.addProperty("sampler", 0);
            textures.add(texture);
            materialToNormal.put(mi, textures.size() - 1);
        }
        // LabPBR specular → glTF metallic-roughness texture
        Map<Integer, Integer> materialToMR = new HashMap<>();
        for (int mi = 0; mi < model.materials.size(); mi++) {
            Ir.Material m = model.materials.get(mi);
            if (m.mrPng == null) continue;
            bin.align4();
            int imgStart = bin.length();
            bin.bytes(m.mrPng);
            int imgView = bin.bufferView(imgStart, m.mrPng.length, null);

            JsonObject image = new JsonObject();
            image.addProperty("bufferView", imgView);
            image.addProperty("mimeType", "image/png");
            images.add(image);

            JsonObject texture = new JsonObject();
            texture.addProperty("source", images.size() - 1);
            texture.addProperty("sampler", 0);
            textures.add(texture);
            materialToMR.put(mi, textures.size() - 1);
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
            if (m.alpha < 1f) {   // onion-skin ghost: fade via base-colour alpha
                pbr.add("baseColorFactor", vec4(1f, 1f, 1f, m.alpha));
            }
            Integer mr = materialToMR.get(mi);
            if (mr != null) {
                JsonObject mrTexture = new JsonObject();
                mrTexture.addProperty("index", mr);
                pbr.add("metallicRoughnessTexture", mrTexture);
                pbr.addProperty("metallicFactor", 1);   // let the texture drive metalness
            } else {
                pbr.addProperty("metallicFactor", 0);
            }
            pbr.addProperty("roughnessFactor", 1);
            material.add("pbrMetallicRoughness", pbr);
            Integer nrm = materialToNormal.get(mi);
            if (nrm != null) {
                JsonObject normalTexture = new JsonObject();
                normalTexture.addProperty("index", nrm);
                material.add("normalTexture", normalTexture);
            }
            boolean texEmissive = m.emissive && tex != null;
            if (texEmissive || m.emissiveColor != null) {
                JsonArray ef = new JsonArray();
                if (m.emissiveColor != null) {   // textureless coloured glow (beacon beams)
                    ef.add(m.emissiveColor[0]); ef.add(m.emissiveColor[1]); ef.add(m.emissiveColor[2]);
                } else {                          // textured emissive (lava/glowstone) — colour from the texture
                    ef.add(1); ef.add(1); ef.add(1);
                }
                material.add("emissiveFactor", ef);
                if (texEmissive) {
                    JsonObject emissiveTexture = new JsonObject();
                    emissiveTexture.addProperty("index", tex);
                    material.add("emissiveTexture", emissiveTexture);
                }
                JsonObject strength = new JsonObject();
                strength.addProperty("emissiveStrength", 4.0);
                JsonObject ext = new JsonObject();
                ext.add("KHR_materials_emissive_strength", strength);
                material.add("extensions", ext);
                anyEmissive = true;
            }
            if (m.translucent || m.alpha < 1f) {
                material.addProperty("alphaMode", "BLEND");   // real see-through (glass, water, ice, ghosts)
            } else {
                material.addProperty("alphaMode", "MASK");    // pixel-art cutout (leaves, grass, …)
                material.addProperty("alphaCutoff", 0.5);
            }
            material.addProperty("doubleSided", true);
            materials.add(material);
        }
        root.add("materials", materials);
        JsonArray extUsed = new JsonArray();
        if (anyEmissive) extUsed.add("KHR_materials_emissive_strength");
        if (model.sun != null || !model.lights.isEmpty()) extUsed.add("KHR_lights_punctual");
        if (extUsed.size() > 0) root.add("extensionsUsed", extUsed);

        // --- animations (optional; one or many named clips) ---------------------------------------
        if (animations != null && !animations.isEmpty()) {
            // de-duplicate clip names so each imports as a distinct Blender Action (NLA stacking, #14)
            List<String> rawNames = new ArrayList<>();
            for (Ir.Animation anim : animations) {
                rawNames.add(anim != null && anim.name != null ? anim.name : "recording");
            }
            List<String> names = NlaStack.uniqueNames(rawNames);
            JsonArray animArray = new JsonArray();
            for (int ai = 0; ai < animations.size(); ai++) {
                Ir.Animation anim = animations.get(ai);
                if (anim == null || anim.times.isEmpty()) continue;
                animArray.add(buildAnimation(bin, anim, names.get(ai), cameraNode));
            }
            if (animArray.size() > 0) root.add("animations", animArray);
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

    /** Short, node-name-safe label for a sound id: drops the namespace, keeps the path. */
    private static String shortName(String sound) {
        if (sound == null) return "sound";
        String s = sound.contains(":") ? sound.substring(sound.indexOf(':') + 1) : sound;
        return s.replaceAll("[^A-Za-z0-9_.]", "_");
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
        if (prim.mode != Ir.Primitive.TRIANGLES) primitive.addProperty("mode", prim.mode);  // POINTS for VFX clouds
        return primitive;
    }

    private static JsonObject buildAnimation(Bin bin, Ir.Animation anim, String name, int cameraNode) {
        int n = anim.times.size();
        float scale = anim.timeScale > 0f ? anim.timeScale : 1f;   // slow-mo (#15): stretch every keyframe time
        bin.align4();
        int tStart = bin.length();
        float tmin = Float.MAX_VALUE, tmax = -Float.MAX_VALUE;
        for (float t : anim.times) {
            float ts = t * scale;
            bin.f(ts);
            tmin = Math.min(tmin, ts);
            tmax = Math.max(tmax, ts);
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

        // animated POV camera (follows the player's eye)
        if (cameraNode >= 0 && anim.cameraTranslations.size() == n) {
            bin.align4();
            int s = bin.length();
            for (float[] v : anim.cameraTranslations) { bin.f(v[0]); bin.f(v[1]); bin.f(v[2]); }
            int ct = bin.accessor(bin.bufferView(s, bin.length() - s, null), FLOAT, n, "VEC3", null, null);
            bin.align4();
            s = bin.length();
            for (float[] q : anim.cameraRotations) { bin.f(q[0]); bin.f(q[1]); bin.f(q[2]); bin.f(q[3]); }
            int cr = bin.accessor(bin.bufferView(s, bin.length() - s, null), FLOAT, n, "VEC4", null, null);
            int sT = samplers.size();
            samplers.add(sampler(timeAcc, ct));
            channels.add(channel(sT, cameraNode, "translation"));
            int sR2 = samplers.size();
            samplers.add(sampler(timeAcc, cr));
            channels.add(channel(sR2, cameraNode, "rotation"));
        }

        JsonObject animation = new JsonObject();
        animation.add("samplers", samplers);
        animation.add("channels", channels);
        animation.addProperty("name", name != null ? name : "recording");
        return animation;
    }

    private static JsonObject lightRef(int lightIndex) {
        JsonObject ext = new JsonObject();
        JsonObject ref = new JsonObject();
        ref.addProperty("light", lightIndex);
        ext.add("KHR_lights_punctual", ref);
        return ext;
    }

    private static JsonObject perspectiveCamera(float yfovRadians) {
        JsonObject perspective = new JsonObject();
        float yfov = Float.isFinite(yfovRadians) && yfovRadians > 0f ? yfovRadians : (float) Math.toRadians(50);
        perspective.addProperty("yfov", yfov);
        perspective.addProperty("znear", 0.05);
        perspective.addProperty("zfar", 1000.0);
        JsonObject cam = new JsonObject();
        cam.addProperty("type", "perspective");
        cam.add("perspective", perspective);
        return cam;
    }

    private static JsonObject cameraNodeObj(String name, int cameraIndex, Ir.Camera cam) {
        JsonObject camNode = new JsonObject();
        camNode.addProperty("name", name);
        camNode.addProperty("camera", cameraIndex);
        camNode.add("translation", vec3(cam.position[0], cam.position[1], cam.position[2]));
        camNode.add("rotation", quat(cam.rotation));   // a degenerate look (NaN/zero) becomes identity
        if (Float.isFinite(cam.focusDistance) && cam.focusDistance > 0f) {   // depth of field → add-on node extras
            JsonObject extras = new JsonObject();
            extras.addProperty("dof_focus", finite(cam.focusDistance));
            extras.addProperty("dof_fstop", finite(cam.fstop));
            camNode.add("extras", extras);
        }
        return camNode;
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

    /** glTF JSON must be valid JSON, which has no NaN/Infinity. Replace any non-finite float with 0 so a
     *  single degenerate value (a NaN camera rotation, a bad vertex) can never make Blender reject the
     *  whole file ("Bad glTF: json contained NaN"). */
    static float finite(float v) {
        return Float.isFinite(v) ? v : 0f;
    }

    private static JsonArray vec3(float x, float y, float z) {
        JsonArray a = new JsonArray();
        a.add(finite(x));
        a.add(finite(y));
        a.add(finite(z));
        return a;
    }

    private static JsonArray vec4(float x, float y, float z, float w) {
        JsonArray a = new JsonArray();
        a.add(finite(x));
        a.add(finite(y));
        a.add(finite(z));
        a.add(finite(w));
        return a;
    }

    /** A valid unit quaternion for a node rotation: identity if the input is non-finite or zero-length
     *  (a degenerate camera look would otherwise emit [0,0,0,0], which has no valid orientation). */
    private static JsonArray quat(float[] r) {
        float x = r[0], y = r[1], z = r[2], w = r[3];
        float len2 = x * x + y * y + z * z + w * w;
        if (!Float.isFinite(len2) || len2 < 1e-8f) {
            x = 0; y = 0; z = 0; w = 1;
        }
        return vec4(x, y, z, w);
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
            if (min != null) { JsonArray j = new JsonArray(); for (float v : min) j.add(finite(v)); a.add("min", j); }
            if (max != null) { JsonArray j = new JsonArray(); for (float v : max) j.add(finite(v)); a.add("max", j); }
            accessors.add(a);
            return accessors.size() - 1;
        }
    }
}
