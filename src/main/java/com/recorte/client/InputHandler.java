package com.recorte.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.recorte.Recorte;
import com.recorte.export.Exporter;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Bridges the keybind and the {@code /recorte export ...} command tree to {@link Exporter}.
 *
 * <pre>
 * /recorte export                 -> entity you're looking at, or yourself
 * /recorte export self
 * /recorte export player &lt;name&gt;
 * /recorte export item   &lt;id&gt;     (e.g. minecraft:diamond_sword)
 * /recorte export block  &lt;id&gt;
 * /recorte export entity &lt;id&gt;     (e.g. minecraft:zombie)
 * /recorte export mod    &lt;modid&gt;  (all items + blocks of a mod)
 * </pre>
 */
@Mod.EventBusSubscriber(modid = Recorte.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class InputHandler {
    private InputHandler() {}

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (KeyBindings.export != null && KeyBindings.export.consumeClick()) {
            Minecraft.getInstance().execute(Exporter::exportLookedAtOrSelf);
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("recorte").then(Commands.literal("export")
                        .executes(c -> run(Exporter::exportLookedAtOrSelf))
                        .then(Commands.literal("self")
                                .executes(c -> run(Exporter::exportLookedAtOrSelf)))
                        .then(Commands.literal("player")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(c -> run(() -> Exporter.exportPlayerByName(
                                                StringArgumentType.getString(c, "name"))))))
                        .then(Commands.literal("item")
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggestResource(
                                                ForgeRegistries.ITEMS.getKeys(), b))
                                        .executes(c -> run(() -> Exporter.exportItem(
                                                ResourceLocationArgument.getId(c, "id"))))))
                        .then(Commands.literal("block")
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggestResource(
                                                ForgeRegistries.BLOCKS.getKeys(), b))
                                        .executes(c -> run(() -> Exporter.exportBlock(
                                                ResourceLocationArgument.getId(c, "id"))))))
                        .then(Commands.literal("entity")
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggestResource(
                                                ForgeRegistries.ENTITY_TYPES.getKeys(), b))
                                        .executes(c -> run(() -> Exporter.exportEntityType(
                                                ResourceLocationArgument.getId(c, "id"))))))
                        .then(Commands.literal("mod")
                                .then(Commands.argument("modid", StringArgumentType.word())
                                        .executes(c -> run(() -> Exporter.exportMod(
                                                StringArgumentType.getString(c, "modid"))))))
                        .then(Commands.literal("scene")
                                .executes(c -> run(() -> Exporter.exportScene(16)))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                        .executes(c -> run(() -> Exporter.exportScene(
                                                IntegerArgumentType.getInteger(c, "radius"))))))
                        .then(Commands.literal("snapshot")
                                .executes(c -> run(() -> Exporter.exportSnapshot(16)))
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 48))
                                        .executes(c -> run(() -> Exporter.exportSnapshot(
                                                IntegerArgumentType.getInteger(c, "radius"))))))));
    }

    private static int run(Runnable task) {
        Minecraft.getInstance().execute(task);
        return 1;
    }
}
