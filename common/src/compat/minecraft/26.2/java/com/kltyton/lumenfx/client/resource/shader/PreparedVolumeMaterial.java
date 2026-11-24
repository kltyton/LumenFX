package com.kltyton.lumenfx.client.resource.shader;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import com.mojang.blaze3d.GpuFormat;
import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** The offline compute material and its immutable source sparse volume inputs. */
public record PreparedVolumeMaterial(Map<Fog, Map<Ray, ShaderProgram>> rays, Map<Cache, ShaderProgram> caches, Map<Bake, ShaderProgram> bakes,
                                     Map<String, SparseVolumeTextureAsset> sparseVolumes) {
    public static final Identifier PRESHADING_PROGRAM = Identifier.fromNamespaceAndPath("lumenfx", "compute/volume/preshaded");

    public PreparedVolumeMaterial {
        rays = Map.copyOf(rays);
        caches = Map.copyOf(caches);
        bakes = Map.copyOf(bakes);
        sparseVolumes = Map.copyOf(sparseVolumes);
    }
    public enum Fog { NONE, HEIGHT, FROXEL }
    public enum Ray { DIRECT, SHADOW, INSCATTERING, TRANSMITTANCE, TRANSMITTANCE_SHADOW, SPOT_SHADOW, TRANSMITTANCE_SPOT_SHADOW }
    public enum Cache { INSCATTERING, INSCATTERING_SHADOW, TRANSMITTANCE, INSCATTERING_SPOT_SHADOW }
    public enum Bake {
        RED(GpuFormat.R16_FLOAT), PACKED(GpuFormat.RG11B10_FLOAT), RGBA(GpuFormat.RGBA16_FLOAT);
        private final GpuFormat format;
        Bake(GpuFormat format) { this.format = format; }
        public GpuFormat format() { return format; }
        public static Bake sourceFormat(int format) {
            return switch (format) {
                case 0 -> RED;
                case 3 -> RGBA;
                default -> PACKED;
            };
        }
    }

    public ShaderProgram ray(Fog fog, Ray mode) { return rays.get(fog).get(mode); }
    public ShaderProgram cache(Cache mode) { return caches.get(mode); }
    public ShaderProgram bake(Bake format) { return bakes.get(format); }
    public boolean supportsLocalLightShapes() {
        return caches.containsKey(Cache.INSCATTERING_SPOT_SHADOW);
    }

    public static PreparedVolumeMaterial prepare(ResourceManager resources, Identifier source,
                                                  Function<Identifier, SparseVolumeTextureAsset> sparseVolumes) throws IOException {
        Identifier metadata = Identifier.fromNamespaceAndPath(source.getNamespace(), "shaders/" + source.getPath() + ".volume.program.json");
        var data = ShaderResourceFiles.json(resources, metadata);
        String schema = data.get("schema").getAsString();
        boolean legacy = "lumenfx.volume_material_program.v1".equals(schema);
        if (!legacy) ShaderResourceFiles.require("lumenfx.volume_material_program.v2", schema, metadata.toString());
        var images = new LinkedHashMap<String, SparseVolumeTextureAsset>();
        for (var texture : data.getAsJsonObject("sparse_volumes").entrySet())
            images.put(texture.getKey(), sparseVolumes.apply(Identifier.parse(texture.getValue().getAsString())));
        var rays = new EnumMap<Fog, Map<Ray, ShaderProgram>>(Fog.class);
        var raySource = data.getAsJsonObject("ray");
        for (var fog : Fog.values()) {
            String key = key(fog);
            var variants = raySource.getAsJsonObject(key);
            var modes = new EnumMap<Ray, ShaderProgram>(Ray.class);
            for (var ray : Ray.values()) {
                if (legacy && (ray == Ray.SPOT_SHADOW || ray == Ray.TRANSMITTANCE_SPOT_SHADOW)) continue;
                modes.put(ray, program(resources, metadata, source + "/" + key + "/" + key(ray), variants.getAsJsonObject(key(ray))));
            }
            rays.put(fog, Map.copyOf(modes));
        }
        var caches = new EnumMap<Cache, ShaderProgram>(Cache.class);
        var cacheSource = data.getAsJsonObject("cache");
        for (var cache : Cache.values()) {
            if (legacy && cache == Cache.INSCATTERING_SPOT_SHADOW) continue;
            caches.put(cache, program(resources, metadata, source + "/cache/" + key(cache), cacheSource.getAsJsonObject(key(cache))));
        }
        var bakes = new EnumMap<Bake, ShaderProgram>(Bake.class);
        var bakeSource = data.getAsJsonObject("bake");
        for (var format : Bake.values()) if (bakeSource.has(key(format)))
            bakes.put(format, program(resources, metadata, source + "/bake/" + key(format), bakeSource.getAsJsonObject(key(format))));
        return new PreparedVolumeMaterial(rays, caches, bakes, images);
    }

    private static String key(Enum<?> mode) { return mode.name().toLowerCase(Locale.ROOT); }

    private static ShaderProgram program(ResourceManager resources, Identifier metadata, String name, JsonObject data) throws IOException {
        var artifacts = data.getAsJsonObject("artifacts");
        byte[] vulkan = ShaderResourceFiles.artifact(resources, metadata, artifacts.getAsJsonObject("vulkan"));
        byte[] opengl = ShaderResourceFiles.artifact(resources, metadata, artifacts.getAsJsonObject("opengl"));
        return ShaderProgramDecoder.decodeStage(name, data.getAsJsonObject("shader"), vulkan, opengl);
    }
}
