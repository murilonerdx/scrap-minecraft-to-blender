package com.recorte.client;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Client-only setup, invoked from the mod constructor via {@code DistExecutor}. */
public final class ClientBootstrap {
    private ClientBootstrap() {}

    public static void init() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(KeyBindings::register);
        com.recorte.export.HttpBridge.start();
    }
}
