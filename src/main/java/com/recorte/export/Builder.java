package com.recorte.export;

import com.recorte.Recorte;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;

/**
 * The <b>Blender → Minecraft</b> side: receives a {@link BuildStructure} (posted by the Blender add-on)
 * and pastes it into the world WorldEdit-style, anchored at the player. Single-player only (it places
 * blocks through the integrated server). The user explicitly triggers the paste with
 * {@code /recorte build}, since it modifies the world.
 */
public final class Builder {
    private Builder() {}

    private static volatile BuildStructure pending;

    /** Stores a structure received over HTTP; the player pastes it with {@code /recorte build}. */
    public static void receive(String json) {
        BuildStructure s = BuildStructure.fromJson(json);
        if (s == null) {
            feedback("§cEstrutura recebida inválida ou vazia.");
            return;
        }
        pending = s;
        feedback(String.format("§a▣ Estrutura §f%s§a recebida (%d blocos). §7Posicione-se e use §f/recorte build",
                s.name, s.size()));
    }

    public static boolean hasPending() {
        return pending != null;
    }

    /** Pastes the pending structure at the player's feet (the structure's 0,0,0 lands on the player). */
    public static void place() {
        BuildStructure s = pending;
        if (s == null) {
            feedback("§eNenhuma estrutura recebida. Envie do Blender (botão §fSend to Minecraft§e).");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null || mc.player == null) {
            feedback("§cColar só funciona em mundo single-player (servidor integrado).");
            return;
        }
        ServerLevel level = server.getLevel(mc.player.level().dimension());
        if (level == null) {
            feedback("§cNão achei o nível do servidor pra colar.");
            return;
        }
        BlockPos anchor = mc.player.blockPosition();
        feedback(String.format("§7Colando §f%s§7 (%d blocos) na sua posição...", s.name, s.size()));
        server.execute(() -> pasteOnServer(level, anchor, s));   // block edits must run on the server thread
    }

    private static void pasteOnServer(ServerLevel level, BlockPos anchor, BuildStructure s) {
        Map<Integer, BlockState> stateCache = new HashMap<>();
        int placed = 0, skipped = 0;
        for (int[] b : s.blocks) {
            BlockState state = stateCache.computeIfAbsent(b[3], idx -> resolve(s.palette.get(idx)));
            if (state == null) {
                skipped++;
                continue;
            }
            // UPDATE_CLIENTS only (flag 2): no neighbour physics, so a pasted structure doesn't collapse
            level.setBlock(anchor.offset(b[0], b[1], b[2]), state, 2);
            placed++;
        }
        final int p = placed, sk = skipped;
        feedback(String.format("§a▣ Colado: §f%d§a blocos%s.", p, sk > 0 ? " §7(" + sk + " ids desconhecidos pulados)" : ""));
        Recorte.LOGGER.info("Build pasted: {} blocks, {} skipped, at {}", p, sk, anchor);
    }

    private static BlockState resolve(String id) {
        try {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(id));
            return block != null ? block.defaultBlockState() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void clear() {
        pending = null;
        feedback("§7Estrutura pendente descartada.");
    }

    private static void feedback(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(message), false);
        }
    }
}
