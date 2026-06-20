package com.recorte.client;

import com.recorte.export.Exporter;
import com.recorte.export.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * The Recorte control panel: a single in-game screen to trigger every export mode (player/entity, scene,
 * snapshot, recording, and item/block/entity/mod by id) with radius and id fields — no commands needed.
 */
public final class RecorteScreen extends Screen {

    private EditBox radiusBox;
    private EditBox idBox;

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

        String recLabel = Recorder.isRecording() ? "■ Stop recording" : "● Record (looked-at / self)";
        addRenderableWidget(Button.builder(Component.literal(recLabel),
                b -> run(Recorder::toggleLookedAtOrSelf)).bounds(left, y, w, 20).build());
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

        // --- id field + item/block/entity/mod ---
        idBox = new EditBox(this.font, left, y, w, 20, Component.literal("id"));
        idBox.setHint(Component.literal("minecraft:diamond_sword   (or a modid)"));
        addRenderableWidget(idBox);
        y += 24;

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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
