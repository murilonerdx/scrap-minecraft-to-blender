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
        // Cape/elytra go into their own object so you can rig/animate the cloth separately in Blender;
        // everything else (armour, held items, Curios) stays grouped as accessories.
        CapturingBuffer accessories = new CapturingBuffer();
        CapturingBuffer cape = new CapturingBuffer();
        PoseStack pose = new PoseStack();
        for (Object layerObj : layers) {
            RenderLayer layer = (RenderLayer) layerObj;
            boolean isCape = layer instanceof net.minecraft.client.renderer.entity.layers.CapeLayer
                    || layer instanceof net.minecraft.client.renderer.entity.layers.ElytraLayer;
            CapturingBuffer target = isCape ? cape : accessories;
            try {
                layer.render(pose, target, FULL_BRIGHT, player, 0f, 0f, 1f, 0f, 0f, 0f);
            } catch (Throwable t) {
                Recorte.LOGGER.warn("Layer {} failed during capture (skipped)",
                        layer.getClass().getSimpleName(), t);
            }
        }
        appendCaptured(accessories, out, Convert.matrixCaptured(), BODY_BONE, "Accessories");
        appendCaptured(cape, out, Convert.matrixCaptured(), BODY_BONE, "Cape");
    }

    /**
     * Replays a living entity's render layers (fur, wool, eyes, saddles, armour, held items) on top of an
     * already-rigged body, so a snapshot mob isn't "bald" — the rig walk only sees the base model parts,
     * not the extra {@link RenderLayer}s. The captured layers attach to the root bone (static overlay).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void captureEntityLayers(LivingEntityRenderer<?, ?> renderer,
                                           net.minecraft.world.entity.LivingEntity entity, Ir.Model out) {
        List<?> layers;
        try {
            Field layersField = ReflectUtil.fieldOfType(LivingEntityRenderer.class, List.class);
            layers = (List<?>) ReflectUtil.get(layersField, renderer);
        } catch (Throwable t) {
            return;
        }
        CapturingBuffer buffer = new CapturingBuffer();
        PoseStack pose = new PoseStack();
        // age 0 / no swing: the exact idle pose the rig was extracted at, so the overlay aligns with it
        for (Object layerObj : layers) {
            try {
                ((RenderLayer) layerObj).render(pose, buffer, FULL_BRIGHT, entity, 0f, 0f, 1f, 0f, 0f, 0f);
            } catch (Throwable t) {
                Recorte.LOGGER.warn("Mob layer {} failed (skipped)", layerObj.getClass().getSimpleName(), t);
            }
        }
        appendCaptured(buffer, out, Convert.matrixCaptured(), 0, "Overlay");
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
        appendCaptured(buffer, out, new Matrix4f(), 0, "Entity");   // standalone mob: full renderer = all layers
        return out;
    }

    /**
     * Converts the recorded quads into IR primitives, one material per distinct texture, at exactly the
     * positions the render path produced (so worn items/armour/Curios land where the game draws them).
     */
    static void appendCaptured(CapturingBuffer buffer, Ir.Model out, Matrix4f m, int boneIndex, String group) {
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

            // Bind this captured object (≈ one accessory/overlay) to the bone nearest its centroid, so a
            // ring on the hand rides the arm and a cape rides the body during animation. The bind-pose
            // POSITION is unchanged (skinning uses inverse-bind), so static exports look identical.
            int joint = nearestBone(out, verts, m, boneIndex);

            // Use the captured position as-is: the render path already places accessories exactly where
            // the game draws them on the body. (We used to shove them horizontally "clear" of the body,
            // which only made worn items float off to the side — trust the real render instead.)
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
                    quad[k] = new Ir.Vertex(p.x, p.y, p.z, n.x, n.y, n.z, a[3], a[4], joint);
                }
                prim.addQuad(quad[0], quad[1], quad[2], quad[3]);
            }
        }
    }

    /** Bone nearest the centroid of a captured group (in export space), or {@code fallback} if not riggable. */
    private static int nearestBone(Ir.Model out, List<float[]> verts, Matrix4f m, int fallback) {
        if (out.bones.size() < 2 || verts.isEmpty()) return fallback;
        Vector3f c = new Vector3f();
        for (float[] a : verts) c.add(m.transformPosition(new Vector3f(a[0], a[1], a[2])));
        c.div(verts.size());
        return nearestBoneTo(out, c.x, c.y, c.z, fallback);
    }

    /** Pure: index of the bone whose bind position is nearest {@code (x,y,z)}; {@code fallback} if none. */
    static int nearestBoneTo(Ir.Model out, float x, float y, float z, int fallback) {
        int best = fallback;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < out.bones.size(); i++) {
            Vector3f bp = out.bones.get(i).globalBind.getTranslation(new Vector3f());
            float d = bp.distanceSquared(new Vector3f(x, y, z));
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
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
