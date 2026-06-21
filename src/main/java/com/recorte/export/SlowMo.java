package com.recorte.export;

/**
 * Studio feature #15 — global <b>slow-motion / time-remap</b> factor for recordings. A factor of N makes
 * the recorders sample N× more densely in real time (so the slowed clip stays smooth) and tags the clip
 * with {@code timeScale = N}, which the {@link GltfWriter} uses to stretch every keyframe time ×N — the
 * action then plays back N× slower at the scene's 30 fps. Factor 1 = real time (no-op).
 */
public final class SlowMo {
    private SlowMo() {}

    public static final float MAX = 16f;
    public static volatile float factor = 1f;

    /** Clamped factor, never below 1 (we only slow down, and avoid divide-by-zero in the throttle). */
    public static float factor() {
        float f = factor;
        if (f < 1f) return 1f;
        return Math.min(f, MAX);
    }

    public static void set(float f) {
        factor = Math.max(1f, Math.min(f, MAX));
    }
}
