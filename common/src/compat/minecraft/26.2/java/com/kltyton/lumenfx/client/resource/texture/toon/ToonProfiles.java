package com.kltyton.lumenfx.client.resource.texture.toon;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextureLoader;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jspecify.annotations.Nullable;

/** The original Unreal ToonProfileTexture slices, addressed with the native eight-bit profile index. */
public final class ToonProfiles {
    public static final Identifier ATLAS = LumenFx.id("lumenfx/toon_profiles/atlas");
    public static final String DEFAULT = "None";
    public static final ToonProfiles EMPTY = new ToonProfiles(Map.of(), null);
    private static final int MAGIC = 0x5054464c;
    private static final int HEADER_BYTES = 20;
    private static final int PIXEL_BYTES = 8;
    private static final int MAX_PATH_BYTES = 8192;
    private static final int MAX_LAYERS = 256;

    private record Slice(Identifier id, Resource resource, int width, int height, int offset, String identity) {
        static Slice read(Identifier id, Resource resource) throws IOException {
            try (var input = resource.open()) {
                var header = ByteBuffer.wrap(input.readNBytes(HEADER_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
                if (header.remaining() != HEADER_BYTES || header.getInt() != MAGIC || header.getInt() != 1)
                    throw new IOException("Invalid LFTP header");
                int width = header.getInt(), height = header.getInt(), pathBytes = header.getInt();
                if (width < 64 || height != 5L + 3L * width || pathBytes <= 0 || pathBytes > MAX_PATH_BYTES)
                    throw new IOException("Invalid LFTP slice dimensions or identity length");
                byte[] path = input.readNBytes(pathBytes);
                if (path.length != pathBytes) throw new IOException("Truncated LFTP identity");
                String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(path)).toString();
                return new Slice(id, resource, width, height, HEADER_BYTES + pathBytes, ToonProfiles.identity(source));
            }
        }

        void copyTo(ByteBuffer destination) throws IOException {
            byte[] row = new byte[Math.multiplyExact(width, PIXEL_BYTES)];
            try (var input = resource.open()) {
                input.skipNBytes(offset);
                var half = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
                for (int y = 0; y < height; y++) {
                    if (input.readNBytes(row, 0, row.length) != row.length)
                        throw new IOException("Truncated LFTP texels");
                    if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) destination.put(row);
                    else for (int i = 0; i < row.length; i += Short.BYTES) destination.putShort(half.getShort(i));
                }
                if (input.read() != -1) throw new IOException("Extra LFTP texels");
            }
        }
    }

    private final Map<String, Integer> indices;
    private final @Nullable PreparedTexture atlas;

    private ToonProfiles(Map<String, Integer> indices, @Nullable PreparedTexture atlas) {
        this.indices = indices;
        this.atlas = atlas;
    }

    public static ToonProfiles prepare(ResourceManager resources, Set<String> materialProfiles) {
        if (materialProfiles.isEmpty()) return EMPTY;
        Set<String> requested = new TreeSet<>();
        for (String path : materialProfiles) requested.add(identity(path));
        requested.remove(DEFAULT);
        if (requested.size() >= MAX_LAYERS)
            throw new IllegalArgumentException("Toon profiles reserve the first of 256 slices for the Unreal default");

        Map<String, Slice> sources = new HashMap<>();
        Set<String> extraPackaged = new TreeSet<>();
        for (var entry : resources.listResources("lumenfx/toon_profiles", id -> id.getPath().endsWith(".lftp")).entrySet()) {
            try {
                Slice slice = Slice.read(entry.getKey(), entry.getValue());
                String path = slice.identity();
                if (sources.putIfAbsent(path, slice) != null)
                    throw new IOException("Duplicate LFTP identity " + path);
                if (!path.equals(DEFAULT) && !requested.contains(path)) extraPackaged.add(path);
            } catch (IOException failure) {
                throw new IllegalArgumentException("Could not read native Toon profile " + entry.getKey(), failure);
            }
        }

        Slice base = require(sources, DEFAULT);
        Set<String> ordered = new TreeSet<>(requested);
        for (String path : requested) {
            Slice slice = require(sources, path);
            if (slice.width != base.width || slice.height != base.height)
                throw new IllegalArgumentException("Toon profile slices use different Unreal atlas resolutions: " + slice.id);
        }
        for (String path : extraPackaged) {
            if (ordered.size() + 1 >= MAX_LAYERS) break;
            Slice slice = sources.get(path);
            if (slice.width == base.width && slice.height == base.height) ordered.add(path);
        }
        int layerCount = ordered.size() + 1;
        int pixels = Math.multiplyExact(Math.multiplyExact(base.width, base.height), layerCount);
        ByteBuffer data = ByteBuffer.allocateDirect(Math.multiplyExact(pixels, PIXEL_BYTES)).order(ByteOrder.nativeOrder());
        Map<String, Integer> indices = new HashMap<>();
        copySlice(base, data);
        indices.put(DEFAULT, 0);
        for (String path : ordered) {
            copySlice(sources.get(path), data);
            indices.put(path, indices.size());
        }

        var clamp = TextureMetadata.Address.CLAMP;
        var metadata = new TextureMetadata(base.width, base.height, false, TextureMetadata.MipMode.NO_MIPMAPS, 0, 2,
                false, false, false, clamp, clamp, TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.POINT,
                1, "ToonProfile", "NativeProfileAtlas", TextureStorageFormat.RGBA16F.name(), "", "", false);
        return new ToonProfiles(Map.copyOf(indices),
                new PreparedTexture(ATLAS, metadata, List.of(data.flip().asReadOnlyBuffer()), layerCount));
    }

    private static Slice require(Map<String, Slice> sources, String path) {
        Slice slice = sources.get(path);
        if (slice == null) throw new IllegalArgumentException("Missing native Unreal Toon profile: " + path);
        return slice;
    }

    private static void copySlice(Slice slice, ByteBuffer destination) {
        try {
            slice.copyTo(destination);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Could not load native Toon profile " + slice.id, failure);
        }
    }

    private static String identity(String source) {
        if (source.equals(DEFAULT)) return DEFAULT;
        if (!source.startsWith("/") || !source.contains(".") || source.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid Unreal Toon profile object path: " + source);
        return SourceNames.canonical(source.replace(':', '.'));
    }

    public int index(String source) {
        Integer value = indices.get(identity(source));
        if (value == null) throw new IllegalStateException("Toon profile is absent from the active atlas: " + source);
        return value;
    }

    public @Nullable EffectTexture upload() { return atlas == null ? null : EffectTextureLoader.upload(atlas); }
}
