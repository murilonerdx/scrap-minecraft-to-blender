package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cinematic recording: captures the whole moment — the scene + every nearby rigged mob animating over
 * time (limbs <em>and</em> world path) + camera + sun + sky — and exports it as ONE animated Blender
 * scene. Start it, let the world act, stop it.
 *
 * <p>Each mob is merged into a single combined model at the origin; its world placement, body yaw and
 * limb poses are written entirely as animation, so scrubbing the timeline plays the whole moment back.
 */
public final class SceneRecorder {
    private SceneRecorder() {}

    private static final int MAX_FRAMES = 30 * 120;   // ~2 minutes at 30 fps
    private static volatile Session active;            // volatile: block events arrive on the server thread

    public static boolean isRecording() {
        return active != null;
    }

    /**
     * Records a point-in-time event (block break/place) into the active cinematic, if any, as a
     * timeline marker. Called from Forge {@code BlockEvent}s, which fire on the integrated-server
     * thread &mdash; hence the volatile {@code active} and the synchronized event list.
     */
    public static void recordEvent(String name, BlockPos pos) {
        if (pos == null) recordEvent(name, Double.NaN, 0, 0);
        else recordEvent(name, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** World-position variant (sounds, particles…); pass {@code wx = NaN} for an event with no position. */
    public static void recordEvent(String name, double wx, double wy, double wz) {
        Session s = active;
        if (s == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float t = (float) (mc.level.getGameTime() * 0.05 - (s.startSeconds < 0 ? 0 : s.startSeconds));
        if (t < 0) t = 0f;
        float[] p = Double.isNaN(wx) ? null : new float[]{
                -(float) (wx - s.center.getX()),
                (float) (wy - s.center.getY()),
                (float) (wz - s.center.getZ())};
        s.anim.event(t, name, p);
    }

    public static void start(int radius) {
        if (active != null) {
            feedback("§eJá gravando cena. Use §f/recorte record scene stop");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            feedback("§cEntre num mundo primeiro.");
            return;
        }
        try {
            int r = Math.max(2, Math.min(radius, 24));
            BlockPos center = mc.player.blockPosition();
            feedback("§7Preparando cinematic (raio " + r + ")... pode travar.");
            Ir.Model out = new SceneExtractor().extract(mc.level, center, r);
            out.sun = Exporter.worldSun();
            // animated POV camera (path filled in each frame) — just the lens here
            out.camera = new Ir.Camera(new float[]{0, 0, 0}, new float[]{0, 0, 0, 1},
                    (float) Math.toRadians(mc.options.fov().get()));
            out.extraCameras.addAll(Exporter.presetCameras(r));   // static cinematic angles too

            List<Tracked> tracked = new ArrayList<>();
            AABB box = new AABB(center).inflate(r);
            for (Entity e : mc.level.getEntitiesOfClass(Entity.class, box)) {
                try {
                    EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(e);
                    if (renderer == null) continue;
                    String group = "entity_" + name(e), prefix = "e" + tracked.size() + "_";

                    // living + riggable → bones (limbs animate); everything else → static capture
                    if (e instanceof LivingEntity && renderer instanceof LivingEntityRenderer<?, ?> living) {
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        EntityModel model = living.getModel();
                        Ir.Model em = new ModelExtractor().extractEntity(e, model, "texture.png");
                        if (em.triangleCount() > 0) {
                            try {
                                @SuppressWarnings({"rawtypes", "unchecked"})
                                ResourceLocation tex = ((EntityRenderer) renderer).getTextureLocation(e);
                                em.materials.get(0).png = TextureExporter.bytesFor(tex);
                            } catch (Throwable ignored) {
                            }
                            int boneOffset = out.bones.size();
                            List<ModelPart> parts = mergeEntity(out, em, group, prefix);
                            tracked.add(new Tracked(e, model, boneOffset, parts, false));
                            continue;
                        }
                    }
                    // non-living (boats, minecarts, item frames, dropped items…) or GeckoLib: capture a
                    // static mesh and animate its world position over time
                    Ir.Model cap = LayerCapturer.captureEntity(renderer, e);
                    if (cap.triangleCount() <= 0) continue;
                    int boneOffset = out.bones.size();
                    mergeEntity(out, cap, group, prefix);
                    tracked.add(new Tracked(e, null, boneOffset, new ArrayList<>(), true));
                } catch (Throwable t) {
                    Recorte.LOGGER.warn("Cinematic: entity {} skipped", e.getType(), t);
                }
            }
            active = new Session(out, center, tracked);
            feedback("§a● Gravando CINEMATIC §f(" + tracked.size() + " entidades)§a... §f/recorte record scene stop");
        } catch (Throwable t) {
            active = null;
            Recorte.LOGGER.error("Cinematic start failed", t);
            feedback("§cFalha ao iniciar: " + t.getMessage());
        }
    }

    /** Called once per rendered frame with the render partial-tick, so the captured motion matches
     *  exactly what's on screen (interpolated), not just the 20 Hz game ticks. Throttled to ~30 fps. */
    public static void renderTick(float partial) {
        if (active == null) return;
        try {
            active.sample(partial);
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Cinematic sample failed", t);
        }
        if (active.frames >= MAX_FRAMES) {
            feedback("§eLimite de gravação — finalizando.");
            stop();
        }
    }

    public static void stop() {
        if (active == null) {
            feedback("§eNenhuma gravação de cena em andamento.");
            return;
        }
        Session s = active;
        active = null;
        if (s.frames < 2) {
            feedback("§eGravação muito curta.");
            return;
        }
        Exporter.exportSceneRecording(s.out, s.anim, s.frames, s.tracked.size());
    }

    /** Appends a rigged entity at the origin (its placement/movement is written as animation later). */
    private static List<ModelPart> mergeEntity(Ir.Model target, Ir.Model src, String group, String texPrefix) {
        int boneOffset = target.bones.size();
        List<ModelPart> parts = new ArrayList<>();
        for (Ir.Bone sb : src.bones) {
            int parent = sb.parentIndex < 0 ? -1 : sb.parentIndex + boneOffset;
            Ir.Bone nb = new Ir.Bone(group + "_" + sb.name, parent, new Matrix4f(sb.globalBind));
            nb.localTransform = new Matrix4f(sb.localTransform);
            target.bones.add(nb);
            parts.add(sb.sourcePart instanceof ModelPart p ? p : null);
        }
        Map<Integer, Integer> matMap = new HashMap<>();
        for (int i = 0; i < src.materials.size(); i++) {
            Ir.Material sm = src.materials.get(i);
            Ir.Material tm = new Ir.Material(group + "_" + sm.name);
            tm.png = sm.png;
            tm.textureFile = sm.textureFile != null ? texPrefix + sm.textureFile : null;
            tm.emissive = sm.emissive;
            target.materials.add(tm);
            matMap.put(i, target.materials.size() - 1);
        }
        for (Ir.Primitive sp : src.primitives) {
            Ir.Primitive tp = target.primitiveForMaterial(matMap.get(sp.materialIndex));
            tp.group = group;
            int base = tp.vertices.size();
            for (Ir.Vertex v : sp.vertices) {
                tp.vertices.add(new Ir.Vertex(v.px, v.py, v.pz, v.nx, v.ny, v.nz, v.u, v.v,
                        v.joint + boneOffset, v.r, v.g, v.b, v.a));
            }
            for (int idx : sp.indices) tp.indices.add(base + idx);
        }
        return parts;
    }

    private static String name(Entity e) {
        return e.getType().getDescriptionId().replaceAll(".*\\.", "");
    }

    private static void feedback(String message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static final class Tracked {
        final Entity entity;
        final EntityModel<?> model;    // null for captured (non-living) entities
        final int boneOffset;
        final List<ModelPart> parts;   // per local bone, the source ModelPart (null for the root)
        final boolean captured;        // true: static mesh, animate world position only (boats, item frames…)

        Tracked(Entity entity, EntityModel<?> model, int boneOffset, List<ModelPart> parts, boolean captured) {
            this.entity = entity;
            this.model = model;
            this.boneOffset = boneOffset;
            this.parts = parts;
            this.captured = captured;
        }
    }

    private static final class Session {
        static final float SAMPLE_DT = 1f / 30f;   // throttle render-frame sampling to ~30 fps

        final Ir.Model out;
        final BlockPos center;
        final List<Tracked> tracked;
        final Ir.Animation anim = new Ir.Animation();
        double startSeconds = -1;
        float lastT = -1f;
        int frames;

        Session(Ir.Model out, BlockPos center, List<Tracked> tracked) {
            this.out = out;
            this.center = center;
            this.tracked = tracked;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void sample(float partial) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            // real elapsed time (so the glTF plays back at true speed regardless of frame rate),
            // throttled so we don't write hundreds of keys per second on a fast machine
            double nowSec = (mc.level.getGameTime() + partial) * 0.05;
            if (startSeconds < 0) startSeconds = nowSec;
            float t = (float) (nowSec - startSeconds);
            if (lastT >= 0f && t - lastT < SAMPLE_DT) return;
            lastT = t;
            anim.times.add(t);

            // day/night timelapse: sample the sun + sky this frame
            try {
                Ir.Light sun = Exporter.worldSun();
                net.minecraft.world.phys.Vec3 sky = mc.player != null
                        ? mc.level.getSkyColor(mc.player.position(), partial)
                        : new net.minecraft.world.phys.Vec3(0.5, 0.6, 0.9);
                anim.worldKey(sun.direction, sun.color, sun.intensity,
                        new float[]{(float) sky.x, (float) sky.y, (float) sky.z});
            } catch (Throwable ignored) {
            }

            // POV camera: the player's eye this frame, in scene space (X negated like the scene)
            net.minecraft.client.Camera cam = mc.gameRenderer.getMainCamera();
            net.minecraft.world.phys.Vec3 cp = cam.getPosition();
            org.joml.Vector3f cf = cam.getLookVector();
            org.joml.Vector3f cu = cam.getUpVector();
            org.joml.Quaternionf cq = new org.joml.Quaternionf()
                    .lookAlong(-cf.x, cf.y, cf.z, -cu.x, cu.y, cu.z).conjugate();
            anim.cameraKey(new float[]{-(float) (cp.x - center.getX()),
                    (float) (cp.y - center.getY()), (float) (cp.z - center.getZ())},
                    new float[]{cq.x, cq.y, cq.z, cq.w});

            for (Tracked tr : tracked) {
                Entity e = tr.entity;
                double ex = net.minecraft.util.Mth.lerp(partial, e.xOld, e.getX());
                double ey = net.minecraft.util.Mth.lerp(partial, e.yOld, e.getY());
                double ez = net.minecraft.util.Mth.lerp(partial, e.zOld, e.getZ());

                if (tr.captured) {
                    // non-living (boat/minecart/item frame/dropped item): animate world position only,
                    // the orientation is baked into the captured mesh
                    anim.key(tr.boneOffset, new float[]{
                            (float) -(ex - center.getX()),
                            (float) (ey - center.getY()),
                            (float) (ez - center.getZ())}, new float[]{0f, 0f, 0f, 1f});
                    continue;
                }

                LivingEntity le = (LivingEntity) e;
                float limbAmount = Math.min(le.walkAnimation.speed(partial), 1.0f);
                float limbSwing = le.walkAnimation.position(partial);
                float age = le.tickCount + partial;
                EntityModel raw = tr.model;
                try {
                    raw.prepareMobModel(le, limbSwing, limbAmount, partial);
                    raw.setupAnim(le, limbSwing, limbAmount, age, 0f, le.getViewXRot(partial));
                } catch (Throwable ignored) {
                }

                // root: absolute world placement + body yaw (interpolated between ticks), so the
                // travel path is smooth instead of stepping 20×/s
                float bodyYaw = net.minecraft.util.Mth.rotLerp(partial, le.yBodyRotO, le.yBodyRot);
                Matrix4f rootM = new Matrix4f()
                        .translate((float) -(ex - center.getX()),
                                (float) (ey - center.getY()),
                                (float) (ez - center.getZ()))
                        .rotateY((float) Math.toRadians(-bodyYaw))
                        .mul(Convert.matrix());
                Vector3f t2 = rootM.getTranslation(new Vector3f());
                Quaternionf q = rootM.getNormalizedRotation(new Quaternionf());
                anim.key(tr.boneOffset, new float[]{t2.x, t2.y, t2.z}, new float[]{q.x, q.y, q.z, q.w});

                // limbs
                for (int i = 1; i < tr.parts.size(); i++) {
                    ModelPart p = tr.parts.get(i);
                    if (p == null) continue;
                    Quaternionf lq = new Quaternionf().rotationZYX(p.zRot, p.yRot, p.xRot);
                    anim.key(tr.boneOffset + i, new float[]{p.x, p.y, p.z}, new float[]{lq.x, lq.y, lq.z, lq.w});
                }
            }
            frames++;
        }
    }
}
