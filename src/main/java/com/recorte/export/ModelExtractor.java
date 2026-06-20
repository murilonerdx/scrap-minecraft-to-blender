package com.recorte.export;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a live {@link PlayerModel} into an {@link Ir.Model}: a clean, rigged, export-ready skeleton.
 *
 * <h2>Coordinate conversion</h2>
 * Minecraft model space uses 1 unit = 1/16 block, +Y pointing <em>down</em>, and the renderer mirrors
 * X/Y. We bake a single conversion matrix {@code M = rotateZ(180deg) * scale(1/16)} into the root bone.
 * That is a proper rotation plus a positive uniform scale (determinant &gt; 0), so winding and normals
 * are preserved and the model comes out upright, mirrored to match the in-game view, at 1 unit = 1 block
 * (so Blender shows it at a sensible ~1.8 m tall).
 *
 * <h2>Rig</h2>
 * The vanilla player is a flat set of sibling parts. We re-parent them into an animator-friendly
 * hierarchy ({@code root -> body -> head/arms/legs}) and merge each second skin layer
 * (hat/jacket/sleeves/pants) into its base bone. Because the bind pose is captured as each bone's
 * global transform, the chosen parenting never distorts the bind pose &mdash; it only decides how
 * rotations propagate when you animate later.
 */
public final class ModelExtractor {

    // Field handles, resolved once from the first live instances we touch (mapping-agnostic).
    private Field fCubes;     // ModelPart.cubes    : List
    private Field fPolygons;  // Cube.polygons      : Polygon[]
    private Field fVertices;  // Polygon.vertices   : Vertex[]
    private Field fNormal;    // Polygon.normal     : Vector3f
    private Field fPos;       // Vertex.pos         : Vector3f
    private Field fU, fV;     // Vertex.u, Vertex.v : float

    private final Matrix4f convert = Convert.matrix();
    private Ir.Model out;
    private java.util.Set<ModelPart> visited;   // dedup parts that are both fields and children

    @SuppressWarnings({"rawtypes", "unchecked"})
    public Ir.Model extract(PlayerModel<?> model, AbstractClientPlayer player, String skinTextureFile) {
        this.out = new Ir.Model();
        HumanoidModel<?> hm = model;

        // Force a clean, deterministic idle pose: no crouch, empty hands. setupAnim also copies
        // each second layer (hat/sleeves/pants) onto its base part, so we can merge them safely.
        hm.crouching = false;
        hm.attackTime = 0f;
        hm.rightArmPose = HumanoidModel.ArmPose.EMPTY;
        hm.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        try {
            ((PlayerModel) model).setupAnim(player, 0f, 0f, 0f, 0f, 0f);
        } catch (Throwable ignored) {
            // If posing fails for any reason we just export whatever pose the parts are currently in.
        }

        int skin = out.materialIndex("skin");
        out.materials.get(skin).textureFile = skinTextureFile;

        // Root carries the MC -> export conversion. Its absolute MC transform is the identity.
        int root = out.addBone(new Ir.Bone("root", -1, new Matrix4f(convert)));

        int body = addBone("body", root, hm.body);
        addCubes(hm.body, body, skin);
        addCubes(model.jacket, body, skin);

        int head = addBone("head", body, hm.head);
        addCubes(hm.head, head, skin);
        addCubes(hm.hat, head, skin);

        int rightArm = addBone("right_arm", body, hm.rightArm);
        addCubes(hm.rightArm, rightArm, skin);
        addCubes(model.rightSleeve, rightArm, skin);

        int leftArm = addBone("left_arm", body, hm.leftArm);
        addCubes(hm.leftArm, leftArm, skin);
        addCubes(model.leftSleeve, leftArm, skin);

        int rightLeg = addBone("right_leg", body, hm.rightLeg);
        addCubes(hm.rightLeg, rightLeg, skin);
        addCubes(model.rightPants, rightLeg, skin);

        int leftLeg = addBone("left_leg", body, hm.leftLeg);
        addCubes(hm.leftLeg, leftLeg, skin);
        addCubes(model.leftPants, leftLeg, skin);

        computeLocalTransforms();
        return out;
    }

    /**
     * Generic entity/mob export. If the model is a {@link net.minecraft.client.model.HierarchicalModel}
     * we walk its root part tree into a rigged skeleton; otherwise we fall back to flat geometry.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Ir.Model extractEntity(net.minecraft.world.entity.Entity entity,
                                  net.minecraft.client.model.EntityModel model,
                                  String textureFile) {
        this.out = new Ir.Model();
        this.visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int mat = out.materialIndex("texture");
        out.materials.get(mat).textureFile = textureFile;

        try {
            model.setupAnim(entity, 0f, 0f, 0f, 0f, 0f);
        } catch (Throwable ignored) {
        }

        int rootBone = out.addBone(new Ir.Bone("root", -1, new Matrix4f(convert)));
        if (model instanceof net.minecraft.client.model.HierarchicalModel hm) {
            walk(hm.root(), "root_part", rootBone, new Matrix4f(), mat);
        } else {
            // Humanoid / AgeableList / other: walk every ModelPart field as a top-level part.
            for (Field f : modelPartFields(model.getClass())) {
                try {
                    ModelPart part = (ModelPart) f.get(model);
                    if (part != null) walk(part, f.getName(), rootBone, new Matrix4f(), mat);
                } catch (Throwable ignored) {
                }
            }
        }
        for (Ir.Primitive p : out.primitives) p.group = "Entity";
        computeLocalTransforms();
        return out;
    }

    private static List<Field> modelPartFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() == ModelPart.class) {
                    f.setAccessible(true);
                    fields.add(f);
                }
            }
        }
        return fields;
    }

    /** Recursively turns a ModelPart subtree into bones + geometry. */
    private void walk(ModelPart part, String name, int parentBone, Matrix4f parentAbs, int mat) {
        if (part == null || (visited != null && !visited.add(part))) return;
        Matrix4f abs = new Matrix4f(parentAbs).mul(localMC(part));
        Matrix4f global = new Matrix4f(convert).mul(abs);
        int boneIndex = out.addBone(new Ir.Bone(name, parentBone, global));
        out.bones.get(boneIndex).sourcePart = part;
        addCubes(part, boneIndex, mat);

        @SuppressWarnings("unchecked")
        Map<String, ModelPart> children = (Map<String, ModelPart>) ReflectUtil.get(childrenField(part), part);
        for (Map.Entry<String, ModelPart> e : children.entrySet()) {
            walk(e.getValue(), e.getKey(), boneIndex, abs, mat);
        }
    }

