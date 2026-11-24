package com.kltyton.lumenfx.client.resource.texture.specular;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextureLoader;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.InputStream;
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

/** The UE light and convolved environment slices, packed without cross-slice filtering. */
public final class SpecularProfiles {
    public static final Identifier ATLAS = LumenFx.id("lumenfx/specular_profiles/atlas");
    public static final int MAX_PROFILES = 255;
    public static final SpecularProfiles EMPTY = new SpecularProfiles(Map.of(), 0, 0, null);
    private static final int MAX_RESOLUTION = 1024;
    private static final int MAX_PATH_BYTES = 8192;
    private static final int HEADER_BYTES = 20;
    private static final int PIXEL_BYTES = 8;
    private static final int SLICES = 2;

    private record Profile(Identifier id, Resource resource, int resolution, boolean halfVector, int pathBytes) { }

    private final Map<String, Integer> indices;
    private final int width;
    private final int height;
    private final @Nullable ByteBuffer data;

    private SpecularProfiles(Map<String, Integer> indices, int width, int height, @Nullable ByteBuffer data) {
        this.indices = indices;
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public static SpecularProfiles prepare(ResourceManager resources, Set<String> materialProfiles) {
        if (materialProfiles.isEmpty()) return EMPTY;
        var requested = new TreeSet<String>();
        for (String source : materialProfiles) requested.add(identity(source));
        if (requested.size() > MAX_PROFILES)
            throw new IllegalArgumentException("Unreal specular profiles reserve one of 256 native entries for the default");

        Map<String, Profile> sources = new HashMap<>();
        for (var entry : resources.listResources("lumenfx/specular_profiles", id -> id.getPath().endsWith(".lfsl")).entrySet()) {
            Identifier id = entry.getKey();
            try (var input = entry.getValue().open()) {
                ByteBuffer header = ByteBuffer.wrap(input.readNBytes(HEADER_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
                if (header.remaining() != HEADER_BYTES || header.getInt() != 0x4c53464c || header.getInt() != 1)
                    throw new IllegalArgumentException("Invalid Unreal specular profile header: " + id);
                int resolution = header.getInt(), mode = header.getInt(), length = header.getInt();
                if (resolution < 2 || resolution > MAX_RESOLUTION || mode < 0 || mode > 1 || length <= 0 || length > MAX_PATH_BYTES)
                    throw new IllegalArgumentException("Invalid Unreal specular profile dimensions or mode: " + id);
                byte[] pathBytes = input.readNBytes(length);
                if (pathBytes.length != length)
                    throw new IllegalArgumentException("Truncated Unreal specular profile path: " + id);
                String source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(pathBytes)).toString();
                String path = identity(source);
                if (requested.contains(path) && sources.putIfAbsent(path, new Profile(id, entry.getValue(), resolution, mode == 1, length)) != null)
                    throw new IllegalArgumentException("Multiple Unreal specular profiles for " + source);
            } catch (IOException error) {
                throw new IllegalArgumentException("Could not read Unreal specular profile " + id, error);
            }
        }

        int resolution = 0;
        for (String path : requested) {
            Profile source = sources.get(path);
            if (source == null) throw new IllegalArgumentException("Missing native Unreal specular profile: " + path);
            if (resolution != 0 && resolution != source.resolution())
                throw new IllegalArgumentException("Unreal specular profiles must share the same native atlas resolution: " + source.id());
            resolution = source.resolution();
        }

        int tiles = requested.size() * SLICES;
        int columns = (int) Math.ceil(Math.sqrt(tiles));
        int width = 1 + columns * resolution, height = ((tiles + columns - 1) / columns) * resolution;
        ByteBuffer atlas = ByteBuffer.allocateDirect(Math.multiplyExact(Math.multiplyExact(width, height), PIXEL_BYTES))
                .order(ByteOrder.nativeOrder());
        atlas.putShort(0, Float.floatToFloat16(resolution));
        Map<String, Integer> indices = new HashMap<>();
        for (String path : requested) {
            Profile source = sources.get(path);
            int index = indices.size() + 1;
            indices.put(path, source.halfVector() ? -index : index);
            copySlices(source, index, resolution, width, columns, atlas);
        }
        return new SpecularProfiles(Map.copyOf(indices), width, height, atlas.asReadOnlyBuffer());
    }

    private static void copySlices(Profile source, int index, int resolution, int width, int columns, ByteBuffer atlas) {
        int rowBytes = resolution * PIXEL_BYTES;
        byte[] row = new byte[rowBytes];
        ByteBuffer values = ByteBuffer.wrap(row).order(ByteOrder.LITTLE_ENDIAN);
        try (InputStream input = source.resource().open()) {
            input.skipNBytes(HEADER_BYTES + source.pathBytes());
            for (int slice = 0; slice < SLICES; slice++) {
                int tile = (index - 1) * SLICES + slice;
                int x = 1 + tile % columns * resolution;
                int y = tile / columns * resolution;
                for (int line = 0; line < resolution; line++) {
                    if (input.readNBytes(row, 0, rowBytes) != rowBytes)
                        throw new IllegalArgumentException("Truncated Unreal specular profile " + source.id());
                    int offset = ((y + line) * width + x) * PIXEL_BYTES;
                    for (int column = 0; column < rowBytes; column += Short.BYTES)
                        atlas.putShort(offset + column, values.getShort(column));
                }
            }
            if (input.read() != -1)
                throw new IllegalArgumentException("Excess Unreal specular profile payload " + source.id());
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not load Unreal specular profile " + source.id(), error);
        }
    }

    private static String identity(String source) {
        if (!source.startsWith("/") || !source.contains(".") || source.indexOf('\0') >= 0)
            throw new IllegalArgumentException("Invalid Unreal specular profile object path: " + source);
        return SourceNames.canonical(source.replace(':', '.'));
    }

    public int index(String source) {
        Integer value = indices.get(identity(source));
        if (value == null) throw new IllegalStateException("Specular profile is absent from the active atlas: " + source);
        return value;
    }

    public @Nullable EffectTexture upload() {
        if (data == null) return null;
        int limit = RenderSystem.getDevice().getDeviceInfo().limits().maxTextureSize();
        if (width > limit || height > limit)
            throw new IllegalArgumentException("Specular profile atlas " + width + " x " + height + " exceeds device texture limit " + limit);
        var clamp = TextureMetadata.Address.CLAMP;
        var metadata = new TextureMetadata(width, height, false, TextureMetadata.MipMode.NO_MIPMAPS, 0, 2,
                false, false, false, clamp, clamp, TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.POINT,
                1, "SpecularProfile", "NativeProfileAtlas", TextureStorageFormat.RGBA16F.name(), "", "", false);
        return EffectTextureLoader.upload(new PreparedTexture(ATLAS, metadata, List.of(data.duplicate())));
    }
}
