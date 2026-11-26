package com.kltyton.lumenfx.client.render.scene.capture.mixin;

import com.kltyton.lumenfx.client.graphics.RenderPearlCompat;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.WorldShadowPassScope;
import com.kltyton.lumenfx.client.render.scene.capture.CapturePipelineFormats;
import com.kltyton.lumenfx.client.render.scene.capture.SceneSurfacePassAccess;
import com.kltyton.lumenfx.client.render.scene.capture.RenderPassScenePipelineAccess;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Selects private scene/shadow pipelines and uniform overrides directly on {@link FrontendRenderPass} for both OpenGL and Vulkan. */
@Mixin(FrontendRenderPass.class)
abstract class RenderPassScenePipelineMixin implements SceneSurfacePassAccess, RenderPassScenePipelineAccess {
    @Shadow @Final @Mutable private List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colorAttachments;
    @Shadow private @Nullable FrontendRenderPipeline boundPipeline;
    @Shadow public abstract void setUniform(String name, GpuBufferSlice slice);

    @Unique private @Nullable List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> lumenfx$hostColorAttachments;
    @Unique private @Nullable UnaryOperator<RenderPipeline> lumenfx$pipelines;
    @Unique private Map<String, GpuBufferSlice> lumenfx$uniformOverrides = Map.of();
    @Unique private boolean lumenfx$applyingOverrides;
    @Unique private @Nullable List<Runnable> lumenfx$afterClose;

    @Override
    public void lumenfx$afterClose(Runnable task) {
        if (this.lumenfx$afterClose == null) this.lumenfx$afterClose = new ArrayList<>();
        this.lumenfx$afterClose.add(task);
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void lumenfx$finishDetachedPasses(CallbackInfo ci) {
        var tasks = this.lumenfx$afterClose;
        this.lumenfx$afterClose = null;
        if (tasks != null) tasks.forEach(Runnable::run);
    }

    @Override
    public void lumenfx$configureSceneCapture(@Nullable UnaryOperator<RenderPipeline> pipelines, Map<String, GpuBufferSlice> uniforms) {
        this.lumenfx$pipelines = pipelines;
        this.lumenfx$uniformOverrides = uniforms == null || uniforms.isEmpty() ? Map.of() : Map.copyOf(uniforms);
    }

    @Override
    public void lumenfx$setExpandedColorAttachments(List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> attachments) {
        if (this.lumenfx$hostColorAttachments == null) {
            this.lumenfx$hostColorAttachments = this.colorAttachments;
        }
        this.colorAttachments = attachments;
    }

    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private CompiledRenderPipeline lumenfx$scenePipeline(CompiledRenderPipeline original) {
        RenderPipeline source = RenderPearlCompat.sourceOf(original);
        if (source == null) return original;
        var hostAttachments = this.lumenfx$hostColorAttachments != null ? this.lumenfx$hostColorAttachments : this.colorAttachments;
        RenderPipeline adapted = WorldShadowPassScope.pipeline(source, hostAttachments);
        adapted = CapturePipelineFormats.adapt(adapted, hostAttachments);
        if (this.lumenfx$pipelines != null) {
            adapted = this.lumenfx$pipelines.apply(adapted);
        }
        return adapted == source ? original : RenderPearlCompat.resolvePipeline(adapted);
    }

    @Inject(method = "setPipeline", at = @At("TAIL"))
    private void lumenfx$applyPipelineUniformOverrides(CompiledRenderPipeline pipeline, CallbackInfo ci) {
        if (this.lumenfx$uniformOverrides.isEmpty() || this.boundPipeline == null) return;
        this.lumenfx$applyingOverrides = true;
        try {
            var indices = this.boundPipeline.uniformIndices();
            for (var entry : this.lumenfx$uniformOverrides.entrySet()) {
                if (indices.containsKey(entry.getKey())) {
                    this.setUniform(entry.getKey(), entry.getValue());
                }
            }
        } finally {
            this.lumenfx$applyingOverrides = false;
        }
    }

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;)V", at = @At("HEAD"), cancellable = true)
    private void lumenfx$overrideBufferUniform(String name, GpuBuffer buffer, CallbackInfo ci) {
        if (this.lumenfx$applyingOverrides || this.lumenfx$uniformOverrides.isEmpty()) return;
        GpuBufferSlice override = this.lumenfx$uniformOverrides.get(name);
        if (override != null) {
            ci.cancel();
            this.lumenfx$applyingOverrides = true;
            try { this.setUniform(name, override); }
            finally { this.lumenfx$applyingOverrides = false; }
        }
    }

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V", at = @At("HEAD"), cancellable = true)
    private void lumenfx$overrideSliceUniform(String name, GpuBufferSlice slice, CallbackInfo ci) {
        if (this.lumenfx$applyingOverrides || this.lumenfx$uniformOverrides.isEmpty()) return;
        GpuBufferSlice override = this.lumenfx$uniformOverrides.get(name);
        if (override != null && override != slice) {
            ci.cancel();
            this.lumenfx$applyingOverrides = true;
            try { this.setUniform(name, override); }
            finally { this.lumenfx$applyingOverrides = false; }
        }
    }
}
