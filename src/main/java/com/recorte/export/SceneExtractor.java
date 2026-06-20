package com.recorte.export;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * Captures the blocks in a cube around a point into a single {@link Ir.Model} &mdash; a Blender-ready
 * diorama of your build/landscape. Hidden faces are culled (so interiors aren't exported), each atlas
 * sprite becomes its own small material, and biome tints (grass/leaves/water) are baked as vertex
 * colours. World space is kept (+Y up); X is negated to convert Minecraft's handedness for glTF.
 */
public final class SceneExtractor {

    private final Map<TextureAtlasSprite, Integer> normalMaterials = new HashMap<>();
    private final Map<TextureAtlasSprite, Integer> emissiveMaterials = new HashMap<>();
    private Field spriteImageField;
    private int spriteCounter;

    public Ir.Model extract(Level level, BlockPos center, int radius) {
        Minecraft mc = Minecraft.getInstance();
        BlockColors blockColors = mc.getBlockColors();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();

        Ir.Model out = new Ir.Model();
        Ir.Bone root = new Ir.Bone("scene", -1, new Matrix4f());
        root.localTransform = new Matrix4f();
        out.addBone(root);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL) continue;
                    BakedModel model = blockRenderer.getBlockModel(state);
                    addBlock(out, level, pos, center, state, model, blockColors, random);
                }
            }
        }
        return out;
    }

    private void addBlock(Ir.Model out, Level level, BlockPos pos, BlockPos center, BlockState state,
                          BakedModel model, BlockColors blockColors, RandomSource random) {
        boolean emissive = state.getLightEmission() > 0;
        for (Direction dir : Direction.values()) {
            if (!Block.shouldRenderFace(state, level, pos, dir, pos.relative(dir))) continue;
            random.setSeed(state.getSeed(pos));
            for (BakedQuad quad : model.getQuads(state, dir, random)) {
                addQuad(out, quad, pos, center, state, level, blockColors, emissive);
            }
        }
        random.setSeed(state.getSeed(pos));
        for (BakedQuad quad : model.getQuads(state, null, random)) {
            addQuad(out, quad, pos, center, state, level, blockColors, emissive);
        }
    }

    private void addQuad(Ir.Model out, BakedQuad quad, BlockPos pos, BlockPos center,
                         BlockState state, Level level, BlockColors blockColors, boolean emissive) {
        TextureAtlasSprite sprite = quad.getSprite();
        int materialIndex = materialForSprite(out, sprite, emissive);
        Ir.Primitive prim = out.primitiveForMaterial(materialIndex);
        prim.group = "Scene";

        float u0 = sprite.getU0(), v0 = sprite.getV0();
        float du = sprite.getU1() - u0, dv = sprite.getV1() - v0;

        float cr = 1f, cg = 1f, cb = 1f;
        if (quad.isTinted()) {
            int color = blockColors.getColor(state, level, pos, quad.getTintIndex());
            cr = ((color >> 16) & 255) / 255f;
            cg = ((color >> 8) & 255) / 255f;
            cb = (color & 255) / 255f;
        }

        // bake world lighting: block+sky light at the face's neighbour + Minecraft face shading
        BlockPos lightPos = pos.relative(quad.getDirection());
        int blockL = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, lightPos);
        int skyL = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, lightPos);
        float lightLevel = Math.max(blockL, skyL) / 15f;
        float bright = (0.12f + 0.88f * lightLevel) * level.getShade(quad.getDirection(), true);
        cr *= bright;
        cg *= bright;
        cb *= bright;

        Vector3f n = new Vector3f(quad.getDirection().step());

        int[] data = quad.getVertices();
        int stride = data.length / 4;
        Ir.Vertex[] q = new Ir.Vertex[4];
        for (int k = 0; k < 4; k++) {
            int o = k * stride;
            float x = -(Float.intBitsToFloat(data[o]) + (pos.getX() - center.getX()));
            float y = Float.intBitsToFloat(data[o + 1]) + (pos.getY() - center.getY());
            float z = Float.intBitsToFloat(data[o + 2]) + (pos.getZ() - center.getZ());
            float u = Float.intBitsToFloat(data[o + 4]);
            float v = Float.intBitsToFloat(data[o + 5]);
            float lu = du != 0f ? (u - u0) / du : u;
            float lv = dv != 0f ? (v - v0) / dv : v;
            q[k] = new Ir.Vertex(x, y, z, -n.x, n.y, n.z, lu, lv, 0, cr, cg, cb, 1f);
        }
        prim.addQuad(q[0], q[1], q[2], q[3]);
    }

    private int materialForSprite(Ir.Model out, TextureAtlasSprite sprite, boolean emissive) {
        Map<TextureAtlasSprite, Integer> map = emissive ? emissiveMaterials : normalMaterials;
        Integer existing = map.get(sprite);
        if (existing != null) return existing;

        String file = "tex_" + spriteCounter + ".png";
        int idx = out.materialIndex("tex_" + spriteCounter + (emissive ? "_glow" : ""));
        Ir.Material material = out.materials.get(idx);
        material.emissive = emissive;
        try {
            NativeImage img = spriteImage(sprite);
            if (img != null) {
                material.png = TextureExporter.pngBytes(img);
                material.textureFile = file;
            }
        } catch (Throwable ignored) {
        }
        // LabPBR normal map from the resource pack, if present (no-op on vanilla)
        try {
            byte[] normal = TextureExporter.siblingTexture(sprite.contents().name(), "_n");
            if (normal != null) {
                material.normalPng = normal;
                material.normalFile = "tex_" + spriteCounter + "_n.png";
            }
        } catch (Throwable ignored) {
        }
        map.put(sprite, idx);
        spriteCounter++;
        return idx;
    }

    private NativeImage spriteImage(TextureAtlasSprite sprite) {
        Object contents = sprite.contents();
        if (spriteImageField == null) {
            spriteImageField = ReflectUtil.fieldOfType(contents.getClass(), NativeImage.class);
        }
        return (NativeImage) ReflectUtil.get(spriteImageField, contents);
    }
}
