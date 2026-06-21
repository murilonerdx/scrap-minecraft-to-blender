package com.recorte.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Studio feature #12 — the <b>pure</b> core that turns recorded {@code "sound:"} {@link Ir.Event}s into
 * positioned {@link Ir.Speaker}s. Sounds repeat constantly (footsteps, every block hit), so we collapse
 * all firings of the same sound at roughly the same spot into one Speaker, keeping the earliest time —
 * a clean, finite set of spatial emitters for the add-on to place in Blender. No Minecraft types, so the
 * self-test can verify the dedup offline.
 */
public final class Speakers {
    private Speakers() {}

    private static final String PREFIX = "sound:";
    private static final float CELL = 2f;   // merge sounds within ~2 blocks of each other

    /** Distinct positioned speakers from an event list (sound events with a position only). */
    public static List<Ir.Speaker> fromEvents(List<Ir.Event> events) {
        Map<String, Ir.Speaker> byKey = new LinkedHashMap<>();
        if (events == null) return new ArrayList<>();
        for (Ir.Event e : events) {
            if (e == null || e.position == null || e.name == null || !e.name.startsWith(PREFIX)) continue;
            String sound = e.name.substring(PREFIX.length());
            String key = sound + "@" + Math.round(e.position[0] / CELL) + ","
                    + Math.round(e.position[1] / CELL) + "," + Math.round(e.position[2] / CELL);
            Ir.Speaker prev = byKey.get(key);
            if (prev == null || e.time < prev.time) {
                byKey.put(key, new Ir.Speaker(sound, e.position, Math.max(0f, e.time), 1f));
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
