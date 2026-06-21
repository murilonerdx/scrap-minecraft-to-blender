package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

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
        final LivingEntity entity;
        final EntityModel<?> model;
        final Ir.Model ir;
        final Ir.Animation anim = new Ir.Animation();
        final PoseSampler.State state;
        int frames;

        Session(LivingEntity entity, EntityModel<?> model, Ir.Model ir) {
            this.entity = entity;
            this.model = model;
            this.ir = ir;
            this.state = new PoseSampler.State(entity);
            this.anim.timeScale = state.timeScale;   // slow-mo: writer stretches the clip's times
        }

        void sample(float partial) {
            if (PoseSampler.sample(entity, model, ir, anim, state, partial)) frames++;
        }
    }
}
