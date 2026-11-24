package com.kltyton.lumenfx.client.render.scene.surface;

import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.hdr.WorldHdrCapture;
import com.kltyton.lumenfx.client.render.light.scene.SceneIndirectDiffuse;
import com.kltyton.lumenfx.client.render.scene.surface.adaptive.AdaptiveParticleBuffer;
import com.kltyton.lumenfx.client.render.texture.OwnedColorTarget;
import com.kltyton.lumenfx.client.render.texture.ToonProfileBindings;
import com.kltyton.lumenfx.client.resource.shader.glsl.GlslSourceScanner;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.render.light.PrimitiveLighting;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/** Submits particle materials to the native scene receiver textures using their ordinary opaque draw. */
public final class OpaqueParticleSurfaces implements AutoCloseable {
    public record Frame(SceneSurfaceCapture.Surfaces scene, GpuTextureView f0Roughness, @Nullable GpuTextureView sourceMaterial,
                        @Nullable GpuTextureView tangentAnisotropy, @Nullable GpuTextureView customData, boolean bottomNormal,
                        int particleLightingFeatures, @Nullable GpuBufferSlice substratePacking,
                        @Nullable GpuTextureView subsurfaceDiffuse, AdaptiveParticleBuffer.@Nullable Frame adaptive,
                        SceneIndirectDiffuse.@Nullable Sample indirectDiffuse) {
        public boolean hasLightingFeature(int feature) { return (particleLightingFeatures & feature) != 0; }

        public SceneIndirectDiffuse.@Nullable Sample indirectDiffuse(MaterialDefinition material) {
            return acceptsIndirectDiffuse(material) ? indirectDiffuse : null;
        }

        public RenderPassDescriptor pass(RenderTarget target, String label) {
            var descriptor = RenderPassDescriptor.create(() -> label)
                    .withColorAttachment(target.getColorTextureView())
                    .withColorAttachment(scene.albedo()).withColorAttachment(scene.normalAmbient())
                    .withColorAttachment(scene.positionDepth()).withColorAttachment(f0Roughness)
                    .withDepthAttachment(Objects.requireNonNull(target.getDepthTextureView()))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, target.width, target.height));
            if (sourceMaterial != null) descriptor.withColorAttachment(sourceMaterial);
            if (tangentAnisotropy != null) descriptor.withColorAttachment(tangentAnisotropy);
            if (customData != null) descriptor.withColorAttachment(customData);
            return descriptor;
        }
    }

    private record Variant(RenderPipeline base, boolean defaultLit, PrimitiveLighting lighting, boolean sourceMaterial,
                           boolean firstPerson, int anisotropyTarget, boolean writesAnisotropy, int customDataTarget,
                           boolean bottomNormal, boolean floatingCustomData, boolean substrate, int profileIndex, boolean irisNormal,
                           boolean indirectDiffuse, boolean volumetricLightmap, boolean indirectToon) { }
    private static final AtomicLong NEXT = new AtomicLong();
    private static final String OUTPUTS = """
            layout(location=1) out vec4 lfxParticleDiffuse;
            layout(location=2) out vec4 lfxParticleNormalAo;
            layout(location=3) out vec4 lfxParticlePositionDepth;
            layout(location=4) out vec4 lfxParticleF0Roughness;
            #if LFX_PARTICLE_GBUFFER
            layout(location=5) out vec4 lfxParticleSourceMaterial;
            #endif
            #if LFX_PARTICLE_ANISOTROPY_TARGET >= 0
            layout(location=LFX_PARTICLE_ANISOTROPY_TARGET) out vec4 lfxParticleTangentAnisotropy;
            #endif
            #if LFX_PARTICLE_CUSTOM_DATA_TARGET >= 0
            layout(location=LFX_PARTICLE_CUSTOM_DATA_TARGET) out vec4 lfxParticleCustomData;
            #endif
            """;
    private final Map<Variant, RenderPipeline> pipelines = new HashMap<>();
    private @Nullable OwnedColorTarget lighting;
    private @Nullable OwnedColorTarget sourceMaterial;
    private @Nullable OwnedColorTarget tangentAnisotropy;
    private @Nullable OwnedColorTarget customData;
    private @Nullable OwnedColorTarget subsurfaceDiffuse;
    private @Nullable OwnedColorTarget standaloneAlbedo;
    private @Nullable OwnedColorTarget standaloneNormal;
    private @Nullable OwnedColorTarget standaloneGeometry;
    private final AdaptiveParticleBuffer adaptive = new AdaptiveParticleBuffer();

    /** Allocates cleared particle-only receiver attachments when the host owns world surface capture (e.g. active Iris). */
    public Frame beginStandalone(CommandEncoder encoder, RenderTarget target,
                                 boolean gbuffer, boolean anisotropy, boolean custom, int particleLightingFeatures) {
        if (standaloneAlbedo == null || standaloneAlbedo.width != target.width || standaloneAlbedo.height != target.height) {
            closeStandalone();
            OwnedColorTarget nextAlbedo = null, nextNormal = null, nextGeometry = null;
            try {
                nextAlbedo = new OwnedColorTarget("LumenFX standalone opaque particle albedo", target.width, target.height, GpuFormat.RGBA32_FLOAT);
                nextNormal = new OwnedColorTarget("LumenFX standalone opaque particle normal and AO", target.width, target.height, GpuFormat.RGBA32_FLOAT);
                nextGeometry = new OwnedColorTarget("LumenFX standalone opaque particle geometry", target.width, target.height, GpuFormat.RGBA32_FLOAT);
                standaloneAlbedo = nextAlbedo;
                standaloneNormal = nextNormal;
                standaloneGeometry = nextGeometry;
            } catch (RuntimeException | Error failure) {
                if (nextGeometry != null) nextGeometry.closeOnFailure(failure);
                if (nextNormal != null) nextNormal.closeOnFailure(failure);
                if (nextAlbedo != null) nextAlbedo.closeOnFailure(failure);
                throw failure;
            }
        }
        var clear = new Vector4f();
        encoder.clearColorTexture(standaloneAlbedo.getColorTexture(), clear);
        encoder.clearColorTexture(standaloneNormal.getColorTexture(), clear);
        encoder.clearColorTexture(standaloneGeometry.getColorTexture(), clear);
        var scene = new SceneSurfaceCapture.Surfaces(
                standaloneAlbedo.getColorTextureView(),
                standaloneNormal.getColorTextureView(),
                standaloneGeometry.getColorTextureView(),
                target.width, target.height);
        return begin(encoder, target, scene, gbuffer, anisotropy, custom, particleLightingFeatures, 0, null);
    }

    private void closeStandalone() {
        if (standaloneAlbedo != null) { standaloneAlbedo.close(); standaloneAlbedo = null; }
        if (standaloneNormal != null) { standaloneNormal.close(); standaloneNormal = null; }
        if (standaloneGeometry != null) { standaloneGeometry.close(); standaloneGeometry = null; }
    }

    public Frame begin(CommandEncoder encoder, RenderTarget target, SceneSurfaceCapture.Surfaces scene,
                       boolean gbuffer, boolean anisotropy, boolean custom, int particleLightingFeatures, int adaptiveWords,
                       SceneIndirectDiffuse.@Nullable Sample indirectDiffuse) {
        boolean floatingData = (particleLightingFeatures & MaterialDefinition.FLOATING_SURFACE_DATA) != 0;
        boolean substrate = (particleLightingFeatures & MaterialDefinition.SUBSTRATE_SURFACE_PACKING) != 0;
        var settings = NiagaraPlatforms.snapshot();
        boolean profile = (particleLightingFeatures & MaterialDefinition.SUBSURFACE_DIFFUSION) != 0;
        boolean scatter = profile && Objects.requireNonNull(settings.console("r.SubsurfaceScattering")).bits() != 0
                && Float.intBitsToFloat(Objects.requireNonNull(settings.console("r.SSS.Scale")).bits()) > 0;
        if (target.width != scene.width() || target.height != scene.height())
            throw new IllegalArgumentException("Opaque particle materials require matching scene receiver attachments");
        int colorAttachments = 5 + (gbuffer ? 1 : 0) + (anisotropy ? 1 : 0) + (custom ? 1 : 0);
        if (RenderSystem.getDevice().getDeviceInfo().limits().maxColorAttachments() < colorAttachments)
            throw new IllegalStateException("Opaque particle materials require " + colorAttachments + " color attachments");
        if (lighting == null || lighting.width != target.width || lighting.height != target.height) {
            var next = new OwnedColorTarget("LumenFX opaque particle specular", target.width, target.height, GpuFormat.RGBA32_FLOAT);
            if (lighting != null) lighting.close();
            lighting = next;
        }
        encoder.clearColorTexture(lighting.getColorTexture(), new Vector4f());
        if (gbuffer) {
            if (sourceMaterial == null || sourceMaterial.width != target.width || sourceMaterial.height != target.height) {
                var next = new OwnedColorTarget("LumenFX opaque particle source material", target.width, target.height, GpuFormat.RGBA32_FLOAT);
                if (sourceMaterial != null) sourceMaterial.close();
                sourceMaterial = next;
            }
            encoder.clearColorTexture(sourceMaterial.getColorTexture(), new Vector4f());
        }
        if (anisotropy) {
            GpuFormat format = customFormat(floatingData);
            if (tangentAnisotropy == null || tangentAnisotropy.width != target.width || tangentAnisotropy.height != target.height
                    || tangentAnisotropy.getColorTexture().getFormat() != format) {
                var next = new OwnedColorTarget("LumenFX opaque particle tangent and anisotropy", target.width, target.height, format);
                if (tangentAnisotropy != null) tangentAnisotropy.close();
                tangentAnisotropy = next;
            }
            encoder.clearColorTexture(tangentAnisotropy.getColorTexture(), new Vector4f(0.5f));
        }
        if (custom) {
            GpuFormat format = customFormat(floatingData);
            if (customData == null || customData.width != target.width || customData.height != target.height
                    || customData.getColorTexture().getFormat() != format) {
                var next = new OwnedColorTarget("LumenFX opaque particle material custom data", target.width, target.height, format);
                if (customData != null) customData.close();
                customData = next;
            }
            encoder.clearColorTexture(customData.getColorTexture(), new Vector4f());
        }
        if (scatter) {
            if (subsurfaceDiffuse == null || subsurfaceDiffuse.width != target.width || subsurfaceDiffuse.height != target.height) {
                var next = new OwnedColorTarget("LumenFX subsurface incident light", target.width, target.height, GpuFormat.RGBA32_FLOAT);
                if (subsurfaceDiffuse != null) subsurfaceDiffuse.close();
                subsurfaceDiffuse = next;
            }
            encoder.clearColorTexture(subsurfaceDiffuse.getColorTexture(), new Vector4f());
        }
        return new Frame(scene, lighting.getColorTextureView(), gbuffer ? Objects.requireNonNull(sourceMaterial).getColorTextureView() : null,
                anisotropy ? Objects.requireNonNull(tangentAnisotropy).getColorTextureView() : null,
                custom ? Objects.requireNonNull(customData).getColorTextureView() : null,
                custom && Objects.requireNonNull(NiagaraPlatforms.snapshot().console("r.ClearCoatNormal")).bits() != 0,
                particleLightingFeatures, substrate ? substratePacking(encoder, target.height) : null,
                scatter ? Objects.requireNonNull(subsurfaceDiffuse).getColorTextureView() : null,
                adaptiveWords == 0 ? null : adaptive.begin(encoder, target, adaptiveWords), indirectDiffuse);
    }

    public record IndirectVariant(boolean indirectDiffuse, boolean volumetricLightmap, boolean indirectToon) { }

    public static boolean acceptsIndirectDiffuse(MaterialDefinition material) {
        int incompatible = MaterialDefinition.SUBSTRATE_SURFACE_PACKING & ~MaterialDefinition.SUBSTRATE_TOON;
        return material.defaultLitSurface() && (material.particleLightingFeatures() & incompatible) == 0;
    }

    public static IndirectVariant resolveIndirectVariant(MaterialDefinition material,
                                                         SceneIndirectDiffuse.@Nullable Sample sample) {
        var effective = acceptsIndirectDiffuse(material) ? sample : null;
        boolean toon = (material.particleLightingFeatures() & MaterialDefinition.SUBSTRATE_TOON) != 0;
        return new IndirectVariant(effective != null, effective != null && effective.lightmap() != null,
                effective != null && toon);
    }

    public RenderPipeline pipeline(RenderPipeline base, MaterialDefinition material, PrimitiveLighting primitive, Frame frame,
                                   boolean firstPerson, int profileIndex, ShaderSource shaders) {
        return pipeline(base, material, primitive, frame, frame.indirectDiffuse(material), firstPerson, profileIndex, shaders);
    }

    public RenderPipeline pipeline(RenderPipeline base, MaterialDefinition material, PrimitiveLighting primitive, Frame frame,
                                   SceneIndirectDiffuse.@Nullable Sample indirectSample,
                                   boolean firstPerson, int profileIndex, ShaderSource shaders) {
        boolean gbuffer = frame.sourceMaterial() != null;
        int nextTarget = gbuffer ? 6 : 5;
        int anisotropyTarget = frame.tangentAnisotropy() == null ? -1 : nextTarget++;
        int customDataTarget = frame.customData() == null ? -1 : nextTarget;
        boolean irisNormal = profileIndex >= 0
                && Objects.requireNonNull(NiagaraPlatforms.snapshot().console("r.IrisNormal")).bits() != 0;
        var indirect = resolveIndirectVariant(material, indirectSample);
        boolean substrate = (material.particleLightingFeatures() & MaterialDefinition.SUBSTRATE_SURFACE_PACKING) != 0;
        return pipelines.computeIfAbsent(new Variant(base, material.defaultLitSurface(), primitive, gbuffer,
                        gbuffer && material.defaultLitSurface() && firstPerson,
                        anisotropyTarget, anisotropyTarget >= 0 && material.anisotropicSurface(), customDataTarget, frame.bottomNormal(),
                        frame.hasLightingFeature(MaterialDefinition.FLOATING_SURFACE_DATA), substrate, profileIndex, irisNormal,
                        indirect.indirectDiffuse(), indirect.volumetricLightmap(), indirect.indirectToon()),
                variant -> create(variant, shaders));
    }

    private static GpuBufferSlice substratePacking(CommandEncoder encoder, int height) {
        var device = RenderSystem.getDevice();
        var platform = NiagaraPlatforms.snapshot();
        int quality = Objects.requireNonNull(platform.console("r.Substrate.ShadingQuality")).bits();
        int frame = (int) ((WorldHdrCapture.INSTANCE.renderFrameSequence() - 1L) & 7L);
        int flags = device.backend instanceof GlDevice ? 1 : 0;
        if (Objects.requireNonNull(platform.console("r.GBufferFormat")).bits() > 1) flags |= 2;
        try (var mapped = encoder.transientMemory().allocateGpuMapped(16,
                device.getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM)) {
            mapped.data().order(ByteOrder.nativeOrder()).putInt(frame).putInt(quality).putInt(height).putInt(flags);
            return mapped.slice();
        }
    }

    private static GpuFormat customFormat(boolean floating) {
        return floating ? GpuFormat.RGBA32_FLOAT : GpuFormat.RGBA8_UNORM;
    }

    private static RenderPipeline create(Variant variant, ShaderSource shaders) {
        RenderPipeline base = variant.base();
        var id = LumenFx.id("pipeline/opaque_particle_surface/" + NEXT.getAndIncrement());
        var builder = com.kltyton.lumenfx.client.platform.LumenClientPlatform.toBuilder(base).withLocation(id).withFragmentShader(id)
                .withShaderDefine("LFX_PARTICLE_SURFACE", 1)
                .withShaderDefine("LFX_PARTICLE_GBUFFER", variant.sourceMaterial() ? 1 : 0)
                .withShaderDefine("LFX_PARTICLE_FIRST_PERSON", variant.firstPerson() ? 1 : 0)
                .withShaderDefine("LFX_PARTICLE_ANISOTROPY_TARGET", variant.anisotropyTarget())
                .withShaderDefine("LFX_PARTICLE_ANISOTROPIC_MATERIAL", variant.writesAnisotropy() ? 1 : 0)
                .withShaderDefine("LFX_PARTICLE_ANISOTROPY_FLOAT", variant.floatingCustomData() ? 1 : 0)
                .withShaderDefine("LFX_PARTICLE_CUSTOM_DATA_TARGET", variant.customDataTarget())
                .withShaderDefine("LFX_PARTICLE_CUSTOM_DATA_FLOAT", variant.floatingCustomData() ? 1 : 0)
                .withShaderDefine("LFX_PARTICLE_PROFILE_INDEX", variant.profileIndex())
                .withShaderDefine("LFX_PARTICLE_SUBSTRATE_PACKING", variant.substrate() ? 1 : 0)
                .withShaderDefine("LFX_LEGACY_IRIS_NORMAL", variant.irisNormal() ? 1 : 0)
                .withShaderDefine("LFX_CLEAR_COAT_BOTTOM_NORMAL", variant.bottomNormal() ? 1 : 0)
                .withShaderDefine("LFX_PARTICLE_LIGHT_CHANNELS", variant.lighting().channels())
                .withShaderDefine("LFX_PARTICLE_CONTACT_SHADOW", variant.lighting().castsContactShadow() ? 128 : 0);
        if (variant.indirectDiffuse()) {
            var bindings = BindGroupLayout.builder();
            SceneIndirectDiffuse.layout(bindings, variant.volumetricLightmap());
            if (variant.indirectToon()) ToonProfileBindings.layout(bindings);
            builder.withBindGroupLayout(bindings.build()).withShaderDefine("LFX_OPAQUE_INDIRECT", 1)
                    .withShaderDefine("LFX_OPAQUE_VOLUMETRIC_LIGHTMAP", variant.volumetricLightmap() ? 1 : 0);
        }
        for (int attachment = 1; attachment <= (variant.sourceMaterial() ? 5 : 4); attachment++) builder.withColorTargetState(attachment,
                new ColorTargetState(Optional.empty(), GpuFormat.RGBA32_FLOAT, ColorTargetState.WRITE_ALL));
        if (variant.anisotropyTarget() >= 0) builder.withColorTargetState(variant.anisotropyTarget(),
                new ColorTargetState(Optional.empty(), customFormat(variant.floatingCustomData()), ColorTargetState.WRITE_ALL));
        if (variant.customDataTarget() >= 0) builder.withColorTargetState(variant.customDataTarget(),
                new ColorTargetState(Optional.empty(), customFormat(variant.floatingCustomData()), ColorTargetState.WRITE_ALL));
        if (variant.substrate()) builder.withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("LfxSubstratePacking", UniformType.UNIFORM_BUFFER).build());
        var pipeline = builder.build();
        String original = Objects.requireNonNull(shaders.get(base.getFragmentShader(), ShaderType.FRAGMENT));
        int insertion = GlslSourceScanner.header(original).insertionIndex();
        StringBuilder source = new StringBuilder(original.substring(0, insertion)).append('\n').append(OUTPUTS);
        if (!variant.defaultLit()) {
            var helperId = LumenFx.id("scene/surface/opaque_particle_unlit");
            String helper = Objects.requireNonNull(shaders.get(helperId, ShaderType.FRAGMENT),
                    () -> "Missing opaque particle surface shader: " + helperId);
            source.append(helper.substring(GlslSourceScanner.header(helper).insertionIndex()));
            source.append("\n#define main lfxOpaqueParticleMain\n");
        }
        if (variant.indirectDiffuse())
            source.append("vec3 lfxOpaqueIndirectDiffuse = vec3(0.0);\n#define main lfxOpaqueParticleMain\n");
        source.append(original.substring(insertion));
        if (!variant.defaultLit()) {
            source.append("""
                    \n#undef main
                    void main() {
                        lfxOpaqueParticleMain();
                        float depth = gl_FragCoord.z;
                    #if LFX_MATERIAL_WRITES_DEPTH
                        depth = lfxMaterialDeviceZ;
                    #elif LFX_NANITE_MATERIAL
                        depth = lfxNaniteGeometry.deviceDepth;
                    #endif
                        lfx_write_unlit_particle_surface(depth);
                    }
                    """);
        } else if (variant.indirectDiffuse()) {
            source.append("""
                    \n#undef main
                    void main() {
                        lfxOpaqueParticleMain();
                    #if LFX_APPLY_FOG
                        fragColor.rgb += lfxOpaqueIndirectDiffuse * lfx_material_fog().a;
                    #else
                        fragColor.rgb += lfxOpaqueIndirectDiffuse;
                    #endif
                    }
                    """);
        }
        String resolved = source.toString();
        if (!RenderSystem.getDevice().precompilePipeline(pipeline, (shader, stage) ->
                shader.equals(id) && stage == ShaderType.FRAGMENT ? resolved : shaders.get(shader, stage)).isValid())
            throw new IllegalStateException("Opaque particle surface pipeline failed to compile: " + base.getLocation());
        return pipeline;
    }

    @Override public void close() {
        if (lighting != null) { lighting.close(); lighting = null; }
        if (sourceMaterial != null) { sourceMaterial.close(); sourceMaterial = null; }
        if (tangentAnisotropy != null) { tangentAnisotropy.close(); tangentAnisotropy = null; }
        if (customData != null) { customData.close(); customData = null; }
        if (subsurfaceDiffuse != null) { subsurfaceDiffuse.close(); subsurfaceDiffuse = null; }
        closeStandalone();
        adaptive.close();
        pipelines.clear();
    }
}
