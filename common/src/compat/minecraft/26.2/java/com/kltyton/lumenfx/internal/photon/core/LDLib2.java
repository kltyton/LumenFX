package com.kltyton.lumenfx.internal.photon.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.kltyton.lumenfx.platform.LumenPlatform;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/** Embedded minimal LDLib2 runtime shim for loaders without external LDLib2. */
public final class LDLib2 {
    public static final String MOD_ID = "ldlib2";
    public static final String NAME = "LDLib2";
    public static final Logger LOGGER = LoggerFactory.getLogger("LumenFX/LDLib2Runtime");
    public static final RandomSource RANDOM = RandomSource.create();
    public static final Gson GSON = new GsonBuilder().create();

    private LDLib2() { }

    public static boolean isValidResourceLocation(String location) {
        return location != null && !location.isEmpty() && Identifier.tryParse(location) != null;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static File getAssetsDir() {
        return LumenPlatform.gameDir().resolve("ldlib2/assets").toFile();
    }

    public static boolean isModLoaded(String modId) {
        return LumenPlatform.isModLoaded(modId);
    }

    public static boolean isIrisLoaded() {
        return isModLoaded("iris");
    }

    public static boolean isOculusLoaded() {
        return isModLoaded("oculus");
    }
}
