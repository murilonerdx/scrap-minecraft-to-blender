package com.recorte.export;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.AABB;

/**
 * Studio feature #19 — samples the world around the player into a small top-down colour grid (surface
 * map colours, height-shaded) plus entity dots, so the control panel can show a <b>thumbnail of what a
 * scene/snapshot export will capture</b> before you run it. The grid mapping is the pure
 * {@link PreviewGrid}; this only feeds it live block/entity data.
 */
public final class ExportPreview {
    private ExportPreview() {}

    public static final class Data {
        public final int size;
        public final int[] colors;        // ARGB grid (row-major), opaque
        public final boolean[] entityCell; // true where a nearby entity sits
        public final int radius;
        public final int entities;
        public final boolean raining;

        Data(int size, int[] colors, boolean[] entityCell, int radius, int entities, boolean raining) {
            this.size = size;
            this.colors = colors;
            this.entityCell = entityCell;
            this.radius = radius;
            this.entities = entities;
            this.raining = raining;
        }
    }

    /** Samples a top-down preview of the {@code radius} footprint around {@code center}. */
    public static Data sample(Level level, BlockPos center, int radius) {
        int size = PreviewGrid.SIZE;
        int cx = center.getX(), cz = center.getZ(), cy = center.getY();
        int[] colors = PreviewGrid.build(cx, cz, radius, size, (wx, wz) -> {
            try {
                int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz);
                BlockPos p = new BlockPos(wx, y - 1, wz);
                BlockState st = level.getBlockState(p);
                MapColor mc = st.getMapColor(level, p);
                int base = (mc == null || mc == MapColor.NONE) ? 0x20303A : mc.col;
                float shade = clamp(0.55f + (y - cy) * 0.02f, 0.35f, 1.0f);
                return 0xFF000000 | shade(base, shade);
            } catch (Throwable t) {
                return 0xFF202830;
            }
        });

        boolean[] entityCell = new boolean[size * size];
        int entities = 0;
        try {
            AABB box = new AABB(center).inflate(radius);
            for (Entity e : level.getEntitiesOfClass(Entity.class, box)) {
                int idx = PreviewGrid.index((int) Math.floor(e.getX()), (int) Math.floor(e.getZ()),
                        cx, cz, radius, size);
                if (idx >= 0) {
                    entityCell[idx] = true;
                    entities++;
                }
            }
        } catch (Throwable ignored) {
        }

        boolean raining = false;
        try {
            raining = level.getRainLevel(1f) > 0.05f;
        } catch (Throwable ignored) {
        }
        return new Data(size, colors, entityCell, radius, entities, raining);
    }

    private static int shade(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int g = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
