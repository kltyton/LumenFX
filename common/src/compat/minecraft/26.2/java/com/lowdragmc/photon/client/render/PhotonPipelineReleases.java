/* Photon by KilaBash (2025), CC BY-NC-SA 4.0. See licenses/photon/LICENSE. */
package com.lowdragmc.photon.client.render;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/** Evicts expired material pipelines and retires their unshared shaders behind the device fence. */
final class PhotonPipelineReleases {
    private PhotonPipelineReleases() {}

    static void release(RenderPipeline pipeline) {
        RenderSystem.assertOnRenderThread();
        var device = RenderSystem.tryGetDevice();
        if (device == null) return;
        if (device.backend instanceof GlDevice gl) {
            var compiled = gl.pipelineCache.remove(pipeline);
            if (compiled == null) return;
            var shaders = takeUnused(gl.shaderCache, gl.pipelineCache.keySet(), pipeline,
                    (key, candidate) -> key.defines().equals(candidate.getShaderDefines())
                            && key.id().equals(shader(candidate, key.type())));
            RenderSystem.queueFencedTask(() -> {
                if (compiled.isValid()) compiled.program().close();
                for (var module : shaders) if (module != GlShaderModule.INVALID_SHADER) module.close();
            });
        } else if (device.backend instanceof VulkanDevice vulkan) {
            var compiled = vulkan.pipelineCache.remove(pipeline);
            if (compiled == null) return;
            var shaders = takeUnused(vulkan.shaderCache, vulkan.pipelineCache.keySet(), pipeline,
                    (key, candidate) -> key.defines().equals(candidate.getShaderDefines())
                            && key.id().equals(shader(candidate, key.type())));
            RenderSystem.queueFencedTask(() -> {
                compiled.destroy();
                for (var module : shaders) if (module != IntermediaryShaderModule.INVALID) module.close();
            });
        }
    }

    private static <Key, Module> ArrayList<Module> takeUnused(Map<Key, Module> shaders, Set<RenderPipeline> remaining,
                                                             RenderPipeline retired, BiPredicate<Key, RenderPipeline> uses) {
        var modules = new ArrayList<Module>();
        var entries = shaders.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var key = entry.getKey();
            if (!uses.test(key, retired) || remaining.stream().anyMatch(pipeline -> uses.test(key, pipeline))) continue;
            modules.add(entry.getValue());
            entries.remove();
        }
        return modules;
    }

    private static Identifier shader(RenderPipeline pipeline, ShaderType type) {
        return type == ShaderType.VERTEX ? pipeline.getVertexShader() : pipeline.getFragmentShader();
    }
}
