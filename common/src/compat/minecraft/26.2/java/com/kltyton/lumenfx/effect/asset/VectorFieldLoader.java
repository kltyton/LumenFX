package com.kltyton.lumenfx.effect.asset;

import com.kltyton.lumenfx.effect.noise.VectorFieldFile;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

/** Vector fields are decoded during resource preparation, never during simulation or rendering. */
public final class VectorFieldLoader {
    private VectorFieldLoader() { }

    public static VectorFieldVolume load(ResourceProvider resources, Identifier id) {
        Identifier path = Identifier.fromNamespaceAndPath(id.getNamespace(), "lumenfx/vector_fields/" + id.getPath() + ".lfvf");
        try (var input = resources.getResourceOrThrow(path).open()) {
            return VectorFieldFile.read(input.readAllBytes());
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot load vector field " + id, error);
        }
    }
}
