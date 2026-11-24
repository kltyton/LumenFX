package com.kltyton.lumenfx.client.render.scene.capture.mixin;

import com.kltyton.lumenfx.client.render.scene.capture.SceneSurfacePassAccess;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies scene-capture pipeline adaptation directly on {@link GlRenderPass} without breaking backend identity. */
@Mixin(GlRenderPass.class)
abstract class GlRenderPassSceneCaptureMixin implements SceneSurfacePassAccess {
    @Shadow @Final protected HashMap<String, GpuBufferSlice> uniforms;
    @Shadow @Final protected Set<String> dirtyUniforms;

    @Unique private @Nullable UnaryOperator<RenderPipeline> lumenfx$pipelines;
    @Unique private Map<String, GpuBufferSlice> lumenfx$uniformOverrides = Map.of();

    @Override
    public void lumenfx$configureSceneCapture(UnaryOperator<RenderPipeline> pipelines, Map<String, GpuBufferSlice> uniforms) {
        this.lumenfx$pipelines = pipelines;
        this.lumenfx$uniformOverrides = uniforms == null || uniforms.isEmpty() ? Map.of() : Map.copyOf(uniforms);
    }

    @ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
    private RenderPipeline lumenfx$adaptPipeline(RenderPipeline pipeline) {
        return this.lumenfx$pipelines != null ? this.lumenfx$pipelines.apply(pipeline) : pipeline;
    }

    @Inject(method = "setPipeline", at = @At("TAIL"))
    private void lumenfx$applyPipelineUniformOverrides(RenderPipeline pipeline, CallbackInfo ci) {
        if (this.lumenfx$uniformOverrides.isEmpty()) return;
        for (var layout : pipeline.getBindGroupLayouts()) {
            for (var uniform : layout.getUniforms()) {
                this.lumenfx$applyUniformOverride(uniform.name());
            }
        }
    }

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBuffer;)V", at = @At("HEAD"), cancellable = true)
    private void lumenfx$overrideBufferUniform(String name, GpuBuffer buffer, CallbackInfo ci) {
        if (this.lumenfx$applyUniformOverride(name)) ci.cancel();
    }

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V", at = @At("HEAD"), cancellable = true)
    private void lumenfx$overrideSliceUniform(String name, GpuBufferSlice slice, CallbackInfo ci) {
        if (this.lumenfx$applyUniformOverride(name)) ci.cancel();
    }

    @Unique
    private boolean lumenfx$applyUniformOverride(String name) {
        if (this.lumenfx$uniformOverrides.isEmpty()) return false;
        GpuBufferSlice override = this.lumenfx$uniformOverrides.get(name);
        if (override == null) return false;
        this.uniforms.put(name, override);
        this.dirtyUniforms.add(name);
        return true;
    }
}
