package com.kltyton.lumenfx.client.compat.iris.material;

import com.kltyton.lumenfx.client.VfxEngine;
import com.kltyton.lumenfx.client.compat.iris.mixin.IrisExtendedShaderAccessor;
import com.kltyton.lumenfx.client.compat.iris.mixin.IrisRenderingPipelineAccessor;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.HashMap;
import java.util.Map;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.joml.Vector4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

/** Adds private MRT attachments to the real Iris draw without replaying its shader or callbacks. */
public final class IrisMaterialCapture {
    private record Key(GlFramebuffer source, int firstOutput, int auxiliaryOutputs) { }
    private record DrawState(int framebuffer, int firstOutput, int[] masks, boolean[] blends) { }
    private static final Map<Key, GlFramebuffer> FRAMEBUFFERS = new HashMap<>();
    private static final IrisDecalReceiverTargets DECAL_RECEIVERS = new IrisDecalReceiverTargets();
    private static TextureTarget baseDepth, geometry, material, lightingParameters, diffuseResponse;
    private static IrisRenderingPipeline owner;
    private static DrawState drawState;
    private static boolean active, written, lightingWritten, captureDiffuse, diffuseWritten;
    private static String specularSource;

    private IrisMaterialCapture() { }

    public static void begin(IrisRenderingPipeline pipeline) {
        RenderSystem.assertOnRenderThread();
        restoreDrawState(); active = false; written = false; lightingWritten = false; diffuseWritten = false; specularSource = null;
        if (!VfxEngine.INSTANCE.hasActiveSceneRenderers() && !VfxEngine.INSTANCE.hasActiveGBufferReaders()) return;
        boolean needsDiffuse = VfxEngine.INSTANCE.hasActiveDecalRenderers();
        var targets = ((IrisRenderingPipelineAccessor)(Object)pipeline).lumenfx$renderTargets();
        int width = targets.getCurrentWidth(), height = targets.getCurrentHeight();
        if (owner != pipeline || baseDepth == null || baseDepth.width != width || baseDepth.height != height) {
            close(); owner = pipeline;
            try {
                baseDepth = new TextureTarget("LumenFX Iris base and depth", width, height, false, GpuFormat.RGBA32_FLOAT);
                geometry = new TextureTarget("LumenFX Iris view-XY and normal", width, height, false, GpuFormat.RGBA32_FLOAT);
                material = new TextureTarget("LumenFX Iris material sample", width, height, false, GpuFormat.RGBA32_FLOAT);
                lightingParameters = new TextureTarget("LumenFX Iris resolved lighting inputs", width, height, false, GpuFormat.RGBA32_FLOAT);
            } catch (RuntimeException | Error error) { close(); throw error; }
        }
        captureDiffuse = needsDiffuse;
        if (captureDiffuse && diffuseResponse == null)
            diffuseResponse = new TextureTarget("LumenFX Iris diffuse material response", width, height, false, GpuFormat.RGBA16_FLOAT);
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var clear = new Vector4f();
        encoder.clearColorTexture(baseDepth.getColorTexture(), new Vector4f(0, 0, 0, -1));
        encoder.clearColorTexture(geometry.getColorTexture(), clear);
        encoder.clearColorTexture(material.getColorTexture(), clear);
        encoder.clearColorTexture(lightingParameters.getColorTexture(), clear);
        if (captureDiffuse) encoder.clearColorTexture(diffuseResponse.getColorTexture(), clear);
        active = true;
    }

