package com.kltyton.lumenfx.client.resource.light;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.EffectResources;
import com.kltyton.lumenfx.client.resource.texture.PreparedTexture;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.TextureStorageFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Resolves authored directional build-data references during the normal effect resource reload. */
public final class DirectionalShadowResources {
    private static final String FIELD = "lumenfx_static_shadow";
    private static final int HEADER_BYTES = 4 * Integer.BYTES + 16 * Double.BYTES;

    public record Asset(PreparedTexture texture, double[] transform) {
        JsonObject template() {
            var value = new JsonObject();
            value.addProperty("texture", texture.id().toString());
            var matrix = new JsonArray();
            for (double entry : transform) matrix.add(entry);
            value.add("world_to_light", matrix);
            return value;
        }
    }

    private DirectionalShadowResources() { }

    public static void prepare(ResourceManager resources, JsonObject effect, Map<Identifier, Asset> assets) {
        JsonObject niagara = EffectResources.jsonObject(effect, "niagara");
        if (niagara == null) return;
        JsonArray emitters = EffectResources.jsonArray(niagara, "emitters");
        if (emitters == null) return;
        for (var item : emitters) {
            if (!item.isJsonObject()) continue;
            var renderers = EffectResources.jsonArray(item.getAsJsonObject(), "renderers");
            if (renderers == null) continue;
            for (var renderer : renderers) {
                if (!renderer.isJsonObject()) continue;
                JsonObject component = EffectResources.jsonObject(renderer.getAsJsonObject(), "component");
                if (component == null) continue;
                String sourceClass = EffectResources.jsonString(component, "source_class");
                if (sourceClass == null || !sourceClass.equalsIgnoreCase("/Script/Engine.DirectionalLightComponent"))
                    continue;
                JsonObject template = EffectResources.jsonObject(component, "template");
                if (template == null) continue;
                String reference = EffectResources.jsonString(template, FIELD);
                if (reference == null) continue;
                Identifier id = Identifier.parse(reference);
                template.add(FIELD, assets.computeIfAbsent(id, key -> read(resources, key)).template());
            }
        }
    }

    private static Asset read(ResourceManager resources, Identifier id) {
        try (var stream = resources.getResourceOrThrow(id).open()) {
            var bytes = ByteBuffer.wrap(stream.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.remaining() < HEADER_BYTES || bytes.getInt() != 0x4453464c || bytes.getInt() != 1)
                throw new IllegalArgumentException("Unsupported directional shadow depth map " + id);
            int width = bytes.getInt(), height = bytes.getInt();
            if (width <= 0 || height <= 0 || (long) width * height * Short.BYTES != bytes.remaining() - 16 * Double.BYTES)
                throw new IllegalArgumentException("Invalid directional shadow depth dimensions " + id);
            var matrix = new double[16];
            for (int index = 0; index < matrix.length; index++) {
                matrix[index] = bytes.getDouble();
                if (!Double.isFinite(matrix[index]))
                    throw new IllegalArgumentException("Invalid directional shadow transform " + id);
            }
            var pixels = ByteBuffer.allocateDirect(bytes.remaining()).put(bytes).flip();
            var clamp = TextureMetadata.Address.CLAMP;
            var metadata = new TextureMetadata(width, height, false, TextureMetadata.MipMode.NO_MIPMAPS, 0, 2,
                    false, false, false, clamp, clamp, TextureMetadata.FilterMode.POINT, TextureMetadata.FilterMode.POINT,
                    1, "StaticShadowDepth", "MapBuildDataRegistry", TextureStorageFormat.R16F.name(), "", "", false);
            return new Asset(new PreparedTexture(id, metadata, List.of(pixels.asReadOnlyBuffer())), matrix);
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read directional shadow depth " + id, failure);
        }
    }
}
