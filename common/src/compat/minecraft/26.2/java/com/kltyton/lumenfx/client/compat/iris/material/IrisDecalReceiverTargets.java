package com.kltyton.lumenfx.client.compat.iris.material;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.kltyton.lumenfx.client.render.decal.DecalBuffers;
import com.kltyton.lumenfx.client.render.hdr.external.GlSceneTransfer;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Optional;
import java.util.Set;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.vertices.ImmediateState;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL43C;

import static com.kltyton.lumenfx.effect.render.decal.DecalMaterialSettings.MATERIAL;
import static com.kltyton.lumenfx.effect.render.decal.DecalMaterialSettings.NORMAL;

/** Resolves decal attributes into the active Complementary deferred terrain inputs before Iris uses them. */
public final class IrisDecalReceiverTargets implements AutoCloseable {
    private static final BindGroupLayout BINDINGS = BindGroupLayout.builder()
            .withSampler("LfxOriginal").withSampler("LfxDecal").withSampler("LfxCapturedDepth")
            .withSampler("LfxCurrentDepth").withSampler("LfxReceiverKind").build();
    private final GlSceneTransfer rgbTransfer = new GlSceneTransfer();
    private final TargetPair material = new TargetPair(GpuFormat.RGBA8_UNORM, false);
    private final TargetPair normal = new TargetPair(GpuFormat.RGBA8_SNORM, true);

    public void resolve(RenderTargets targets, Set<Integer> flipped, ShaderpackMaterialSurfaces surfaces,
                        GpuTextureView depth, DecalBuffers.Frame decals) {
        var receiver = surfaces.diffuseResponse();
        if (receiver == null) return;
        var materialTarget = (decals.outputs() & MATERIAL) == 0 ? null : targets.get(6);
        var normalTarget = (decals.outputs() & NORMAL) == 0 ? null : targets.get(4);
        if (matches(materialTarget, InternalTextureFormat.RGB8, decals)) {
            material.resolve(materialTarget, flipped.contains(6), surfaces.baseDepth(), depth,
                    receiver, decals.material(), rgbTransfer);
        }
        if (matches(normalTarget, InternalTextureFormat.RGBA8_SNORM, decals)) {
            normal.resolve(normalTarget, flipped.contains(4), surfaces.baseDepth(), depth, receiver, decals.normal(), null);
        }
    }

    private static boolean matches(@Nullable RenderTarget target, InternalTextureFormat format, DecalBuffers.Frame decals) {
        return target != null && target.getInternalFormat() == format
                && target.getWidth() == decals.width() && target.getHeight() == decals.height();
    }

    private static RenderPipeline pipeline(GpuFormat format, boolean normal) {
        return PhotonFullscreenPass.builder(LumenFx.id("post/iris_projected_material"))
                .withLocation(LumenFx.id(normal ? "pipeline/iris_projected_normal" : "pipeline/iris_projected_roughness"))
                .withShaderDefine("LFX_PROJECT_NORMAL", normal ? 1 : 0)
                .withBindGroupLayout(BINDINGS)
                .withColorTargetState(new ColorTargetState(Optional.empty(), format, 15))
                .build();
    }

    @Override public void close() {
        material.close();
        normal.close();
        rgbTransfer.close();
    }

    private static final class TargetPair implements AutoCloseable {
        private final GpuFormat format;
        private final boolean normal;
        private @Nullable OwnedColorTarget source, resolved;
        private @Nullable RenderPipeline pipeline;

        private TargetPair(GpuFormat format, boolean normal) {
            this.format = format;
            this.normal = normal;
        }

        private void resolve(RenderTarget target, boolean alt, GpuTextureView capturedDepth,
                             GpuTextureView currentDepth, GpuTextureView receiver, GpuTextureView decal,
                             @Nullable GlSceneTransfer transfer) {
            int width = target.getWidth(), height = target.getHeight();
            ensureTargets(width, height);
            if (pipeline == null) pipeline = pipeline(format, normal);
            int texture = alt ? target.getAltTexture() : target.getMainTexture();
            if (transfer != null) {
                // Blaze3D doesn't allocate RGB8. Its RGBA8 attachment preserves the same three unorm channels.
                transfer.importColor(texture, width, height, source.getColorTexture());
            } else {
                copy(texture, ((GlTexture) source.getColorTexture()).glId(), width, height);
                copy(texture, ((GlTexture) resolved.getColorTexture()).glId(), width, height);
            }
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            boolean previousBypass = ImmediateState.bypass;
            ImmediateState.bypass = true;
            try {
                PhotonFullscreenPass.draw("LumenFX projected Iris material", pipeline, resolved.getColorTextureView(), pass -> {
                    pass.bindTexture("LfxOriginal", source.getColorTextureView(), sampler);
                    pass.bindTexture("LfxDecal", decal, sampler);
                    pass.bindTexture("LfxCapturedDepth", capturedDepth, sampler);
                    pass.bindTexture("LfxCurrentDepth", currentDepth, sampler);
                    pass.bindTexture("LfxReceiverKind", receiver, sampler);
                });
            } finally {
                ImmediateState.bypass = previousBypass;
            }
            if (transfer != null) transfer.exportColor(resolved.getColorTexture(), texture, width, height);
            else copy(((GlTexture) resolved.getColorTexture()).glId(), texture, width, height);
        }

        private void ensureTargets(int width, int height) {
            if (source != null && source.width == width && source.height == height) return;
            close();
            try {
                source = new OwnedColorTarget("LumenFX original Iris receiver", width, height, format);
                resolved = new OwnedColorTarget("LumenFX resolved Iris receiver", width, height, format);
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private static void copy(int source, int target, int width, int height) {
            GL43C.glCopyImageSubData(source, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0,
                    target, GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, width, height, 1);
        }

        @Override public void close() {
            if (source != null) source.close();
            if (resolved != null) resolved.close();
            source = resolved = null;
            pipeline = null;
        }
    }
}
