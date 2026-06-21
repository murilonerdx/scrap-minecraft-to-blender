package com.recorte.client;

import com.recorte.export.ExportPreview;
import com.recorte.export.Exporter;
import com.recorte.export.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The Recorte control panel: a single in-game screen to trigger every export mode (player/entity, scene,
 * snapshot, recording, and item/block/entity/mod by id) with radius and id fields — no commands needed.
 */
public final class RecorteScreen extends Screen {

    private EditBox radiusBox;
    private EditBox idBox;
    private ExportPreview.Data preview;   // #19: top-down thumbnail of the scene/snapshot footprint
    private int lastPreviewRadius = -1;
    // searchable item/block browser
    private final java.util.List<ResourceLocation> results = new java.util.ArrayList<>();
    private int resultsX, resultsY, resultsW;
    private static final int ROW_H = 12, MAX_RESULTS = 8;

    public RecorteScreen() {
        super(Component.literal("Recorte — Export to Blender"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 44;
        int w = 220;
        int left = cx - w / 2;

        // --- you / looked-at ---
        addRenderableWidget(Button.builder(Component.literal("Export (looked-at / self)"),
                b -> run(Exporter::exportLookedAtOrSelf)).bounds(left, y, w, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("Looked-at block"),
                b -> run(Exporter::exportLookedAtBlock)).bounds(left, y, 108, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Held item"),
                b -> run(Exporter::exportHeldItem)).bounds(left + 112, y, 108, 20).build());
        y += 24;

        String recLabel = Recorder.isRecording() ? "■ Stop recording" : "● Record (looked-at / self)";
        addRenderableWidget(Button.builder(Component.literal(recLabel),
                b -> run(Recorder::toggleLookedAtOrSelf)).bounds(left, y, w, 20).build());
        y += 24;

        String liveLabel = com.recorte.export.HttpBridge.liveMode
                ? "◉ Live link: ON (Blender auto-updates)" : "○ Live link: OFF";
        addRenderableWidget(Button.builder(Component.literal(liveLabel), b -> {
            com.recorte.export.Exporter.toggleLive();
            this.rebuildWidgets();
        }).bounds(left, y, w, 20).build());
        y += 32;

        // --- radius + scene/snapshot ---
        radiusBox = new EditBox(this.font, left, y, 50, 20, Component.literal("radius"));
        radiusBox.setValue("16");
        addRenderableWidget(radiusBox);
        addRenderableWidget(Button.builder(Component.literal("Scene"),
                b -> run(() -> Exporter.exportScene(radius()))).bounds(left + 56, y, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Snapshot"),
                b -> run(() -> Exporter.exportSnapshot(radius()))).bounds(left + 140, y, 80, 20).build());
        y += 36;

        // --- search field + item/block/entity/mod ---
        idBox = new EditBox(this.font, left, y, w, 20, Component.literal("id"));
        idBox.setHint(Component.literal("search items/blocks…  (or type a full id / modid)"));
        idBox.setResponder(this::onSearch);
        addRenderableWidget(idBox);
        y += 24;

        resultsX = left;
        resultsY = y;       // the search-results overlay covers the type buttons while you're searching
        resultsW = w;
        addRenderableWidget(Button.builder(Component.literal("Item"),
                b -> withId(Exporter::exportItem)).bounds(left, y, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Block"),
                b -> withId(Exporter::exportBlock)).bounds(left + 56, y, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Entity"),
                b -> withId(Exporter::exportEntityType)).bounds(left + 112, y, 52, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Mod"),
                b -> run(() -> Exporter.exportMod(idBox.getValue().trim()))).bounds(left + 168, y, 52, 20).build());
        y += 32;

        addRenderableWidget(Button.builder(Component.literal("Close"),
                b -> this.onClose()).bounds(cx - 50, y, 100, 20).build());
    }

    /** Filters items then blocks whose id contains the query, up to MAX_RESULTS, for the click-to-export list. */
    private void onSearch(String query) {
        results.clear();
        String q = query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.length() < 2 || q.contains(":") && q.endsWith(":")) {
            return;   // too short, or just a namespace — let the explicit Item/Block buttons handle full ids
        }
        for (ResourceLocation id : ForgeRegistries.ITEMS.getKeys()) {
            if (idMatches(id, q)) {
                results.add(id);
                if (results.size() >= MAX_RESULTS) return;
            }
        }
        for (ResourceLocation id : ForgeRegistries.BLOCKS.getKeys()) {
            if (idMatches(id, q) && !results.contains(id)) {
                results.add(id);
                if (results.size() >= MAX_RESULTS) return;
            }
        }
    }

    private static boolean idMatches(ResourceLocation id, String q) {
        return id.getPath().toLowerCase(java.util.Locale.ROOT).contains(q)
                || id.toString().toLowerCase(java.util.Locale.ROOT).contains(q);
    }

    private void drawResults(GuiGraphics g, int mouseX, int mouseY) {
        if (results.isEmpty()) return;
        int h = results.size() * ROW_H + 2;
        g.fill(resultsX, resultsY, resultsX + resultsW, resultsY + h, 0xF00D1117);
        g.fill(resultsX, resultsY, resultsX + resultsW, resultsY + 1, 0xFF3A4655);
        for (int i = 0; i < results.size(); i++) {
            ResourceLocation id = results.get(i);
            int ry = resultsY + 1 + i * ROW_H;
            boolean hover = mouseX >= resultsX && mouseX <= resultsX + resultsW && mouseY >= ry && mouseY < ry + ROW_H;
            if (hover) g.fill(resultsX, ry, resultsX + resultsW, ry + ROW_H, 0x40FFFFFF);
            boolean isItem = ForgeRegistries.ITEMS.containsKey(id);
            g.drawString(this.font, (isItem ? "§b[I] §f" : "§a[B] §f") + id, resultsX + 4, ry + 2, 0xFFFFFF, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!results.isEmpty()) {
            for (int i = 0; i < results.size(); i++) {
                int ry = resultsY + 1 + i * ROW_H;
                if (mx >= resultsX && mx <= resultsX + resultsW && my >= ry && my < ry + ROW_H) {
                    ResourceLocation id = results.get(i);
                    boolean isItem = ForgeRegistries.ITEMS.containsKey(id);
                    results.clear();
                    run(() -> {
                        if (isItem) Exporter.exportItem(id);
                        else Exporter.exportBlock(id);
                    });
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private int radius() {
        try {
            return Math.max(1, Integer.parseInt(radiusBox.getValue().trim()));
        } catch (NumberFormatException e) {
            return 16;
        }
    }

    private void withId(java.util.function.Consumer<ResourceLocation> action) {
        ResourceLocation id = ResourceLocation.tryParse(idBox.getValue().trim());
        if (id == null) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal("§cID inválido: " + idBox.getValue()), true);
            }
            return;
        }
        run(() -> action.accept(id));
    }

    private void run(Runnable export) {
        this.onClose();
        Minecraft.getInstance().execute(export);
    }

    /** Samples a top-down preview of the current radius, but only when the radius actually changes. */
    private void refreshPreview() {
        int r = radius();
        if (preview != null && r == lastPreviewRadius) return;
        lastPreviewRadius = r;
        if (minecraft != null && minecraft.level != null && minecraft.player != null) {
            try {
                preview = ExportPreview.sample(minecraft.level, minecraft.player.blockPosition(), r);
            } catch (Throwable ignored) {
                preview = null;
            }
        }
    }

    /** Draws the top-down export-footprint thumbnail (surface colours + entity dots) under the title. */
    private void drawPreview(GuiGraphics g) {
        if (preview == null) return;
        int cell = 2, gs = preview.size, pw = gs * cell;
        int px = Math.min(this.width / 2 + 116, this.width - pw - 6);
        int py = 44;
        g.fill(px - 2, py - 2, px + pw + 2, py + pw + 2, 0xFF101418);   // border
        for (int row = 0; row < gs; row++) {
            for (int col = 0; col < gs; col++) {
                int idx = row * gs + col;
                int color = preview.entityCell[idx] ? 0xFFFF4040 : preview.colors[idx];
                g.fill(px + col * cell, py + row * cell, px + col * cell + cell, py + row * cell + cell, color);
            }
        }
        int mid = px + pw / 2, midY = py + pw / 2;   // player at centre
        g.fill(mid - 1, midY - 1, mid + 2, midY + 2, 0xFFFFFFFF);
        String lbl = "r" + preview.radius + " · " + preview.entities + " ent" + (preview.raining ? " · rain" : "");
        g.drawString(this.font, lbl, px, py + pw + 3, 0xB0B0B0, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        refreshPreview();
        drawPreview(g);
        super.render(g, mouseX, mouseY, partialTick);
        drawResults(g, mouseX, mouseY);   // click-to-export search results, drawn on top
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
