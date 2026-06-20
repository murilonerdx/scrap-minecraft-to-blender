package com.recorte.export;

import org.joml.Matrix4f;

/**
 * The single Minecraft-model-space to export-space conversion, shared by the body extractor and the
 * render-layer capturer so everything lands in the same coordinate system.
 *
 * <p>{@code M = rotateZ(180deg) * scale(1/16)}: a proper rotation plus a positive uniform scale
 * (determinant &gt; 0), so winding and normals are preserved. The result is upright, mirrored to match
 * the in-game view, at 1 unit = 1 block.
 */
public final class Convert {
    private Convert() {}

    public static final float SCALE = 1f / 16f;

    public static Matrix4f matrix() {
        return new Matrix4f().rotateZ((float) Math.PI).scale(SCALE);
    }

    /**
     * For geometry captured from the live render pipeline (armour, items, Curios): Minecraft's model
     * and item renderers already divide positions by 16, so the captured vertices arrive in block
     * units. Only the axis flip is needed here &mdash; applying the 1/16 scale again would shrink
     * everything 16x.
     */
    public static Matrix4f matrixCaptured() {
        return new Matrix4f().rotateZ((float) Math.PI);
    }
}
