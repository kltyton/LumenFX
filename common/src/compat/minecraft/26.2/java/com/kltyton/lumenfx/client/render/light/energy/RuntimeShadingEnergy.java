package com.kltyton.lumenfx.client.render.light.energy;

import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ComputeVolume;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.resource.shader.PreparedShadingEnergyShaders;
import com.kltyton.lumenfx.client.resource.texture.EffectSamplerFactory;
import com.kltyton.lumenfx.client.resource.texture.TextureMetadata;
import com.kltyton.lumenfx.client.resource.texture.energy.ShadingEnergyTable.Kind;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Runs the fixed RenderCore generator on the host device when requested by the source console setting. */
public final class RuntimeShadingEnergy implements AutoCloseable {
    private static final int SAMPLES = 1 << 14;
    private static final int FIXED_RESOLUTION = 32;
    private static final ComputeVolume.Sampling VOLUME_SAMPLING = new ComputeVolume.Sampling(
            new EffectSamplerFactory.Settings(TextureMetadata.Address.CLAMP, TextureMetadata.Address.CLAMP,
                    TextureMetadata.FilterMode.LINEAR, TextureMetadata.FilterMode.POINT, 1), TextureMetadata.Address.CLAMP);

    private record Pass(String name, String variant, GpuFormat format, int size, boolean volume, int dispatchDepth) { }
    private record Kernel(ComputeKernel nativeKernel, NiagaraGpuComputeStage stage) { }
    private record Key(String name, String variant) { }
    private static final class Image implements AutoCloseable {
        final Pass pass;
        final @Nullable ComputeTexture texture;
        final @Nullable ComputeVolume volume;
        long generatedFrame = Long.MIN_VALUE;

        Image(Pass pass, ComputeRuntime runtime) {
            this.pass = pass;
            String label = "LumenFX shading energy " + pass.name();
            volume = pass.volume() ? ComputeVolume.createTarget(label, pass.size(), pass.size(), pass.size(),
                    pass.format(), true, false, new float[4], VOLUME_SAMPLING) : null;
            texture = pass.volume() ? null : runtime.createTexture(label, pass.format(), pass.size(), pass.size());
        }

        @Override public void close() {
            if (volume != null) volume.close();
            else Objects.requireNonNull(texture).close();
        }
    }

    private final PreparedShadingEnergyShaders prepared;
    private final ComputeRuntime runtime;
    private final Map<Key, Kernel> kernels = new HashMap<>();
    private final Map<Kind, Image> images = new EnumMap<>(Kind.class);

    public RuntimeShadingEnergy(PreparedShadingEnergyShaders prepared) {
        this.prepared = prepared;
        runtime = ComputeRuntime.create(RenderSystem.getDevice());
    }

    public static boolean enabled() {
        return setting(NiagaraPlatforms.snapshot(), "RuntimeGeneration") > 0;
    }

    public ComputeTexture texture(Kind kind, CommandEncoder encoder, long frame) {
        if (kind == Kind.GGX_TRANSMISSION)
            throw new IllegalArgumentException("GGX transmission is a three-dimensional energy table");
        return Objects.requireNonNull(generate(kind, encoder, frame).texture);
    }

    public ComputeVolume transmission(CommandEncoder encoder, long frame) {
        return Objects.requireNonNull(generate(Kind.GGX_TRANSMISSION, encoder, frame).volume);
    }

    private Image generate(Kind kind, CommandEncoder encoder, long frame) {
        RenderSystem.assertOnRenderThread();
        Pass pass = pass(kind, NiagaraPlatforms.snapshot());
        Image previous = images.get(kind);
        if (previous != null && previous.pass.equals(pass)) {
            if (previous.generatedFrame != frame) dispatch(previous, encoder, frame);
            return previous;
        }
        Image next = new Image(pass, runtime);
        try {
            dispatch(next, encoder, frame);
        } catch (RuntimeException | Error failure) {
            next.close();
            throw failure;
        }
        images.put(kind, next);
        if (previous != null) previous.close();
        return next;
    }

