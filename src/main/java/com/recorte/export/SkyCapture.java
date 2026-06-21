package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Studio feature #11 — exports the in-game sky as Blender geometry: a vertex-coloured <b>sky dome</b>
 * (gradient from the live sky colour at the zenith to a hazier horizon) enclosing the scene, plus a
 * procedural <b>cloud layer</b> ({@code Clouds} object, soft self-lit) at the dimension's cloud height.
 * Complements the add-on's World-background sky with actual geometry you can see and render. Reads only
 * public sky/cloud colours; fully defensive (any failure → no dome, never a crash).
 */
public final class SkyCapture {
    private SkyCapture() {}

    public static void appendSky(Ir.Model out, BlockPos center, int radius) {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            if (level == null) return;

            Vec3 eye = mc.player != null ? mc.player.getEyePosition(1f) : Vec3.atCenterOf(center);
            Vec3 sky = level.getSkyColor(eye, 1f);
            float[] zenith = {(float) sky.x, (float) sky.y, (float) sky.z};
            float[] horizon = {mix(zenith[0], 1f, 0.45f), mix(zenith[1], 1f, 0.45f), mix(zenith[2], 1f, 0.45f)};

            float domeRadius = radius * 6f + 32f;
            SkyGeometry.addDome(out, domeRadius, zenith, horizon, 16, 24, 0);

            float cloudH = level.effects().getCloudHeight();   // NaN in dimensions without clouds (nether/end)
            if (!Float.isNaN(cloudH)) {
                Vec3 cc = level.getCloudColor(1f);
                float[] cloudColor = {(float) cc.x, (float) cc.y, (float) cc.z};
                float cloudY = Math.min(cloudH - center.getY(), domeRadius * 0.7f);
                cloudY = Math.max(cloudY, radius + 8f);
                SkyGeometry.addClouds(out, domeRadius, cloudY, 12f, cloudColor, 0xC10D5L, 0);
            }
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Sky capture skipped", t);
        }
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
