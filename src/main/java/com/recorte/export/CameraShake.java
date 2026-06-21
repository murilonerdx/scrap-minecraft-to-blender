package com.recorte.export;

/**
 * Procedural handheld camera shake — a small, organic perturbation of an animated camera's position and
 * rotation, layered on top of the recorded/flythrough path so it feels hand-held instead of robotic.
 * Pure (just {@code sin} of time), so it can be unit-tested headlessly. Toggle with
 * {@code /recorte cam shake <amount>} (0 = off).
 */
public final class CameraShake {
    private CameraShake() {}

    /** 0 = off; higher = more shake. Set by the command, read while building camera keyframes. */
    public static volatile float amount = 0f;

    /** Position offset (export-space metres) at time {@code t} seconds. {0,0,0} when off. */
    public static float[] positionOffset(float t) {
        if (amount <= 0f) return new float[]{0f, 0f, 0f};
        float a = amount * 0.03f;
        return new float[]{
                a * (sin(t * 6.1f) + 0.5f * sin(t * 13.7f)),
                a * (sin(t * 5.3f) + 0.5f * sin(t * 11.2f)),
                a * (sin(t * 7.7f) + 0.5f * sin(t * 15.1f))};
    }

    /** Rotation perturbation as small pitch/yaw/roll in radians at time {@code t}. {0,0,0} when off. */
    public static float[] rotationEuler(float t) {
        if (amount <= 0f) return new float[]{0f, 0f, 0f};
        float a = amount * 0.008f;
        return new float[]{a * sin(t * 5.7f), a * sin(t * 6.9f), a * sin(t * 4.3f)};
    }

    private static float sin(float x) {
        return (float) Math.sin(x);
    }
}
