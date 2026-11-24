package com.kltyton.lumenfx.client.resource.texture.subsurface;

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
import java.nio.charset.CharacterCodingException;
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

/** Profile zero is Unreal's built-in profile. Asset rows retain their original RGBA16 UNORM values. */
public final class SubsurfaceProfiles {
    public static final Identifier ATLAS = LumenFx.id("lumenfx/subsurface/atlas");
    public static final SubsurfaceProfiles EMPTY = new SubsurfaceProfiles(Map.of(), null);
    private static final String DIRECTORY = "lumenfx/subsurface";
    private static final int WIDTH = 66;
    private static final int ROW_BYTES = WIDTH * 4 * Short.BYTES;
    private static final int MAX_PATH_BYTES = 4096;
    private static final int HEADER_BYTES = 16;

    private record Row(String objectPath, ByteBuffer values) { }

    private final Map<String, Integer> indices;
    private final @Nullable ByteBuffer data;

    private SubsurfaceProfiles(Map<String, Integer> indices, @Nullable ByteBuffer data) {
        this.indices = indices;
        this.data = data;
    }

    public static SubsurfaceProfiles prepare(ResourceManager resources, Set<String> materialProfiles) {
        if (materialProfiles.isEmpty()) return EMPTY;
        var requested = new TreeSet<String>();
        for (String path : materialProfiles) requested.add(identity(path));
        requested.add("");
        if (requested.size() > 256)
            throw new IllegalArgumentException("A LumenFX resource generation supports up to 256 subsurface profile rows");

        Map<String, Row> rows = new HashMap<>();
        for (var entry : resources.listResources(DIRECTORY, id -> id.getPath().endsWith(".lfsp")).entrySet()) {
            Row row = read(entry.getKey(), entry.getValue());
            if (requested.contains(row.objectPath()) && rows.putIfAbsent(row.objectPath(), row) != null)
                throw new IllegalArgumentException("Multiple Unreal subsurface profile rows for " + row.objectPath());
        }

        Map<String, Integer> indices = new HashMap<>();
        ByteBuffer atlas = ByteBuffer.allocateDirect(requested.size() * ROW_BYTES).order(ByteOrder.nativeOrder());
        for (String path : requested) {
            Row row = rows.get(path);
            if (row == null)
                throw new IllegalArgumentException("Missing native Unreal subsurface profile: " + (path.isEmpty() ? "default" : path));
            indices.put(path, indices.size());
            while (row.values().hasRemaining()) atlas.putShort(row.values().getShort());
        }
        return new SubsurfaceProfiles(Map.copyOf(indices), atlas.flip().asReadOnlyBuffer());
    }

    private static Row read(Identifier id, Resource resource) {
        ByteBuffer file;
        try (var input = resource.open()) {
            file = ByteBuffer.wrap(input.readNBytes(HEADER_BYTES + MAX_PATH_BYTES + ROW_BYTES + 1))
                    .order(ByteOrder.LITTLE_ENDIAN);
        } catch (IOException error) {
            throw new IllegalArgumentException("Could not read Unreal subsurface profile " + id, error);
        }
        if (file.remaining() < HEADER_BYTES + ROW_BYTES || file.getInt() != 0x5053464c
                || file.getInt() != 1 || file.getInt() != WIDTH)
            throw new IllegalArgumentException("Invalid Unreal subsurface profile header: " + id);
        int length = file.getInt();
        if (length < 0 || length > MAX_PATH_BYTES || file.remaining() != length + ROW_BYTES)
            throw new IllegalArgumentException("Invalid Unreal subsurface profile payload: " + id);
        String path;
        try {
            path = StandardCharsets.UTF_8.newDecoder().decode(file.slice(file.position(), length)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Invalid Unreal subsurface profile object path: " + id, error);
        }
        if (!path.isEmpty() && (!path.startsWith("/") || !path.contains(".") || path.indexOf('\0') >= 0))
            throw new IllegalArgumentException("Invalid Unreal subsurface profile object path: " + id);
        file.position(file.position() + length);
        return new Row(identity(path), file);
    }

    private static String identity(String path) {
        return SourceNames.canonical(path.replace(':', '.'));
    }

    public int index(String objectPath) {
        Integer row = indices.get(identity(objectPath));
        if (row == null) throw new IllegalStateException("Subsurface profile is absent from the active atlas: " + objectPath);
        return row;
    }

    public @Nullable EffectTexture upload() {
        if (data == null) return null;
        var clamp = TextureMetadata.Address.CLAMP;
        var metadata = new TextureMetadata(WIDTH, indices.size(), false, TextureMetadata.MipMode.NO_MIPMAPS, 0, 2,
                false, false, false, clamp, clamp, TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.POINT,
                1, "SubsurfaceProfile", "NativeProfileAtlas", TextureStorageFormat.RGBA16.name(), "", "", false);
        return EffectTextureLoader.upload(new PreparedTexture(ATLAS, metadata, List.of(data.duplicate())));
    }
}
