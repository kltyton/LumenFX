/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.resource;

import com.kltyton.lumenfx.platform.LumenPackPlatform;
import com.kltyton.lumenfx.platform.LumenPlatform;
import com.lowdragmc.photon.Photon;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Makes installed offline Photon effect packs available through Minecraft's pack repository. */
public final class PhotonPackSource {
    private PhotonPackSource() {}

    public static RepositorySource installedPacks() {
        return accept -> {
            var directory = LumenPlatform.gameDir().resolve("photon/fxpacks");
            for (var file : entries(directory)) {
                String name = file.getFileName().toString();
                if (name.endsWith(".fxpack") && Files.isRegularFile(file)) {
                    accept.accept(create("fxpack/" + name, name, new FilePackResources.FileResourcesSupplier(file)));
                }
            }
            for (var modRoot : LumenPackPlatform.installedModRoots()) {
                addModPacks(accept, modRoot.modId(), modRoot.root().resolve("fxpacks"));
            }
        };
    }

    private static void addModPacks(Consumer<Pack> accept, String modId, Path directory) {
        for (var folder : entries(directory)) {
            if (!Files.isDirectory(folder)) continue;
            String name = folder.getFileName().toString();
            accept.accept(create("fxpack/mod/" + modId + "/" + name, name,
                    new PathPackResources.PathResourcesSupplier(folder)));
        }
    }

    private static List<Path> entries(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (var children = Files.list(directory)) {
            return children.sorted().toList();
        } catch (IOException error) {
            Photon.LOGGER.error("Reading installed effect packs in {}", directory, error);
            return List.of();
        }
    }

    private static Pack create(String id, String name, Pack.ResourcesSupplier resources) {
        var location = new PackLocationInfo(id, Component.literal(name), PackSource.BUILT_IN, Optional.empty());
        var metadata = LumenPackPlatform.createHiddenBuiltinPackMetadata(Component.literal(name));
        return new Pack(location, resources, metadata, new PackSelectionConfig(true, Pack.Position.BOTTOM, true));
    }
}
