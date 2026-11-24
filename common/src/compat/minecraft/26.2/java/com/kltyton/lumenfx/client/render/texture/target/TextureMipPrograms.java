package com.kltyton.lumenfx.client.render.texture.target;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeKernel;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuComputeStage;
import com.kltyton.lumenfx.client.resource.shader.PreparedTextureMipShaders;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Targets retain their generation's lazily created mip kernels until their images retire. */
public final class TextureMipPrograms implements AutoCloseable {
    private record Stage(ComputeKernel kernel, NiagaraGpuComputeStage dispatch) { }
    private final PreparedTextureMipShaders prepared;
    private final Map<String, Stage> stages = new LinkedHashMap<>();
    private @Nullable ComputeRuntime runtime;
    private int references = 1;
    private boolean ownerReleased;

    public TextureMipPrograms(PreparedTextureMipShaders prepared) { this.prepared = Objects.requireNonNull(prepared); }

    public TextureMipPrograms retain() {
        RenderSystem.assertOnRenderThread();
        if (ownerReleased || references == 0) throw new IllegalStateException("Texture mip generation has retired");
        references = Math.incrementExact(references);
        return this;
    }

    public ComputeRuntime runtime() {
        RenderSystem.assertOnRenderThread();
        if (references == 0) throw new IllegalStateException("Texture mip generation has been released");
        if (runtime == null) runtime = ComputeRuntime.create(RenderSystem.getDevice());
        return runtime;
    }

    public boolean supports(GpuFormat format) { return runtime().supportsColorImageStores(format); }

    public void generate(CommandEncoder encoder, ComputeTexture texture, GpuSampler sampler) {
        RenderSystem.assertOnRenderThread();
        if (texture.mips() == 1) return;
        NiagaraGpuComputeStage shader = stage(texture.srgb() ? "srgb" : "linear");
        for (int mip = 1; mip < texture.mips(); mip++) {
            final int level = mip;
            var bindings = ComputeBindings.builder();
            for (var binding : shader.shader().bindings()) {
                if (binding instanceof ShaderBinding.SampledTexture input) {
                    if (!input.name().equals("MipInSRV") && !input.name().startsWith("MipInSRV_"))
                        throw new IllegalArgumentException("Unexpected render-target mip input " + input.name());
                    if (input.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE)
                        bindings.sampledImage(input.name(), texture, mip - 1, 0, 1, false);
                    else bindings.texture(input.name(), texture, mip - 1, 0, 1, false, sampler);
                } else if (binding instanceof ShaderBinding.StorageImage output) {
                    if (!output.name().equals("MipOutUAV")) throw new IllegalArgumentException("Unexpected render-target mip output " + output.name());
                    bindings.image(output.name(), texture, output.access(), mip, 0, 1);
                }
            }
            shader.dispatch(encoder, bindings, qualified -> {
                String name = qualified.substring(qualified.lastIndexOf('.') + 1);
                return switch (name) {
                    case "TexelSize" -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                            .putFloat(1.0f / texture.width(level)).putFloat(1.0f / texture.height(level)).array();
                    case "TextureSize" -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(texture.width(level)).putInt(texture.height(level)).array();
                    case "Offset" -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array();
                    case "NumMips" -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(1).array();
                    default -> null;
                };
            }, texture.width(mip), texture.height(mip), 1);
        }
    }

    private NiagaraGpuComputeStage stage(String name) {
        Stage existing = stages.get(name);
        if (existing != null) return existing.dispatch();
        var definition = Objects.requireNonNull(prepared.stages().get(name), "Missing texture mip variant " + name);
        ComputeKernel kernel = runtime().createKernel(definition.shader());
        try {
            Stage loaded = new Stage(kernel, new NiagaraGpuComputeStage(definition, kernel));
            stages.put(name, loaded);
            return loaded.dispatch();
        } catch (RuntimeException | Error failure) { kernel.close(); throw failure; }
    }

    public void releaseTarget() {
        RenderSystem.assertOnRenderThread();
        if (references <= 0) throw new IllegalStateException("Texture mip reference underflow");
        if (--references != 0) return;
        stages.values().forEach(stage -> stage.kernel().close()); stages.clear();
        if (runtime != null) { runtime.close(); runtime = null; }
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (ownerReleased) return;
        ownerReleased = true;
        releaseTarget();
    }
}
