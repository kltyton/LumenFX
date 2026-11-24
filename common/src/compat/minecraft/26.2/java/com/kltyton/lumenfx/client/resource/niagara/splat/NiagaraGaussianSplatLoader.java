package com.kltyton.lumenfx.client.resource.niagara.splat;

import com.kltyton.lumenfx.effect.niagara.interfaces.splat.NiagaraGaussianSplatAsset;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public final class NiagaraGaussianSplatLoader {
    private NiagaraGaussianSplatLoader() { }

    public static NiagaraGaussianSplatAsset load(ResourceManager resources, Identifier key) {
        var file = Identifier.fromNamespaceAndPath(key.getNamespace(), "lumenfx/gaussian_splats/" + key.getPath() + ".lfgs");
        try (var stream = resources.getResourceOrThrow(file).open()) {
            return NiagaraGaussianSplatAsset.read(stream.readAllBytes());
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read Niagara Gaussian Splat " + file, failure);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Cannot decode Niagara Gaussian Splat " + file, failure);
        }
    }
}
