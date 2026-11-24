package com.kltyton.lumenfx.client.compat.kbr;

import com.kltyton.lumenfx.platform.LumenPlatform;

/** Detects Kltyton Bedrock Runtime ({@code kltyton_bedrock_runtime}) without hard-linking its classes. */
public final class KbrCompat {
    public static final String MOD_ID = "kltyton_bedrock_runtime";

    private KbrCompat() {
    }

    public static boolean isPresent() {
        return LumenPlatform.isModLoaded(MOD_ID);
    }
}
