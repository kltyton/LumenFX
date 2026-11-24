package com.kltyton.lumenfx.platform;

import java.nio.file.Path;
import java.util.List;
import javax.annotation.Nullable;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

final class LumenPlatformImpl {
    private LumenPlatformImpl() { }

    static boolean isModLoaded(String modId) {
        return modFile(modId) != null;
    }

    static @Nullable String modVersion(String modId) {
        IModFileInfo file = modFile(modId);
        if (file == null) return null;
        List<IModInfo> mods = file.getMods();
        return mods.isEmpty() ? null : mods.get(0).getVersion().toString();
    }

    static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @SuppressWarnings("removal")
    private static @Nullable IModFileInfo modFile(String modId) {
        try {
            LoadingModList loading = LoadingModList.get();
            if (loading != null) {
                IModFileInfo file = loading.getModFileById(modId);
                if (file != null) return file;
            }
            ModList loaded = ModList.get();
            return loaded == null ? null : loaded.getModFileById(modId);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
