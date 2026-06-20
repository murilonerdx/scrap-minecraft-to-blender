package com.recorte.export;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.recorte.Recorte;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures geometry by replaying the actual render path into a {@link CapturingBuffer}, then turning the
 * recorded quads into IR primitives. Two entry points:
 * <ul>
 *   <li>{@link #captureExtras} &mdash; replays just the player renderer's {@link RenderLayer}s (armour,
 *       held items, Curios/artifacts) on top of the already-rigged body.</li>
 *   <li>{@link #captureEntity} &mdash; replays a whole entity renderer, which works for ANY mob
 *       regardless of model type (vanilla {@code HumanoidModel}, {@code HierarchicalModel}, GeckoLib…).</li>
 * </ul>
 */
public final class LayerCapturer {
    private LayerCapturer() {}

    private static final int BODY_BONE = 1;          // ModelExtractor bone order: root=0, body=1
    private static final int FULL_BRIGHT = LightTexture.pack(15, 15);
    private static final float SHELL = 0.025f;       // outward push so accessories don't clip the body

    /** Player accessories: replay each render layer and attach to the body bone as a separate object. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void captureExtras(LivingEntityRenderer<?, ?> renderer,
                                     AbstractClientPlayer player,
                                     PlayerModel<?> model,
                                     Ir.Model out) {
        List<?> layers;
        try {
            Field layersField = ReflectUtil.fieldOfType(LivingEntityRenderer.class, List.class);
            layers = (List<?>) ReflectUtil.get(layersField, renderer);
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Could not access render layers; skipping accessories", t);
            return;
        }

        model.setAllVisible(true);
        CapturingBuffer buffer = new CapturingBuffer();
        PoseStack pose = new PoseStack();
        for (Object layerObj : layers) {
            RenderLayer layer = (RenderLayer) layerObj;
            try {
                layer.render(pose, buffer, FULL_BRIGHT, player, 0f, 0f, 1f, 0f, 0f, 0f);
            } catch (Throwable t) {
                Recorte.LOGGER.warn("Layer {} failed during capture (skipped)",
                        layer.getClass().getSimpleName(), t);
            }
        }
        appendCaptured(buffer, out, Convert.matrixCaptured(), BODY_BONE, "Accessories");
    }

    /** Whole-entity capture (mobs). Renders the entity renderer into the buffer; static (no bones). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Ir.Model captureEntity(EntityRenderer renderer, Entity entity) {
        Ir.Model out = new Ir.Model();
        Ir.Bone root = new Ir.Bone("root", -1, new Matrix4f());
        root.localTransform = new Matrix4f();
        out.addBone(root);

        CapturingBuffer buffer = new CapturingBuffer();
        PoseStack pose = new PoseStack();
        try {
            // The renderer applies its own flip/scale/translate, so the captured geometry is already
            // upright, in block units, with feet near the origin — no extra conversion needed.
            renderer.render(entity, 0f, 1.0f, pose, buffer, FULL_BRIGHT);
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Entity render capture failed for {}", entity.getType(), t);
        }
        appendCaptured(buffer, out, new Matrix4f(), 0, "Entity");
        return out;
    }

    /** Converts the recorded quads into IR primitives, one material per distinct texture. */
    private static void appendCaptured(CapturingBuffer buffer, Ir.Model out, Matrix4f m, int boneIndex, String group) {
        Map<Integer, Integer> texIdToMaterial = new HashMap<>();
        int textureCounter = out.materials.size();
        java.util.Set<String> seenQuads = new java.util.HashSet<>();   // drop duplicate passes (enchant glint, etc.)

        for (Map.Entry<RenderType, CapturingBuffer.Captured> entry : buffer.captured().entrySet()) {
            List<float[]> verts = entry.getValue().verts;
            if (verts.size() < 4) continue;

            int texId = resolveTextureId(entry.getKey());
            Integer materialIndex = texIdToMaterial.get(texId);
            if (materialIndex == null) {
                String file = "tex_" + textureCounter + ".png";
                byte[] png = null;
                try {
                    if (texId > 0) png = TextureExporter.dumpTextureId(texId);
                } catch (Throwable t) {
                    Recorte.LOGGER.warn("Could not export captured texture {}", texId, t);
                }
                materialIndex = out.materialIndex("captured_" + textureCounter);
                Ir.Material material = out.materials.get(materialIndex);
                material.textureFile = png != null ? file : null;
                material.png = png;
                texIdToMaterial.put(texId, materialIndex);
                textureCounter++;
            }

            Ir.Primitive prim = out.primitiveForMaterial(materialIndex);
            prim.group = group;
            for (int i = 0; i + 3 < verts.size(); i += 4) {
                StringBuilder key = new StringBuilder();
                for (int k = 0; k < 4; k++) {
                    float[] a = verts.get(i + k);
                    key.append(Math.round(a[0] * 1000)).append(',')
                            .append(Math.round(a[1] * 1000)).append(',')
                            .append(Math.round(a[2] * 1000)).append(';');
                }
                if (!seenQuads.add(key.toString())) continue;   // duplicate pass (glint/overlay) — skip
                Ir.Vertex[] quad = new Ir.Vertex[4];
                for (int k = 0; k < 4; k++) {
                    float[] a = verts.get(i + k);
                    Vector3f p = m.transformPosition(new Vector3f(a[0], a[1], a[2]));
                    Vector3f n = m.transformDirection(new Vector3f(a[5], a[6], a[7]));
                    if (n.lengthSquared() > 1.0e-8f) n.normalize();
                    // push slightly outward along the normal so worn items sit just OUTSIDE the body
                    // surface instead of z-fighting / clipping into it
                    quad[k] = new Ir.Vertex(p.x + n.x * SHELL, p.y + n.y * SHELL, p.z + n.z * SHELL,
                            n.x, n.y, n.z, a[3], a[4], boneIndex);
                }
                prim.addQuad(quad[0], quad[1], quad[2], quad[3]);
            }
        }
    }

    /** Binds the render type's texture and returns the GL texture id, restoring state afterwards. */
    private static int resolveTextureId(RenderType type) {
        try {
            type.setupRenderState();
            int id = RenderSystem.getShaderTexture(0);
            type.clearRenderState();
            return id;
        } catch (Throwable t) {
            return 0;
        }
    }
}
