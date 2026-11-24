package com.kltyton.lumenfx;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public final class LumenFx {
    public static final String MOD_ID = "lumenfx";

    private LumenFx() { }

    public static ResourceLocation id(String path) {
        return Objects.requireNonNull(ResourceLocation.tryBuild(MOD_ID, path), "Invalid resource path: " + path);
    }
}
