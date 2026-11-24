package com.kltyton.lumenfx.client.render.niagara.gpu.scene;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.kltyton.lumenfx.client.render.decal.DecalReceiverView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraSceneInputs.Texture;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.Surfaces;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Packs the opaque material outputs captured by the host draw for legacy Niagara GBuffer shaders. */
public final class NiagaraSceneGBuffer implements AutoCloseable {
    private static final ColorTargetState COLOR = new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL);
    private static final ColorTargetState FLOAT = new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL);
    private record Variant(boolean particles, boolean packedTangent, boolean precise, boolean staticLighting) { }
    private record ShaderpackVariant(int colourEncoding, int specularEncoding, boolean particles, boolean photonMask,
                                     boolean packedTangent, boolean precise, boolean staticLighting) { }
    private final Map<Variant, RenderPipeline> pipelines = new HashMap<>();
    private final Map<ShaderpackVariant, RenderPipeline> shaderpackPipelines = new HashMap<>();

    private static RenderPipeline shaderpackPipeline(ShaderpackVariant variant) {
        var bindings = BindGroupLayout.builder().withUniform("DecalReceiverView", UniformType.UNIFORM_BUFFER)
                .withSampler("SurfaceBaseDepth").withSampler("SurfaceGeometry").withSampler("SurfaceMaterial");
        if (variant.particles()) {
            bindings.withSampler("ParticleAlbedo").withSampler("ParticleNormalAmbient")
                    .withSampler("ParticleGeometry").withSampler("ParticleSourceMaterial")
                    .withSampler("ParticleF0Roughness");
        }
        if (variant.photonMask()) {
            bindings.withSampler("PhotonOpaqueMask");
        }
        if (variant.packedTangent()) bindings.withSampler("ParticleTangent");
        var format = variant.precise() ? FLOAT : COLOR;
        var builder = RenderPipeline.builder()
                .withLocation(LumenFx.id("pipeline/niagara_shaderpack_gbuffer_" + variant.colourEncoding() + "_" + variant.specularEncoding()
                        + (variant.particles() ? "_particles" : "")
                        + (variant.photonMask() ? "_photon" : "")
                        + (variant.packedTangent() ? "_basis" : "")
                        + (variant.precise() ? "_float" : "")
                        + (variant.staticLighting() ? "_static" : "")))
                .withVertexShader(LumenFx.id("post/fullscreen"))
                .withFragmentShader(LumenFx.id("post/niagara_shaderpack_gbuffer"))
                .withShaderDefine("LFX_HOST_ENCODING", variant.colourEncoding())
                .withShaderDefine("LFX_HOST_SPECULAR_ENCODING", variant.specularEncoding())
                .withShaderDefine("LFX_GBUFFER_PARTICLES", variant.particles() ? 1 : 0)
                .withShaderDefine("LFX_GBUFFER_PHOTON_MASK", variant.photonMask() ? 1 : 0)
                .withShaderDefine("LFX_GBUFFER_PARTICLE_BASIS", variant.packedTangent() ? 1 : 0)
                .withShaderDefine("LFX_GBUFFER_FLOAT", variant.precise() ? 1 : 0)
                .withShaderDefine("LFX_GBUFFER_STATIC_LIGHTING", variant.staticLighting() ? 1 : 0)
                .withBindGroupLayout(bindings.build())
                .withColorTargetState(0, format).withColorTargetState(1, format).withColorTargetState(2, format)
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (variant.packedTangent()) builder.withColorTargetState(3, FLOAT);
        return builder.build();
    }

    private static RenderPipeline pipeline(Variant variant) {
        var bindings = BindGroupLayout.builder().withUniform("DecalReceiverView", UniformType.UNIFORM_BUFFER)
                .withSampler("SurfaceAlbedo").withSampler("SurfaceNormalAmbient").withSampler("SurfaceGeometry");
        if (variant.particles()) bindings.withSampler("ParticleSourceMaterial").withSampler("ParticleF0Roughness");
        if (variant.packedTangent()) bindings.withSampler("ParticleTangent");
        String path = variant.packedTangent() ? "pipeline/niagara_scene_gbuffer_basis"
                : variant.particles() ? "pipeline/niagara_scene_gbuffer_particles" : "pipeline/niagara_scene_gbuffer";
        var format = variant.precise() ? FLOAT : COLOR;
        var builder = RenderPipeline.builder().withLocation(LumenFx.id(path + (variant.precise() ? "_float" : "")
                        + (variant.staticLighting() ? "_static" : "")))
                .withVertexShader(LumenFx.id("post/fullscreen")).withFragmentShader(LumenFx.id("post/niagara_scene_gbuffer"))
                .withShaderDefine("LFX_GBUFFER_PARTICLES", variant.particles() ? 1 : 0).withBindGroupLayout(bindings.build())
                .withShaderDefine("LFX_GBUFFER_PARTICLE_BASIS", variant.packedTangent() ? 1 : 0)
                .withShaderDefine("LFX_GBUFFER_FLOAT", variant.precise() ? 1 : 0)
                .withShaderDefine("LFX_GBUFFER_STATIC_LIGHTING", variant.staticLighting() ? 1 : 0)
                .withColorTargetState(0, format).withColorTargetState(1, format).withColorTargetState(2, format)
                .withDepthStencilState(Optional.empty()).withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
        if (variant.packedTangent()) builder.withColorTargetState(3, FLOAT);
        return builder.build();
    }

    private record Targets(OwnedColorTarget a, OwnedColorTarget b, OwnedColorTarget c, boolean precise) implements AutoCloseable {
        @Override public void close() {
            try { c.close(); }
            finally {
                try { b.close(); }
                finally { a.close(); }
            }
        }
    }

    private final DecalReceiverView normals = new DecalReceiverView();
    private @Nullable Targets targets;
    private @Nullable OwnedColorTarget tangentTarget;

    public NiagaraSceneInputs resolve(CommandEncoder encoder, Surfaces surfaces, Matrix4fc modelView, NiagaraSceneInputs scene) {
        return resolve(encoder, surfaces, modelView, scene, null);
    }

    public NiagaraSceneInputs resolve(CommandEncoder encoder, Surfaces surfaces, Matrix4fc modelView, NiagaraSceneInputs scene,
                                     OpaqueParticleSurfaces.@Nullable Frame particle) {
        RenderSystem.assertOnRenderThread();
        var particleTangent = particle == null ? null : particle.tangentAnisotropy();
        boolean slab = particle != null && particle.hasLightingFeature(MaterialDefinition.SUBSTRATE_SLAB);
        boolean packedTangent = slab && particleTangent != null;
        if (particle != null) scene = scene.withTextureIfAbsent(Texture.GBUFFER_D, particle.customData())
                .withTextureIfAbsent(Texture.PARTICLE_DIFFUSE, particle.scene().albedo())
                .withTextureIfAbsent(Texture.PARTICLE_F0, particle.f0Roughness())
                .withTextureIfAbsent(Texture.PARTICLE_SOURCE_MATERIAL, particle.sourceMaterial());
        if (!packedTangent) scene = scene.withTextureIfAbsent(Texture.GBUFFER_F, particleTangent);
        if (scene.textures().containsKey(Texture.GBUFFER_A) && scene.textures().containsKey(Texture.GBUFFER_B)
                && scene.textures().containsKey(Texture.GBUFFER_C)
                && (!packedTangent || scene.textures().containsKey(Texture.GBUFFER_F))) return scene;
        int width = surfaces.width(), height = surfaces.height();
        var particleMaterial = particle == null ? null : particle.sourceMaterial();
        var output = targets(width, height, particle != null && particle.hasLightingFeature(MaterialDefinition.SUBSTRATE_SURFACE_PACKING));
        var outputTangent = packedTangent ? tangentTarget(width, height).getColorTextureView() : null;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice view;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(DecalReceiverView.UNIFORM_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            normals.write(modelView, mapped.data().order(ByteOrder.nativeOrder()), 0);
            view = mapped.slice();
        }
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX Niagara captured GBuffer")
                .withColorAttachment(output.a().getColorTextureView())
                .withColorAttachment(output.b().getColorTextureView())
                .withColorAttachment(output.c().getColorTextureView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        if (outputTangent != null) descriptor.withColorAttachment(outputTangent);
        try (var pass = encoder.createRenderPass(descriptor)) {
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            var variant = new Variant(particleMaterial != null, packedTangent, output.precise(),
                    NiagaraPlatforms.snapshot().staticLightingAllowed());
            var pipeline = pipelines.computeIfAbsent(variant, NiagaraSceneGBuffer::pipeline);
            pass.setPipeline(pipeline);
            pass.setUniform("DecalReceiverView", view);
            pass.bindTexture("SurfaceAlbedo", surfaces.albedo(), nearest);
            pass.bindTexture("SurfaceNormalAmbient", surfaces.normalAmbient(), nearest);
            pass.bindTexture("SurfaceGeometry", surfaces.positionDepth(), nearest);
            if (particleMaterial != null) {
                pass.bindTexture("ParticleSourceMaterial", particleMaterial, nearest);
                pass.bindTexture("ParticleF0Roughness", java.util.Objects.requireNonNull(particle).f0Roughness(), nearest);
            }
            if (packedTangent) pass.bindTexture("ParticleTangent", java.util.Objects.requireNonNull(particleTangent), nearest);
            pass.draw(3, 1, 0, 0);
        }
        return scene.withTextureIfAbsent(Texture.GBUFFER_A, output.a().getColorTextureView())
                .withTextureIfAbsent(Texture.GBUFFER_B, output.b().getColorTextureView())
                .withTextureIfAbsent(Texture.GBUFFER_C, output.c().getColorTextureView())
                .withTextureIfAbsent(Texture.GBUFFER_F, outputTangent);
    }

    public NiagaraSceneInputs resolveShaderpack(CommandEncoder encoder, ShaderpackMaterialSurfaces surfaces,
                                                Matrix4fc modelView, ColourEncoding encoding, NiagaraSceneInputs scene,
                                                OpaqueParticleSurfaces.@Nullable Frame particle) {
        return resolveShaderpack(encoder, surfaces, modelView, encoding, scene, particle, null);
    }

    public NiagaraSceneInputs resolveShaderpack(CommandEncoder encoder, ShaderpackMaterialSurfaces surfaces,
                                                Matrix4fc modelView, ColourEncoding encoding, NiagaraSceneInputs scene,
                                                OpaqueParticleSurfaces.@Nullable Frame particle,
                                                @Nullable GpuTextureView photonOpaqueMask) {
        RenderSystem.assertOnRenderThread();
        var particleMaterial = particle == null ? null : particle.sourceMaterial();
        var particleTangent = particle == null ? null : particle.tangentAnisotropy();
        boolean hasParticles = particleMaterial != null
                && particle.scene().width() == surfaces.width() && particle.scene().height() == surfaces.height();
        boolean hasPhotonMask = photonOpaqueMask != null
                && photonOpaqueMask.texture().getWidth(0) == surfaces.width()
                && photonOpaqueMask.texture().getHeight(0) == surfaces.height();
        boolean slab = hasParticles && particle.hasLightingFeature(MaterialDefinition.SUBSTRATE_SLAB);
        boolean packedTangent = hasParticles && slab && particleTangent != null;
        if (particle != null) scene = scene.withTextureIfAbsent(Texture.GBUFFER_D, particle.customData())
                .withTextureIfAbsent(Texture.PARTICLE_DIFFUSE, particle.scene().albedo())
                .withTextureIfAbsent(Texture.PARTICLE_F0, particle.f0Roughness())
                .withTextureIfAbsent(Texture.PARTICLE_SOURCE_MATERIAL, particle.sourceMaterial());
        if (!packedTangent) scene = scene.withTextureIfAbsent(Texture.GBUFFER_F, particleTangent);
        if (scene.textures().containsKey(Texture.GBUFFER_A) && scene.textures().containsKey(Texture.GBUFFER_B)
                && scene.textures().containsKey(Texture.GBUFFER_C)
                && (!packedTangent || scene.textures().containsKey(Texture.GBUFFER_F))) return scene;
        int width = surfaces.width(), height = surfaces.height();
        boolean precise = hasParticles && particle.hasLightingFeature(MaterialDefinition.SUBSTRATE_SURFACE_PACKING);
        var output = targets(width, height, precise);
        var outputTangent = packedTangent ? tangentTarget(width, height).getColorTextureView() : null;
        int alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        GpuBufferSlice view;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(DecalReceiverView.UNIFORM_BYTES, alignment, GpuBuffer.USAGE_UNIFORM)) {
            normals.write(modelView, mapped.data().order(ByteOrder.nativeOrder()), 0);
            view = mapped.slice();
        }
        int colourCode = encoding.orDefaultForFormat(true).shaderCode();
        int specularCode = switch (surfaces.encoding()) {
            case SEUS_PBR -> 1;
            case LAB_PBR -> 2;
            default -> 0;
        };
        var descriptor = RenderPassDescriptor.create(() -> "LumenFX Niagara shaderpack GBuffer")
                .withColorAttachment(output.a().getColorTextureView())
                .withColorAttachment(output.b().getColorTextureView())
                .withColorAttachment(output.c().getColorTextureView())
                .withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
        if (outputTangent != null) descriptor.withColorAttachment(outputTangent);
        try (var pass = encoder.createRenderPass(descriptor)) {
            var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
            var variant = new ShaderpackVariant(colourCode, specularCode, hasParticles, hasPhotonMask, packedTangent,
                    output.precise(), NiagaraPlatforms.snapshot().staticLightingAllowed());
            var pipeline = shaderpackPipelines.computeIfAbsent(variant, NiagaraSceneGBuffer::shaderpackPipeline);
            pass.setPipeline(pipeline);
            pass.setUniform("DecalReceiverView", view);
            pass.bindTexture("SurfaceBaseDepth", surfaces.baseDepth(), nearest);
            pass.bindTexture("SurfaceGeometry", surfaces.geometry(), nearest);
            pass.bindTexture("SurfaceMaterial", surfaces.material(), nearest);
            if (hasParticles) {
                var pScene = java.util.Objects.requireNonNull(particle).scene();
                pass.bindTexture("ParticleAlbedo", pScene.albedo(), nearest);
                pass.bindTexture("ParticleNormalAmbient", pScene.normalAmbient(), nearest);
                pass.bindTexture("ParticleGeometry", pScene.positionDepth(), nearest);
                pass.bindTexture("ParticleSourceMaterial", particleMaterial, nearest);
                pass.bindTexture("ParticleF0Roughness", particle.f0Roughness(), nearest);
            }
            if (hasPhotonMask) {
                pass.bindTexture("PhotonOpaqueMask", java.util.Objects.requireNonNull(photonOpaqueMask), nearest);
            }
            if (packedTangent) pass.bindTexture("ParticleTangent", java.util.Objects.requireNonNull(particleTangent), nearest);
            pass.draw(3, 1, 0, 0);
        }
        return scene.withTextureIfAbsent(Texture.GBUFFER_A, output.a().getColorTextureView())
                .withTextureIfAbsent(Texture.GBUFFER_B, output.b().getColorTextureView())
                .withTextureIfAbsent(Texture.GBUFFER_C, output.c().getColorTextureView())
                .withTextureIfAbsent(Texture.GBUFFER_F, outputTangent);
    }

    static boolean targetsReusable(boolean existingPrecise, boolean requestedPrecise) {
        return existingPrecise == requestedPrecise;
    }

    private OwnedColorTarget tangentTarget(int width, int height) {
        var old = tangentTarget;
        if (old != null && old.width == width && old.height == height) return old;
        var next = new OwnedColorTarget("Niagara scene world tangent and anisotropy", width, height, FLOAT.format());
        tangentTarget = next;
        if (old != null) old.close();
        return next;
    }

    private Targets targets(int width, int height, boolean precise) {
        var old = targets;
        if (old != null && old.a().width == width && old.a().height == height && targetsReusable(old.precise(), precise)) return old;
        OwnedColorTarget a = null, b = null, c = null;
        try {
            GpuFormat format = precise ? FLOAT.format() : COLOR.format();
            a = new OwnedColorTarget("Niagara scene normals", width, height, format);
            b = new OwnedColorTarget("Niagara scene shading model", width, height, format);
            c = new OwnedColorTarget("Niagara scene base color and AO", width, height, format);
            targets = new Targets(a, b, c, precise);
        } catch (RuntimeException | Error failure) {
            if (c != null) c.closeOnFailure(failure);
            if (b != null) b.closeOnFailure(failure);
            if (a != null) a.closeOnFailure(failure);
            throw failure;
        }
        if (old != null) old.close();
        return targets;
    }

    @Override public void close() {
        var old = targets;
        targets = null;
        try {
            if (old != null) old.close();
        } finally {
            var tangent = tangentTarget;
            tangentTarget = null;
            if (tangent != null) tangent.close();
        }
    }
}
