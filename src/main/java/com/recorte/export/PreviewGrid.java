package com.recorte.export;

/**
 * Studio feature #19 — the <b>pure</b> core of the in-game export preview: maps the square export
 * footprint (centre ± radius) onto an N×N grid and samples a colour per cell, so the control panel can
 * draw a top-down thumbnail of what a {@code scene}/{@code snapshot} will capture. No Minecraft types,
 * so the cell mapping and grid build are headless-testable; {@link ExportPreview} feeds it live data.
 */
public final class PreviewGrid {
    private PreviewGrid() {}

    public static final int SIZE = 40;   // grid resolution (cells per side)

    /** A colour for the surface at a world column. */
    public interface ColorSampler {
        int colorAt(int worldX, int worldZ);
    }

    /** Grid index (row-major) for a world x/z within the footprint, or -1 if outside. */
    public static int index(int worldX, int worldZ, int cx, int cz, int radius, int size) {
        int span = 2 * radius;
        int col = (int) ((double) (worldX - (cx - radius)) / span * size);
        int row = (int) ((double) (worldZ - (cz - radius)) / span * size);
        if (col < 0 || col >= size || row < 0 || row >= size) return -1;
        return row * size + col;
    }

    /** Builds a {@code size×size} ARGB grid by sampling the surface colour at each cell's centre. */
    public static int[] build(int cx, int cz, int radius, int size, ColorSampler sampler) {
        int[] out = new int[size * size];
        int span = 2 * radius;
        for (int row = 0; row < size; row++) {
            for (int col = 0; col < size; col++) {
                int wx = cx - radius + (int) ((col + 0.5) / size * span);
                int wz = cz - radius + (int) ((row + 0.5) / size * span);
                out[row * size + col] = sampler.colorAt(wx, wz);
            }
        }
        return out;
    }
}
