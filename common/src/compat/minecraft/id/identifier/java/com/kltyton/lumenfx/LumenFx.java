package com.kltyton.lumenfx;

import net.minecraft.resources.Identifier;

public final class LumenFx {
    public static final String MOD_ID = "lumenfx";

    private LumenFx() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
