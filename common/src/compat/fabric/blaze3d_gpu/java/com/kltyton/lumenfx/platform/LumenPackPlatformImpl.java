package com.kltyton.lumenfx.platform;

import com.kltyton.lumenfx.platform.LumenPackPlatform.ModContentRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;

final class LumenPackPlatformImpl {
    private LumenPackPlatformImpl() { }

    static List<PackResources> openInstalledModAssetPacks() {
        List<PackResources> packs = new ArrayList<>();
        try {
            int index = 0;
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                String modId = mod.getMetadata().getId();
                for (Path root : mod.getRootPaths()) {
                    if (!Files.isDirectory(root)) continue;
                    String name = "mod/" + modId + "/" + (index++);
                    var location = new PackLocationInfo(name, Component.literal(name), PackSource.DEFAULT, Optional.empty());
                    packs.add(new PathPackResources(location, root));
                }
            }
            return packs;
        } catch (RuntimeException | Error failure) {
            for (var pack : packs) {
                try { pack.close(); } catch (RuntimeException | Error close) { failure.addSuppressed(close); }
            }
            throw failure;
        }
    }

    static List<ModContentRoot> installedModRoots() {
        List<ModContentRoot> roots = new ArrayList<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modId = mod.getMetadata().getId();
            for (Path root : mod.getRootPaths()) {
                roots.add(new ModContentRoot(modId, root));
            }
        }
        return roots;
    }

    static Pack.Metadata createHiddenBuiltinPackMetadata(Component title) {
        return new Pack.Metadata(title, PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
    }
}
