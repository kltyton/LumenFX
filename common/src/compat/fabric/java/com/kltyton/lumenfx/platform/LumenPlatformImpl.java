package com.kltyton.lumenfx.platform;

import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

final class LumenPlatformImpl {
    private LumenPlatformImpl() { }

    static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    static @Nullable String modVersion(String modId) {
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(modId);
        return mod.map(container -> container.getMetadata().getVersion().getFriendlyString()).orElse(null);
    }

    static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }
}
