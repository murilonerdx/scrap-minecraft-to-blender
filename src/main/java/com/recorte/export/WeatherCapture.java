package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

/**
 * Studio feature #10 — captures live weather (rain/snow) as a <b>point cloud</b> filling the scene
 * volume ({@code Weather} object). When it's raining, every point is placed by the pure
 * {@link WeatherField}; rain vs snow and "does it even precipitate here" come from the centre biome
 * ({@code hasPrecipitation} / {@code coldEnoughToSnow}), the density from the rain strength. In Blender
 * the points import as loose vertices — instance a streak (rain) or flake (snow) on each with Geometry
 * Nodes and animate them falling. Fully defensive: clear weather or any error → nothing emitted.
 */
public final class WeatherCapture {
    private WeatherCapture() {}

    public static int appendWeather(Ir.Model out, BlockPos center, int radius) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Level level = mc.level;
            if (level == null) return 0;
            float rain = level.getRainLevel(1f);            // 0 (clear) .. 1 (downpour)
            if (rain <= 0.05f) return 0;

            Biome biome = level.getBiome(center).value();
            if (!biome.hasPrecipitation()) return 0;        // deserts/savannas: no weather
            boolean snow = biome.coldEnoughToSnow(center);

            List<float[]> pts = WeatherField.points(radius, rain, snow, 0x5EEDL);
            if (pts.isEmpty()) return 0;

            int mat = out.materialIndex("Weather");
            Ir.Material m = out.materials.get(mat);
            m.translucent = true;                           // BLEND
            m.alpha = snow ? 0.85f : 0.6f;                  // snow reads more solid than rain
            Ir.Primitive prim = out.primitiveForMaterial(mat);
            prim.group = "Weather";
            prim.mode = Ir.Primitive.POINTS;

            for (float[] p : pts) {
                prim.addPoint(new Ir.Vertex(p[0], p[1], p[2], 0f, 1f, 0f, 0f, 0f, 0, p[3], p[4], p[5], 1f));
            }
            return pts.size();
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Weather capture skipped", t);
            return 0;
        }
    }
}
