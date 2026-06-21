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
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);   // always clean up, even if the write/read above threw
        }
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

    /**
     * Loads a sibling PBR texture from the active resource pack for a sprite &mdash; the LabPBR
     * {@code _n} (normal) or {@code _s} (specular) map next to the base texture. Returns the raw PNG
     * bytes, or {@code null} if the pack doesn't ship one (the common case for vanilla).
     */
    public static byte[] siblingTexture(net.minecraft.resources.ResourceLocation spriteName, String suffix) {
        if (spriteName == null) return null;
        net.minecraft.resources.ResourceLocation rl = new net.minecraft.resources.ResourceLocation(
                spriteName.getNamespace(), "textures/" + spriteName.getPath() + suffix + ".png");
        try {
            java.util.Optional<net.minecraft.server.packs.resources.Resource> res =
                    Minecraft.getInstance().getResourceManager().getResource(rl);
            if (res.isEmpty()) return null;
            try (java.io.InputStream in = res.get().open()) {
                return in.readAllBytes();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Repacks a LabPBR specular ({@code _s}) texture into a glTF metallic-roughness texture. LabPBR
     * packs perceptual smoothness in R and F0/metalness in G; glTF wants roughness in G and metalness
     * in B. Returns the new PNG bytes, or {@code null} if the input is missing/undecodable.
     */
    public static byte[] labPbrToMetallicRoughness(byte[] specularPng) {
        if (specularPng == null) return null;
        try (NativeImage src = NativeImage.read(new java.io.ByteArrayInputStream(specularPng))) {
            int w = src.getWidth(), h = src.getHeight();
            NativeImage out = new NativeImage(w, h, false);
            try {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int p = src.getPixelRGBA(x, y);      // 0xAABBGGRR
                        int smooth = p & 0xFF;               // LabPBR: perceptual smoothness
                        int f0 = (p >> 8) & 0xFF;            // LabPBR: F0 / metalness
                        int roughness = 255 - smooth;
                        int metallic = f0 >= 230 ? 255 : 0;  // 230..255 = predefined metals
                        out.setPixelRGBA(x, y, (255 << 24) | (metallic << 16) | (roughness << 8));
                    }
                }
                return pngBytes(out);
            } finally {
                out.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Slices a stacked animated-texture source (frames stacked vertically, as Minecraft stores water/
     * lava/fire/portal/…) into one PNG per frame. {@code frameHeight} is the sprite's logical frame
     * height; the source height divided by it gives the frame count.
     */
    public static java.util.List<byte[]> sliceFrames(NativeImage src, int frameHeight) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        int w = src.getWidth(), h = src.getHeight();
        if (frameHeight <= 0 || frameHeight > h) frameHeight = h;
        int n = Math.max(1, h / frameHeight);
        for (int f = 0; f < n; f++) {
            try (NativeImage frame = new NativeImage(w, frameHeight, false)) {
                for (int y = 0; y < frameHeight; y++) {
                    for (int x = 0; x < w; x++) {
                        frame.setPixelRGBA(x, y, src.getPixelRGBA(x, f * frameHeight + y));
                    }
                }
                out.add(pngBytes(frame));
            } catch (Throwable t) {
                break;
            }
        }
        return out;
    }

    /** Encoded PNG bytes of an in-memory image (sprite contents, etc.). */
    public static byte[] pngBytes(NativeImage image) throws IOException {
        Path tmp = Files.createTempFile("recorte_img", ".png");
        try {
            image.writeToFile(tmp);
            return Files.readAllBytes(tmp);
        } finally {
            Files.deleteIfExists(tmp);   // always clean up, even on error
        }
    }
}
