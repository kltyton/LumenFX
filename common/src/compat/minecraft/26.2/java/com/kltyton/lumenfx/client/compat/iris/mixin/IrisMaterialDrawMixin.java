package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.kltyton.lumenfx.client.compat.iris.material.IrisMaterialCapture;
import com.kltyton.lumenfx.client.compat.iris.particle.KbrIrisParticleCoverage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlRenderPass;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;
import java.nio.IntBuffer;
import java.util.Collection;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds material MRT storage after Iris setup and restores it at the end of each native draw command. */
@Mixin(GlCommandEncoder.class)
abstract class IrisMaterialDrawMixin {
    @WrapMethod(method = "trySetup")
    private boolean lumenfx$materialTargets(GlRenderPass pass, Collection<String> dynamicUniforms, Operation<Boolean> original) {
        lumenfx$restoreDrawState();
        boolean accepted = original.call(pass, dynamicUniforms);
        if (accepted) {
            var pipeline = ((IrisGlRenderPassAccessor)(Object)pass).lumenfx$pipeline();
            IrisMaterialCapture.beforeDraw(pipeline.program(), pipeline.info());
            KbrIrisParticleCoverage.beginDraw(pipeline.program().getProgramId(), pipeline.info());
        }
        return accepted;
    }

    @WrapMethod(method = "executeDraw")
    private void lumenfx$drawScope(GlRenderPass pass, int baseVertex, int firstIndex, int count,
            @Nullable IndexType indexType, int instances, int firstInstance, Operation<Void> original) {
        try { original.call(pass, baseVertex, firstIndex, count, indexType, instances, firstInstance); }
        finally { lumenfx$restoreDrawState(); }
    }

    @WrapMethod(method = "executeDraws")
    private void lumenfx$multipleDrawScope(GlRenderPass pass, @Nullable IndexType indexType,
            @Nullable PointerBuffer offsets, IntBuffer counts, IntBuffer vertices, int count, Operation<Void> original) {
        try { original.call(pass, indexType, offsets, counts, vertices, count); }
        finally { lumenfx$restoreDrawState(); }
    }

    @WrapMethod(method = "executeDrawIndirect")
    private void lumenfx$indirectScope(GlRenderPass pass, @Nullable IndexType indexType, GlBuffer commands,
            long offset, int count, Operation<Void> original) {
        try { original.call(pass, indexType, commands, offset, count); }
        finally { lumenfx$restoreDrawState(); }
    }

    @WrapMethod(method = "executeDrawMultiple")
    private <T> void lumenfx$batchScope(GlRenderPass pass, Collection<RenderPass.Draw<T>> draws,
            @Nullable GpuBuffer indices, @Nullable IndexType indexType, Collection<String> dynamicUniforms,
            T argument, Operation<Void> original) {
        try { original.call(pass, draws, indices, indexType, dynamicUniforms, argument); }
        finally { lumenfx$restoreDrawState(); }
    }

    @WrapMethod(method = "submitRenderPass")
    private void lumenfx$restoreMaterialTargets(Operation<Void> original) {
        lumenfx$restoreDrawState();
        original.call();
    }

    @Unique
    private static void lumenfx$restoreDrawState() {
        KbrIrisParticleCoverage.endDraw();
        IrisMaterialCapture.restoreDrawState();
    }
}
