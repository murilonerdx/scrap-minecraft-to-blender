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
        return extract(level, center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius));
    }

    /** Extracts every block / fluid / block-entity in the inclusive box between two corners. */
    public Ir.Model extract(Level level, BlockPos corner1, BlockPos corner2) {
        int minX = Math.min(corner1.getX(), corner2.getX()), maxX = Math.max(corner1.getX(), corner2.getX());
        int minY = Math.min(corner1.getY(), corner2.getY()), maxY = Math.max(corner1.getY(), corner2.getY());
        int minZ = Math.min(corner1.getZ(), corner2.getZ()), maxZ = Math.max(corner1.getZ(), corner2.getZ());
        BlockPos center = new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);

        Minecraft mc = Minecraft.getInstance();
        BlockColors blockColors = mc.getBlockColors();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();

        Ir.Model out = new Ir.Model();
        Ir.Bone root = new Ir.Bone("scene", -1, new Matrix4f());
        root.localTransform = new Matrix4f();
        out.addBone(root);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) continue;
                    // fluids (water/lava) render separately from block models — emit their top surface
                    net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
                    if (!fluid.isEmpty()) addFluidSurface(out, level, pos, center, fluid);
                    // block entities (chests, signs, banners, beds, bells, shulkers…) via their renderer
                    if (state.hasBlockEntity()) {
                        net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                        if (be != null) captureBlockEntity(out, be, pos, center);
                    }
                    if (state.getRenderShape() != RenderShape.MODEL) continue;
                    BakedModel model = blockRenderer.getBlockModel(state);
                    addBlock(out, level, pos, center, state, model, blockColors, random);
                }
            }
        }
        return out;
    }

    /**
     * Renders a block entity (chest/sign/banner/bed/bell/shulker…) into a {@link CapturingBuffer} and
     * folds the captured geometry into the scene at the block's position (X negated, no inflation).
     * Runs on the render thread (scene export does), so the GL texture binds inside {@code appendCaptured} work.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void captureBlockEntity(Ir.Model out, net.minecraft.world.level.block.entity.BlockEntity be,
                                    BlockPos pos, BlockPos center) {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer =
                mc.getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer == null) return;
        CapturingBuffer buffer = new CapturingBuffer();
        com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
        try {
            renderer.render(be, 0f, pose, buffer,
                    net.minecraft.client.renderer.LightTexture.FULL_BRIGHT,
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY);
        } catch (Throwable t) {
            return;   // some block entities (text, modded) may not render headless — skip
        }
        int dx = pos.getX() - center.getX();
        int dy = pos.getY() - center.getY();
        int dz = pos.getZ() - center.getZ();
        Matrix4f m = new Matrix4f().translate(-dx, dy, dz).scale(-1f, 1f, 1f);
        LayerCapturer.appendCaptured(buffer, out, m, 0, "BlockEntities", 0f);
    }

    /**
     * Emits the top surface of a fluid (water/lava) as a quad with the fluid's still sprite — scenes
     * skip fluids otherwise. Biome-tinted for water, emissive for lava, with the same lighting bake as
     * solid faces. Only the exposed top layer is emitted (the block above isn't the same fluid).
     */
    private static final Direction[] FLUID_SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private void addFluidSurface(Ir.Model out, Level level, BlockPos pos, BlockPos center,
                                 net.minecraft.world.level.material.FluidState fluid) {
        TextureAtlasSprite sprite = fluidStillSprite(fluid);
        if (sprite == null) return;

        boolean lava = fluid.is(net.minecraft.tags.FluidTags.LAVA);
        int materialIndex = materialForSprite(out, sprite, lava);
        Ir.Primitive prim = out.primitiveForMaterial(materialIndex);
        prim.group = "Fluids";

        float h = fluid.getHeight(level, pos);
        float cr = 1f, cg = 1f, cb = 1f;
        if (!lava) {
            int color = net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(level, pos);
            cr = ((color >> 16) & 255) / 255f;
            cg = ((color >> 8) & 255) / 255f;
            cb = (color & 255) / 255f;
        }
        int blockL = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos);
        int skyL = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos);
        float bright = 0.12f + 0.88f * Math.max(blockL, skyL) / 15f;

        int dx = pos.getX() - center.getX();
        int dy = pos.getY() - center.getY();
        int dz = pos.getZ() - center.getZ();

        // top surface — only when exposed (no same fluid directly above)
        if (level.getBlockState(pos.above()).getFluidState().getType() != fluid.getType()) {
            float s = bright * level.getShade(Direction.UP, true);
            prim.addQuad(
                    fluidVertex(0, h, 0, dx, dy, dz, 0, 0, cr * s, cg * s, cb * s),
                    fluidVertex(1, h, 0, dx, dy, dz, 1, 0, cr * s, cg * s, cb * s),
                    fluidVertex(1, h, 1, dx, dy, dz, 1, 1, cr * s, cg * s, cb * s),
                    fluidVertex(0, h, 1, dx, dy, dz, 0, 1, cr * s, cg * s, cb * s));
        }

        // exposed vertical sides (waterfalls, pool edges), from y=0 to the fluid height
        for (Direction dir : FLUID_SIDES) {
            BlockPos np = pos.relative(dir);
            BlockState ns = level.getBlockState(np);
            if (ns.getFluidState().getType() == fluid.getType()) continue;   // submerged side
            if (ns.isSolidRender(level, np)) continue;                       // occluded by a full block
            float s = bright * level.getShade(dir, true);
            emitFluidSide(prim, dir, h, dx, dy, dz, cr * s, cg * s, cb * s);
        }
    }

    private static void emitFluidSide(Ir.Primitive prim, Direction dir, float h, int dx, int dy, int dz,
                                      float r, float g, float b) {
        float[][] c = sideCorners(dir, h);
        Vector3f n = new Vector3f(dir.step());
        prim.addQuad(
                new Ir.Vertex(-(c[0][0] + dx), c[0][1] + dy, c[0][2] + dz, -n.x, n.y, n.z, c[0][3], c[0][4], 0, r, g, b, 1f),
                new Ir.Vertex(-(c[1][0] + dx), c[1][1] + dy, c[1][2] + dz, -n.x, n.y, n.z, c[1][3], c[1][4], 0, r, g, b, 1f),
                new Ir.Vertex(-(c[2][0] + dx), c[2][1] + dy, c[2][2] + dz, -n.x, n.y, n.z, c[2][3], c[2][4], 0, r, g, b, 1f),
                new Ir.Vertex(-(c[3][0] + dx), c[3][1] + dy, c[3][2] + dz, -n.x, n.y, n.z, c[3][3], c[3][4], 0, r, g, b, 1f));
    }

    /** {lx, ly, lz, u, v} for the 4 corners of a fluid side face up to height {@code h}. */
    private static float[][] sideCorners(Direction dir, float h) {
        float t = 1f - h;
        switch (dir) {
            case NORTH: return new float[][]{{0, 0, 0, 0, 1}, {1, 0, 0, 1, 1}, {1, h, 0, 1, t}, {0, h, 0, 0, t}};
            case SOUTH: return new float[][]{{1, 0, 1, 0, 1}, {0, 0, 1, 1, 1}, {0, h, 1, 1, t}, {1, h, 1, 0, t}};
            case WEST:  return new float[][]{{0, 0, 1, 0, 1}, {0, 0, 0, 1, 1}, {0, h, 0, 1, t}, {0, h, 1, 0, t}};
            default:    return new float[][]{{1, 0, 0, 0, 1}, {1, 0, 1, 1, 1}, {1, h, 1, 1, t}, {1, h, 0, 0, t}};
        }
    }

    private static Ir.Vertex fluidVertex(float lx, float ly, float lz, int dx, int dy, int dz,
                                         float u, float v, float r, float g, float b) {
        return new Ir.Vertex(-(lx + dx), ly + dy, lz + dz, 0f, 1f, 0f, u, v, 0, r, g, b, 1f);
    }

    private TextureAtlasSprite fluidStillSprite(net.minecraft.world.level.material.FluidState fluid) {
        try {
            net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions ext =
                    net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fluid);
            net.minecraft.resources.ResourceLocation tex = ext.getStillTexture();
            if (tex == null) return null;
            return Minecraft.getInstance().getModelManager()
                    .getAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS).getSprite(tex);
        } catch (Throwable t) {
            return null;
        }
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
                int frameH = sprite.contents().height();
                if (img.getHeight() > frameH) {
                    // animated texture (water/lava/fire/portal…): source is frames stacked vertically.
                    // Use frame 0 for the model (the UVs expect a single frame) + keep the sequence.
                    java.util.List<byte[]> frames = TextureExporter.sliceFrames(img, frameH);
                    if (!frames.isEmpty()) {
                        material.png = frames.get(0);
                        material.frameSequence = frames;
                        material.textureFile = file;
                    }
                } else {
                    material.png = TextureExporter.pngBytes(img);
                    material.textureFile = file;
                }
            }
        } catch (Throwable ignored) {
        }
        // LabPBR maps from the resource pack, if present (no-op on vanilla)
        try {
            net.minecraft.resources.ResourceLocation spriteName = sprite.contents().name();
            byte[] normal = TextureExporter.siblingTexture(spriteName, "_n");
            if (normal != null) {
                material.normalPng = normal;
                material.normalFile = "tex_" + spriteCounter + "_n.png";
            }
            byte[] mr = TextureExporter.labPbrToMetallicRoughness(
                    TextureExporter.siblingTexture(spriteName, "_s"));
            if (mr != null) {
                material.mrPng = mr;
                material.mrFile = "tex_" + spriteCounter + "_mr.png";
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
