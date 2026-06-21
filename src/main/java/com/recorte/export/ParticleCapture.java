package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Studio feature #8 — captures the currently-alive particles (fire, smoke, portal, redstone…) as a
 * <b>point cloud</b>: one glTF {@code POINTS} primitive whose vertices carry each particle's position
 * and colour. In Blender it imports as a mesh of loose vertices with a {@code COLOR_0} attribute —
 * ready to feed Geometry Nodes (instance a billboard / volume on each point) for a render-quality VFX.
 *
 * <p>The particle list lives inside {@link ParticleEngine} with no public accessor, so we reach it by
 * reflection <em>by type</em> (the project's obfuscation-proof convention): the engine's particle map
 * is the only {@code Map} whose values are {@code Collection}s. Each particle's <b>position</b> comes
 * from the public {@link Particle#getBoundingBox()} (mappings-independent — no fragile field access),
 * and its colour from the first RGB float triple, defended with a white fallback. Everything is wrapped
 * in {@code try/catch}: if the layout ever shifts, you get an empty cloud, never a crash.
 */
public final class ParticleCapture {
    private ParticleCapture() {}

    private static final int MAX_POINTS = 50_000;   // safety cap for huge particle storms

    /** Appends a "Particles" POINTS primitive (positions + colours) to {@code out}, centred on {@code center}. */
    public static int appendParticles(Ir.Model out, BlockPos center) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.particleEngine == null) return 0;
            Collection<Particle> particles = liveParticles(mc.particleEngine);
            if (particles == null || particles.isEmpty()) return 0;

            List<Field> colorFields = ReflectUtil.fieldsOfType(Particle.class, float.class);
            int mat = out.materialIndex("Particles");
            Ir.Primitive prim = out.primitiveForMaterial(mat);
            prim.group = "Particles";
            prim.mode = Ir.Primitive.POINTS;

            int n = 0;
            for (Particle p : particles) {
                if (p == null) continue;
                if (n >= MAX_POINTS) break;
                AABB bb = p.getBoundingBox();
                if (bb == null) continue;
                Vec3 c = bb.getCenter();
                float px = -(float) (c.x - center.getX());   // export space: X negated, centred
                float py = (float) (c.y - center.getY());
                float pz = (float) (c.z - center.getZ());
                float[] rgb = color(colorFields, p);
                prim.addPoint(new Ir.Vertex(px, py, pz, 0f, 1f, 0f, 0f, 0f, 0, rgb[0], rgb[1], rgb[2], 1f));
                n++;
            }
            if (n == 0) {                       // nothing captured — drop the empty primitive/material
                out.primitives.remove(prim);
            }
            return n;
        } catch (Throwable t) {                 // reflection drift / no level / off-thread: never crash an export
            Recorte.LOGGER.warn("Particle capture skipped", t);
            return 0;
        }
    }

    /** The engine's live particle collection: the only {@code Map} field whose values are {@code Collection}s. */
    @SuppressWarnings("unchecked")
    private static Collection<Particle> liveParticles(ParticleEngine engine) {
        java.util.ArrayList<Particle> all = new java.util.ArrayList<>();
        try {
            for (Field f : ParticleEngine.class.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object value = f.get(engine);
                if (!(value instanceof Map<?, ?> map)) continue;
                boolean isParticleMap = false;
                for (Object v : map.values()) {
                    if (v instanceof Collection<?> queue) {
                        for (Object item : queue) {
                            if (item instanceof Particle pt) { all.add(pt); isParticleMap = true; }
                        }
                    }
                }
                if (isParticleMap) return all;   // found the particles map (spriteSets' values aren't Collections)
            }
        } catch (Throwable ignored) {
        }
        return all.isEmpty() ? null : all;
    }

    // Particle's base-class float fields in declaration order: bbWidth, bbHeight, gravity, rCol, gCol,
    // bCol, alpha, roll, oRoll. The colour triple is fixed at indices 3..5 (subclasses can't reorder the
    // base class), so we read those directly rather than guessing — guarded to white if anything's off.
    private static final int R_COL = 3;

    /** Particle colour (rCol/gCol/bCol); white if the field layout looks wrong (out-of-range values). */
    private static float[] color(List<Field> floats, Particle p) {
        try {
            if (floats.size() > R_COL + 2) {
                float r = floats.get(R_COL).getFloat(p);
                float g = floats.get(R_COL + 1).getFloat(p);
                float b = floats.get(R_COL + 2).getFloat(p);
                if (in01(r) && in01(g) && in01(b)) return new float[]{r, g, b};
            }
        } catch (Throwable ignored) {
        }
        return new float[]{1f, 1f, 1f};
    }

    private static boolean in01(float v) {
        return v >= 0f && v <= 1f;
    }
}
