package com.recorte.export;

import java.util.HashMap;
import java.util.Map;

/**
 * Studio feature #16 — maps Minecraft bone names to a consistent <b>humanoid</b> naming scheme
 * (Mixamo-compatible: Hips / Spine / Head / Left·RightArm / Left·RightUpLeg) so an exported rig can be
 * retargeted to/from other humanoid armatures in Blender (Rokoko, Auto-Rig Pro, Rigify…). Pure and
 * headless-testable; {@link Exporter} applies it to a player/humanoid model on a retarget export.
 */
public final class RetargetMap {
    private RetargetMap() {}

    private static final Map<String, String> MAP = new HashMap<>();
    static {
        MAP.put("root", "Hips");
        MAP.put("hips", "Hips");
        MAP.put("pelvis", "Hips");
        MAP.put("body", "Spine");
        MAP.put("torso", "Spine");
        MAP.put("spine", "Spine");
        MAP.put("chest", "Spine");
        MAP.put("head", "Head");
        MAP.put("rightarm", "RightArm");
        MAP.put("leftarm", "LeftArm");
        MAP.put("rightleg", "RightUpLeg");
        MAP.put("leftleg", "LeftUpLeg");
    }

    /** Humanoid label for a Minecraft bone name (case/separator-insensitive), or null if unknown. */
    public static String humanoid(String mcBone) {
        if (mcBone == null) return null;
        String key = mcBone.toLowerCase().replaceAll("[^a-z0-9]", "");
        return MAP.get(key);
    }

    /** Tags every mappable bone of {@code model} with its humanoid retarget name. Returns how many matched. */
    public static int apply(Ir.Model model) {
        int n = 0;
        for (Ir.Bone b : model.bones) {
            String h = humanoid(b.name);
            if (h != null) {
                b.retargetName = h;
                n++;
            }
        }
        return n;
    }
}
