package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Studio feature #13 — <b>takes</b>: record the same subject several times on one shared rig, each
 * recording becoming its own named clip. {@code take start} captures the rig on the first take (re-uses
 * it after), samples each render frame via {@link PoseSampler}, and {@code take stop} banks the clip;
 * {@code take export} writes them all as one multi-clip glTF (one Blender Action per take) so you can
 * compare and keep the best. They share the rig's origin, so the takes overlay for easy comparison.
 */
public final class TakeRecorder {
    private TakeRecorder() {}

    private static final int MAX_FRAMES = 30 * 120;   // ~2 minutes per take
    private static Session session;

    public static boolean isRecording() {
        return session != null && session.current != null;
    }

    public static boolean hasTakes() {
        return session != null && !session.takes.isEmpty();
    }

    /** Begins a new take. Captures the rig (and subject) on the first take; re-uses both afterwards. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void start(String name) {
        if (session != null && session.current != null) {
            feedback("§eJá gravando um take. Use §f/recorte take stop§e.");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (session == null) {
            LivingEntity target = (mc.crosshairPickEntity instanceof LivingEntity le) ? le : mc.player;
            if (target == null) return;
            EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(target);
            if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) {
                feedback("§cEssa entidade não pode ser gravada (sem modelo rigável).");
                return;
            }
            try {
                EntityModel model = living.getModel();
                Ir.Model ir = new ModelExtractor().extractEntity(target, model, "texture.png");
                if (ir.triangleCount() <= 0) {
                    feedback("§cModelo não rigável (GeckoLib). Takes só funcionam em mobs vanilla por enquanto.");
                    return;
                }
                try {
                    ResourceLocation tex = ((EntityRenderer) renderer).getTextureLocation(target);
                    ir.materials.get(0).png = TextureExporter.bytesFor(tex);
                } catch (Throwable ignored) {
                }
                session = new Session(target, model, ir);
            } catch (Throwable t) {
                Recorte.LOGGER.error("Failed to start takes", t);
                feedback("§cFalha ao iniciar takes: " + t.getMessage());
                return;
            }
        }
        if (!session.entity.isAlive()) {
            feedback("§cO sujeito dos takes não existe mais. §f/recorte take clear§c e recomece.");
            return;
        }
        session.current = new Ir.Animation();
        session.current.name = (name != null && !name.isEmpty()) ? name : "take_" + (session.takes.size() + 1);
        session.state = new PoseSampler.State(session.entity);
        session.current.timeScale = session.state.timeScale;   // slow-mo: writer stretches the clip
        session.frames = 0;
        feedback("§a● Gravando take §f" + session.current.name + "§a... §7/recorte take stop");
    }

    /** Called each rendered frame while a take is in progress. */
    public static void renderTick(float partial) {
        if (session == null || session.current == null) return;
        try {
            if (PoseSampler.sample(session.entity, session.model, session.ir, session.current, session.state, partial)) {
                session.frames++;
            }
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Take sample failed", t);
        }
        if (session.frames >= MAX_FRAMES) {
            feedback("§eLimite do take atingido — finalizando.");
            stop();
        }
    }

    /** Banks the in-progress take. */
    public static void stop() {
        if (session == null || session.current == null) {
            feedback("§eNenhum take em gravação. §f/recorte take start");
            return;
        }
        if (session.frames < 2) {
            feedback("§eTake muito curto — descartado.");
            session.current = null;
            return;
        }
        String tn = session.current.name;
        session.takes.add(session.current);
        int n = session.takes.size();
        session.current = null;
        feedback(String.format("§a■ Take §f%s§a salvo (%d frames). Total: §f%d§a. §7take start (outro) · take export",
                tn, session.frames, n));
    }

    /** Writes every banked take as one multi-clip glTF, then ends the session. */
    public static void export() {
        if (session == null || session.takes.isEmpty()) {
            feedback("§eNenhum take gravado ainda. §f/recorte take start");
            return;
        }
        Exporter.exportTakes(session.ir, new ArrayList<>(session.takes), session.label);
        session = null;
    }

    public static void clear() {
        session = null;
        feedback("§7Takes descartados.");
    }

    public static void list() {
        if (session == null) {
            feedback("§7Nenhuma sessão de takes. §f/recorte take start");
            return;
        }
        StringBuilder sb = new StringBuilder("§7Takes (§f" + session.label + "§7): ");
        if (session.takes.isEmpty()) sb.append("nenhum ainda");
        for (int i = 0; i < session.takes.size(); i++) {
            sb.append("§f").append(session.takes.get(i).name);
            if (i < session.takes.size() - 1) sb.append("§7, ");
        }
        feedback(sb.toString());
    }

    private static void feedback(String message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(message), true);
        }
    }

    /** A multi-take session: one shared rig + the subject, a list of banked takes, and the current one. */
    private static final class Session {
        final LivingEntity entity;
        final EntityModel<?> model;
        final Ir.Model ir;
        final String label;
        final List<Ir.Animation> takes = new ArrayList<>();
        Ir.Animation current;
        PoseSampler.State state;
        int frames;

        Session(LivingEntity entity, EntityModel<?> model, Ir.Model ir) {
            this.entity = entity;
            this.model = model;
            this.ir = ir;
            this.label = entity.getType().getDescriptionId().replaceAll(".*\\.", "");
        }
    }
}
