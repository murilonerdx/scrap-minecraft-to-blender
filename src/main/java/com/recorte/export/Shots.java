package com.recorte.export;

import java.util.ArrayList;
import java.util.List;

/**
 * Studio feature #17 — the <b>pure</b> core for <b>named shot markers</b>. While recording a cinematic
 * you tag cut points with {@code /recorte shot <name>}; each becomes a {@code "shot:"} event on the
 * timeline. This extracts just those, in time order, so they can be written as a clean {@code shots.csv}
 * and turned into named Blender timeline markers. No Minecraft types, so the self-test runs offline.
 */
public final class Shots {
    private Shots() {}

    public static final String PREFIX = "shot:";

    /** One named shot: a label and the time (seconds) it was marked. */
    public static final class Shot {
        public final String name;
        public final float time;

        public Shot(String name, float time) {
            this.name = name;
            this.time = time;
        }
    }

    /** The shot markers from an event list, in chronological order (drops the {@code shot:} prefix). */
    public static List<Shot> fromEvents(List<Ir.Event> events) {
        List<Shot> out = new ArrayList<>();
        if (events == null) return out;
        for (Ir.Event e : events) {
            if (e == null || e.name == null || !e.name.startsWith(PREFIX)) continue;
            String name = e.name.substring(PREFIX.length());
            out.add(new Shot(name.isEmpty() ? "shot" : name, Math.max(0f, e.time)));
        }
        out.sort((a, b) -> Float.compare(a.time, b.time));
        return out;
    }
}
