package com.recorte.export;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.List;

/**
 * A voxel structure sent <b>from Blender into Minecraft</b> (the reverse of the exporter): a block
 * palette plus a list of {@code [x, y, z, paletteIndex]} placements, already in Minecraft orientation
 * (Y up) and relative to a 0,0,0 anchor. {@link Builder} pastes it into the world WorldEdit-style.
 *
 * <p>Pure (Gson only), so the parse/validation is headless-testable. JSON shape:
 * <pre>{ "palette": ["minecraft:stone", …], "blocks": [[x,y,z,idx], …], "name": "…" }</pre>
 */
public final class BuildStructure {
    private static final Gson GSON = new Gson();
    public static final int MAX_BLOCKS = 2_000_000;   // safety cap

    public String name = "build";
    public List<String> palette = new ArrayList<>();
    public int[][] blocks = new int[0][];

    /** Parses + validates a structure; returns null if it's missing/empty/oversized/malformed. */
    public static BuildStructure fromJson(String json) {
        try {
            BuildStructure s = GSON.fromJson(json, BuildStructure.class);
            if (s == null || s.palette == null || s.palette.isEmpty() || s.blocks == null) return null;
            if (s.blocks.length == 0 || s.blocks.length > MAX_BLOCKS) return null;
            // every placement must be [x,y,z,idx] with idx inside the palette
            for (int[] b : s.blocks) {
                if (b == null || b.length < 4) return null;
                if (b[3] < 0 || b[3] >= s.palette.size()) return null;
            }
            if (s.name == null || s.name.isEmpty()) s.name = "build";
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    public int size() {
        return blocks.length;
    }

    /** The palette block id for a placement row (e.g. {@code "minecraft:obsidian"}). */
    public String blockId(int[] placement) {
        return palette.get(placement[3]);
    }
}
