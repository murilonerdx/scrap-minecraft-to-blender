package com.recorte;

import com.mojang.logging.LogUtils;
import com.recorte.client.ClientBootstrap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Recorte &mdash; a client-side tool that exports the in-game player model (body, skin, and later
 * armour, held items and Curios accessories) to glTF and OBJ for Blender.
 */
@Mod(Recorte.MODID)
public final class Recorte {
    public static final String MODID = "recorte";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Recorte() {
        // Everything this mod does is client-side; never touch client classes on a dedicated server.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientBootstrap::init);
    }
}
