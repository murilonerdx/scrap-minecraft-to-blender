package com.recorte.export;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Intermediate Representation of a captured model.
 *
 * <p>A {@link Model} is a flat list of {@link Bone}s (a skeleton), a list of {@link Material}s and a
 * list of {@link Primitive}s (geometry grouped by material). Each vertex is rigidly bound to exactly
 * one bone. This maps directly onto a glTF skinned mesh, and degrades gracefully to a plain OBJ
 * (just ignore the bones).
 */
public final class Ir {
    private Ir() {}

    public static final class Model {
        public final List<Bone> bones = new ArrayList<>();
        public final List<Material> materials = new ArrayList<>();
        public final List<Primitive> primitives = new ArrayList<>();
        public Camera camera;   // optional: the in-game camera, framed to match the export
        public final List<Camera> extraCameras = new ArrayList<>();   // preset angles (orbit/top) for renders
        public Light sun;       // optional: a directional light matching the in-game sun
        public final List<Light> lights = new ArrayList<>();          // point lights (torches, glowstone…)
        public final List<Speaker> speakers = new ArrayList<>();      // positioned sound emitters → Blender Speakers
        public boolean useRetargetNames;   // #16: name bone nodes with humanoid retarget names (Mixamo-style)

        public int addBone(Bone b) {
            bones.add(b);
            return bones.size() - 1;
        }

        /** Returns the index of the material with this name, creating it if necessary. */
        public int materialIndex(String name) {
            for (int i = 0; i < materials.size(); i++) {
                if (materials.get(i).name.equals(name)) return i;
            }
            materials.add(new Material(name));
            return materials.size() - 1;
        }

        /** Total triangle count across all primitives (for user feedback). */
        public int triangleCount() {
            int t = 0;
            for (Primitive p : primitives) t += p.indices.size() / 3;
            return t;
        }

        /** Returns the (single) primitive for a material, creating it on first use. */
        public Primitive primitiveForMaterial(int materialIndex) {
            for (Primitive p : primitives) {
                if (p.materialIndex == materialIndex) return p;
            }
            Primitive p = new Primitive(materialIndex);
            primitives.add(p);
            return p;
        }
    }

    /** A single joint of the skeleton. */
    public static final class Bone {
        public final String name;
        public int parentIndex;              // -1 for the root (mutable: a rider gets re-parented onto its mount)
        public final Matrix4f globalBind;     // export-space global bind transform (axis + scale already applied)
        public Matrix4f localTransform;       // node transform relative to parent; filled in by the extractor
        public transient Object sourcePart;   // the live ModelPart this bone samples (for animation recording)
        public String retargetName;           // #16: humanoid label (Hips/Spine/Head/…) for retargeting

        public Bone(String name, int parentIndex, Matrix4f globalBind) {
            this.name = name;
            this.parentIndex = parentIndex;
            this.globalBind = globalBind;
        }
    }

    /**
     * A recorded animation: shared keyframe times plus, per animated bone, the local translation and
     * rotation at each keyframe. Maps directly onto glTF animation samplers/channels.
     */
    public static final class Animation {
        public String name = "recording";                            // glTF animation name (Blender Action)
        public float timeScale = 1f;                                 // slow-mo (#15): writer stretches times ×this
        public final List<Float> times = new ArrayList<>();          // seconds, one per keyframe
        public final Map<Integer, List<float[]>> translations = new java.util.LinkedHashMap<>();  // bone -> [x,y,z]/key
        public final Map<Integer, List<float[]>> rotations = new java.util.LinkedHashMap<>();      // bone -> [x,y,z,w]/key
        public final List<float[]> cameraTranslations = new ArrayList<>();   // animated camera path (POV)
        public final List<float[]> cameraRotations = new ArrayList<>();
        // point-in-time events (block break/place…) → Blender timeline markers; written from the
        // server thread, so keep it synchronized
        public final List<Event> events = java.util.Collections.synchronizedList(new ArrayList<>());
        // day/night timelapse: the sun + sky sampled per keyframe (parallel to times)
        public final List<float[]> sunDirections = new ArrayList<>();   // travel direction, export space
        public final List<float[]> sunColors = new ArrayList<>();       // r,g,b
        public final List<Float> sunIntensities = new ArrayList<>();
        public final List<float[]> skyColors = new ArrayList<>();       // world background r,g,b

