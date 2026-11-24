package com.lowdragmc.photon;

import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Identifiers retained by the Photon asset and shader formats. */
public final class Photon {
    public static final String MOD_ID = "photon";
    public static final Logger LOGGER = LoggerFactory.getLogger("LumenFX Photon");

    private Photon() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static boolean isUsingShaderPack() {
        return ShaderpackCompat.isShaderPackInUse();
    }
}
