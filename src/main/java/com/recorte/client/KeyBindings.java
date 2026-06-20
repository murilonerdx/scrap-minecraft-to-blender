package com.recorte.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/** Registers the export keybind. Default key is {@code O}; the player can rebind it under Controls. */
public final class KeyBindings {
    private KeyBindings() {}

    public static final String CATEGORY = "key.categories.recorte";
    public static KeyMapping export;
    public static KeyMapping record;
    public static KeyMapping menu;

    public static void register(RegisterKeyMappingsEvent event) {
        export = new KeyMapping(
                "key.recorte.export",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                CATEGORY);
        event.register(export);

        record = new KeyMapping(
                "key.recorte.record",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                CATEGORY);
        event.register(record);

        menu = new KeyMapping(
                "key.recorte.menu",
                KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                CATEGORY);
        event.register(menu);
    }
}