        public void key(int bone, float[] translation, float[] rotation) {
            translations.computeIfAbsent(bone, k -> new ArrayList<>()).add(translation);
            List<float[]> rots = rotations.computeIfAbsent(bone, k -> new ArrayList<>());
            rots.add(continuous(rots, rotation));
        }

        public void cameraKey(float[] translation, float[] rotation) {
            cameraTranslations.add(translation);
            cameraRotations.add(continuous(cameraRotations, rotation));
        }

        /**
         * Flips a quaternion into the same hemisphere as the previous keyframe (negates it when their
         * dot product is negative). {@code q} and {@code -q} are the same orientation, but glTF/Blender
         * interpolate quaternions component-wise, so a sign flip between keys makes a limb spin the long
         * way around &mdash; the cause of jittery, wrong-looking recorded animations. Enforcing one
         * hemisphere makes every interpolation take the short path.
         */
        private static float[] continuous(List<float[]> track, float[] q) {
            // a degenerate orientation (e.g. a camera looking straight along its up vector) can yield a
            // NaN quaternion; reuse the last good key (or identity) so the animation never carries NaN
            if (!(Float.isFinite(q[0]) && Float.isFinite(q[1]) && Float.isFinite(q[2]) && Float.isFinite(q[3]))) {
                return track.isEmpty() ? new float[]{0, 0, 0, 1} : track.get(track.size() - 1).clone();
            }
            if (!track.isEmpty()) {
                float[] p = track.get(track.size() - 1);
                float dot = p[0] * q[0] + p[1] * q[1] + p[2] * q[2] + p[3] * q[3];
                if (dot < 0f) return new float[]{-q[0], -q[1], -q[2], -q[3]};
            }
            return q;
        }

        public void event(float time, String name, float[] position) {
            events.add(new Event(time, name, position));
        }

        public void worldKey(float[] sunDir, float[] sunColor, float sunIntensity, float[] skyColor) {
            sunDirections.add(sunDir);
            sunColors.add(sunColor);
            sunIntensities.add(sunIntensity);
            skyColors.add(skyColor);
        }
    }

    /** A point-in-time event on the recorded timeline (block break/place) → a Blender timeline marker. */
    public static final class Event {
        public final float time;        // seconds from the start of the recording
        public final String name;       // e.g. "break:minecraft:stone"
        public final float[] position;  // export-space x,y,z (may be null)

        public Event(float time, String name, float[] position) {
            this.time = time;
            this.name = name;
            this.position = position;
        }
    }

    /** A perspective camera matching the in-game view (position + rotation in export space). */
    public static final class Camera {
        public final float[] position;       // x, y, z
        public final float[] rotation;       // quaternion x, y, z, w
        public final float yfovRadians;
        public String name;                  // optional: a placed camera's name (else GltfWriter numbers it)
        public float focusDistance;          // depth of field: distance to the focus plane (0 = no DOF)
        public float fstop = 2.8f;            // aperture (smaller = shallower / blurrier background)

        public Camera(float[] position, float[] rotation, float yfovRadians) {
            this.position = position;
            this.rotation = rotation;
            this.yfovRadians = yfovRadians;
        }
    }

    /** A light exported via KHR_lights_punctual: a directional sun, or a positioned point light. */
    public static final class Light {
        public final String type;            // "directional" or "point"
        public final float[] direction;      // directional: travel direction (normalized, export space)
        public final float[] position;       // point: x,y,z in export space
        public final float[] color;          // r, g, b in 0..1
        public final float intensity;

        /** Directional (sun). */
        public Light(float[] direction, float[] color, float intensity) {
            this.type = "directional";
            this.direction = direction;
            this.position = null;
            this.color = color;
            this.intensity = intensity;
        }

        private Light(float[] position, float[] color, float intensity, boolean point) {
            this.type = "point";
            this.direction = null;
            this.position = position;
            this.color = color;
            this.intensity = intensity;
        }

