package com.kltyton.lumenfx.client.render.niagara.gpu.terrain;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Layer;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Snapshot;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Uint4;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.terrain.NiagaraGpuTerrainImages.CollisionGrids;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.terrain.NiagaraLandscape.Frame;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/** Binds the source landscape shader's cached collision fields and runtime virtual texture layers. */
public final class NiagaraGpuLandscapeBindings {
    private static final double TILE = 2097152.0;
    private static final List<String> IMAGES = List.of("CachedHeightTexture", "CachedPhysMatTexture", "BaseColorVirtualTexture",
            "BaseColorVirtualTexturePageTable", "HeightVirtualTexture", "HeightVirtualTexturePageTable",
            "NormalVirtualTexture0", "NormalVirtualTexture1", "NormalVirtualTexturePageTable");
    private record Source(String compileName, String identity) { }
    private record Bound(String identity, @Nullable Frame frame, Map<String, byte[]> values) { }
    private record Image(@Nullable GpuTextureView view, @Nullable GpuSampler sampler) { }
    private final Map<String, Source> bindings = new HashMap<>();
    private final Map<String, Bound> active = new HashMap<>();
    private final NiagaraGpuTerrainImages images;
    private @Nullable CollisionGrids grids;

    public NiagaraGpuLandscapeBindings(JsonObject program, NiagaraGpuTerrainImages images) {
        this.images = images;
        for (var element : program.getAsJsonArray("interfaces")) {
            var data = element.getAsJsonObject();
            if (!data.has("landscape") || !data.has("shader_symbol")) continue;
            String symbol = data.get("shader_symbol").getAsString();
            var source = new Source(SourceNames.canonical(data.get("compile_name").getAsString()), data.get("interface_id").getAsString());
            if (bindings.putIfAbsent(symbol, source) != null)
                throw new IllegalArgumentException("Duplicate Niagara Landscape shader symbol " + symbol);
        }
    }

    public void beginTick(@Nullable CollisionGrids grids, Map<String, String> aliases, Map<String, Frame> frames) {
        this.grids = grids;
        active.clear();
        for (var entry : bindings.entrySet()) {
            var source = entry.getValue();
            String id = aliases.getOrDefault(source.compileName(), source.identity());
            var frame = frames.get(id);
            active.put(entry.getKey(), new Bound(id, frame, uniforms(frame)));
        }
    }

    public void preStage() {
        if (grids == null) return;
        for (var value : active.values()) grids.use(value.identity(), value.frame() == null ? null : value.frame().grid());
    }

    public void clear() {
        active.clear();
        grids = null;
    }

    public @Nullable byte[] value(String qualified) {
        String name = qualified.substring(qualified.lastIndexOf('.') + 1);
        int separator = name.lastIndexOf('_');
        if (separator < 0) return null;
        var bound = active.get(name.substring(0, separator));
        return bound == null ? null : bound.values().get(name.substring(separator + 1));
    }

    private static Map<String, byte[]> uniforms(@Nullable Frame frame) {
        var result = new HashMap<String, byte[]>();
        var grid = frame == null ? null : frame.grid();
        result.put("CachedHeightTextureEnabled", words(grid != null && grid.hasHeight() ? 1 : 0));
        result.put("CachedHeightTextureLWCTile", grid == null ? floats(0, 0, 0) : vector(grid.tile()));
        result.put("CachedHeightTextureWorldToUvTransform", matrix(grid == null ? new Matrix4f() : grid.toGrid(new Matrix4f())));
        result.put("CachedHeightTextureUvToWorldTransform", matrix(grid == null ? new Matrix4f() : grid.toWorld(new Matrix4f())));
        result.put("CachedHeightTextureUvScaleBias", grid == null ? floats(0, 0, 0, 0)
                : floats(grid.scaleX(), grid.scaleY(), grid.biasX(), grid.biasY()));
        result.put("CachedHeightTextureWorldGridSize", grid == null ? floats(0, 0) : floats(grid.spacingX(), grid.spacingY()));
        result.put("CachedHeightTextureDimension", grid == null || !grid.hasHeight() ? words(0, 0) : words(grid.width(), grid.height()));
        result.put("CachedPhysMatTextureDimension", grid == null || !grid.hasMaterial() ? words(0, 0) : words(grid.width(), grid.height()));
        Snapshot color = frame == null ? null : frame.color();
        Snapshot height = frame == null ? null : frame.height();
        Snapshot normal = frame == null ? null : frame.normal();
        Layer base = layer(color, 0), heightLayer = layer(height, 0);
        Layer normal0 = normalLayer(normal, 0), normal1 = normalLayer(normal, 1);
        virtualUniforms(result, "BaseColor", base == null ? null : color, false);
        virtualUniforms(result, "Height", heightLayer == null ? null : height, true);
        virtualUniforms(result, "Normal", normal0 == null || normal1 == null ? null : normal, false);
        result.put("BaseColorVirtualTextureUnpackSRGB", words(color != null && base != null && !base.yCoCg()
                && color.material() == Material.BaseColor_Normal_Roughness ? 1 : 0));
        result.put("BaseColorVirtualTextureUnpackYCoCg", words(base != null && base.yCoCg() ? 1 : 0));
        result.put("BaseColorVirtualTextureUniforms", layerUniforms(base));
        result.put("HeightVirtualTextureUniforms", layerUniforms(heightLayer));
        result.put("NormalVirtualTextureUnpackMode", words(normalMode(normal)));
        result.put("NormalVirtualTextureUniforms0", layerUniforms(normal0));
        result.put("NormalVirtualTextureUniforms1", layerUniforms(normal1));
        return result;
    }

