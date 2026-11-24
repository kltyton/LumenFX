package com.kltyton.lumenfx.client.resource.light;

import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.effect.render.light.scene.SkyLightIrradiance.Reflection;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

public final class SkyLightReflectionResources {
    private SkyLightReflectionResources() { }

    public record Asset(PreparedTexture cube, @Nullable PreparedTexture preintegrated) { }

    static Asset read(ResourceManager resources, Identifier id) {
        try (var stream = resources.getResourceOrThrow(id).open(); var channel = Channels.newChannel(stream)) {
            var header = read(channel, ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN));
            int magic = header.getInt(), version = header.getInt();
            if (magic != 0x4b53464c || version < 1 || version > 2)
                throw new IllegalArgumentException("Unsupported source sky reflection " + id);
            int extent = header.getInt(), levels = header.getInt();
            if (extent <= 0 || Integer.bitCount(extent) != 1 || levels != Integer.SIZE - Integer.numberOfLeadingZeros(extent))
                throw new IllegalArgumentException("Invalid source sky reflection extent or mip count " + id);
            var format = TextureStorageFormat.RGBA16F;
            var mips = new ArrayList<ByteBuffer>(levels);
            for (int mip = 0; mip < levels; mip++) {
                int size = extent >> mip;
                int bytes = Math.multiplyExact(format.byteSize(size, size), 6);
                mips.add(read(channel, ByteBuffer.allocateDirect(bytes)).asReadOnlyBuffer());
            }
            var metadata = new TextureMetadata(extent, extent, false, TextureMetadata.MipMode.NATIVE_BUILD, 0, 2,
                    false, false, false, TextureMetadata.Address.CLAMP, TextureMetadata.Address.CLAMP,
                    TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.LINEAR, 1,
                    "SkyLight", "ReflectionEnvironment", format.name(), "", "", false);
            var gf = version >= 2 ? preintegrated(channel, Reflection.preintegratedTexture(id)) : null;
            if (stream.read() != -1) throw new IllegalArgumentException("Trailing source sky reflection data " + id);
            return new Asset(new PreparedTexture(id, metadata, mips, 0, true), gf);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read source sky reflection " + id, failure);
        }
    }

    private static PreparedTexture preintegrated(ReadableByteChannel channel, Identifier id) throws IOException {
        var header = read(channel, ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN));
        int encoding = header.getInt(), width = header.getInt(), height = header.getInt();
        var format = switch (encoding) {
            case 1 -> TextureStorageFormat.RG8;
            case 2 -> TextureStorageFormat.RG16;
            default -> throw new IllegalArgumentException("Invalid source preintegrated GF encoding " + id);
        };
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid source preintegrated GF extent " + id);
        var data = read(channel, ByteBuffer.allocateDirect(format.byteSize(width, height))).asReadOnlyBuffer();
        var metadata = new TextureMetadata(width, height, false, TextureMetadata.MipMode.NO_MIPMAPS, 0, 2,
                false, false, false, TextureMetadata.Address.CLAMP, TextureMetadata.Address.CLAMP,
                TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.POINT, 1,
                "SkyLight", "PreintegratedGF", format.name(), "", "", false);
        return new PreparedTexture(id, metadata, List.of(data));
    }

    private static ByteBuffer read(ReadableByteChannel channel, ByteBuffer target) throws IOException {
        while (target.hasRemaining()) if (channel.read(target) < 0) throw new EOFException("Truncated source sky reflection");
        return target.flip();
    }
}
