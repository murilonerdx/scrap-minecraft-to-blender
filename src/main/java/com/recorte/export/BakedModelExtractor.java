package com.recorte.export;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts geometry from a {@link BakedModel} (items, blocks &mdash; swords, eggs, …) into an
 * {@link Ir.Model}. Baked quads are in [0,1] block space; we centre them at the origin and flip to
 * match the entity orientation. Each atlas sprite becomes its own small material, with the UVs remapped
 * from atlas space to that sprite's 0..1 range, so textures stay tiny instead of dragging the whole atlas.
 */
public final class BakedModelExtractor {

    // [0,1] block space -> centred, axis-flipped export space (1 unit = 1 block).
    private final Matrix4f convert = new Matrix4f().rotateZ((float) Math.PI).translate(-0.5f, -0.5f, -0.5f);

    private final Map<TextureAtlasSprite, Integer> spriteToMaterial = new HashMap<>();
    private Field spriteImageField;   // SpriteContents.originalImage : NativeImage
    private int spriteCounter;

    public Ir.Model extract(BakedModel model, BlockState state, String name) {
        Ir.Model out = new Ir.Model();
        Ir.Bone root = new Ir.Bone(name, -1, new Matrix4f(convert));
        root.localTransform = new Matrix4f(convert);
        out.addBone(root);

        RandomSource random = RandomSource.create(42L);
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction dir : Direction.values()) {
            quads.addAll(model.getQuads(state, dir, random));
        }
        quads.addAll(model.getQuads(state, null, random));

        for (BakedQuad quad : quads) {
            TextureAtlasSprite sprite = quad.getSprite();
            int materialIndex = materialForSprite(out, sprite);
            Ir.Primitive prim = out.primitiveForMaterial(materialIndex);
            prim.group = name;

            float u0 = sprite.getU0(), v0 = sprite.getV0();
            float du = sprite.getU1() - u0, dv = sprite.getV1() - v0;
            Vector3f normal = root.globalBind.transformDirection(new Vector3f(quad.getDirection().step()));
            if (normal.lengthSquared() > 1.0e-8f) normal.normalize();

            int[] data = quad.getVertices();
            int stride = data.length / 4;
            Ir.Vertex[] q = new Ir.Vertex[4];
            for (int k = 0; k < 4; k++) {
                int o = k * stride;
                float x = Float.intBitsToFloat(data[o]);
                float y = Float.intBitsToFloat(data[o + 1]);
                float z = Float.intBitsToFloat(data[o + 2]);
                float u = Float.intBitsToFloat(data[o + 4]);
                float v = Float.intBitsToFloat(data[o + 5]);
                float lu = du != 0f ? (u - u0) / du : u;
                float lv = dv != 0f ? (v - v0) / dv : v;
                Vector3f p = root.globalBind.transformPosition(new Vector3f(x, y, z));
                q[k] = new Ir.Vertex(p.x, p.y, p.z, normal.x, normal.y, normal.z, lu, lv, 0);
            }
            prim.addQuad(q[0], q[1], q[2], q[3]);
        }
        return out;
    }

    private int materialForSprite(Ir.Model out, TextureAtlasSprite sprite) {
        Integer existing = spriteToMaterial.get(sprite);
        if (existing != null) return existing;

        String file = "tex_" + spriteCounter + ".png";
        int idx = out.materialIndex("tex_" + spriteCounter);
        Ir.Material material = out.materials.get(idx);
        try {
            NativeImage img = spriteImage(sprite);
            if (img != null) {
                material.png = TextureExporter.pngBytes(img);
                material.textureFile = file;
            }
        } catch (Throwable ignored) {
        }
        spriteToMaterial.put(sprite, idx);
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
