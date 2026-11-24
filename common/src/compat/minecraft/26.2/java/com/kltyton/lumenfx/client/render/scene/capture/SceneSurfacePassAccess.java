package com.kltyton.lumenfx.client.render.scene.capture;

import com.kltyton.lumenfx.LumenFx;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.Map;
import java.util.function.UnaryOperator;
import net.minecraft.resources.Identifier;

/** Configures scene-capture pipeline adaptation directly on a native render-pass backend. */
public interface SceneSurfacePassAccess {
    void lumenfx$configureSceneCapture(UnaryOperator<RenderPipeline> pipelines, Map<String, GpuBufferSlice> uniforms);

    /**
     * Builds a derived pipeline identifier that preserves Sodium's {@code "sodium"} namespace marker
     * so Sodium's Vulkan pipeline layout mixin continues attaching its chunk push-constant range.
     */
    static Identifier derivedPipelineLocation(RenderPipeline host, String path) {
        if (host != null && host.getLocation().getNamespace().contains("sodium")) {
            return Identifier.fromNamespaceAndPath("lumenfx_sodium", path);
        }
        return LumenFx.id(path);
    }
}
