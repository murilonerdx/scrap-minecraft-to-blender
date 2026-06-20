package com.recorte.export;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads textures back from the GPU as PNG bytes. Works for any GL texture object &mdash; the player
 * skin, the block/item atlas, armour textures, Curios/GeckoLib textures &mdash; because it reads the
 * live GL texture rather than trying to locate a source file. Must run on the render thread.
 */
public final class TextureExporter {
    private TextureExporter() {}

    /** Encoded PNG bytes of the GL texture object {@code glId} (level 0). */
    public static byte[] dumpTextureId(int glId) throws IOException {
        GlStateManager._bindTexture(glId);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) {
            throw new IOException("texture " + glId + " has no GPU image (" + w + "x" + h + ")");
        }
        Path tmp = Files.createTempFile("recorte_tex", ".png");
        try (NativeImage img = new NativeImage(w, h, false)) {
            img.downloadTexture(0, false);
            img.writeToFile(tmp);
        }
        byte[] bytes = Files.readAllBytes(tmp);
        Files.deleteIfExists(tmp);
        return bytes;
    }

    /** Encoded PNG bytes of a player's skin. */
    public static byte[] skinBytes(AbstractClientPlayer player) throws IOException {
        return bytesFor(player.getSkinTextureLocation());
    }

    /** Encoded PNG bytes of a registered texture (entity textures, etc.). */
    public static byte[] bytesFor(net.minecraft.resources.ResourceLocation loc) throws IOException {
        int id = Minecraft.getInstance().getTextureManager().getTexture(loc).getId();
        return dumpTextureId(id);
    }

    /** Encoded PNG bytes of an in-memory image (sprite contents, etc.). */
    public static byte[] pngBytes(NativeImage image) throws IOException {
        Path tmp = Files.createTempFile("recorte_img", ".png");
        image.writeToFile(tmp);
        byte[] bytes = Files.readAllBytes(tmp);
        Files.deleteIfExists(tmp);
        return bytes;
    }
}
