package com.recorte.export;

import org.joml.Matrix4f;

/**
 * Pure rig grounding: shifts a rig vertically so its lowest vertex sits at {@code y = 0} (feet on the
 * Blender floor). Minecraft's model space (via {@link Convert#matrix()}, which flips Y) puts the feet
 * ~1.5 blocks <em>below</em> the rig origin, and the game's renderer adds that offset back at draw time;
 * standalone exports and recordings don't, so without this the character sinks into the ground. No
 * Minecraft types, so it's headless-testable.
 */
public final class RigGround {
    private RigGround() {}

    /** Lowest vertex Y across all primitives (0 if there are none). */
    public static float minY(Ir.Model m) {
        float min = Float.MAX_VALUE;
        for (Ir.Primitive p : m.primitives) {
            for (Ir.Vertex v : p.vertices) if (v.py < min) min = v.py;
        }
        return min == Float.MAX_VALUE ? 0f : min;
    }

    /**
     * Lifts the rig so {@link #minY} becomes 0. Shifts every bone's bind (and each root's local
     * transform) and every vertex by the same translation, so the skin stays consistent.
     */
    public static void toFloor(Ir.Model m) {
        float min = minY(m);
        if (Math.abs(min) < 1e-4f) return;
        Matrix4f t = new Matrix4f().translation(0f, -min, 0f);
        for (Ir.Bone b : m.bones) {
            b.globalBind.set(t.mul(b.globalBind, new Matrix4f()));
        }
        for (Ir.Bone b : m.bones) {   // only roots: children are relative and unchanged
            if (b.parentIndex < 0 && b.localTransform != null) {
                b.localTransform.set(t.mul(b.localTransform, new Matrix4f()));
            }
        }
        for (Ir.Primitive p : m.primitives) {
            for (int i = 0; i < p.vertices.size(); i++) {
                Ir.Vertex v = p.vertices.get(i);
                p.vertices.set(i, new Ir.Vertex(v.px, v.py - min, v.pz, v.nx, v.ny, v.nz,
                        v.u, v.v, v.joint, v.r, v.g, v.b, v.a));
            }
        }
    }
}
