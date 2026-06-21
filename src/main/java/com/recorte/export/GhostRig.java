package com.recorte.export;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Onion-skinning: snap "ghost" copies of an entity at different poses/positions (move it, snap, repeat),
 * then export them all in one glTF — fading from faint (oldest) to solid (newest) — as an animation
 * reference. {@code /recorte ghost add | clear | export}. Each snap captures the LIVE pose statically.
 */
public final class GhostRig {
    private GhostRig() {}

    private static final List<Ghost> ghosts = new ArrayList<>();

    /** Snap the entity you're looking at (or yourself) at its current pose + position. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void add() {
        Minecraft mc = Minecraft.getInstance();
        Entity e = (mc.crosshairPickEntity != null) ? mc.crosshairPickEntity : mc.player;
        if (e == null) return;
        try {
            EntityRenderer renderer = mc.getEntityRenderDispatcher().getRenderer(e);
            if (renderer == null) return;
            Ir.Model model = LayerCapturer.captureEntity(renderer, e);
            if (model.triangleCount() <= 0) {
                feedback("§cSem geometria pra esse fantasma.");
                return;
            }
            ghosts.add(new Ghost(model, e.getX(), e.getY(), e.getZ(), name(e)));
            feedback("§a● Fantasma §f" + ghosts.size() + "§a capturado §7(" + name(e) + ")");
        } catch (Throwable t) {
            feedback("§cFalha ao capturar fantasma: " + t.getMessage());
        }
    }

    public static void clear() {
        ghosts.clear();
        feedback("§eFantasmas limpos.");
    }

    public static void export() {
        if (ghosts.size() < 2) {
            feedback("§cCapture ao menos 2 fantasmas (§f/recorte ghost add§c) antes de exportar.");
            return;
        }
        Exporter.exportGhosts(new ArrayList<>(ghosts));
    }

    private static String name(Entity e) {
        return e.getType().getDescriptionId().replaceAll(".*\\.", "");
    }

    private static void feedback(String msg) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), true);
        }
    }

    static final class Ghost {
        final Ir.Model model;
        final double x, y, z;
        final String name;

        Ghost(Ir.Model model, double x, double y, double z, String name) {
            this.model = model;
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
        }
    }
}
