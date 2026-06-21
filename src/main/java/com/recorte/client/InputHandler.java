package com.recorte.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.recorte.Recorte;
import com.recorte.export.CameraRig;
import com.recorte.export.CameraShake;
import com.recorte.export.Exporter;
import com.recorte.export.GhostRig;
import com.recorte.export.HttpBridge;
import com.recorte.export.Recorder;
import com.recorte.export.SceneRecorder;
import com.recorte.export.SlowMo;
import com.recorte.export.TakeRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
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
        if (KeyBindings.record != null && KeyBindings.record.consumeClick()) {
            Minecraft.getInstance().execute(Recorder::toggleLookedAtOrSelf);
        }
        if (KeyBindings.menu != null && KeyBindings.menu.consumeClick()) {
            Minecraft.getInstance().setScreen(new RecorteScreen());
        }
    }

    /** Recording is sampled per rendered frame (interpolated) for smooth, high-fps keyframes. */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        float partial = mc.getFrameTime();
        Recorder.renderTick(partial);
        SceneRecorder.renderTick(partial);
        TakeRecorder.renderTick(partial);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        long time = mc.level.getGameTime();
        if (time % 10 == 0) {
            try {
                net.minecraft.world.phys.Vec3 sky = mc.level.getSkyColor(mc.player.position(), 1.0f);
                float t = mc.level.getTimeOfDay(1.0f);
                HttpBridge.setEnv(String.format(java.util.Locale.ROOT,
                        "{\"sky\":[%.4f,%.4f,%.4f],\"timeOfDay\":%.4f}", sky.x, sky.y, sky.z, t));
            } catch (Throwable ignored) {
            }
        }
        if (HttpBridge.liveMode && time % 40 == 0) {
            Exporter.exportLive();   // snapshot is heavier — refresh every ~2s, on the render thread
        }
    }

    /** While a cinematic is recording, log block breaks as timeline markers (single-player: the
     *  integrated server posts these on the shared Forge bus). */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!SceneRecorder.isRecording()) return;
        SceneRecorder.recordEvent("break:" + blockKey(event.getState()), event.getPos());
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!SceneRecorder.isRecording()) return;
        SceneRecorder.recordEvent("place:" + blockKey(event.getPlacedBlock()), event.getPos());
    }

    private static String blockKey(net.minecraft.world.level.block.state.BlockState state) {
        net.minecraft.resources.ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null ? id.toString() : "unknown";
    }

    /** While a cinematic is recording, log every sound played as a timeline marker (footsteps, hits,
     *  music…) so audio can be re-synced in the render. */
    @SubscribeEvent
    public static void onPlaySound(net.minecraftforge.client.event.sound.PlaySoundEvent event) {
        if (!SceneRecorder.isRecording()) return;
        net.minecraft.client.resources.sounds.SoundInstance s = event.getSound();
        if (s == null) return;
        net.minecraft.resources.ResourceLocation id = s.getLocation();
        SceneRecorder.recordEvent("sound:" + (id != null ? id.toString() : "unknown"),
                s.getX(), s.getY(), s.getZ());
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("recorte").then(Commands.literal("export")
                        .executes(c -> run(Exporter::exportLookedAtOrSelf))
                        .then(Commands.literal("self")
                                .executes(c -> run(Exporter::exportLookedAtOrSelf)))
                        .then(Commands.literal("animlib")
                                .executes(c -> run(Exporter::exportAnimLibrary)))
                        .then(Commands.literal("retarget")
                                .executes(c -> run(Exporter::exportRetarget)))
                        .then(Commands.literal("region")
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(c -> {
                                                    net.minecraft.core.BlockPos a = BlockPosArgument.getBlockPos(c, "from");
                                                    net.minecraft.core.BlockPos b = BlockPosArgument.getBlockPos(c, "to");
                                                    return run(() -> Exporter.exportRegion(
                                                            a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ()));
                                                }))))
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
                                                IntegerArgumentType.getInteger(c, "radius")))))))
                        .then(Commands.literal("record")
                                .then(Commands.literal("start").executes(c -> run(Recorder::startLookedAtOrSelf)))
                                .then(Commands.literal("stop").executes(c -> run(Recorder::stop)))
                                .then(Commands.literal("scene")
                                        .then(Commands.literal("start")
                                                .executes(c -> run(() -> SceneRecorder.start(16)))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(2, 24))
                                                        .executes(c -> run(() -> SceneRecorder.start(
                                                                IntegerArgumentType.getInteger(c, "radius"))))))
                                        .then(Commands.literal("stop").executes(c -> run(SceneRecorder::stop)))))
                        .then(Commands.literal("live").executes(c -> run(Exporter::toggleLive)))
                        .then(Commands.literal("cam")
                                .then(Commands.literal("add")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(c -> run(() -> CameraRig.add(
                                                        StringArgumentType.getString(c, "name"))))))
                                .then(Commands.literal("clear").executes(c -> run(CameraRig::clear)))
                                .then(Commands.literal("list").executes(c -> run(CameraRig::list)))
                                .then(Commands.literal("path")
                                        .executes(c -> run(() -> Exporter.exportCameraPath(8)))
                                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 120))
                                                .executes(c -> run(() -> Exporter.exportCameraPath(
                                                        IntegerArgumentType.getInteger(c, "seconds"))))))
                                .then(Commands.literal("shake")
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(0, 10))
                                                .executes(c -> run(() -> {
                                                    CameraShake.amount = IntegerArgumentType.getInteger(c, "amount");
                                                    Minecraft mc = Minecraft.getInstance();
                                                    if (mc.player != null) {
                                                        mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                                                CameraShake.amount > 0 ? "§aCamera shake: §f" + (int) CameraShake.amount
                                                                        : "§eCamera shake OFF"), true);
                                                    }
                                                })))))
                        .then(Commands.literal("ghost")
                                .then(Commands.literal("add").executes(c -> run(GhostRig::add)))
                                .then(Commands.literal("clear").executes(c -> run(GhostRig::clear)))
                                .then(Commands.literal("export").executes(c -> run(GhostRig::export))))
                        .then(Commands.literal("take")
                                .then(Commands.literal("start")
                                        .executes(c -> run(() -> TakeRecorder.start(null)))
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(c -> run(() -> TakeRecorder.start(
                                                        StringArgumentType.getString(c, "name"))))))
                                .then(Commands.literal("stop").executes(c -> run(TakeRecorder::stop)))
                                .then(Commands.literal("export").executes(c -> run(TakeRecorder::export)))
                                .then(Commands.literal("clear").executes(c -> run(TakeRecorder::clear)))
                                .then(Commands.literal("list").executes(c -> run(TakeRecorder::list))))
                        .then(Commands.literal("shot")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(c -> run(() -> SceneRecorder.recordShot(
                                                StringArgumentType.getString(c, "name"))))))
                        .then(Commands.literal("slowmo")
                                .then(Commands.argument("factor", IntegerArgumentType.integer(1, 16))
                                        .executes(c -> run(() -> {
                                            int f = IntegerArgumentType.getInteger(c, "factor");
                                            SlowMo.set(f);
                                            Minecraft mc = Minecraft.getInstance();
                                            if (mc.player != null) {
                                                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                                        f > 1 ? "§aSlow-mo: §f" + f + "×§a (próximas gravações ficam " + f
                                                                + "× mais lentas e densas)"
                                                                : "§eSlow-mo OFF (tempo real)"), true);
                                            }
                                        })))));
    }

    private static int run(Runnable task) {
        Minecraft.getInstance().execute(task);
        return 1;
    }
}
