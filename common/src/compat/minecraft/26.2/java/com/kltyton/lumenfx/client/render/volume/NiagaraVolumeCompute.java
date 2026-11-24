package com.kltyton.lumenfx.client.render.volume;

import com.kltyton.lumenfx.client.VfxEngine;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.render.compute.ComputeAccess;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.ShaderProgram;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Atmosphere;
import com.kltyton.lumenfx.client.render.scene.atmosphere.AtmosphereMaterialTextures;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial.Bake;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial.Cache;
import com.kltyton.lumenfx.client.resource.shader.PreparedVolumeMaterial.Ray;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.texture.MaterialTextureBindings;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory.Settings;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset.Frame;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/** Evaluates authored volume materials directly against their real 3D images. */
final class NiagaraVolumeCompute implements AutoCloseable {
    record ShadowInputs(GpuBufferSlice uniform, GpuTextureView depth, GpuSampler sampler, boolean spot) { }
    record DirectionalShadowInputs(GpuBufferSlice uniform, DirectionalShadowRenderer.@Nullable Frame frame,
                                   GpuTextureView fallbackDepth) { }
    record DrawUniforms(GpuBufferSlice volume, GpuBufferSlice clock, @Nullable GpuBufferSlice instance,
                        @Nullable GpuBufferSlice view, Map<String, GpuBufferSlice> collections,
                        @Nullable Atmosphere atmosphere) {
        @Nullable GpuBufferSlice find(String name) {
            return switch (name) {
                case "NiagaraVolumeView" -> volume;
                case "EffectFrame" -> clock;
                case "MaterialInstance" -> instance;
                case "MaterialView" -> view;
                case "Projection" -> RenderSystem.getProjectionMatrixBuffer();
                default -> collections.get(name);
            };
        }
    }

    private final Map<ShaderProgram, ComputeKernel> kernels = new IdentityHashMap<>();
    private final Map<Frame, SparseVolumeImages> volumes = new IdentityHashMap<>();
    private @Nullable ComputeRuntime runtime;
    private @Nullable ComputeTexture radiance;
    private @Nullable ComputeVolume lightingCache;
    private @Nullable VolumeCoefficients coefficients;
    private @Nullable ComputeVolume blackAttribute;

    GpuTextureView integrate(CommandEncoder encoder, PreparedVolumeMaterial prepared, MaterialDefinition material,
                             DrawUniforms uniforms, GpuTextureView depth, @Nullable GpuTextureView sceneColor,
                             @Nullable ShadowInputs shadows, @Nullable DirectionalShadowInputs directionalShadows,
                             Ray ray, VolumeFogInputs fog) {
        if (runtime == null) runtime = ComputeRuntime.create(RenderSystem.getDevice());
        var backend = runtime;
        int width = depth.getWidth(0), height = depth.getHeight(0);
        if (radiance == null || radiance.width() != width || radiance.height() != height) {
            if (radiance != null) radiance.close();
            radiance = null;
            radiance = backend.createTexture2D("Niagara.VolumeRadiance", GpuFormat.RGBA16_FLOAT, width, height, 1);
        }
        var result = radiance;
        var shader = prepared.ray(fog.mode(), ray);
        var resources = bindings(encoder, shader, prepared, material, uniforms, depth, sceneColor, shadows,
                directionalShadows, result, fog);
        kernels.computeIfAbsent(shader, backend::createKernel).dispatch(resources, width, height);
        return result.sampledView();
    }

    void cacheLight(CommandEncoder encoder, PreparedVolumeMaterial prepared, MaterialDefinition material,
                    DrawUniforms uniforms, GpuTextureView depth, @Nullable GpuTextureView sceneColor,
                    @Nullable ShadowInputs shadows, @Nullable DirectionalShadowInputs directionalShadows,
                    VolumeLightingGrid grid, Cache mode) {
        if (runtime == null) runtime = ComputeRuntime.create(RenderSystem.getDevice());
        var backend = runtime;
        if (lightingCache == null || lightingCache.width() != grid.x() || lightingCache.height() != grid.y()
                || lightingCache.depth() != grid.z()) {
            if (lightingCache != null) lightingCache.close();
            lightingCache = null;
            var clamp = TextureMetadata.Address.CLAMP;
            var linear = TextureMetadata.FilterMode.LINEAR;
            var sampling = new ComputeVolume.Sampling(new Settings(clamp, clamp, linear, TextureMetadata.FilterMode.POINT, 1), clamp);
            lightingCache = ComputeVolume.createTarget("Niagara.VolumeLightingCache", grid.x(), grid.y(), grid.z(),
                    GpuFormat.RG11B10_FLOAT, true, false, new float[4], sampling);
        }
        var shader = prepared.cache(mode);
        var resources = bindings(encoder, shader, prepared, material, uniforms, depth, sceneColor, shadows,
                directionalShadows, null, VolumeFogInputs.NONE);
        kernels.computeIfAbsent(shader, backend::createKernel).dispatch(resources, grid.x(), grid.y(), grid.z());
    }

