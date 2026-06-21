package com.recorte.export;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Studio feature #14 — supports <b>NLA stacking</b> (clips as non-linear strips in Blender). Each glTF
 * animation imports as a Blender Action, and the add-on lays them out one strip per NLA track so you can
 * blend/reorder them. Blender needs every Action to have a <b>distinct name</b> or the importer mangles
 * collisions, so this pure helper de-duplicates the clip-name list (stable order, colliding names get a
 * {@code _2}, {@code _3}… suffix) before the writer emits them. Headless-testable.
 */
public final class NlaStack {
    private NlaStack() {}

    /** Returns clip names made unique, preserving order; blanks become {@code clip}. */
    public static List<String> uniqueNames(List<String> names) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : names) {
            String base = (raw == null || raw.isEmpty()) ? "clip" : raw;
            String name = base;
            int n = 2;
            while (!seen.add(name)) {
                name = base + "_" + n++;
            }
            out.add(name);
        }
        return out;
    }
}
