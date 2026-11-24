package com.kltyton.lumenfx.client.render.texture;

import com.kltyton.lumenfx.client.VfxEngine;

import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.DynamicMaterialValues;
import com.kltyton.lumenfx.effect.render.material.MaterialInstanceParameters;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats;
import com.kltyton.lumenfx.client.resource.texture.EffectTexture;
import com.kltyton.lumenfx.client.resource.texture.EffectTextures;
import com.kltyton.lumenfx.client.resource.texture.storage.NativeTextureUpload;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class MaterialTextureBindings {
    private static final MaterialSamplerCache SHARED_SAMPLERS = new MaterialSamplerCache();

    private MaterialTextureBindings() { }

    public static void clearSharedSamplers() {
        SHARED_SAMPLERS.clear();
    }

    private static void prepareRasterViews(MaterialDefinition material) {
        RenderSystem.assertOnRenderThread();
        for (var binding : material.textures().entrySet()) {
            var instance = material.instanceParameters().texture(binding.getKey());
            if (instance != null && instance.arrayTexture() != null) rasterArrayView(instance.arrayTexture());
            var id = binding.getValue();
            var target = EffectTextures.target(id);
            if (target != null) {
                var image = target.snapshot();
                if (image.dimension() == TextureParameter.Dimension.ARRAY && image.arrayTexture() != null)
                    rasterArrayView(image.arrayTexture());
            } else {
                var image = EffectTextures.get(id);
                if (image != null && image.dimension() == TextureParameter.Dimension.ARRAY) image.arrayView();
            }
        }
    }

    public static void prepare(CommandEncoder encoder, MaterialDefinition material) {
        var parameters = material.instanceParameters();
        var dynamics = parameters.dynamicValues();
        prepare(encoder, material, parameters.publication(), dynamics == null ? null : dynamics.snapshot());
    }

    public static void prepare(CommandEncoder encoder, MaterialDefinition material,
                               MaterialInstanceParameters.Publication state, DynamicMaterialValues.@Nullable Snapshot edits) {
        MaterialInstanceTextures.prepare(material, state, edits);
        prepareRasterViews(material);
        if (!material.virtualTextureReads().isEmpty()) VfxEngine.INSTANCE.materialVirtualTextures().prepare(encoder, material);
    }

    public static void bind(RenderPass pass, MaterialDefinition material, GpuSampler hostSampler, float viewMipBias) {
        if (!material.virtualTextureReads().isEmpty()) VfxEngine.INSTANCE.materialVirtualTextures().bind(pass, material);
        for (var binding : material.textures().entrySet()) {
            String name = binding.getKey();
            GpuSampler shared = sharedSampler(material, name, viewMipBias);
            var instance = material.instanceParameters().texture(name);
            if (instance != null) {
                bindRaster(pass, name, instance, expectedDimension(binding.getValue()), hostSampler, shared);
                continue;
            }
            var prepared = EffectTextures.get(binding.getValue());
            if (prepared != null) pass.bindTexture(name, prepared.rasterView(), shared == null ? prepared.sampler() : shared);
            else {
                var target = EffectTextures.target(binding.getValue());
                if (target != null) {
                    var snapshot = target.preparedSnapshot();
                    bindRaster(pass, name, snapshot, TextureParameter.Dimension.D2, hostSampler, shared);
                } else pass.bindTexture(name, Minecraft.getInstance().getTextureManager().getTexture(binding.getValue()).getTextureView(),
                        shared == null ? hostSampler : shared);
            }
        }
    }

    private static @Nullable GpuSampler sharedSampler(MaterialDefinition material, String name, float viewMipBias) {
        var explicit = material.textureSamplers().get(name);
        if (explicit != null) return SHARED_SAMPLERS.sampler(explicit, viewMipBias);
        var source = material.textureSamplerSources().get(name);
        return source == null ? null : SHARED_SAMPLERS.sampler(source, viewMipBias);
    }

    private static void bindRaster(RenderPass pass, String name, TextureParameter.Snapshot image,
                                   TextureParameter.Dimension expected, GpuSampler fallbackSampler, @Nullable GpuSampler shared) {
        if (image.bound() && image.dimension() != expected)
            throw new IllegalArgumentException("Material texture dimension does not match its compiled source: " + name
                    + " expects " + expected + " but received " + image.dimension());
        if (!image.bound()) throw new IllegalArgumentException("Material texture is unbound: " + name);
        GpuTextureView view = switch (image.dimension()) {
            case ARRAY -> rasterArrayView(Objects.requireNonNull(image.arrayTexture(), "Array snapshot has no native array"));
            case CUBE, CUBE_ARRAY, D2 -> Objects.requireNonNull(image.view(), "Raster snapshot has no host texture view");
            case VOLUME -> volumeImage(name, image).rasterView();
        };
        GpuSampler sampler = shared != null ? shared : image.sampler();
        if (sampler == null && image.dimension() == TextureParameter.Dimension.VOLUME)
            sampler = volumeImage(name, image).rasterSampler();
        pass.bindTexture(name, view, sampler == null ? fallbackSampler : sampler);
    }

    private static ComputeVolume volumeImage(String name, TextureParameter.Snapshot image) {
        return Objects.requireNonNull(image.volume(), "Volume snapshot has no native image: " + name);
    }

    private static GpuTextureView rasterArrayView(ComputeTexture array) {
        return array.rasterArrayView(() -> NativeTextureUpload.createRasterArrayView(RenderSystem.getDevice(), array));
    }

    public static TextureParameter.Dimension expectedDimension(Identifier id) {
        var image = EffectTextures.get(id);
        if (image != null) return image.dimension();
        return TextureParameter.Dimension.D2;
    }

    public static void bind(ComputeBindings.Builder resources, ShaderBinding.SampledTexture binding,
                            TextureParameter.Snapshot image, MaterialDefinition material, float viewMipBias) {
        GpuSampler shared = binding.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE
                ? null : sharedSampler(material, binding.name(), viewMipBias);
        bind(resources, binding, image, 0, shared);
    }

    public static void bind(ComputeBindings.Builder resources, ShaderBinding.SampledTexture binding,
                            TextureParameter.Snapshot image, int volumeTilingAxes) {
        bind(resources, binding, image, volumeTilingAxes, null);
    }

    private static void bind(ComputeBindings.Builder resources, ShaderBinding.SampledTexture binding,
                             TextureParameter.Snapshot image, int volumeTilingAxes, @Nullable GpuSampler shared) {
        TextureParameter.Dimension expected = binding.cube() && binding.array() ? TextureParameter.Dimension.CUBE_ARRAY
                : binding.array() ? TextureParameter.Dimension.ARRAY
                : binding.cube() ? TextureParameter.Dimension.CUBE
                : binding.volume() ? TextureParameter.Dimension.VOLUME : TextureParameter.Dimension.D2;
        if (!image.bound() || image.dimension() != expected)
            throw new IllegalArgumentException("Sampled descriptor dimension does not match its resolved texture: " + binding.name());
        if (binding.volume()) {
            if (image.volume() == null) throw new IllegalArgumentException("Volume snapshot has no native volume: " + binding.name());
            if (!image.volume().accepts(binding))
                throw new IllegalArgumentException("Volume texture format does not match descriptor: " + binding.name());
            resources.volume(binding.name(), image.volume(), volumeTilingAxes, shared);
            return;
        }
        if (expected == TextureParameter.Dimension.ARRAY) {
            var array = Objects.requireNonNull(image.arrayTexture(), "Array snapshot has no native array");
            if (!SampledTextureFormats.accepts(binding, array.texture()))
                throw new IllegalArgumentException("Array texture format does not match descriptor: " + binding.name());
            if (binding.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) resources.fullArraySampledImage(binding.name(), array);
            else resources.fullArrayTexture(binding.name(), array,
                    shared == null ? Objects.requireNonNull(image.sampler(), "Array snapshot has no sampler") : shared);
            return;
        }
        var view = Objects.requireNonNull(image.view(), "2D/cube snapshot has no host view");
        if (!SampledTextureFormats.accepts(binding, view.texture()))
            throw new IllegalArgumentException("Texture format does not match descriptor: " + binding.name());
        if (binding.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE) resources.sampledImage(binding.name(), view);
        else resources.texture(binding.name(), view, shared == null ? Objects.requireNonNull(image.sampler(), "Texture snapshot has no sampler") : shared);
    }
}