    private void dispatch(Image image, CommandEncoder encoder, long frame) {
        Pass pass = image.pass;
        Kernel kernel = kernels.computeIfAbsent(new Key(pass.name(), pass.variant()), this::kernel);
        var bindings = ComputeBindings.builder();
        ShaderBinding.StorageImage output = kernel.stage().shader().bindings().stream()
                .filter(ShaderBinding.StorageImage.class::isInstance).map(ShaderBinding.StorageImage.class::cast)
                .findFirst().orElseThrow();
        if (image.volume != null) bindings.volumeImage(output.name(), image.volume, output.access());
        else bindings.image(output.name(), Objects.requireNonNull(image.texture), output.access());
        kernel.stage().dispatch(encoder, bindings, qualified -> switch (qualified.substring(qualified.lastIndexOf('.') + 1)) {
            case "NumSamples" -> uint(SAMPLES);
            case "EnergyTableResolution" -> uint(pass.size());
            default -> null;
        }, pass.size(), pass.size(), pass.dispatchDepth());
        image.generatedFrame = frame;
    }

    private Kernel kernel(Key key) {
        var stage = prepared.programs().get(key.name()).get(key.variant());
        ComputeKernel kernel = runtime.createKernel(stage.shader());
        try {
            return new Kernel(kernel, new NiagaraGpuComputeStage(stage, kernel));
        } catch (RuntimeException | Error failure) {
            kernel.close();
            throw failure;
        }
    }

    private Pass pass(Kind kind, NiagaraPlatformEnvironment platform) {
        int size = Math.clamp(setting(platform, "Resolution"), 16, 512);
        return switch (kind) {
            case GGX_MAX_SPECULAR -> new Pass("ggx_max_half", "substrate", GpuFormat.RGBA16_FLOAT, FIXED_RESOLUTION, false, 4);
            case DIFFUSE -> runtime.supportsColorImageStores(GpuFormat.R16_UNORM)
                    ? new Pass("diffuse_unorm16", "substrate", GpuFormat.R16_UNORM, size, false, 1)
                    : new Pass("diffuse_unorm8", "substrate", GpuFormat.R8_UNORM, size, false, 1);
            case GGX_REFLECTION, GGX_TRANSMISSION, SHEEN, SHEEN_LEGACY -> specularPass(kind, platform, size);
            default -> throw new IllegalArgumentException("Shading table is loaded from Engine Content: " + kind);
        };
    }

    private Pass specularPass(Kind kind, NiagaraPlatformEnvironment platform, int size) {
        boolean volume = kind == Kind.GGX_TRANSMISSION;
        String name = switch (kind) {
            case GGX_REFLECTION -> "reflection";
            case GGX_TRANSMISSION -> "transmission";
            default -> "sheen";
        };
        String variant = kind == Kind.SHEEN_LEGACY ? "legacy" : "substrate";
        if (volume) size = FIXED_RESOLUTION;
        GpuFormat format;
        String suffix;
        if (setting(platform, "Format") > 0) {
            format = GpuFormat.RG32_FLOAT;
            suffix = "_float";
        } else if (supportsSpecular(GpuFormat.RG16_UNORM)) {
            format = GpuFormat.RG16_UNORM;
            suffix = "_unorm16";
        } else {
            format = GpuFormat.RG8_UNORM;
            suffix = "_unorm8";
        }
        return new Pass(name + suffix, variant, format, size, volume, volume ? size : 1);
    }

    private boolean supportsSpecular(GpuFormat format) {
        return runtime.supportsColorImageStores(format) && ComputeVolume.supportsGridFormat(format);
    }

    private static int setting(NiagaraPlatformEnvironment platform, String field) {
        return Objects.requireNonNull(platform.console("r.Shading.EnergyConservation." + field)).bits();
    }

    private static byte[] uint(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        try {
            images.values().forEach(Image::close);
        } finally {
            images.clear();
            try { kernels.values().forEach(kernel -> kernel.nativeKernel().close()); }
            finally { kernels.clear(); runtime.close(); }
        }
    }
}