    private static Matrix4f localMC(ModelPart p) {
        Matrix4f m = new Matrix4f().translate(p.x, p.y, p.z);
        if (p.xRot != 0f || p.yRot != 0f || p.zRot != 0f) {
            m.rotate(new Quaternionf().rotationZYX(p.zRot, p.yRot, p.xRot));
        }
        return m;
    }

    private Field fChildren;

    private Field childrenField(ModelPart p) {
        if (fChildren == null) fChildren = ReflectUtil.fieldOfType(ModelPart.class, Map.class);
        return fChildren;
    }

    /** Creates a bone whose global bind transform is {@code convert * absolute(part)}. */
    private int addBone(String name, int parentIndex, ModelPart part) {
        Matrix4f global = new Matrix4f(convert).mul(absolute(part));
        return out.addBone(new Ir.Bone(name, parentIndex, global));
    }

    /** Absolute MC-space transform of a top-level part: {@code T(pivot) * R(ZYX)}. */
    private static Matrix4f absolute(ModelPart p) {
        Matrix4f m = new Matrix4f().translate(p.x, p.y, p.z);
        if (p.xRot != 0f || p.yRot != 0f || p.zRot != 0f) {
            m.rotate(new Quaternionf().rotationZYX(p.zRot, p.yRot, p.xRot));
        }
        return m;
    }

    private void computeLocalTransforms() {
        for (Ir.Bone b : out.bones) {
            if (b.parentIndex < 0) {
                b.localTransform = new Matrix4f(b.globalBind);
            } else {
                Matrix4f parentInv = new Matrix4f(out.bones.get(b.parentIndex).globalBind).invert();
                b.localTransform = parentInv.mul(b.globalBind, new Matrix4f());
            }
        }
    }

    /** Extracts every cube of {@code part} into the primitive for {@code materialIndex}, bound to {@code boneIndex}. */
    private void addCubes(ModelPart part, int boneIndex, int materialIndex) {
        if (part == null) return;
        Ir.Bone bone = out.bones.get(boneIndex);
        Matrix4f g = bone.globalBind;

        List<?> cubes = (List<?>) ReflectUtil.get(cubesField(part), part);
        Ir.Primitive prim = out.primitiveForMaterial(materialIndex);

        for (Object cube : cubes) {
            Object[] polygons = (Object[]) ReflectUtil.get(polygonsField(cube), cube);
            for (Object polygon : polygons) {
                Object[] verts = (Object[]) ReflectUtil.get(verticesField(polygon), polygon);
                Vector3f n = (Vector3f) ReflectUtil.get(normalField(polygon), polygon);
                Vector3f nWorld = g.transformDirection(new Vector3f(n)).normalize();

                Ir.Vertex[] quad = new Ir.Vertex[4];
                for (int i = 0; i < 4 && i < verts.length; i++) {
                    Object vx = verts[i];
                    Vector3f local = (Vector3f) ReflectUtil.get(posField(vx), vx);
                    Vector3f world = g.transformPosition(new Vector3f(local));
                    float u = ReflectUtil.getFloat(uField(vx), vx);
                    float v = ReflectUtil.getFloat(vField(vx), vx);
                    quad[i] = new Ir.Vertex(world.x, world.y, world.z, nWorld.x, nWorld.y, nWorld.z, u, v, boneIndex);
                }
                if (quad[3] != null) {
                    prim.addQuad(quad[0], quad[1], quad[2], quad[3]);
                }
            }
        }
    }

    // --- lazily resolved field handles ---------------------------------------------------------

    private Field cubesField(ModelPart p) {
        if (fCubes == null) fCubes = ReflectUtil.fieldOfType(ModelPart.class, List.class);
        return fCubes;
    }

    private Field polygonsField(Object cube) {
        if (fPolygons == null) fPolygons = ReflectUtil.arrayField(cube.getClass());
        return fPolygons;
    }

    private Field verticesField(Object polygon) {
        if (fVertices == null) fVertices = ReflectUtil.arrayField(polygon.getClass());
        return fVertices;
    }

    private Field normalField(Object polygon) {
        if (fNormal == null) fNormal = ReflectUtil.fieldOfType(polygon.getClass(), Vector3f.class);
        return fNormal;
    }

    private Field posField(Object vertex) {
        if (fPos == null) fPos = ReflectUtil.fieldOfType(vertex.getClass(), Vector3f.class);
        return fPos;
    }

    private Field uField(Object vertex) {
        if (fU == null) resolveUv(vertex);
        return fU;
    }

    private Field vField(Object vertex) {
        if (fV == null) resolveUv(vertex);
        return fV;
    }

    private void resolveUv(Object vertex) {
        List<Field> floats = ReflectUtil.fieldsOfType(vertex.getClass(), float.class);
        if (floats.size() < 2) {
            throw new IllegalStateException("Expected u,v float fields on " + vertex.getClass());
        }
        fU = floats.get(0);
        fV = floats.get(1);
    }
}
