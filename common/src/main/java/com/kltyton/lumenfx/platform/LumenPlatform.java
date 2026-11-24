package com.kltyton.lumenfx.platform;

import java.nio.file.Path;
import javax.annotation.Nullable;

/** Loader-independent platform hooks implemented once per loader under {@code src/compat/<loader>}. */
public final class LumenPlatform {
    private LumenPlatform() { }

    public static boolean isModLoaded(String modId) {
        return LumenPlatformImpl.isModLoaded(modId);
    }

    public static @Nullable String modVersion(String modId) {
        return LumenPlatformImpl.modVersion(modId);
    }

    public static Path gameDir() {
        return LumenPlatformImpl.gameDir();
    }
}
