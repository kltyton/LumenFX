package com.kltyton.lumenfx.client.render.texture.virtual;

import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture.Material;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTextureMaterialPass;
import com.kltyton.lumenfx.api.client.parameter.RuntimeVirtualTexturePageRenderer;
import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.resource.shader.PreparedRuntimeVirtualTextureShaders;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jspecify.annotations.Nullable;

/** Runs the source compression pass over a page raster and transfers its actual storage words. */
public final class RuntimeVirtualTexturePrograms implements AutoCloseable {
    private record Stage(ComputeKernel kernel, NiagaraGpuComputeStage dispatch) { }
    private static final byte[] PAGE_ORIGINS = new byte[8 * Integer.BYTES];
    private final PreparedRuntimeVirtualTextureShaders prepared;
    private final Map<Material, Stage> stages = new EnumMap<>(Material.class);
    private final RuntimeVirtualTextureCopies copies = new RuntimeVirtualTextureCopies();
    private @Nullable ComputeRuntime runtime;

    public RuntimeVirtualTexturePrograms(PreparedRuntimeVirtualTextureShaders prepared) {
        this.prepared = Objects.requireNonNull(prepared);
    }

    public RuntimeVirtualTexturePageRenderer renderer(RuntimeVirtualTextureMaterialPass material) {
        Objects.requireNonNull(material);
        return new RuntimeVirtualTexturePageRenderer() {
            @Override public boolean ready(ClientLevel level) { return material.ready(level); }
            @Override public CompletableFuture<Page> render(ClientLevel level, Request request) {
                var raster = material.draw(level, request);
                try {
                    return encode(request, raster.targets(), raster.nativeMrt()).whenComplete((page, failure) -> raster.close());
                } catch (RuntimeException | Error failure) {
                    RenderSystem.queueFencedTask(raster::close);
                    throw failure;
                }
            }
        };
    }

    private CompletableFuture<RuntimeVirtualTexturePageRenderer.Page> encode(RuntimeVirtualTexturePageRenderer.Request request,
                                                                             List<GpuTextureView> targets,
                                                                             boolean nativeMrt) {
        RenderSystem.assertOnRenderThread();
        int size = request.view().pixelSize();
        for (var target : targets) {
            int mip = target.baseMipLevel();
            if (target.isClosed() || target.texture().isClosed() || target.texture().getDepthOrLayers() != 1
                    || target.texture().getWidth(mip) != size || target.texture().getHeight(mip) != size)
                throw new IllegalArgumentException("Runtime virtual texture material raster does not match the page");
        }
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var first = request.layers().getFirst().format();
        return switch (first) {
            case BC1, BC3, BC4, BC5 -> compress(encoder, request, targets, size);
            case BGRA8, RGB565, G16 -> {
                if (!nativeMrt) yield copy(encoder, request, targets);
                var packed = copies.pack(encoder, request, targets);
                try { yield copy(encoder, request, packed.targets()).whenComplete((page, failure) -> packed.close()); }
                catch (RuntimeException | Error failure) {
                    RenderSystem.queueFencedTask(packed::close);
                    throw failure;
                }
            }
        };
    }

