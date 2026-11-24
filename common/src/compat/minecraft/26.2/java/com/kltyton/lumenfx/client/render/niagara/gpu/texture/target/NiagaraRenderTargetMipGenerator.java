package com.kltyton.lumenfx.client.render.niagara.gpu.texture.target;

import com.kltyton.lumenfx.client.render.compute.ComputeBindings;
import com.kltyton.lumenfx.client.render.compute.ComputeTexture;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Dispatches the original Niagara mip shader over consecutive subresources of one image. */
public final class NiagaraRenderTargetMipGenerator {
    private NiagaraRenderTargetMipGenerator() { }

    public static void generate(NiagaraGpuPrograms programs, CommandEncoder encoder, ComputeTexture texture, String type) {
        generate(programs, encoder, texture, type, texture.srgb());
    }

    public static void generate(NiagaraGpuPrograms programs, CommandEncoder encoder, ComputeTexture texture, String type, boolean srgb) {
        RenderSystem.assertOnRenderThread();
        if (texture.mips() == 1) return;
        int halfWidth = switch (type) {
            case "Unfiltered", "Linear" -> 1;
            case "Blur1" -> 1; case "Blur2" -> 2; case "Blur3" -> 3; case "Blur4" -> 4;
            default -> throw new IllegalArgumentException("Unknown Niagara mip generation type " + type);
        };
        var stage = programs.targetMipStage(type.startsWith("Blur"), srgb);
        var sampler = programs.targetMipSampler(type.equals("Unfiltered"));
        {
            for (int mip = 1; mip < texture.mips(); mip++) {
                final int level = mip;
                ComputeBindings.Builder bindings = ComputeBindings.builder();
                for (var binding : stage.shader().bindings()) {
                    if (binding instanceof ShaderBinding.SampledTexture sampled) {
                        if (!sampled.name().equals("MipInSRV") && !sampled.name().startsWith("MipInSRV_"))
                            throw new IllegalArgumentException("Unexpected Niagara mip input " + sampled.name());
                        if (sampled.form() == ShaderBinding.DescriptorForm.SAMPLED_IMAGE)
                            bindings.sampledImage(sampled.name(), texture, mip - 1, 0, 1, false);
                        else bindings.texture(sampled.name(), texture, mip - 1, 0, 1, false, sampler);
                    } else if (binding instanceof ShaderBinding.StorageImage output) {
                        if (!output.name().equals("MipOutUAV"))
                            throw new IllegalArgumentException("Unexpected Niagara mip output " + output.name());
                        bindings.image(output.name(), texture, output.access(), mip, 0, 1);
                    }
                }
                stage.dispatch(encoder, bindings, qualified -> {
                    String name = qualified.substring(qualified.lastIndexOf('.') + 1);
                    return switch (name) {
                        case "SrcTexelSize" -> floats(1.0f / texture.width(level - 1), 1.0f / texture.height(level - 1));
                        case "DstTexelSize" -> floats(1.0f / texture.width(level), 1.0f / texture.height(level));
                        case "MipOutSize" -> integers(texture.width(level), texture.height(level));
                        case "KernelHWidth" -> integers(halfWidth);
                        default -> null;
                    };
                }, texture.width(mip), texture.height(mip), 1);
            }
        }
    }

    private static byte[] floats(float... values) {
        ByteBuffer result = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : values) result.putFloat(value);
        return result.array();
    }
    private static byte[] integers(int... values) {
        ByteBuffer result = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) result.putInt(value);
        return result.array();
    }
}