    void bakeCoefficients(CommandEncoder encoder, PreparedVolumeMaterial source, MaterialDefinition material,
                          DrawUniforms uniforms, GpuTextureView depth, VolumePreshadingGrid grid, Bake format) {
        if (runtime == null) runtime = ComputeRuntime.create(RenderSystem.getDevice());
        var backend = runtime;
        if (coefficients == null || !coefficients.matches(grid, format.format())) {
            if (coefficients != null) coefficients.close();
            coefficients = null;
            coefficients = VolumeCoefficients.create(grid, format.format());
        }
        var shader = Objects.requireNonNull(source.bake(format), "Missing Niagara material coefficient shader for " + format);
        var resources = bindings(encoder, shader, source, material, uniforms, depth, null, null, null, null, VolumeFogInputs.NONE);
        kernels.computeIfAbsent(shader, backend::createKernel).dispatch(resources, grid.x(), grid.y(), grid.z());
    }

    private ComputeBindings bindings(CommandEncoder encoder, ShaderProgram shader, PreparedVolumeMaterial prepared,
                                     MaterialDefinition material, DrawUniforms uniforms, GpuTextureView depth,
                                     @Nullable GpuTextureView sceneColor, @Nullable ShadowInputs shadows,
                                     @Nullable DirectionalShadowInputs directionalShadows,
                                     @Nullable ComputeTexture outputImage, VolumeFogInputs fog) {
        var builder = ComputeBindings.builder();
        for (var binding : shader.bindings()) {
            String name = binding.name();
            if (binding instanceof ShaderBinding.UniformBuffer) {
                var uniform = switch (name) {
                    case "VolumePointShadow" -> Objects.requireNonNull(shadows).uniform();
                    case DirectionalShadowRenderer.UNIFORM -> directionalShadows == null
                            ? DirectionalShadowRenderer.uniforms(encoder, null, DirectionalShadowRenderer.VOLUME_CASCADE_LIMIT)
                            : directionalShadows.uniform();
                    case "ExponentialFog" -> fog.height();
                    case "FogVolume" -> Objects.requireNonNull(fog.froxel()).uniforms();
                    default -> uniforms.find(name);
                };
                if (uniform == null && name.endsWith("_Uniforms")) {
                    var asset = prepared.sparseVolumes().get(name.substring(0, name.lastIndexOf('_')));
                    if (asset != null) uniform = sparseUniforms(encoder, asset);
                }
                if (uniform == null) uniform = VfxEngine.INSTANCE.materialVirtualTextures().uniform(material, name);
                builder.uniform(name, Objects.requireNonNull(uniform, "Missing volume material uniform " + name));
            } else if (binding instanceof ShaderBinding.StorageImage image) {
                if (image.volume()) {
                    var destination = switch (name) {
                        case "VolumeBakeExtinction" -> Objects.requireNonNull(coefficients).extinction();
                        case "VolumeBakeEmission" -> Objects.requireNonNull(coefficients).emission();
                        case "VolumeBakeAlbedo" -> Objects.requireNonNull(coefficients).albedo();
                        case "VolumeCacheOutput" -> Objects.requireNonNull(lightingCache);
                        default -> throw new IllegalArgumentException("Unknown volume output " + name);
                    };
                    builder.volumeImage(name, destination, image.access());
                } else builder.image(name, Objects.requireNonNull(outputImage), image.access(), 0, 0, 1);
            } else if (binding instanceof ShaderBinding.SampledTexture sampled) {
                if (AtmosphereMaterialTextures.bind(builder, name, uniforms.atmosphere())) continue;
                switch (name) {
                    case "VolumeSceneDepth" -> builder.texture(name, depth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    case "VolumeLightingCache" -> builder.volume(name, Objects.requireNonNull(lightingCache), 0);
                    case "VolumeBakedExtinction" -> builder.volume(name, Objects.requireNonNull(coefficients).extinction(), 0);
                    case "VolumeBakedEmission" -> builder.volume(name, Objects.requireNonNull(coefficients).emission(), 0);
                    case "VolumeBakedAlbedo" -> builder.volume(name, Objects.requireNonNull(coefficients).albedo(), 0);
                    case "FogVolumeSampler" -> builder.texture(name, Objects.requireNonNull(fog.froxel()).integrated(),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                    case "VolumeShadowDepth", "VolumeSpotShadowDepth" -> {
                        var shadow = Objects.requireNonNull(shadows);
                        builder.texture(name, shadow.depth(), shadow.sampler());
                    }
                    case DirectionalShadowRenderer.DEPTH -> {
                        var frame = directionalShadows == null ? null : directionalShadows.frame();
                        var texture = frame == null ? null : frame.texture();
                        builder.texture(name, texture == null ? depth : texture,
                                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    }
                    case "LfxStaticDirectionalDepth" -> {
                        var frame = directionalShadows == null ? null : directionalShadows.frame();
                        var baked = frame == null ? null : frame.staticShadow();
                        builder.texture(name, baked == null ? depth : baked.texture(),
                                RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                    }
                    case "LfxSceneColor" -> builder.texture(name, Objects.requireNonNull(sceneColor),
                            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
                    default -> sample(builder, sampled, material, prepared);
                }
            }
        }
        return builder.build();
    }

    private void sample(ComputeBindings.Builder builder, ShaderBinding.SampledTexture binding, MaterialDefinition material,
                        PreparedVolumeMaterial prepared) {
        String name = binding.name();
        if (VfxEngine.INSTANCE.materialVirtualTextures().bind(builder, material, binding)) return;
        int separator = name.lastIndexOf('_');
        var sparse = separator < 0 ? null : prepared.sparseVolumes().get(name.substring(0, separator));
        if (sparse != null) {
            var frame = sparse.frames().getFirst();
            var images = volumes.computeIfAbsent(frame, value -> SparseVolumeImages.upload("Niagara.Material." + name, value));
            var texture = switch (name.substring(separator + 1)) {
                case "Table" -> images.pageTable();
                case "A" -> images.a();
                case "B" -> images.b();
                default -> throw new IllegalArgumentException("Unknown sparse volume descriptor " + name);
            };
            if (texture == null) {
                if (blackAttribute == null) blackAttribute = SparseVolumeImages.blackAttribute();
                texture = blackAttribute;
            }
            builder.volume(name, texture, 0);
            return;
        }
        var instance = material.instanceParameters().texture(name);
        if (instance == null) {
            var id = Objects.requireNonNull(material.textures().get(name), "Missing volume material texture " + name);
            if (EffectTextures.get(id) != null || EffectTextures.target(id) != null) instance = EffectTextures.snapshot(id);
            else {
                var texture = Minecraft.getInstance().getTextureManager().getTexture(id);
                instance = TextureParameter.snapshot(texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            }
        }
        MaterialTextureBindings.bind(builder, binding, instance, material, WorldHdrCapture.INSTANCE.materialViewMipBias());
    }

    private static GpuBufferSlice sparseUniforms(CommandEncoder encoder, SparseVolumeTextureAsset asset) {
        var frame = asset.frames().getFirst();
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = encoder.transientMemory().allocateGpuMapped(48, alignment, GpuBuffer.USAGE_UNIFORM)) {
            var data = mapped.data().order(ByteOrder.nativeOrder());
            var size = asset.resolution();
            data.putFloat(size.x() / 16f).putFloat(size.y() / 16f).putFloat(size.z() / 16f).putInt(SparseVolumeImages.packedPageOffset(frame));
            var extent = frame.atlasExtent();
            data.putFloat(1f / extent.x()).putFloat(1f / extent.y()).putFloat(1f / extent.z()).putInt(SparseVolumeImages.packedFrameMip(frame));
            data.putInt(asset.addressX().ordinal()).putInt(asset.addressY().ordinal()).putInt(asset.addressZ().ordinal()).putInt(0);
            return mapped.slice();
        }
    }

    @Override public void close() {
        kernels.values().forEach(ComputeKernel::close);
        kernels.clear();
        volumes.values().forEach(SparseVolumeImages::close);
        volumes.clear();
        if (radiance != null) { radiance.close(); radiance = null; }
        if (lightingCache != null) { lightingCache.close(); lightingCache = null; }
        if (coefficients != null) { coefficients.close(); coefficients = null; }
        if (blackAttribute != null) { blackAttribute.close(); blackAttribute = null; }
        if (runtime != null) { runtime.close(); runtime = null; }
    }
}