    private CompletableFuture<RuntimeVirtualTexturePageRenderer.Page> compress(CommandEncoder encoder,
            RuntimeVirtualTexturePageRenderer.Request request, List<GpuTextureView> targets, int size) {
        if ((size & 3) != 0) throw new IllegalArgumentException("Runtime virtual texture BC pages must be four-texel aligned");
        int blocks = size / 4;
        var stage = stage(request.definition().material());
        var bindings = ComputeBindings.builder();
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        var images = new ComputeTexture[request.layers().size()];
        try {
            for (var binding : stage.shader().bindings()) {
                if (binding instanceof ShaderBinding.SampledTexture input) {
                    int index = layerIndex(input.name(), "RenderTexture");
                    var texture = targets.get(index);
                    if (input.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE)
                        bindings.sampledImage(input.name(), texture);
                    else bindings.texture(input.name(), texture, sampler);
                } else if (binding instanceof ShaderBinding.StorageImage output) {
                    int index = layerIndex(output.name(), "OutCompressTexture");
                    boolean eightBytes = switch (request.layers().get(index).format()) {
                        case BC1, BC4 -> true;
                        case BC3, BC5 -> false;
                        default -> throw new IllegalArgumentException("Expected a BC layer " + index);
                    };
                    GpuFormat expected = eightBytes ? GpuFormat.RG32_UINT : GpuFormat.RGBA32_UINT;
                    if (output.format() != expected || images[index] != null)
                        throw new IllegalArgumentException("Incompatible RVT block descriptor " + output.name());
                    var image = runtime().createTexture("LumenFX RVT packed layer " + index, expected, blocks, blocks);
                    images[index] = image;
                    bindings.image(output.name(), image, output.access());
                }
            }
            if (Arrays.asList(images).contains(null)) throw new IllegalArgumentException("RVT compression omits a material layer");
            byte[] sourceRect = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(0).putInt(0).putInt(size).putInt(size).array();
            stage.dispatch(encoder, bindings, qualified -> switch (qualified.substring(qualified.lastIndexOf('.') + 1)) {
                case "SourceRect" -> sourceRect;
                case "DestPos" -> PAGE_ORIGINS;
                default -> null;
            }, blocks, blocks, 1);
            List<CompletableFuture<byte[]>> layers = new ArrayList<>(images.length);
            for (var image : images) layers.add(RuntimeVirtualTextureReadback.copy(encoder, image.texture(), 0, true));
            return collect(layers);
        } finally {
            for (var image : images) if (image != null) RenderSystem.queueFencedTask(image::close);
        }
    }

    private static int layerIndex(String name, String prefix) {
        if (!name.startsWith(prefix) || name.length() == prefix.length())
            throw new IllegalArgumentException("Unexpected virtual texture shader descriptor " + name);
        int result = name.charAt(prefix.length()) - '0';
        return Objects.checkIndex(result, 3);
    }

    private static CompletableFuture<RuntimeVirtualTexturePageRenderer.Page> copy(CommandEncoder encoder,
            RuntimeVirtualTexturePageRenderer.Request request, List<GpuTextureView> targets) {
        if (targets.size() != request.layers().size()) throw new IllegalArgumentException("RVT copy requires each final material layer");
        List<CompletableFuture<byte[]>> layers = new ArrayList<>(targets.size());
        for (int index = 0; index < targets.size(); index++) {
            var target = targets.get(index);
            var format = request.layers().get(index).format();
            GpuFormat expected = switch (format) {
                case BGRA8 -> GpuFormat.RGBA8_UNORM;
                case G16 -> GpuFormat.R16_UNORM;
                case RGB565 -> GpuFormat.R16_UINT;
                default -> throw new IllegalArgumentException("Expected an uncompressed RVT layer");
            };
            if (target.texture().getFormat() != expected) throw new IllegalArgumentException("RVT material has incompatible uncompressed storage");
            var bytes = RuntimeVirtualTextureReadback.copy(encoder, target.texture(), target.baseMipLevel(), false);
            layers.add(format == RuntimeVirtualTexturePageRenderer.Format.BGRA8 ? bytes.thenApply(RuntimeVirtualTexturePrograms::bgra) : bytes);
        }
        return collect(layers);
    }

    private static byte[] bgra(byte[] bytes) {
        for (int index = 0; index < bytes.length; index += 4) {
            byte red = bytes[index];
            bytes[index] = bytes[index + 2];
            bytes[index + 2] = red;
        }
        return bytes;
    }

    private static CompletableFuture<RuntimeVirtualTexturePageRenderer.Page> collect(List<CompletableFuture<byte[]>> layers) {
        return CompletableFuture.allOf(layers.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> new RuntimeVirtualTexturePageRenderer.Page(layers.stream().map(CompletableFuture::join).toList()));
    }

    private ComputeRuntime runtime() {
        if (runtime == null) runtime = ComputeRuntime.create(RenderSystem.getDevice());
        return runtime;
    }

    private NiagaraGpuComputeStage stage(Material material) {
        return stages.computeIfAbsent(material, key -> {
            var source = Objects.requireNonNull(prepared.stages().get(key), "Missing runtime virtual texture BC kernel " + key);
            var kernel = runtime().createKernel(source.shader());
            try { return new Stage(kernel, new NiagaraGpuComputeStage(source, kernel)); }
            catch (RuntimeException | Error failure) { kernel.close(); throw failure; }
        }).dispatch();
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        stages.values().forEach(stage -> stage.kernel().close());
        stages.clear();
        copies.clear();
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
