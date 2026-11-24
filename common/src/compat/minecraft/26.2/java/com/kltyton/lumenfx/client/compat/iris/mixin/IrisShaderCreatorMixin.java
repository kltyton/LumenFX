package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.kltyton.lumenfx.client.compat.iris.motion.IrisShaderSources;
import com.kltyton.lumenfx.client.compat.iris.material.IrisMaterialPrograms;
import com.kltyton.lumenfx.client.compat.iris.particle.KbrIrisParticleCoverage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.google.common.collect.ImmutableSet;
import java.util.function.Supplier;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import net.irisshaders.iris.pipeline.programs.ShaderSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderCreator.class, remap = false)
abstract class IrisShaderCreatorMixin {
    @WrapMethod(method = "create")
    private static ShaderSupplier lumenfx$sourceMaterialScope(WorldRenderingPipeline pipeline, String name,
            ShaderKey shaderKey, ProgramSource source, ProgramId programId,
            GlFramebuffer beforeTranslucent, GlFramebuffer afterTranslucent, AlphaTest alpha,
            VertexFormat format, ShaderAttributeInputs inputs, FrameUpdateNotifier notifier,
            IrisRenderingPipeline parent, Supplier<ImmutableSet<Integer>> flipped, FogMode fog,
            boolean intensity, boolean fullbright, boolean shadow, boolean lines,
            CustomUniforms uniforms, Patch patch, Operation<ShaderSupplier> original) {
        boolean previous = IrisMaterialPrograms.enterSourceCreation(!shadow);
        try {
            return original.call(pipeline, name, shaderKey, source, programId, beforeTranslucent, afterTranslucent,
                    alpha, format, inputs, notifier, parent, flipped, fog, intensity, fullbright, shadow, lines, uniforms, patch);
        } finally { IrisMaterialPrograms.restoreSourceCreation(previous); }
    }

    @Inject(method = {"create", "createFallback", "createShadow", "createFallbackShadow"},
            at = @At("RETURN"), remap = false)
    private static void lumenfx$recordSupplier(CallbackInfoReturnable<ShaderSupplier> callback) {
        ShaderSupplier result = callback.getReturnValue();
        IrisShaderSources.registerSupplier(result.id().program(), result);
    }

    @WrapMethod(method = "link")
    private static PartialShader lumenfx$linkMaterialInputs(
            String name, String vertex, String geometry, String tessControl, String tessEval,
            String fragment, VertexFormat vertexFormat, boolean isFallback,
            Operation<PartialShader> original) {
        var coverage = KbrIrisParticleCoverage.prepare(name, vertex, fragment,
                geometry != null || tessControl != null || tessEval != null);
        String drawFragment = coverage == null ? fragment : coverage.fragment();
        var material = isFallback ? null : IrisMaterialPrograms.prepare(drawFragment);
        PartialShader shader = original.call(name, coverage == null ? vertex : coverage.vertex(),
                geometry, tessControl, tessEval, material == null ? drawFragment : material.fragment(),
                vertexFormat, isFallback);
        if (coverage != null) KbrIrisParticleCoverage.registerProgram(shader.program());
        IrisShaderSources.register(shader.program(), vertex, geometry, tessControl, tessEval,
                fragment, vertexFormat, isFallback);
        IrisMaterialPrograms.register(shader.program(), material);
        return shader;
    }
}