        /** A positioned point light (torch, glowstone…). */
        public static Light point(float[] position, float[] color, float intensity) {
            return new Light(position, color, intensity, true);
        }
    }

    /** A positioned sound emitter (a recorded sound, jukebox, note block…) → a Blender Speaker object. */
    public static final class Speaker {
        public final String sound;       // sound event id, e.g. "minecraft:block.note_block.harp"
        public final float[] position;   // export-space x,y,z
        public final float time;         // seconds into the recording the sound fired (0 for static scenes)
        public final float gain;         // volume hint (1 = normal)

        public Speaker(String sound, float[] position, float time, float gain) {
            this.sound = sound;
            this.position = position;
            this.time = time;
            this.gain = gain;
        }
    }

    public static final class Material {
        public final String name;
        public String textureFile;             // PNG filename written next to the model (set by caller)
        public byte[] png;                     // encoded PNG bytes, for embedding in the GLB
        public boolean emissive;               // glows (lava, glowstone, …): emissive in glTF
        public float[] emissiveColor;          // textureless glow (beacon beams): constant emissiveFactor r,g,b
        public boolean translucent;            // glass/water/ice…: real alpha BLEND instead of MASK cutout
        public float alpha = 1f;               // <1 = semi-transparent (onion-skin ghosts) → BLEND + baseColor alpha
        public String normalFile;              // optional LabPBR normal map filename
        public byte[] normalPng;               // optional normal map PNG bytes → glTF normalTexture
        public String mrFile;                  // optional metallic-roughness filename
        public byte[] mrPng;                   // optional glTF metallic-roughness PNG (from LabPBR _s)
        public java.util.List<byte[]> frameSequence;   // animated texture: one PNG per frame (png = frame 0)

        public Material(String name) {
            this.name = name;
        }
    }

    /** Geometry for one material: a triangle soup (or a point cloud) with an interleaved-free vertex list. */
    public static final class Primitive {
        public static final int TRIANGLES = 4, POINTS = 0;   // glTF primitive modes

        public final int materialIndex;
        public final List<Vertex> vertices = new ArrayList<>();
        public final List<Integer> indices = new ArrayList<>();
        public String group = "Player";   // exported as a separate Blender object per group
        public int mode = TRIANGLES;       // POINTS for particle/VFX clouds

        public Primitive(int materialIndex) {
            this.materialIndex = materialIndex;
        }

        /** Adds a single point (its own index) — for POINTS-mode primitives. */
        public void addPoint(Vertex v) {
            indices.add(vertices.size());
            vertices.add(v);
        }

        /** Adds a quad (4 vertices, in winding order) as two triangles. */
        public void addQuad(Vertex a, Vertex b, Vertex c, Vertex d) {
            int base = vertices.size();
            vertices.add(a);
            vertices.add(b);
            vertices.add(c);
            vertices.add(d);
            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
            indices.add(base);
            indices.add(base + 2);
            indices.add(base + 3);
        }
    }

    public static final class Vertex {
        public final float px, py, pz;        // position, export space (metres)
        public final float nx, ny, nz;        // normal, export space
        public final float u, v;              // texture coords, 0..1, top-left origin
        public final float r, g, b, a;        // vertex colour (block tints); white = no tint
        public final int joint;               // bone index this vertex is bound to

        public Vertex(float px, float py, float pz,
                      float nx, float ny, float nz,
                      float u, float v, int joint) {
            this(px, py, pz, nx, ny, nz, u, v, joint, 1f, 1f, 1f, 1f);
        }

        public Vertex(float px, float py, float pz,
                      float nx, float ny, float nz,
                      float u, float v, int joint,
                      float r, float g, float b, float a) {
            this.px = px; this.py = py; this.pz = pz;
            this.nx = nx; this.ny = ny; this.nz = nz;
            this.u = u; this.v = v;
            this.r = r; this.g = g; this.b = b; this.a = a;
            this.joint = joint;
        }

        public boolean isWhite() {
            return r == 1f && g == 1f && b == 1f && a == 1f;
        }
    }
}
