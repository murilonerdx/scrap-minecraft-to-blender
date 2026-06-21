package com.recorte.export;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The shared, render-frame pose sampler used by both the single recorder and the multi-take recorder:
 * replays the renderer's {@code prepareMobModel}/{@code setupAnim} so each bone reads the same pose the
 * game shows, keys the root's world path (relative to the take's start, X negated for export space) and
 * every limb's local pose, throttled to ~30 fps. Extracting it keeps one tested sampling path.
 */
public final class PoseSampler {
    private PoseSampler() {}

    public static final float SAMPLE_DT = 1f / 30f;   // throttle render-frame sampling to ~30 fps

    /** Mutable per-recording state: the capture origin plus the sampling throttle/counters. */
    public static final class State {
        public final double startX, startY, startZ;
        public final float startYaw;
        public double startSeconds = -1;
        public float lastT = -1f;
        public int frames;

        public State(LivingEntity e) {
            this.startX = e.getX();
            this.startY = e.getY();
            this.startZ = e.getZ();
            this.startYaw = e.yBodyRot;
        }
    }

    /** Samples one render-frame pose into {@code anim}; returns true if a keyframe was actually added. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean sample(LivingEntity entity, EntityModel<?> model, Ir.Model ir,
                                 Ir.Animation anim, State st, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;

        // real elapsed time + throttle, so playback speed is correct and files stay sane
        double nowSec = (mc.level.getGameTime() + partial) * 0.05;
        if (st.startSeconds < 0) st.startSeconds = nowSec;
        float t = (float) (nowSec - st.startSeconds);
        if (st.lastT >= 0f && t - st.lastT < SAMPLE_DT) return false;
        st.lastT = t;

        float limbAmount = Math.min(entity.walkAnimation.speed(partial), 1.0f);
        float limbSwing = entity.walkAnimation.position(partial);
        float ageInTicks = entity.tickCount + partial;
        float headPitch = entity.getViewXRot(partial);

        EntityModel raw = model;
        raw.prepareMobModel(entity, limbSwing, limbAmount, partial);
        raw.setupAnim(entity, limbSwing, limbAmount, ageInTicks, 0f, headPitch);

        anim.times.add(t);

        // root bone (0): world movement + body yaw (interpolated), so the mob travels its path
        double cx = Mth.lerp(partial, entity.xOld, entity.getX());
        double cy = Mth.lerp(partial, entity.yOld, entity.getY());
        double cz = Mth.lerp(partial, entity.zOld, entity.getZ());
        double dx = cx - st.startX, dy = cy - st.startY, dz = cz - st.startZ;
        float bodyYaw = Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
        float dyaw = (float) Math.toRadians(bodyYaw - st.startYaw);
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
        st.frames++;
        return true;
    }
}
