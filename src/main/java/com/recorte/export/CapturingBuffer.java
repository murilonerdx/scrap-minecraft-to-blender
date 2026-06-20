package com.recorte.export;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link MultiBufferSource} that records every vertex pushed to it, grouped by {@link RenderType}
 * (which carries the texture). Render the player's layers into this and you capture armour, held
 * items, capes and any modded accessory (Curios, GeckoLib, …) regardless of how they are drawn.
 *
 * <p>Minecraft's model/item rendering calls the default {@code VertexConsumer.vertex(Matrix4f, …)} /
 * {@code putBulkData(…)} helpers, which decode down to the primitive methods below, so we only need to
 * record position, UV and normal. Positions arrive already transformed by the supplied pose stack.
 */
public final class CapturingBuffer implements MultiBufferSource {

    /** One captured vertex: position, uv, normal (8 floats). Four in a row make a quad. */
    public static final class Captured {
        public final List<float[]> verts = new ArrayList<>();
    }

    private final Map<RenderType, Captured> buffers = new LinkedHashMap<>();

    public Map<RenderType, Captured> captured() {
        return buffers;
    }

    @Override
    public VertexConsumer getBuffer(RenderType renderType) {
        Captured c = buffers.computeIfAbsent(renderType, t -> new Captured());
        return new Recorder(c);
    }

    /** Accumulates one vertex at a time and appends it on {@link #endVertex()}. */
    private static final class Recorder implements VertexConsumer {
        private final Captured out;
        private float x, y, z, u, v, nx, ny, nz;

        Recorder(Captured out) {
            this.out = out;
        }

        @Override
        public VertexConsumer vertex(double px, double py, double pz) {
            this.x = (float) px;
            this.y = (float) py;
            this.z = (float) pz;
            return this;
        }

        @Override
        public VertexConsumer uv(float tu, float tv) {
            this.u = tu;
            this.v = tv;
            return this;
        }

        @Override
        public VertexConsumer normal(float dx, float dy, float dz) {
            this.nx = dx;
            this.ny = dy;
            this.nz = dz;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int ou, int ov) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int lu, int lv) {
            return this;
        }

        @Override
        public void endVertex() {
            out.verts.add(new float[]{x, y, z, u, v, nx, ny, nz});
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
        }

        @Override
        public void unsetDefaultColor() {
        }
    }
}
