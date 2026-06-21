package com.recorte.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Studio feature #18 — a named, savable bundle of studio settings (default scene radius, slow-mo factor,
 * camera shake, fps, depth of field). {@link Presets} writes these as JSON files you can reload, so a
 * look you dialed in is one command away next session. Free of Minecraft types (it only touches the pure
 * {@link SlowMo} / {@link CameraShake} holders), so the self-test can round-trip it headless.
 */
public final class StudioConfig {
    /** The live studio settings (defaults for new exports / the control panel). */
    public static volatile StudioConfig CURRENT = new StudioConfig();

    public int radius = 16;        // default scene/snapshot radius
    public float slowmo = 1f;      // recording time-stretch (see SlowMo)
    public float shake = 0f;       // camera shake amount (see CameraShake)
    public int fps = 30;           // recording sample rate
    public boolean dof = true;     // depth of field on the POV/placed cameras
    public int width = 1920;       // render resolution (studio scene template, #20)
    public int height = 1080;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** A config reflecting the live global state (so saving a preset captures what you have set). */
    public static StudioConfig snapshot() {
        StudioConfig c = new StudioConfig();
        c.radius = CURRENT.radius;
        c.fps = CURRENT.fps;
        c.dof = CURRENT.dof;
        c.width = CURRENT.width;
        c.height = CURRENT.height;
        c.slowmo = SlowMo.factor();
        c.shake = CameraShake.amount;
        return c;
    }

    /** Makes this config the live state: updates the globals the recorders read + becomes CURRENT. */
    public void apply() {
        SlowMo.set(slowmo);
        CameraShake.amount = Math.max(0f, shake);
        radius = Math.max(1, Math.min(radius, 64));
        if (fps <= 0) fps = 30;
        CURRENT = this;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static StudioConfig fromJson(String json) {
        StudioConfig c = GSON.fromJson(json, StudioConfig.class);
        return c != null ? c : new StudioConfig();
    }

    public String describe() {
        return String.format(java.util.Locale.ROOT,
                "radius %d · slowmo %.0f× · shake %.0f · fps %d · dof %s · %dx%d",
                radius, slowmo, shake, fps, dof ? "on" : "off", width, height);
    }
}
