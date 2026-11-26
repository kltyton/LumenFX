package com.kltyton.lumenfx.platform;

import com.kltyton.lumenfx.platform.LumenPackPlatform.ModContentRoot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.resource.ResourcePackLoader;

final class LumenPackPlatformImpl {
    private LumenPackPlatformImpl() { }

    static List<PackResources> openInstalledModAssetPacks() {
        List<PackResources> packs = new ArrayList<>();
        try {
            ModList list = ModList.get();
            if (list == null) return packs;
            for (var info : list.getModFiles()) {
                String name = ResourcePackLoader.getPackName(info.getFile());
                var location = new PackLocationInfo(name, Component.literal(name), PackSource.DEFAULT, Optional.empty());
                var metadata = new Pack.Metadata(Component.literal(name), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
                ResourcePackLoader.createPackForMod(info).openResources(location, metadata).forEach(packs::add);
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
        ModList list = ModList.get();
        if (list == null) return List.of();
        List<ModContentRoot> roots = new ArrayList<>();
        for (var modFile : list.getModFiles()) {
            String modId = modFile.getMods().isEmpty()
                    ? modFile.getFile().getFileName()
                    : modFile.getMods().get(0).getModId();
            for (Path root : modFile.getFile().getContents().getContentRoots()) {
                roots.add(new ModContentRoot(modId, root));
            }
        }
        return roots;
    }

    static Pack.Metadata createHiddenBuiltinPackMetadata(Component title) {
        return new Pack.Metadata(title, PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), true);
    }
}