    private static void virtualUniforms(Map<String, byte[]> result, String channel, @Nullable Snapshot texture, boolean height) {
        String prefix = channel + "VirtualTexture";
        Vec3 tile = texture == null ? Vec3.ZERO : tile(texture.origin());
        result.put(prefix + "Enabled", words(texture == null ? 0 : 1));
        result.put(prefix + "LWCTile", vector(tile));
        result.put(prefix + "PackedUniform0", texture == null ? words(0, 0, 0, 0) : words(texture.table0()));
        result.put(prefix + "PackedUniform1", texture == null ? words(0, 0, 0, 0) : words(texture.table1()));
        if (texture == null) result.put(prefix + "WorldToUvTransform", matrix(new Matrix4f()));
        else {
            Vec3 offset = texture.origin().subtract(tile.scale(TILE));
            Vec3 u = texture.u(), v = texture.v();
            result.put(prefix + "WorldToUvTransform", matrix(new Matrix4f(
                    (float)offset.x, (float)offset.y, (float)offset.z, 0,
                    (float)u.x, (float)u.y, (float)u.z, 0,
                    (float)v.x, (float)v.y, (float)v.z, 0,
                    height ? texture.heightScale() : 0, height ? texture.heightBias() : 0, 0, height ? 0 : 1)));
        }
    }

    public void bind(ShaderProgram shader, ComputeBindings.Builder resources, Supplier<GpuTextureView> black) {
        for (var descriptor : shader.bindings()) {
            if (!(descriptor instanceof ShaderBinding.SampledTexture sampled) || sampled.array() || sampled.volume() || sampled.cube()) continue;
            String name = sampled.name();
            for (var entry : active.entrySet()) {
                String prefix = entry.getKey() + "_";
                if (!name.startsWith(prefix)) continue;
                String field = name.substring(prefix.length());
                for (String candidate : IMAGES) {
                    if (!field.equals(candidate) && !field.startsWith(candidate + "_")) continue;
                    var image = image(entry.getValue(), candidate);
                    images.bind(sampled, resources, image.view(), image.sampler(), black);
                    break;
                }
            }
        }
    }

    private Image image(Bound value, String field) {
        var frame = value.frame();
        if (field.equals("CachedHeightTexture")) return new Image(grids == null ? null : grids.height(value.identity()),
                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        if (field.equals("CachedPhysMatTexture")) return new Image(grids == null ? null : grids.material(value.identity()), point());
        if (frame == null) return new Image(null, null);
        Snapshot source = field.startsWith("BaseColor") ? frame.color() : field.startsWith("Height") ? frame.height() : frame.normal();
        Layer layer = field.startsWith("Normal") ? normalLayer(source, field.endsWith("1") ? 1 : 0) : layer(source, 0);
        if (layer == null) return new Image(null, null);
        return field.endsWith("PageTable") ? new Image(layer.pageTable(), point()) : new Image(layer.physical(), source.sampler());
    }

    private static GpuSampler point() { return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST); }
    private static @Nullable Layer layer(@Nullable Snapshot texture, int index) {
        return texture == null ? null : texture.layers().get(index);
    }
    private static @Nullable Layer normalLayer(@Nullable Snapshot texture, int index) {
        return layer(texture, normalMode(texture) == 2 ? index + 1 : index);
    }
    private static int normalMode(@Nullable Snapshot texture) {
        if (texture == null) return 0;
        return switch (texture.material()) {
            case BaseColor_Normal_Roughness -> 3;
            case BaseColor_Normal_Specular -> 1;
            case BaseColor_Normal_Specular_YCoCg, BaseColor_Normal_Specular_Mask_YCoCg -> 2;
            default -> 0;
        };
    }
    private static Vec3 tile(Vec3 world) {
        return new Vec3(Math.floor(world.x / TILE + .5), Math.floor(world.y / TILE + .5), Math.floor(world.z / TILE + .5));
    }
    private static byte[] layerUniforms(@Nullable Layer layer) {
        return layer == null ? words(0, 0, 0, 0) : words(layer.uniforms());
    }
    private static byte[] words(Uint4 value) { return words(value.x(), value.y(), value.z(), value.w()); }
    private static byte[] vector(Vec3 value) { return floats((float)value.x, (float)value.y, (float)value.z); }
    private static byte[] matrix(Matrix4f value) { return floats(value.transpose().get(new float[16])); }
    private static byte[] words(int... values) {
        var buffer = ByteBuffer.allocate(values.length * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }
    private static byte[] floats(float... values) {
        var buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) buffer.putFloat(value);
        return buffer.array();
    }
}
