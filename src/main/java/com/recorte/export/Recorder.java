package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Records a live animation of an entity: starts on demand, samples each bone's pose every client tick
 * (replaying the renderer's {@code prepareMobModel}/{@code setupAnim} so we read the same pose the game
 * shows), and on stop exports a rigged glTF with a keyframed animation track. Whatever the mob does
 * in-game &mdash; walking, attacking, looking around &mdash; becomes a Blender animation.
 */
public final class Recorder {
    private Recorder() {}

    private static final int MAX_FRAMES = 30 * 120;   // ~2 minutes cap at 30 fps
    private static Session active;

    public static boolean isRecording() {
        return active != null;
    }

    /** Toggles recording the entity you're looking at (or yourself). */
    public static void toggleLookedAtOrSelf() {
        if (active != null) {
            stop();
            return;
        }
        startLookedAtOrSelf();
    }

    @SuppressWarnings("rawtypes")
    public static void startLookedAtOrSelf() {
        Minecraft mc = Minecraft.getInstance();
        LivingEntity target = (mc.crosshairPickEntity instanceof LivingEntity le) ? le : mc.player;
        if (target == null) return;
        EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(target);
        start(target, renderer);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void start(LivingEntity entity, EntityRenderer renderer) {
        if (active != null) {
            feedback("§eJá está gravando. Use §f/recorte record stop§e.");
            return;
        }
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) {
            feedback("§cEssa entidade não pode ser gravada (sem modelo rigável).");
            return;
        }
        try {
            EntityModel model = living.getModel();
            Ir.Model ir = new ModelExtractor().extractEntity(entity, model, "texture.png");
            if (ir.triangleCount() <= 0) {
                feedback("§cModelo não rigável (GeckoLib). Gravação ao vivo só funciona em mobs vanilla por enquanto.");
                return;
            }
            try {
                ResourceLocation tex = ((EntityRenderer) renderer).getTextureLocation(entity);
                ir.materials.get(0).png = TextureExporter.bytesFor(tex);
            } catch (Throwable ignored) {
            }
            active = new Session(entity, model, ir);
            feedback("§a● Gravando §f" + name(entity) + "§a... faça a ação e use §f/recorte record stop");
        } catch (Throwable t) {
            active = null;
            Recorte.LOGGER.error("Failed to start recording", t);
            feedback("§cFalha ao iniciar gravação: " + t.getMessage());
        }
    }

    /** Sampling now happens on the render thread for smooth, high-fps keyframes (see {@link #renderTick}). */
    public static void tick() {
    }

    /** Called once per rendered frame with the render partial-tick, so limb poses and the travel
     *  path match what's on screen (interpolated), not just the 20 Hz ticks. Throttled to ~30 fps. */
    public static void renderTick(float partial) {
        if (active == null) return;
        try {
            active.sample(partial);
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Recording sample failed", t);
        }
        if (active.frames >= MAX_FRAMES) {
            feedback("§eLimite de gravação atingido — finalizando.");
            stop();
        }
    }

    public static void stop() {
        if (active == null) {
            feedback("§eNão há gravação em andamento.");
            return;
        }
        Session s = active;
        active = null;
        if (s.frames < 2) {
            feedback("§eGravação muito curta (sem frames).");
            return;
        }
        Exporter.exportRecording(s.ir, s.anim, name(s.entity), s.frames);
    }

    private static String name(LivingEntity e) {
        return e.getType().getDescriptionId().replaceAll(".*\\.", "");
    }

    private static void feedback(String message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(message), true);
        }
    }

    /** One in-progress recording. */
    private static final class Session {
        static final float SAMPLE_DT = 1f / 30f;   // throttle render-frame sampling to ~30 fps

        final LivingEntity entity;
        final EntityModel<?> model;
        final Ir.Model ir;
        final Ir.Animation anim = new Ir.Animation();
        final double startX, startY, startZ;
        final float startYaw;
        double startSeconds = -1;
        float lastT = -1f;
        int frames;

        Session(LivingEntity entity, EntityModel<?> model, Ir.Model ir) {
            this.entity = entity;
            this.model = model;
            this.ir = ir;
            this.startX = entity.getX();
            this.startY = entity.getY();
            this.startZ = entity.getZ();
            this.startYaw = entity.yBodyRot;
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        void sample(float partial) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;

            // real elapsed time + throttle, so playback speed is correct and files stay sane
            double nowSec = (mc.level.getGameTime() + partial) * 0.05;
            if (startSeconds < 0) startSeconds = nowSec;
            float t = (float) (nowSec - startSeconds);
            if (lastT >= 0f && t - lastT < SAMPLE_DT) return;
            lastT = t;

            float limbAmount = Math.min(entity.walkAnimation.speed(partial), 1.0f);
            float limbSwing = entity.walkAnimation.position(partial);
            float ageInTicks = entity.tickCount + partial;
            float headPitch = entity.getViewXRot(partial);

            EntityModel raw = model;
            raw.prepareMobModel(entity, limbSwing, limbAmount, partial);
            raw.setupAnim(entity, limbSwing, limbAmount, ageInTicks, 0f, headPitch);

            anim.times.add(t);

            // root bone (0): world movement + body yaw (interpolated), so the mob travels its path
            double cx = net.minecraft.util.Mth.lerp(partial, entity.xOld, entity.getX());
            double cy = net.minecraft.util.Mth.lerp(partial, entity.yOld, entity.getY());
            double cz = net.minecraft.util.Mth.lerp(partial, entity.zOld, entity.getZ());
            double dx = cx - startX, dy = cy - startY, dz = cz - startZ;
            float bodyYaw = net.minecraft.util.Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
            float dyaw = (float) Math.toRadians(bodyYaw - startYaw);
            Matrix4f rootM = new Matrix4f()
                    .translate((float) -dx, (float) dy, (float) dz)   // negate X to match export space
                    .rotateY(-dyaw)
                    .mul(Convert.matrix());
            Vector3f rt = rootM.getTranslation(new Vector3f());
            Quaternionf rq = rootM.getNormalizedRotation(new Quaternionf());
            anim.key(0, new float[]{rt.x, rt.y, rt.z}, new float[]{rq.x, rq.y, rq.z, rq.w});

            // limb bones: each ModelPart's local pose
            for (int i = 1; i < ir.bones.size(); i++) {
                if (!(ir.bones.get(i).sourcePart instanceof ModelPart p)) continue;
                Quaternionf q = new Quaternionf().rotationZYX(p.zRot, p.yRot, p.xRot);
                anim.key(i, new float[]{p.x, p.y, p.z}, new float[]{q.x, q.y, q.z, q.w});
            }
            frames++;
        }
    }
}
