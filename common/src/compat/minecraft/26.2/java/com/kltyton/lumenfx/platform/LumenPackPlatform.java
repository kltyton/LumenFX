package com.kltyton.lumenfx.platform;

import java.nio.file.Path;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

/** MC 26.2 pack discovery helpers implemented per loader. */
public final class LumenPackPlatform {
    public record ModContentRoot(String modId, Path root) { }

    private LumenPackPlatform() { }

    public static List<PackResources> openInstalledModAssetPacks() {
        return LumenPackPlatformImpl.openInstalledModAssetPacks();
    }

    public static List<ModContentRoot> installedModRoots() {
        return LumenPackPlatformImpl.installedModRoots();
    }

    public static Pack.Metadata createHiddenBuiltinPackMetadata(Component title) {
        return LumenPackPlatformImpl.createHiddenBuiltinPackMetadata(title);
    }
}
