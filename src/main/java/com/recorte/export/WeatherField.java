package com.recorte.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Studio feature #10 — the <b>pure</b>, headless-testable core of the weather capture: distributes
 * precipitation points (rain or snow) through a column volume over the scene. Kept free of any
 * Minecraft type so the self-test can assert the field's bounds, colours and intensity scaling without
 * launching the game; {@link WeatherCapture} reads the live weather/biome and feeds this.
 *
 * <p>Each returned point is {@code {x, y, z, r, g, b}} already in export-local space (centred on the
 * origin): {@code x}/{@code z} fill a square of half-width {@code radius}, {@code y} spans the scene
 * column plus headroom above it. Point count scales with rain intensity.
 */
public final class WeatherField {
    private WeatherField() {}

    public static final int MAX_POINTS = 8000;
    public static final int HEADROOM = 20;          // blocks of precipitation above the scene top
    private static final float DENSITY = 1.5f;      // points per column block at full intensity

    /** Precipitation points filling the volume; deterministic for a given {@code seed}. */
    public static List<float[]> points(int radius, float intensity, boolean snow, long seed) {
        List<float[]> out = new ArrayList<>();
        if (radius <= 0 || intensity <= 0f) return out;
        int count = Math.min(MAX_POINTS, (int) (intensity * DENSITY * 4f * radius * radius));
        Random rng = new Random(seed);
        float topY = radius + HEADROOM;
        for (int i = 0; i < count; i++) {
            float x = (rng.nextFloat() * 2f - 1f) * radius;
            float z = (rng.nextFloat() * 2f - 1f) * radius;
            float y = -radius + rng.nextFloat() * (topY + radius);   // [-radius, radius + HEADROOM]
            float[] c = color(snow, rng);
            out.add(new float[]{x, y, z, c[0], c[1], c[2]});
        }
        return out;
    }

    /** Snow is white with a faint cool tint; rain is a translucent blue-grey. Slight per-drop variation. */
    private static float[] color(boolean snow, Random rng) {
        float jitter = 0.85f + rng.nextFloat() * 0.15f;   // 0.85..1.0 brightness
        if (snow) {
            return new float[]{0.95f * jitter, 0.97f * jitter, 1.0f * jitter};
        }
        return new float[]{0.55f * jitter, 0.65f * jitter, 0.82f * jitter};
    }
}