    public static void beforeDraw(GlProgram program, RenderPipeline pipeline) {
        if (!active || net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered()
                || !(program instanceof ExtendedShader shader)) return;
        var layout = IrisMaterialPrograms.layout(program.getProgramId());
        var depth = pipeline.getDepthStencilState();
        if (layout == null || depth == null || !depth.writeDepth() || !GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK)
                || GL30C.glIsEnabledi(GL11C.GL_BLEND, 0)) return;
        var access = (IrisExtendedShaderAccessor)(Object)shader;
        if (access.lumenfx$parent() != owner) return;
        try (var stack = MemoryStack.stackPush()) {
            var primaryMask = stack.malloc(4);
            GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, 0, primaryMask);
            if (primaryMask.get(0) == 0 && primaryMask.get(1) == 0 && primaryMask.get(2) == 0) return;
        }
        if (layout.auxiliaryOutputs() == 0) {
            return;
        }
        int first = layout.firstOutput(), auxiliary = layout.auxiliaryOutputs();
        if (auxiliary == 5 && !captureDiffuse) auxiliary = 4;
        GlFramebuffer source = owner.isBeforeTranslucent ? access.lumenfx$beforeTranslucent() : access.lumenfx$afterTranslucent();
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int[] masks = new int[auxiliary]; boolean[] blends = new boolean[auxiliary];
        try (var stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            for (int index = 0; index < auxiliary; index++) {
                GL30C.glGetBooleani_v(GL11C.GL_COLOR_WRITEMASK, first + index, mask);
                for (int channel = 0; channel < 4; channel++) if (mask.get(channel) != 0) masks[index] |= 1 << channel;
                blends[index] = GL30C.glIsEnabledi(GL11C.GL_BLEND, first + index);
            }
        }
        drawState = new DrawState(previousDraw, first, masks, blends);
        try {
            var framebuffer = FRAMEBUFFERS.computeIfAbsent(new Key(source, first, auxiliary), IrisMaterialCapture::framebuffer);
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            framebuffer.bindAsDrawBuffer();
            for (int index = 0; index < auxiliary; index++) {
                GlStateManager._disableBlend(first + index);
                GlStateManager._colorMask(first + index, 15);
            }
            written = true;
            lightingWritten |= auxiliary >= 4;
            diffuseWritten |= auxiliary == 5;
            if (auxiliary >= 4 && specularSource == null) specularSource = IrisMaterialPrograms.specularSource(program.getProgramId());
        } catch (RuntimeException | Error error) {
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
            restoreDrawState(); throw error;
        }
    }

    private static GlFramebuffer framebuffer(Key key) {
        var result = new GlFramebuffer();
        try {
            int[] outputs = new int[key.firstOutput() + key.auxiliaryOutputs()];
            for (int index = 0; index < key.firstOutput(); index++) {
                result.addColorAttachment(index, key.source().getColorAttachment(index));
                outputs[index] = index;
            }
            TextureTarget[] surfaces = {baseDepth, geometry, material, lightingParameters, diffuseResponse};
            for (int index = 0; index < key.auxiliaryOutputs(); index++) {
                int attachment = key.firstOutput() + index;
                result.addColorAttachment(attachment, ((GlTexture)surfaces[index].getColorTexture()).glId());
                outputs[attachment] = attachment;
            }
            var targets = ((IrisRenderingPipelineAccessor)(Object)owner).lumenfx$renderTargets();
            result.addDepthAttachment(targets.getDepthTexture()); result.drawBuffers(outputs);
            int status = result.getStatus();
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("Iris material framebuffer is incomplete: " + status);
            return result;
        } catch (RuntimeException | Error error) { result.destroy(); throw error; }
    }

    public static void restoreDrawState() {
        DrawState state = drawState;
        if (state == null) return;
        drawState = null;
        for (int index = 0; index < state.masks().length; index++) {
            GlStateManager._colorMask(state.firstOutput() + index, state.masks()[index]);
            if (state.blends()[index]) GlStateManager._enableBlend(state.firstOutput() + index);
            else GlStateManager._disableBlend(state.firstOutput() + index);
        }
        GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, state.framebuffer());
    }

    public static void finish() { restoreDrawState(); active = false; }

    public static void resolveProjectedDecalMaterials(net.irisshaders.iris.targets.RenderTargets targets,
            java.util.Set<Integer> flipped, ShaderpackMaterialSurfaces surfaces,
            com.mojang.blaze3d.textures.GpuTextureView depth,
            com.kltyton.lumenfx.client.render.decal.DecalBuffers.Frame decals) {
        DECAL_RECEIVERS.resolve(targets, flipped, surfaces, depth, decals);
    }

    public static @Nullable ShaderpackMaterialSurfaces surfaces(ShaderpackMaterialSurfaces.RawSpecularEncoding encoding) {
        return surfaces(encoding, null);
    }

    public static @Nullable ShaderpackMaterialSurfaces surfaces(ShaderpackMaterialSurfaces.RawSpecularEncoding encoding,
                                                               @Nullable Matrix4fc materialProjection) {
        if (!written || baseDepth == null) return null;
        return new ShaderpackMaterialSurfaces(baseDepth.getColorTextureView(), geometry.getColorTextureView(),
                material.getColorTextureView(), lightingWritten ? lightingParameters.getColorTextureView() : null,
                encoding, baseDepth.width, baseDepth.height, materialProjection, specularSource, null,
                diffuseWritten ? diffuseResponse.getColorTextureView() : null);
    }

    public static void release(IrisRenderingPipeline pipeline) { if (owner == pipeline) close(); }

    public static void close() {
        restoreDrawState(); active = false; written = false; lightingWritten = false; diffuseWritten = false; specularSource = null;
        FRAMEBUFFERS.values().forEach(GlFramebuffer::destroy); FRAMEBUFFERS.clear();
        if (baseDepth != null) baseDepth.destroyBuffers();
        if (geometry != null) geometry.destroyBuffers();
        if (material != null) material.destroyBuffers();
        if (lightingParameters != null) lightingParameters.destroyBuffers();
        if (diffuseResponse != null) diffuseResponse.destroyBuffers();
        DECAL_RECEIVERS.close();
        baseDepth = geometry = material = lightingParameters = diffuseResponse = null; owner = null;
    }
}
