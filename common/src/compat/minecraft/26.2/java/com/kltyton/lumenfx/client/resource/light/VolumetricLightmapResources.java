package com.kltyton.lumenfx.client.resource.light;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.effect.render.light.scene.VolumetricLightmap;
import com.kltyton.lumenfx.effect.render.light.scene.VolumetricLightmap.Layer;
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
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class VolumetricLightmapResources {
    private static final int HEADER_BYTES = 9 * Integer.BYTES + 6 * Double.BYTES;

    public record Asset(VolumetricLightmap lightmap, List<PreparedTexture> textures) {
        public JsonObject template() {
            var result = new JsonObject();
            result.addProperty("asset", lightmap.asset().toString());
            result.add("min", point(lightmap.min()));
            result.add("max", point(lightmap.max()));
            result.addProperty("brick_size", lightmap.brickSize());
            result.addProperty("sky_bent_normal", lightmap.skyBentNormal());
            result.addProperty("directional_shadow", lightmap.directionalShadow());
            return result;
        }

        private static JsonArray point(Vec3 point) {
            var result = new JsonArray();
            result.add(point.x);
            result.add(point.y);
            result.add(point.z);
            return result;
        }
    }

    private record Extent(int x, int y, int z) {
        Extent {
            if (x <= 0 || y <= 0 || z <= 0) throw new IllegalArgumentException("Invalid volumetric lightmap texture extent");
        }
    }

    private VolumetricLightmapResources() { }

    public static Asset read(ResourceManager resources, Identifier id) {
        try (var stream = resources.getResourceOrThrow(id).open(); var channel = Channels.newChannel(stream)) {
            var header = read(channel, ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN));
            if (header.getInt() != 0x4c56464c || header.getInt() != 1)
                throw new IllegalArgumentException("Unsupported volumetric lightmap " + id);
            int brickSize = header.getInt();
            var indirection = new Extent(header.getInt(), header.getInt(), header.getInt());
            var bricks = new Extent(header.getInt(), header.getInt(), header.getInt());
            var min = new Vec3(header.getDouble(), header.getDouble(), header.getDouble());
            var max = new Vec3(header.getDouble(), header.getDouble(), header.getDouble());
            var encoded = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            var textures = new ArrayList<PreparedTexture>();
            boolean sky = false, directional = false;
            for (var layer : Layer.values()) {
                int encoding = read(channel, encoded.clear()).getInt();
                var format = format(layer, encoding);
                if (format == null) continue;
                var extent = layer == Layer.INDIRECTION ? indirection : bricks;
                int bytes = Math.multiplyExact(format.byteSize(extent.x(), extent.y()), extent.z());
                var pixels = read(channel, ByteBuffer.allocateDirect(bytes)).asReadOnlyBuffer();
                var address = TextureMetadata.Address.CLAMP;
                var filtering = layer == Layer.INDIRECTION ? TextureMetadata.FilterMode.POINT : TextureMetadata.FilterMode.LINEAR;
                var metadata = new TextureMetadata(extent.x(), extent.y(), false, TextureMetadata.MipMode.NO_MIPMAPS, 0, 2,
                        false, false, false, address, address, filtering, TextureMetadata.FilterMode.POINT,
                        1, "VolumetricLightmap", "MapBuildDataRegistry", format.name(), "", "", false);
                textures.add(new PreparedTexture(VolumetricLightmap.texture(id, layer), metadata, List.of(pixels), extent.z()));
                if (layer == Layer.SKY_BENT_NORMAL) sky = true;
                if (layer == Layer.DIRECTIONAL_SHADOW) directional = true;
            }
            if (stream.read() != -1) throw new IllegalArgumentException("Trailing volumetric lightmap data " + id);
            return new Asset(new VolumetricLightmap(id, min, max, brickSize, sky, directional), List.copyOf(textures));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read volumetric lightmap " + id, failure);
        }
    }

    private static @Nullable TextureStorageFormat format(Layer layer, int encoding) {
        var format = switch (layer) {
            case INDIRECTION -> encoding == 1 ? TextureStorageFormat.RGBA8 : null;
            case AMBIENT -> encoding == 3 ? TextureStorageFormat.R11G11B10 : null;
            case DIRECTIONAL_SHADOW -> encoding == 4 ? TextureStorageFormat.G8 : null;
            default -> switch (encoding) {
                case 1 -> TextureStorageFormat.RGBA8;
                case 2 -> TextureStorageFormat.BGRA8;
                default -> null;
            };
        };
        if (format == null && !(encoding == 0 && (layer == Layer.SKY_BENT_NORMAL || layer == Layer.DIRECTIONAL_SHADOW)))
            throw new IllegalArgumentException("Unexpected source format " + encoding + " for " + layer);
        return format;
    }

    private static ByteBuffer read(ReadableByteChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new EOFException("Truncated volumetric lightmap");
        return bytes.flip();
    }
}
