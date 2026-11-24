package com.kltyton.lumenfx.effect.asset.unity;

import com.kltyton.lumenfx.effect.unity.UnityShapeTexture.Pixels;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

public final class UnityShapeTextureLoader {
    private UnityShapeTextureLoader() { }

    public static Pixels load(ResourceProvider resources, Identifier id) {
        try (var stream = resources.getResourceOrThrow(id).open()) {
            var data = ByteBuffer.wrap(stream.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (data.remaining() < 12 || data.getInt() != 1)
                throw new IOException("Invalid Unity shape texture header");
            int width = data.getInt(), height = data.getInt();
            if (width < 1 || height < 1 || (long)width * height != data.remaining() / 16L || data.remaining() % 16 != 0)
                throw new IOException("Unity shape texture dimensions do not match the pixel payload");
            var pixels = new float[data.remaining() / Float.BYTES];
            data.asFloatBuffer().get(pixels);
            return new Pixels(width, height, pixels);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read Unity particle emission texture " + id, failure);
        }
    }
}
