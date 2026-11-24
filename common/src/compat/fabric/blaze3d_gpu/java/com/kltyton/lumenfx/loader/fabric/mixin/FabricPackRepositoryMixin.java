package com.kltyton.lumenfx.loader.fabric.mixin;

import com.lowdragmc.photon.client.resource.PhotonPackSource;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.RepositorySource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

/** Registers installed Photon .fxpack repository sources into Minecraft's client resource pack repository on Fabric. */
@Mixin(Minecraft.class)
abstract class FabricPackRepositoryMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/repository/PackRepository;<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V"
            ),
            index = 0
    )
    private RepositorySource[] lumenfx$addPhotonPackSource(RepositorySource[] sources) {
        RepositorySource[] extended = Arrays.copyOf(sources, sources.length + 1);
        extended[sources.length] = PhotonPackSource.installedPacks();
        return extended;
    }
}
