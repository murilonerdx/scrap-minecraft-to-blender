package com.recorte.export;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The studio camera rig: cameras you place in-game with {@code /recorte cam add <name>}. Each is stored
 * in world space (eye position + look) and converted into export-space {@link Ir.Camera}s relative to a
 * scene's centre at export time, so every scene/snapshot/cinematic carries your named cameras to switch
 * between in Blender.
 */
public final class CameraRig {
    private CameraRig() {}

    private static final List<Placed> cameras = new ArrayList<>();

    /** Drops a camera at the player's current eye + look. */
    public static void add(String name) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        net.minecraft.client.Camera cam = mc.gameRenderer.getMainCamera();
        float focus = (mc.hitResult != null
                && mc.hitResult.getType() != net.minecraft.world.phys.HitResult.Type.MISS)
                ? (float) mc.hitResult.getLocation().distanceTo(cam.getPosition()) : 12f;
        cameras.add(new Placed(name, cam.getPosition(),
                new Vector3f(cam.getLookVector()), new Vector3f(cam.getUpVector()),
                (float) Math.toRadians(mc.options.fov().get()), focus));
        feedback("§a● Câmera §f" + name + "§a colocada §7(" + cameras.size() + " no rig)");
    }

    public static void clear() {
        cameras.clear();
        feedback("§eRig de câmeras limpo.");
    }

    public static void list() {
        if (cameras.isEmpty()) {
            feedback("§7Nenhuma câmera no rig. Use §f/recorte cam add <nome>");
            return;
        }
        StringBuilder sb = new StringBuilder("§7Câmeras (" + cameras.size() + "): §f");
        for (int i = 0; i < cameras.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(cameras.get(i).name);
        }
        feedback(sb.toString());
    }

    public static int count() {
        return cameras.size();
    }

    /** Placed cameras as export-space {@link Ir.Camera}s relative to {@code center} (X negated). */
    public static List<Ir.Camera> toExportCameras(BlockPos center) {
        List<Ir.Camera> out = new ArrayList<>();
        for (Placed p : cameras) {
            float px = -(float) (p.pos.x - center.getX());
            float py = (float) (p.pos.y - center.getY());
            float pz = (float) (p.pos.z - center.getZ());
            Quaternionf q = new Quaternionf()
                    .lookAlong(-p.look.x, p.look.y, p.look.z, -p.up.x, p.up.y, p.up.z).conjugate();
            Ir.Camera c = new Ir.Camera(new float[]{px, py, pz}, new float[]{q.x, q.y, q.z, q.w}, p.yfov);
            c.name = "cam_" + p.name;
            c.focusDistance = p.focus;
            out.add(c);
        }
        return out;
    }

    private static void feedback(String msg) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), true);
        }
    }

    private static final class Placed {
        final String name;
        final Vec3 pos;
        final Vector3f look, up;
        final float yfov;
        final float focus;

        Placed(String name, Vec3 pos, Vector3f look, Vector3f up, float yfov, float focus) {
            this.name = name;
            this.pos = pos;
            this.look = look;
            this.up = up;
            this.yfov = yfov;
            this.focus = focus;
        }
    }
}
