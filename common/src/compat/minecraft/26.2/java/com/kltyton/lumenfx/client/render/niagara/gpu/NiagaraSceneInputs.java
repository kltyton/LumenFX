package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.kltyton.lumenfx.client.render.compute.VolumeGpuTexture;
import com.kltyton.lumenfx.client.render.compute.format.SampledTextureFormats;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.world.phys.Vec3;
import java.util.Objects;
import java.util.EnumMap;
import java.util.Map;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Scene resources borrowed from the current post-opaque view during Niagara dispatch. */
public record NiagaraSceneInputs(@Nullable Clouds clouds, @Nullable Atmosphere atmosphere,
                                 Map<Texture, GpuTextureView> textures, float preExposure,
                                 @Nullable NiagaraGlobalDistanceField globalDistanceField,
                                 @Nullable NiagaraHardwareTrace hardwareTrace, boolean customDepthBeforeBasePass) {
    public static final NiagaraSceneInputs EMPTY = new NiagaraSceneInputs(null, null, Map.of(), 1f);

    public enum Texture {
        SCENE_COLOR("SceneColorTexture"), PARTIAL_DEPTH("ScenePartialDepthTexture", Format.DEPTH),
        CUSTOM_DEPTH("CustomDepthTexture", Format.DEPTH), CUSTOM_STENCIL("CustomStencilTexture", Format.STENCIL),
        GBUFFER_A("GBufferATexture"), GBUFFER_B("GBufferBTexture"), GBUFFER_C("GBufferCTexture"),
        GBUFFER_D("GBufferDTexture"), GBUFFER_E("GBufferETexture"), GBUFFER_F("GBufferFTexture"),
        GBUFFER_VELOCITY("GBufferVelocityTexture"), GBUFFER_SGGX("GBufferSGGXTexture"),
        SCREEN_SPACE_AO("ScreenSpaceAOTexture"),
        PARTICLE_DIFFUSE("LumenFxParticleDiffuse"), PARTICLE_F0("LumenFxParticleF0"),
        PARTICLE_SOURCE_MATERIAL("LumenFxParticleSourceMaterial");

        enum Format { COLOR, DEPTH, STENCIL }
        private final String shaderName;
        final Format format;
        Texture(String name) { this(name, Format.COLOR); }
        Texture(String name, Format format) { this.shaderName = "SceneTexturesStruct_" + name; this.format = format; }
        public String shaderName() { return shaderName; }
        public static @Nullable Texture shaderTexture(String name) {
            for (Texture texture : values())
                if (name.equals(texture.shaderName) || name.startsWith(texture.shaderName + "_")) return texture;
            return null;
        }
    }

    public NiagaraSceneInputs(@Nullable Clouds clouds, @Nullable Atmosphere atmosphere) {
        this(clouds, atmosphere, Map.of(), 1f);
    }

    public NiagaraSceneInputs(@Nullable Clouds clouds, @Nullable Atmosphere atmosphere,
                              Map<Texture, GpuTextureView> textures, float preExposure) {
        this(clouds, atmosphere, textures, preExposure, null);
    }

    public NiagaraSceneInputs(@Nullable Clouds clouds, @Nullable Atmosphere atmosphere,
                              Map<Texture, GpuTextureView> textures, float preExposure,
                              @Nullable NiagaraGlobalDistanceField globalDistanceField) {
        this(clouds, atmosphere, textures, preExposure, globalDistanceField, null);
    }

    public NiagaraSceneInputs(@Nullable Clouds clouds, @Nullable Atmosphere atmosphere,
                              Map<Texture, GpuTextureView> textures, float preExposure,
                              @Nullable NiagaraGlobalDistanceField globalDistanceField, @Nullable NiagaraHardwareTrace hardwareTrace) {
        this(clouds, atmosphere, textures, preExposure, globalDistanceField, hardwareTrace, true);
    }

    public NiagaraSceneInputs {
        textures = Map.copyOf(textures);
        textures.forEach((key, view) -> validateTexture(view, key.format));
        if (!Float.isFinite(preExposure) || preExposure <= 0) throw new IllegalArgumentException("Invalid Niagara scene exposure");
    }

    public NiagaraSceneInputs withTextureIfAbsent(Texture field, @Nullable GpuTextureView view) {
        if (view == null || textures.containsKey(field)) return this;
        var values = new EnumMap<Texture, GpuTextureView>(Texture.class);
        values.putAll(textures);
        values.put(field, view);
        return new NiagaraSceneInputs(clouds, atmosphere, values, preExposure, globalDistanceField, hardwareTrace, customDepthBeforeBasePass);
    }

    public NiagaraSceneInputs withGlobalDistanceField(@Nullable NiagaraGlobalDistanceField field) {
        return field == globalDistanceField ? this : new NiagaraSceneInputs(clouds, atmosphere, textures, preExposure, field, hardwareTrace,
                customDepthBeforeBasePass);
    }

    public NiagaraSceneInputs withAtmosphere(Atmosphere value) {
        return new NiagaraSceneInputs(clouds, value, textures, preExposure, globalDistanceField, hardwareTrace, customDepthBeforeBasePass);
    }

    public NiagaraSceneInputs withCustomDepth(GpuTextureView depth, @Nullable GpuTextureView stencil, boolean beforeBasePass) {
        var values = new EnumMap<Texture, GpuTextureView>(Texture.class);
        values.putAll(textures);
        values.put(Texture.CUSTOM_DEPTH, depth);
        if (stencil != null) values.put(Texture.CUSTOM_STENCIL, stencil);
        return new NiagaraSceneInputs(clouds, atmosphere, values, preExposure, globalDistanceField, hardwareTrace, beforeBasePass);
    }

    public record Clouds(GpuTextureView texture, float scaleX, float scaleY, float maxX, float maxY) {
        public Clouds {
            validateTexture(texture, Texture.Format.COLOR);
            finite(scaleX, scaleY, maxX, maxY);
        }
    }

    /**
     * The planet center is in translated source-world centimeters and radii use source kilometers.
     * The optional light direction is the current view's actual atmosphere light index 0, in source XYZ axes;
     * it is absent when that light does not provide per-pixel transmittance.
     */
    public record Atmosphere(GpuTextureView transmittance, float translatedCenterX, float translatedCenterY,
                             float translatedCenterZ, float viewHeight, float bottomRadiusKm, float topRadiusKm,
                             @Nullable Vec3 atmosphereLightDirection, @Nullable AerialPerspective aerialPerspective,
                             @Nullable AtmosphereLight firstLight, @Nullable AtmosphereLight secondLight,
                             @Nullable GpuBufferSlice distantSkyLight, @Nullable AtmosphereSkyView skyView,
                             @Nullable AtmosphereDiskView diskView, boolean reflectionCapture) {
        public Atmosphere(GpuTextureView transmittance, float translatedCenterX, float translatedCenterY,
                          float translatedCenterZ, float viewHeight, float bottomRadiusKm, float topRadiusKm,
                          @Nullable Vec3 atmosphereLightDirection, @Nullable AerialPerspective aerialPerspective,
                          @Nullable AtmosphereLight firstLight, @Nullable AtmosphereLight secondLight,
                          @Nullable GpuBufferSlice distantSkyLight, @Nullable AtmosphereSkyView skyView,
                          @Nullable AtmosphereDiskView diskView) {
            this(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight, bottomRadiusKm,
                    topRadiusKm, atmosphereLightDirection, aerialPerspective, firstLight, secondLight, distantSkyLight,
                    skyView, diskView, false);
        }

        public Atmosphere(GpuTextureView transmittance, float translatedCenterX, float translatedCenterY,
                          float translatedCenterZ, float viewHeight, float bottomRadiusKm, float topRadiusKm,
                          @Nullable Vec3 atmosphereLightDirection, @Nullable AerialPerspective aerialPerspective,
                          @Nullable AtmosphereLight firstLight, @Nullable AtmosphereLight secondLight,
                          @Nullable GpuBufferSlice distantSkyLight, @Nullable AtmosphereSkyView skyView) {
            this(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight, bottomRadiusKm,
                    topRadiusKm, atmosphereLightDirection, aerialPerspective, firstLight, secondLight, distantSkyLight, skyView, null);
        }
        public Atmosphere(GpuTextureView transmittance, float translatedCenterX, float translatedCenterY,
                          float translatedCenterZ, float viewHeight, float bottomRadiusKm, float topRadiusKm,
                          @Nullable Vec3 atmosphereLightDirection, @Nullable AerialPerspective aerialPerspective,
                          @Nullable AtmosphereLight firstLight, @Nullable AtmosphereLight secondLight,
                          @Nullable GpuBufferSlice distantSkyLight) {
            this(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight, bottomRadiusKm,
                    topRadiusKm, atmosphereLightDirection, aerialPerspective, firstLight, secondLight, distantSkyLight, null);
        }
        public Atmosphere(GpuTextureView transmittance, float translatedCenterX, float translatedCenterY,
                          float translatedCenterZ, float viewHeight, float bottomRadiusKm, float topRadiusKm,
                          @Nullable Vec3 atmosphereLightDirection) {
            this(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight,
                    bottomRadiusKm, topRadiusKm, atmosphereLightDirection, null, null, null, null);
        }

        public Atmosphere(GpuTextureView transmittance, float translatedCenterX, float translatedCenterY,
                          float translatedCenterZ, float viewHeight, float bottomRadiusKm, float topRadiusKm) {
            this(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight,
                    bottomRadiusKm, topRadiusKm, null);
        }

        public Atmosphere {
            validateTexture(transmittance, Texture.Format.COLOR);
            finite(translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight, bottomRadiusKm, topRadiusKm);
            if (atmosphereLightDirection != null && (!Double.isFinite(atmosphereLightDirection.x)
                    || !Double.isFinite(atmosphereLightDirection.y) || !Double.isFinite(atmosphereLightDirection.z)))
                throw new IllegalArgumentException("Non-finite Niagara atmosphere light direction");
            if (bottomRadiusKm <= 0 || topRadiusKm <= bottomRadiusKm)
                throw new IllegalArgumentException("Invalid Niagara atmosphere radii");
            if (distantSkyLight != null && (distantSkyLight.length() != 16 || distantSkyLight.buffer().isClosed()
                    || (distantSkyLight.buffer().usage() & GpuBuffer.USAGE_COPY_SRC) == 0))
                throw new IllegalArgumentException("Distant sky light requires one live, copyable float4");
        }

        public Atmosphere withAerialPerspective(AerialPerspective volume) {
            return new Atmosphere(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight,
                    bottomRadiusKm, topRadiusKm, atmosphereLightDirection, volume, firstLight, secondLight, distantSkyLight, skyView, diskView, reflectionCapture);
        }

        public Atmosphere withLights(@Nullable Vec3 direction, @Nullable AtmosphereLight first, @Nullable AtmosphereLight second) {
            return new Atmosphere(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight,
                    bottomRadiusKm, topRadiusKm, direction, aerialPerspective, first, second, distantSkyLight, skyView, diskView, reflectionCapture);
        }

        public Atmosphere withDistantSkyLight(GpuBufferSlice light) {
            return new Atmosphere(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight,
                    bottomRadiusKm, topRadiusKm, atmosphereLightDirection, aerialPerspective, firstLight, secondLight, light, skyView, diskView, reflectionCapture);
        }

        public Atmosphere withSkyView(AtmosphereSkyView view) {
            return new Atmosphere(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight,
                    bottomRadiusKm, topRadiusKm, atmosphereLightDirection, aerialPerspective, firstLight, secondLight, distantSkyLight, view, diskView, reflectionCapture);
        }

        public Atmosphere withDiskView(AtmosphereDiskView view) {
            return new Atmosphere(transmittance, translatedCenterX, translatedCenterY, translatedCenterZ, viewHeight,
                    bottomRadiusKm, topRadiusKm, atmosphereLightDirection, aerialPerspective, firstLight, secondLight, distantSkyLight, skyView, view, reflectionCapture);
        }

        public Atmosphere forViewOrigin(Vec3 previous, Vec3 current) {
            if (previous.equals(current)) return this;
            var offset = previous.subtract(current).scale(100.0);
            return new Atmosphere(transmittance, (float) (translatedCenterX + offset.x), (float) (translatedCenterY + offset.z),
                    (float) (translatedCenterZ + offset.y), viewHeight, bottomRadiusKm, topRadiusKm, atmosphereLightDirection,
                    aerialPerspective == null ? null : aerialPerspective.withDrawOrigin(current.subtract(previous)),
                    firstLight, secondLight, distantSkyLight, skyView, diskView, reflectionCapture);
        }

        public Vec3 directionalShaderColor(SceneDirectionalLight light) {
            if (firstLight != null && firstLight.usesGroundColor(light)) return firstLight.groundIlluminance();
            if (secondLight != null && secondLight.usesGroundColor(light)) return secondLight.groundIlluminance();
            return light.color();
        }
    }

    public record AtmosphereSkyView(GpuTextureView texture, Vec3 forward, Vec3 left, Vec3 up, Vec3 luminanceFactor,
                                    float heightKm, boolean holdout, @Nullable Vec3 orthographicHeight,
                                    @Nullable SkyViewQuantization quantization) {
        public AtmosphereSkyView(GpuTextureView texture, Vec3 forward, Vec3 left, Vec3 up, Vec3 luminanceFactor,
                                 float heightKm, boolean holdout) {
            this(texture, forward, left, up, luminanceFactor, heightKm, holdout, null, null);
        }

        public AtmosphereSkyView { validateTexture(texture, Texture.Format.COLOR); }
    }

    public record SkyViewQuantization(Vec3 error, int width, int height, int frameIndexMod8) { }

    /** Planet-relative sky camera and its orthographic viewport steps in source kilometers. */
    public record AtmosphereDiskView(Vec3 origin, Vec3 viewportStepX, Vec3 viewportStepY) { }

    public record AtmosphereLight(Vec3 direction, Vec3 outerSpaceIlluminance, Vec3 groundIlluminance,
                                  @Nullable SceneDirectionalLight sceneLight, float diskCosHalfApex, Vec3 diskLuminance) {
        public AtmosphereLight(Vec3 direction, Vec3 outerSpaceIlluminance, Vec3 groundIlluminance,
                               @Nullable SceneDirectionalLight sceneLight) {
            this(direction, outerSpaceIlluminance, groundIlluminance, sceneLight, 1f, Vec3.ZERO);
        }

        boolean usesGroundColor(SceneDirectionalLight light) {
            return sceneLight == light && light.atmosphere() != null && !light.atmosphere().perPixelTransmittance();
        }
    }

    public record AerialPerspective(GpuTextureView volume, GpuSampler sampler, Matrix4fc worldToClip, Matrix4fc clipToWorld,
                                    float startKm, float depthKm, int slices, boolean orthographic,
                                    Vec3 cameraOffsetBlocks, float orthoHeightBlocks, boolean holdout, Vec3 drawOriginOffsetBlocks,
                                    boolean reflection360) {
        public AerialPerspective(GpuTextureView volume, GpuSampler sampler, Matrix4fc worldToClip, Matrix4fc clipToWorld,
                                 float startKm, float depthKm, int slices, boolean orthographic, Vec3 cameraOffsetBlocks,
                                 float orthoHeightBlocks, boolean holdout, Vec3 drawOriginOffsetBlocks) {
            this(volume, sampler, worldToClip, clipToWorld, startKm, depthKm, slices, orthographic, cameraOffsetBlocks,
                    orthoHeightBlocks, holdout, drawOriginOffsetBlocks, false);
        }

        public AerialPerspective(GpuTextureView volume, GpuSampler sampler, Matrix4fc worldToClip, Matrix4fc clipToWorld,
                                 float startKm, float depthKm, int slices, boolean orthographic) {
            this(volume, sampler, worldToClip, clipToWorld, startKm, depthKm, slices, orthographic,
                    Vec3.ZERO, 0f, false, Vec3.ZERO);
        }

        public AerialPerspective {
            var texture = volume.texture();
            if (volume.isClosed() || texture.isClosed() || !(texture instanceof VolumeGpuTexture)
                    || (texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0
                    || !SampledTextureFormats.floatingColor(texture))
                throw new IllegalArgumentException("Aerial perspective requires a live sampled 3D color texture");
            finite(startKm, depthKm, orthoHeightBlocks);
            if (startKm < 0 || depthKm <= 0 || slices <= 0 || texture.getDepthOrLayers() != slices)
                throw new IllegalArgumentException("Invalid aerial perspective depth slices");
        }

        public AerialPerspective withDrawOrigin(Vec3 offsetBlocks) {
            return new AerialPerspective(volume, sampler, worldToClip, clipToWorld, startKm, depthKm, slices,
                    orthographic, cameraOffsetBlocks, orthoHeightBlocks, holdout, drawOriginOffsetBlocks.add(offsetBlocks), reflection360);
        }
    }

    private static void validateTexture(GpuTextureView view, Texture.Format format) {
        Objects.requireNonNull(view);
        var texture = view.texture();
        if (view.isClosed() || texture.isClosed() || texture.getDepthOrLayers() != 1
                || (texture.usage() & GpuTexture.USAGE_TEXTURE_BINDING) == 0
                || (texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0)
            throw new IllegalArgumentException("Niagara scene inputs require a live sampled 2D texture");
        boolean matches = switch (format) {
            case COLOR -> SampledTextureFormats.floatingColor(texture);
            case DEPTH -> texture.getFormat().hasDepthAspect();
            case STENCIL -> texture.getFormat() == GpuFormat.RG8_UINT;
        };
        if (!matches) throw new IllegalArgumentException("Niagara scene texture has the wrong source format: " + format);
    }
    private static void finite(float... values) {
        for (float value : values) if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite Niagara scene input");
    }
}
