package com.recorte.export;

/**
 * Studio feature #11 — the <b>pure</b>, headless-testable geometry of the sky: a gradient dome and a
 * procedural cloud layer, built straight into an {@link Ir.Model} (no Minecraft types, so the self-test
 * can verify the sphere radius, the colour gradient and the cloud bounds offline). {@link SkyCapture}
 * reads the live sky/cloud colours and calls this.
 */
public final class SkyGeometry {
    private SkyGeometry() {}

    /**
     * Adds an inward-facing UV-sphere "Sky" dome of {@code radius}, vertex-coloured with a vertical
     * gradient from {@code horizon} (bottom) through the equator up to {@code zenith} (top). Normals
     * point inward so it's visible from inside the scene.
     */
    public static void addDome(Ir.Model out, float radius, float[] zenith, float[] horizon,
                               int rings, int segments, int joint) {
        int mat = out.materialIndex("Sky");
        Ir.Primitive prim = out.primitiveForMaterial(mat);
        prim.group = "Sky";
        int rowLen = segments + 1;
        int base = prim.vertices.size();
        for (int i = 0; i <= rings; i++) {
            double phi = Math.PI * i / rings;            // 0 = top, PI = bottom
            float cy = (float) Math.cos(phi);            // +1 top .. -1 bottom
            float sy = (float) Math.sin(phi);
            float t = (cy + 1f) * 0.5f;                  // 1 at zenith, 0 at nadir
            float r = lerp(horizon[0], zenith[0], t);
            float g = lerp(horizon[1], zenith[1], t);
            float b = lerp(horizon[2], zenith[2], t);
            for (int j = 0; j <= segments; j++) {
                double theta = 2 * Math.PI * j / segments;
                float x = (float) (sy * Math.cos(theta)) * radius;
                float y = cy * radius;
                float z = (float) (sy * Math.sin(theta)) * radius;
                float len = Math.max(1e-4f, (float) Math.sqrt(x * x + y * y + z * z));
                prim.vertices.add(new Ir.Vertex(x, y, z, -x / len, -y / len, -z / len,
                        j / (float) segments, i / (float) rings, joint, r, g, b, 1f));
            }
        }
        for (int i = 0; i < rings; i++) {
            for (int j = 0; j < segments; j++) {
                int a = base + i * rowLen + j;
                int bb = a + 1;
                int c = a + rowLen;
                int d = c + 1;
                prim.indices.add(a); prim.indices.add(c); prim.indices.add(bb);   // inward winding
                prim.indices.add(bb); prim.indices.add(c); prim.indices.add(d);
            }
        }
    }

    /**
     * Adds a "Clouds" layer at height {@code cloudY}: a grid of horizontal quads (cell size {@code cell})
     * over a square of half-width {@code extent}, with a clumped procedural pattern (holes between
     * clouds). Returns the number of cloud cells emitted.
     */
    public static int addClouds(Ir.Model out, float extent, float cloudY, float cell,
                                float[] color, long seed, int joint) {
        int mat = out.materialIndex("Clouds");
        Ir.Material m = out.materials.get(mat);
        m.translucent = true;
        m.alpha = 0.8f;
        m.emissiveColor = new float[]{color[0], color[1], color[2]};   // soft self-lit clouds (uniform colour)
        Ir.Primitive prim = out.primitiveForMaterial(mat);
        prim.group = "Clouds";
        float r = color[0], g = color[1], b = color[2];
        int cells = 0;
        int n = (int) Math.ceil(extent / cell);
        for (int ix = -n; ix < n; ix++) {
            for (int iz = -n; iz < n; iz++) {
                if (!isCloud(ix, iz, seed)) continue;
                float x0 = ix * cell, x1 = x0 + cell;
                float z0 = iz * cell, z1 = z0 + cell;
                prim.addQuad(
                        new Ir.Vertex(x0, cloudY, z0, 0, 1, 0, 0, 0, joint, r, g, b, 1f),
                        new Ir.Vertex(x1, cloudY, z0, 0, 1, 0, 1, 0, joint, r, g, b, 1f),
                        new Ir.Vertex(x1, cloudY, z1, 0, 1, 0, 1, 1, joint, r, g, b, 1f),
                        new Ir.Vertex(x0, cloudY, z1, 0, 1, 0, 0, 1, joint, r, g, b, 1f));
                cells++;
            }
        }
        if (cells == 0) out.primitives.remove(prim);
        return cells;
    }

    /** Clumped cloud test: a low-frequency blob mask AND a per-cell hole, for broken-cloud cover. */
    private static boolean isCloud(int x, int z, long seed) {
        return hash(Math.floorDiv(x, 3), Math.floorDiv(z, 3), seed) > 0.45f
                && hash(x, z, seed ^ 0x9E3779B9L) > 0.25f;
    }

    private static float hash(int x, int z, long seed) {
        long h = x * 73856093L ^ z * 19349663L ^ seed * 83492791L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h >>> 16) & 0xFFFFL) / 65535f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
