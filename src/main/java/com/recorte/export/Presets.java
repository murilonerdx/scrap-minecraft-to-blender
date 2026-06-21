package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Studio feature #18 — saves/loads/lists named {@link StudioConfig} presets as JSON under
 * {@code recorte_exports/presets/}. Commands: {@code /recorte preset save|load|list <name>}.
 */
public final class Presets {
    private Presets() {}

    private static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("recorte_exports").resolve("presets");
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }

    public static void save(String name) {
        try {
            Path d = dir();
            Files.createDirectories(d);
            StudioConfig cfg = StudioConfig.snapshot();
            Files.writeString(d.resolve(sanitize(name) + ".json"), cfg.toJson());
            feedback("§a■ Preset §f" + name + "§a salvo §7(" + cfg.describe() + ")");
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Preset save failed", t);
            feedback("§cFalha ao salvar preset: " + t.getMessage());
        }
    }

    public static void load(String name) {
        try {
            Path f = dir().resolve(sanitize(name) + ".json");
            if (!Files.exists(f)) {
                feedback("§ePreset §f" + name + "§e não existe. §7/recorte preset list");
                return;
            }
            StudioConfig cfg = StudioConfig.fromJson(Files.readString(f));
            cfg.apply();
            feedback("§a■ Preset §f" + name + "§a carregado §7(" + cfg.describe() + ")");
        } catch (Throwable t) {
            Recorte.LOGGER.warn("Preset load failed", t);
            feedback("§cFalha ao carregar preset: " + t.getMessage());
        }
    }

    public static void list() {
        List<String> names = listNames();
        if (names.isEmpty()) {
            feedback("§7Nenhum preset salvo. §f/recorte preset save <nome>");
            return;
        }
        feedback("§7Presets: §f" + String.join("§7, §f", names));
    }

    public static List<String> listNames() {
        List<String> out = new ArrayList<>();
        try (var s = Files.list(dir())) {
            s.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                String n = p.getFileName().toString();
                out.add(n.substring(0, n.length() - ".json".length()));
            });
        } catch (Throwable ignored) {
        }
        Collections.sort(out);
        return out;
    }

    private static void feedback(String message) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(message), true);
        }
    }
}
