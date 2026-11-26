package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.kltyton.lumenfx.client.compat.iris.material.IrisMaterialCapture;
import com.kltyton.lumenfx.client.compat.iris.particle.KbrIrisParticleCoverage;
import com.kltyton.lumenfx.client.graphics.RenderPearlCompat;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlBuffer;
import com.mojang.renderpearl.backend.opengl.GlCommandEncoder;
import com.mojang.renderpearl.backend.opengl.GlRenderPass;
import java.nio.IntBuffer;
import net.irisshaders.iris.mixinterface.GlRenderPipelineAccess;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds material MRT storage after Iris setup and restores it at the end of each native draw command. */
@Mixin(GlCommandEncoder.class)
abstract class IrisMaterialDrawMixin {
    @WrapMethod(method = "setupDraw")
    private void lumenfx$materialTargets(GlRenderPass pass, Operation<Void> original) {
        lumenfx$restoreDrawState();
        original.call(pass);
        var pipeline = ((IrisGlRenderPassAccessor) (Object) pass).lumenfx$pipeline();
        if (pipeline != null) {
            var createInfo = ((GlRenderPipelineAccess) (Object) pipeline).getCreateInfo();
            if (createInfo != null) {
                RenderPipeline info = RenderPearlCompat.pipelineFromCreateInfo(createInfo);
                IrisMaterialCapture.beforeDraw(pipeline.program(), info);
                KbrIrisParticleCoverage.beginDraw(pipeline.program().getProgramId(), info);
            }
        }
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
