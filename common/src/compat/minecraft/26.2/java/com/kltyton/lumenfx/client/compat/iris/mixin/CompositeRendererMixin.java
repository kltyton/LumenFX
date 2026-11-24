package com.kltyton.lumenfx.client.compat.iris.mixin;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.kltyton.lumenfx.client.compat.iris.IrisCompat;
import com.kltyton.lumenfx.client.compat.iris.IrisCompositeStageHolder;
import com.kltyton.lumenfx.client.compat.iris.IrisCompositeStagePlanner;
import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.opengl.GlStateManager;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.DepthColorStorage;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.pathways.CenterDepthSampler;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.irisshaders.iris.pipeline.CompositePass;
import net.irisshaders.iris.pipeline.CompositeRenderer;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ComputeSource;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.BufferFlipper;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.vertices.ImmediateState;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Inserts LumenFX deferred translucency stages into Iris composite pass execution without altering DoF passes. */
@Mixin(CompositeRenderer.class)
abstract class CompositeRendererMixin implements IrisCompositeStageHolder {
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private ImmutableList<?> passes;
    @Shadow @Final private CompositePass compositePass;

    @Unique private List<IrisCompositeStagePlanner.PassInput> lumenfx$passInputs = List.of();
    @Unique private boolean lumenfx$colortex0AltAtEnd;
    @Unique private @Nullable ColourEncoding lumenfx$cachedEncoding;
    @Unique private IrisCompositeStagePlanner.@Nullable StagePlan lumenfx$cachedPlan;
    @Unique private @Nullable Object lumenfx$activeSession;

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void lumenfx$capturePasses(
            WorldRenderingPipeline pipeline,
            CompositePass pass,
            PackDirectives packDirectives,
            ProgramSource[] sources,
            ComputeSource[][] computes,
            RenderTargets renderTargets,
            ShaderStorageBufferHolder ssboHolder,
            TextureAccess noiseTexture,
            FrameUpdateNotifier updateNotifier,
            CenterDepthSampler centerDepthSampler,
            BufferFlipper flipped,
            Supplier<ShadowRenderTargets> shadowTargetsSupplier,
            TextureStage textureStage,
            Object2ObjectMap<String, TextureAccess> customTextureIds,
            Object2ObjectMap<String, TextureAccess> irisCustomTextures,
            Set<GlImage> customImages,
            ImmutableMap<Integer, Boolean> explicitFlips,
            CustomUniforms customUniforms,
            CallbackInfo ci
    ) {
        if (pass != CompositePass.COMPOSITE) {
            return;
        }
        this.lumenfx$colortex0AltAtEnd = flipped.snapshot().contains(0);
        this.lumenfx$passInputs = lumenfx$buildPassInputs(sources, computes, this.passes, this.lumenfx$colortex0AltAtEnd);
    }

    @Override
    public IrisCompositeStagePlanner.StagePlan lumenfx$stagePlan(ColourEncoding initialEncoding) {
        if (this.compositePass != CompositePass.COMPOSITE) {
            return IrisCompositeStagePlanner.StagePlan.fallbackImmediate(0, initialEncoding);
        }
        if (this.lumenfx$cachedPlan != null && this.lumenfx$cachedEncoding == initialEncoding) {
            return this.lumenfx$cachedPlan;
        }
        IrisCompositeStagePlanner.StagePlan plan = IrisCompositeStagePlanner.plan(
                this.lumenfx$passInputs, this.lumenfx$colortex0AltAtEnd, initialEncoding);
        this.lumenfx$cachedEncoding = initialEncoding;
        this.lumenfx$cachedPlan = plan;
        return plan;
    }

    @Override
    public void lumenfx$setActiveFrameContext(@Nullable Object frameBase) {
        this.lumenfx$activeSession = frameBase;
    }

