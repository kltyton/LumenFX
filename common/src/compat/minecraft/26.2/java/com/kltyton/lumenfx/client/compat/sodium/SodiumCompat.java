package com.kltyton.lumenfx.client.compat.sodium;

import com.kltyton.lumenfx.platform.LumenPlatform;

/** Detects whether Sodium is present without referencing Sodium classes at startup. */
public final class SodiumCompat {
    public static final String SODIUM_MOD_ID = "sodium";

    private SodiumCompat() {
    }

    public static boolean isSodiumPresent() {
        return LumenPlatform.isModLoaded(SODIUM_MOD_ID);
    }
}