    @WrapOperation(
            method = "renderAll",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/ImmutableList;get(I)Ljava/lang/Object;"
            ),
            remap = false
    )
    private Object lumenfx$beforeCompositePass(ImmutableList<?> list, int index, Operation<Object> original) {
        if (this.compositePass == CompositePass.COMPOSITE
                && index > 0
                && this.lumenfx$activeSession != null
                && IrisCompat.requiresCutpoint(this.lumenfx$activeSession, index)) {
            boolean prevIgnorePass = ImmediateState.temporarilyIgnorePass;
            try {
                lumenfx$unbindIrisLoopState();
                ImmediateState.temporarilyIgnorePass = false;
                IrisCompat.onCompositeCutpoint(this.lumenfx$activeSession, this.renderTargets, index);
            } finally {
                ImmediateState.temporarilyIgnorePass = prevIgnorePass;
                lumenfx$restoreIrisLoopState(true);
            }
        }
        return original.call(list, index);
    }

    @Inject(method = "renderAll", at = @At("RETURN"), remap = false)
    private void lumenfx$afterAllCompositePasses(CallbackInfo ci) {
        if (this.compositePass != CompositePass.COMPOSITE || this.lumenfx$activeSession == null) {
            return;
        }
        Object session = this.lumenfx$activeSession;
        this.lumenfx$activeSession = null;
        if (IrisCompat.requiresCutpoint(session, this.passes.size())) {
            boolean prevIgnorePass = ImmediateState.temporarilyIgnorePass;
            try {
                lumenfx$unbindIrisLoopState();
                ImmediateState.temporarilyIgnorePass = false;
                IrisCompat.finishCompositeSession(session, this.renderTargets, this.passes.size());
            } finally {
                ImmediateState.temporarilyIgnorePass = prevIgnorePass;
                lumenfx$restoreIrisLoopState(false);
            }
        }
    }

    @Unique
    private static void lumenfx$unbindIrisLoopState() {
        ProgramUniforms.clearActiveUniforms();
        ProgramSamplers.clearActiveSamplers();
        BlendModeOverride.restore();
        DepthColorStorage.unlockDepthColor();
        GlStateManager._glUseProgram(0);
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
        GlStateManager._bindTexture(0);
    }

    @Unique
    private static void lumenfx$restoreIrisLoopState(boolean rebindFullscreenQuad) {
        BlendModeOverride.restore();
        DepthColorStorage.unlockDepthColor();
        GlStateManager._disableBlend(0);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._disableCull();
        GlStateManager._disableScissorTest();
        GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
        if (rebindFullscreenQuad) {
            FullScreenQuadRenderer.INSTANCE.bind();
        }
        GlStateManager._colorMask(15);
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0);
    }

    @Unique
    private static List<IrisCompositeStagePlanner.PassInput> lumenfx$buildPassInputs(
            ProgramSource[] sources,
            ComputeSource[][] computes,
            ImmutableList<?> builtPasses,
            boolean colortex0AltAtEnd
    ) {
        record Pending(String name, @Nullable String fragment, List<String> computeSources,
                       boolean writes0, int bufferMask, int colortex0Slot, @Nullable Boolean altBefore) { }
        List<Pending> collected = new ArrayList<>(builtPasses.size());
        int passCursor = 0;
        for (int raw = 0; raw < sources.length; raw++) {
            ProgramSource source = sources[raw];
            List<String> computeSources = lumenfx$extractComputeSources(computes, raw);
            if (source != null && source.isValid()) {
                if (passCursor >= builtPasses.size()) {
                    break;
                }
                CompositePassAccessor passObj = (CompositePassAccessor) builtPasses.get(passCursor++);
                int[] drawBuffers = passObj.lumenfx$drawBuffers();
                if (drawBuffers == null) {
                    drawBuffers = source.getDirectives().getDrawBuffers();
                }
                int bufferMask = lumenfx$bufferMask(drawBuffers);
                int colortex0Slot = lumenfx$colortex0Slot(drawBuffers);
                boolean writes0 = (bufferMask & 1) != 0;
                ImmutableSet<Integer> altSet = passObj.lumenfx$stageReadsFromAlt();
                Boolean altBefore = altSet != null ? altSet.contains(0) : null;
                collected.add(new Pending(source.getName(), source.getFragmentSource().orElse(null),
                        computeSources, writes0, bufferMask, colortex0Slot, altBefore));
            } else if (computes.length != 0 && raw < computes.length && computes[raw] != null && computes[raw].length > 0) {
                if (passCursor >= builtPasses.size()) {
                    break;
                }
                CompositePassAccessor passObj = (CompositePassAccessor) builtPasses.get(passCursor++);
                collected.add(new Pending(passObj.lumenfx$name(), null, computeSources, false, 0, -1, null));
            }
        }
        boolean nextAlt = colortex0AltAtEnd;
        IrisCompositeStagePlanner.PassInput[] resolved = new IrisCompositeStagePlanner.PassInput[collected.size()];
        for (int i = collected.size() - 1; i >= 0; i--) {
            Pending item = collected.get(i);
            boolean alt = item.altBefore() != null ? item.altBefore() : nextAlt;
            nextAlt = alt;
            resolved[i] = new IrisCompositeStagePlanner.PassInput(
                    item.name(), item.fragment(), item.computeSources(), item.writes0(), alt,
                    item.bufferMask(), item.colortex0Slot());
        }
        return List.of(resolved);
    }

    @Unique
    private static List<String> lumenfx$extractComputeSources(ComputeSource[][] computes, int index) {
        if (computes.length == 0 || index >= computes.length || computes[index] == null || computes[index].length == 0) {
            return List.of();
        }
        List<String> list = new ArrayList<>(computes[index].length);
        for (ComputeSource cs : computes[index]) {
            if (cs != null) {
                cs.getSource().ifPresent(list::add);
            }
        }
        return list.isEmpty() ? List.of() : List.copyOf(list);
    }

    @Unique
    private static int lumenfx$colortex0Slot(int @Nullable [] drawBuffers) {
        if (drawBuffers == null || drawBuffers.length == 0) {
            return 0;
        }
        for (int i = 0; i < drawBuffers.length; i++) {
            if (drawBuffers[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    @Unique
    private static int lumenfx$bufferMask(int @Nullable [] drawBuffers) {
        if (drawBuffers == null) {
            return 0;
        }
        int mask = 0;
        for (int buffer : drawBuffers) {
            if (buffer >= 0 && buffer < 16) {
                mask |= (1 << buffer);
            }
        }
        return mask;
    }
}
