package com.kltyton.lumenfx.client.render.hdr;
import com.kltyton.lumenfx.LumenFx;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.client.render.decal.DecalNormalHistory;
import com.kltyton.lumenfx.client.render.scene.surface.OpaqueParticleSurfaces;
import com.kltyton.lumenfx.client.compat.shaderpack.FrameOwnership;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackCompat;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackFrame;
import com.kltyton.lumenfx.client.compat.shaderpack.ShaderpackMaterialSurfaces;
import com.kltyton.lumenfx.client.compat.shaderpack.ColourEncoding;
import com.kltyton.lumenfx.client.render.hdr.external.GlSceneTransfer;
import com.kltyton.lumenfx.client.render.hdr.external.HostSurfaceReconstructor;
import com.kltyton.lumenfx.client.render.fog.FogView;
import com.kltyton.lumenfx.client.render.fog.VolumetricFogTarget;
import com.kltyton.lumenfx.client.render.fog.FogIntegration;
import com.kltyton.lumenfx.client.render.light.shadow.PointShadowSceneRenderer.VolumeShadow;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionAtlas;
import com.kltyton.lumenfx.client.render.light.function.LightFunctionRenderer.VolumeMaterial;
import com.kltyton.lumenfx.client.render.motion.MotionHistory;
import com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer;
import com.kltyton.lumenfx.client.render.temporal.geometry.GeometryMotionRenderer.CoverageTargets;
import com.kltyton.lumenfx.client.render.temporal.velocity.SceneVelocityTarget;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.effect.RenderSettings;
import com.kltyton.lumenfx.effect.render.TranslucencyPass;
import com.kltyton.lumenfx.effect.render.fog.VolumetricFog;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.kltyton.lumenfx.client.render.translucency.TranslucencyLightingVolume.LocalLights;
import com.kltyton.lumenfx.client.render.translucency.TranslucencyLightingVolume.DirectionalLights;
import com.kltyton.lumenfx.client.render.translucency.TranslucencyLightingVolume.ViewHistory;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneIndirectLighting;
import com.kltyton.lumenfx.client.render.light.scene.SceneIndirectDiffuse;
import com.kltyton.lumenfx.client.render.light.scene.DeferredSkyLighting;
import net.minecraft.client.Minecraft;
import com.kltyton.lumenfx.client.render.light.shadow.directional.DirectionalShadowRenderer;
import com.lowdragmc.photon.client.render.PhotonFXLayer;
import com.lowdragmc.photon.client.render.OpaqueDepthCapture;
import com.lowdragmc.photon.client.render.PhotonFullscreenPass;
import com.lowdragmc.photon.client.render.PhotonRenderOutput;
import com.lowdragmc.photon.client.render.PhotonTextureCopy;
import com.lowdragmc.photon.client.render.PhotonWorldRenderState;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Captures native world colors before UNORM clipping, then composes effects in linear HDR. */
public final class WorldHdrCapture {
    public static final WorldHdrCapture INSTANCE = new WorldHdrCapture();
    private static final Vector4f TRANSLUCENCY_CLEAR = new Vector4f(0, 0, 0, 1);
    private long outputFrameIndex;
    private final GeometryMotionRenderer geometryMotion = new GeometryMotionRenderer();
    private final DecalNormalHistory decalNormals = new DecalNormalHistory();
    private final com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture surfaceCapture =
            new com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture();
    private final com.kltyton.lumenfx.client.render.scene.surface.PhotonOpaqueMaskCapture photonOpaqueMask =
            new com.kltyton.lumenfx.client.render.scene.surface.PhotonOpaqueMaskCapture();
    private final SceneVelocityTarget niagaraVelocityTarget = new SceneVelocityTarget("LumenFX Niagara opaque velocity");
    private final PhotonTextureCopy niagaraPartialDepth = new PhotonTextureCopy("Niagara partial opaque depth", 0);
    private @Nullable GpuTextureView partialDepth;
    private @Nullable GpuTextureView shaderpackOpaqueDepth;
    private @Nullable GpuTexture photonExternalOpaqueDepth;
    private @Nullable GpuTextureView photonExternalOpaqueDepthView;
    private @Nullable GpuTextureView externalMotionDepth;
    private boolean externalOpaquePhase;
    private final com.kltyton.lumenfx.client.render.scene.material.SceneColorCopy materialSceneColor =
            new com.kltyton.lumenfx.client.render.scene.material.SceneColorCopy();
    private boolean materialSceneColorReady;
    private boolean motionFrameOpen, geometryPrepared;
    private boolean temporalParticleMotion;
    private FrameOwnership frameOwnership = FrameOwnership.inactive();
    private @Nullable ShaderpackFrame shaderpackFrame;
    private List<ParticleLight> sceneLights = List.of();
    private List<ScenePointLight> sceneComponentLights = List.of();
    private List<SceneDirectionalLight> sceneDirectionalLights = List.of();
    private LightFunctionAtlas.@Nullable Frame forwardLightFunctions;
    private DirectionalShadowRenderer.@Nullable Frame forwardDirectionalShadows;
    private DirectionalLights translucencyDirectionalLights = DirectionalLights.EMPTY;
    private LocalLights translucencyLocalLights = LocalLights.EMPTY;
    private List<ScenePointLight> fogLocalLights = List.of();
    private Map<ScenePointLight, VolumeShadow> fogLocalShadows = Map.of();
    private @Nullable VolumeMaterial fogDirectionalMaterial;
    private LightFunctionAtlas.@Nullable Frame fogLightFunctions;
    private com.kltyton.lumenfx.client.render.decal.DecalBuffers.@Nullable Frame sceneDecals;
    private float jitterX, jitterY, previousJitterX, previousJitterY;
    private boolean sourceJitterKnown = true, previousJitterKnown = true;
    private int postVolumeUserFlags;
    private TextureTarget encodedWorld;
    private TextureTarget linearWorld;
    private TextureTarget photonWorld;
    private TextureTarget translucency;
    private TextureTarget beforeDofTranslucency;
    private TextureTarget afterMotionBlurTranslucency;
    private TextureTarget composedWorld;
    private TextureTarget compositionScratch;
    private enum Transfer { DECODE, ENCODE }
    private record ColorTransfer(Transfer transfer, int encoding, GpuFormat format) { }
    private final Map<ColorTransfer, RenderPipeline> colorTransfers = new HashMap<>();
    private GlSceneTransfer externalTransfer;
    private final com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy depthCopy =
            new com.kltyton.lumenfx.client.render.hdr.external.DepthTextureCopy();
    private @Nullable HostSurfaceReconstructor hostSurfaceReconstructor;
    private final Map<GpuFormat, RenderPipeline> modulationComposites = new HashMap<>();
    private final TranslucencyModulationTargets modulationTargets = new TranslucencyModulationTargets();
    private RenderTarget output;
    private RenderSettings settings;
    private GpuBufferSlice worldFog;
    private final Matrix4f worldProjection = new Matrix4f();
    private final Matrix4f unjitteredWorldProjection = new Matrix4f();
    private boolean viewPaused;
    private long renderFrameSequence;
    private final Matrix4f cameraProjectionInverse = new Matrix4f();
    private final Matrix4f baseCameraProjection = new Matrix4f();
    private final Vector4f cameraNearPoint = new Vector4f();
    private boolean projectionCaptured;
    private com.kltyton.lumenfx.client.render.scene.material.@Nullable FirstPersonMaterialView firstPersonMaterialView;
    private final net.minecraft.client.renderer.Projection firstPersonProjection = new net.minecraft.client.renderer.Projection();
    private final Matrix4f firstPersonProjectionMatrix = new Matrix4f();
    private boolean nearClipCaptured;
    private float nearClipCm;
    private float depthBias, depthScaleCm;
    private long cameraCutVersion;
    private boolean capturingNative;
    private boolean composing;
    private boolean translucencyReady;
    private boolean beforeDofReady, afterMotionBlurReady;
    private @Nullable RenderTarget translucencyWrittenDepth;
    private final EnumSet<TranslucencyPass> translucencyDepthStages = EnumSet.noneOf(TranslucencyPass.class);
    private boolean opaqueParticleDepthWritten;
    private final FogView fogView = new FogView();
    private final Map<VolumetricFog, VolumetricFogTarget> volumeFog = new HashMap<>();
    private final Map<VolumetricFog, VolumetricFogTarget.Frame> volumeFrames = new HashMap<>();
    private Object volumeFogWorld;
    private FogIntegration volumeIntegrator;
    private boolean volumeSceneLighting;
    private Vec3 fogCamera;
    private Object fogWorld, fogCameraOwner;
    private boolean fogViewReady;
    private boolean decalNormalReprojection;
    private boolean capturesNiagaraGBuffer;
    private boolean capturesSubsurfaceHistory;
    private @Nullable GpuTextureView sceneDecalAo;
    private OpaqueParticleSurfaces.@Nullable Frame particleGBuffer;

    private static final float[] DEFAULT_TAA_PARAMETERS = {0f, 1f, 0f, 0f};

    private WorldHdrCapture() { }

    public void configureFogIntegration(FogIntegration fog) {
        RenderSystem.assertOnRenderThread();
        if (volumeIntegrator != null)
            throw new IllegalStateException("The previous fog integration resources have not been released");
        volumeIntegrator = fog;
    }

    private FogIntegration fogIntegration() {
        if (volumeIntegrator == null) throw new IllegalStateException("The fog integration program has not been prepared");
        return volumeIntegrator;
    }

    public void beginRenderFrame() {
        renderFrameSequence = Math.incrementExact(renderFrameSequence);
    }

    public long renderFrameSequence() { return renderFrameSequence; }

    public void prepareFrameOwnership() {
        RenderSystem.assertOnRenderThread();
        FrameOwnership next = ShaderpackCompat.prepareFrameOwnership();
        if (next.identity() != frameOwnership.identity() || next.external() != frameOwnership.external()) {
            cameraCut();
            geometryMotion.discardFrame();
            MotionHistory.INSTANCE.reset();
        }
        frameOwnership = next;
    }

    public boolean externalSceneOwner() { return frameOwnership.external(); }

    public float materialViewMipBias() {
        return 0;
    }

    public float materialResolutionFraction() {
        return 1;
    }

    public float @Nullable [] temporalAAParameters() {
        RenderSystem.assertOnRenderThread();
        if (externalSceneOwner()) {
            float[] samples = java.util.Objects.requireNonNull(shaderpackFrame,
                    "Temporal view requires the current shaderpack frame").temporalAAParameters();
            return samples != null && samples.length == 4 ? samples : null;
        }
        return DEFAULT_TAA_PARAMETERS;
    }

    public float eyeAdaptationExposure() {
        return shaderpackFrame != null && Float.isFinite(shaderpackFrame.cameraExposure()) && shaderpackFrame.cameraExposure() > 0f
                ? shaderpackFrame.cameraExposure() : 1f;
    }

    public int postVolumeUserFlags() { return postVolumeUserFlags; }

    public void setPostVolumeUserFlags(int postVolumeUserFlags) { this.postVolumeUserFlags = postVolumeUserFlags; }

    public void writeMaterialView(java.nio.ByteBuffer destination, int inputs) {
        RenderSystem.assertOnRenderThread();
        if ((inputs & 1) == 0) destination.putFloat(0).putFloat(0).putFloat(0).putFloat(0);
        else {
            float[] samples = temporalAAParameters();
            if (samples == null) throw new IllegalStateException("The shaderpack has not supplied its temporal sample parameters");
            if (samples.length != 4) throw new IllegalStateException("Shaderpack temporal sample parameters must have four components");
            for (float value : samples) destination.putFloat(value);
        }
        // This scene is maintained without pre-exposure; camera tone exposure is applied downstream.
        float stateFrameIndex = (inputs & MaterialDefinition.VIEW_STATE_FRAME_INDEX) == 0
                ? 0f : (Math.max(0L, renderFrameSequence - 1L) & 63L);
        destination.putFloat((inputs & 4) == 0 ? 0 : nearClipCm()).putFloat(1).putFloat(1).putFloat(stateFrameIndex);
    }

    public void begin(RenderTarget destination, RenderSettings profile) {
        begin(destination, profile, RenderSystem.getShaderFog());
    }

    public void begin(RenderTarget destination, RenderSettings profile, GpuBufferSlice fog) {
        RenderSystem.assertOnRenderThread();
        if (capturingNative || composing) throw new IllegalStateException("A world HDR capture is already active");
        surfaceCapture.discardFrame();
        shaderpackFrame = null;
        sceneLights = java.util.List.of();
        sceneComponentLights = List.of();
        sceneDirectionalLights = List.of();
        forwardLightFunctions = null;
        forwardDirectionalShadows = null;
        translucencyDirectionalLights = DirectionalLights.EMPTY;
        translucencyLocalLights = LocalLights.EMPTY;
        fogLocalLights = List.of(); fogLocalShadows = Map.of();
        fogDirectionalMaterial = null;
        fogLightFunctions = null;
        sceneDecals = null;
        partialDepth = null;
        photonOpaqueMask.discardFrame();
        worldFog = fog;
        materialSceneColorReady = false;
        projectionCaptured = false;
        nearClipCaptured = false;
        translucencyReady = false;
        beforeDofReady = afterMotionBlurReady = false;
        translucencyWrittenDepth = null;
        translucencyDepthStages.clear();
        opaqueParticleDepthWritten = false;
        modulationTargets.reset();
        volumeFrames.clear();
        volumeSceneLighting = false;
        fogViewReady = false;
        geometryPrepared = false;
        capturesNiagaraGBuffer = false;
        capturesSubsurfaceHistory = false;
        sceneDecalAo = null;
        particleGBuffer = null;
        var effects = com.kltyton.lumenfx.client.VfxEngine.INSTANCE;
        boolean nativeDecalReceivers = profile != null && !externalSceneOwner() && effects.hasActiveDecalRenderers();
        decalNormalReprojection = nativeDecalReceivers && DecalNormalHistory.enabled();
        if (!decalNormalReprojection) decalNormals.close();
        if (profile == null) {
            if (externalSceneOwner()) {
                output = destination;
                settings = RenderSettings.STANDARD;
                motionFrameOpen = MotionHistory.INSTANCE.isEnabled();
                capturesNiagaraGBuffer = effects.hasActiveGBufferReaders();
                temporalParticleMotion = effects.hasActiveNiagaraVelocityReaders();
            }
            return;
        }
        output = destination;
        settings = profile;
        motionFrameOpen = MotionHistory.INSTANCE.isEnabled();
        capturesNiagaraGBuffer = effects.hasActiveGBufferReaders();
        temporalParticleMotion = effects.hasActiveNiagaraVelocityReaders();
        if (externalSceneOwner()) return;
        ensureLinearWorld(destination.width, destination.height, GpuFormat.RGBA16_FLOAT);
        capturingNative = true;
        var platform = NiagaraPlatforms.snapshot();
        boolean hasLitOpaque = effects.hasActiveLitOpaqueMaterials();
        capturesSubsurfaceHistory = effects.hasActiveSubsurfaceHistory();
        boolean deferredSky = hasLitOpaque && (DeferredSkyLighting.active(profile.indirectLighting())
                || effects.hasActiveDeferredSkyLighting());
        temporalParticleMotion |= capturesSubsurfaceHistory;
        boolean opaqueIndirect = hasLitOpaque && (SceneIndirectDiffuse.hasOpaqueBasePassLighting(profile.indirectLighting(), platform)
                || effects.hasActiveOpaqueIndirectLighting(platform));
        if (nativeDecalReceivers || effects.hasActiveLightRenderers() || capturesNiagaraGBuffer
                || opaqueIndirect || deferredSky || capturesSubsurfaceHistory)
            surfaceCapture.begin(destination, nativeDecalReceivers);
    }

    private void ensureLinearWorld(int width, int height, GpuFormat format) {
        if (linearWorld != null && linearWorld.getColorTexture().getFormat() != format) {
            linearWorld.destroyBuffers();
            linearWorld = null;
        }
        if (linearWorld == null) {
            linearWorld = new TextureTarget("LumenFX linear particle view", width, height, true, format);
        } else if (linearWorld.width != width || linearWorld.height != height) {
            linearWorld.resize(width, height);
        }
    }

    private void ensureShaderpackTransfer(int width, int height, GpuFormat format) {
        ensureLinearWorld(width, height, GpuFormat.RGBA32_FLOAT);
        if (encodedWorld != null && encodedWorld.getColorTexture() != null
                && encodedWorld.getColorTexture().getFormat() != format) {
            encodedWorld.destroyBuffers();
            encodedWorld = null;
        }
        if (encodedWorld == null) {
            encodedWorld = new TextureTarget("LumenFX shaderpack transfer", width, height, false, format);
        } else if (encodedWorld.width != width || encodedWorld.height != height) {
            encodedWorld.resize(width, height);
        }
    }

    private void ensurePhotonWorld(int width, int height) {
        if (photonWorld != null && photonWorld.getColorTexture() != null
                && photonWorld.getColorTexture().getFormat() != GpuFormat.RGBA16_FLOAT) {
            photonWorld.destroyBuffers();
            photonWorld = null;
        }
        if (photonWorld == null) {
            photonWorld = new TextureTarget("LumenFX external Photon view", width, height, false, GpuFormat.RGBA16_FLOAT);
        } else if (photonWorld.width != width || photonWorld.height != height) {
            photonWorld.resize(width, height);
        }
    }

    private static boolean isFloatingPointFormat(GpuFormat format) {
        return format == GpuFormat.RGBA16_FLOAT || format == GpuFormat.RGBA32_FLOAT || format == GpuFormat.RG11B10_FLOAT
                || format == GpuFormat.R16_FLOAT || format == GpuFormat.R32_FLOAT
                || format == GpuFormat.RG16_FLOAT || format == GpuFormat.RG32_FLOAT;
    }

    private static GpuFormat resolveShaderpackTransferFormat(ShaderpackFrame frame) {
        return switch (frame.sceneColorFormatName()) {
            case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
            case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
            case "RGBA8", "RGB8", "RG8", "R8", "RGBA", "RGBA4", "RGBA2", "RGB5_A1", "RGB565", "R3_G3_B2" ->
                GpuFormat.RGBA8_UNORM;
            case "RGBA16", "RGB16", "RG16", "R16" -> GpuFormat.RGBA16_UNORM;
            case "RGBA32F", "RGB32F", "RG32F", "R32F" -> GpuFormat.RGBA32_FLOAT;
            default -> GpuFormat.RGBA16_FLOAT;
        };
    }

    public ShaderpackMaterialSurfaces resolveShaderpackSurfaces(ShaderpackFrame frame, Matrix4fc fallbackProjection) {
        RenderSystem.assertOnRenderThread();
        var existing = frame.materialSurfaces();
        if (existing != null && existing.width() == linearWorld.width && existing.height() == linearWorld.height) {
            return existing;
        }
        Matrix4fc projection = frame.materialProjection();
        if (projection == null) {
            projection = existing != null && existing.materialProjection() != null
                    ? existing.materialProjection()
                    : fallbackProjection;
        }
        if (hostSurfaceReconstructor == null) {
            hostSurfaceReconstructor = new HostSurfaceReconstructor();
        }
        return hostSurfaceReconstructor.reconstruct(linearWorld, projection);
    }

    private void copyDepth(GpuTexture source, GpuTexture destination) {
        copyDepth(RenderSystem.getDevice().createCommandEncoder(), source, destination);
    }

    private void copyDepth(RenderTarget source, RenderTarget destination) {
        copyDepth(Objects.requireNonNull(source.getDepthTexture()), Objects.requireNonNull(destination.getDepthTexture()));
    }

    /** Copies any host, shaderpack, or internal depth texture safely even when USAGE_COPY_SRC is absent. */
    public void copyDepth(CommandEncoder encoder, GpuTexture source, GpuTexture destination) {
        RenderSystem.assertOnRenderThread();
        if (externalTransfer == null) {
            externalTransfer = new GlSceneTransfer();
        }
        depthCopy.copy(encoder, source, destination, externalTransfer);
    }

    public boolean capturingNative() { return capturingNative; }
    public com.kltyton.lumenfx.client.render.scene.surface.SceneSurfaceCapture.@Nullable Surfaces sceneSurfaces() { return surfaceCapture.surfaces(); }
    public boolean composing() { return composing; }
    public @Nullable ShaderpackFrame shaderpackFrame() { return shaderpackFrame; }
    public @Nullable GpuTextureView nativeEncodedScene() {
        return composing && shaderpackFrame == null ? output.getColorTextureView() : null;
    }
    public @Nullable GpuTextureView shaderpackEncodedScene() {
        return composing && shaderpackFrame != null ? encodedWorld.getColorTextureView() : null;
    }
    public com.kltyton.lumenfx.client.render.decal.DecalBuffers.@Nullable Frame sceneDecals() { return sceneDecals; }
    public void sceneDecals(com.kltyton.lumenfx.client.render.decal.DecalBuffers.@Nullable Frame value) { sceneDecals = value; }
    public void sceneLights(List<ParticleLight> lights, List<ScenePointLight> components, List<SceneDirectionalLight> directional) {
        if (!volumeFrames.isEmpty()) throw new IllegalStateException("Scene lights must be published before volume preparation");
        sceneLights = List.copyOf(lights);
        sceneComponentLights = List.copyOf(components);
        sceneDirectionalLights = List.copyOf(directional);
        forwardLightFunctions = null;
        forwardDirectionalShadows = null;
        translucencyDirectionalLights = DirectionalLights.EMPTY;
    }
    public List<ParticleLight> sceneLights() { return sceneLights; }
    public List<ScenePointLight> sceneComponentLights() { return sceneComponentLights; }
    public List<SceneDirectionalLight> sceneDirectionalLights() { return sceneDirectionalLights; }
    public LightFunctionAtlas.@Nullable Frame forwardLightFunctions() { return forwardLightFunctions; }
    public void forwardLightFunctions(LightFunctionAtlas.@Nullable Frame frame) { forwardLightFunctions = frame; }
    public DirectionalShadowRenderer.@Nullable Frame forwardDirectionalShadows() { return forwardDirectionalShadows; }
    public void forwardDirectionalShadows(DirectionalShadowRenderer.@Nullable Frame frame) { forwardDirectionalShadows = frame; }
    public DirectionalLights translucencyDirectionalLights() { return translucencyDirectionalLights; }
    public void translucencyDirectionalLights(DirectionalLights value) { translucencyDirectionalLights = value; }
    public LocalLights translucencyLocalLights() { return translucencyLocalLights; }
    public void translucencyLocalLights(LocalLights value) { translucencyLocalLights = value; }
    public @Nullable ViewHistory translucencyViewHistory(boolean paused) {
        return !fogViewReady || fogWorld == null || fogCameraOwner == null ? null
                : new ViewHistory(fogWorld, fogCameraOwner, cameraCutVersion, renderFrameSequence, paused);
    }
    public boolean usesVolumeSceneLighting() { return composing && volumeSceneLighting; }

    public void volumeSceneLighting(boolean required) { volumeSceneLighting = required; }
    public SceneIndirectLighting sceneIndirectLighting() {
        return composing ? settings.indirectLighting() : SceneIndirectLighting.NONE;
    }

    public void fogLocalLights(List<ScenePointLight> lights, Map<ScenePointLight, VolumeShadow> shadows,
                               LightFunctionAtlas.@Nullable Frame lightFunctions, @Nullable VolumeMaterial directionalMaterial) {
        if (!volumeFrames.isEmpty()) throw new IllegalStateException("Local lights must be published before volume preparation");
        fogLocalLights = List.copyOf(lights);
        fogLocalShadows = shadows;
        fogLightFunctions = lightFunctions;
        fogDirectionalMaterial = directionalMaterial;
    }
    public boolean viewPaused() { return (temporalParticleMotion || decalNormalReprojection) && motionFrameOpen && viewPaused; }
    public boolean fogViewPaused() { return viewPaused; }
    public boolean particleMotionActive() { return (temporalParticleMotion || decalNormalReprojection) && motionFrameOpen && geometryPrepared; }
    public boolean decalNormalReprojection() { return decalNormalReprojection && motionFrameOpen && geometryPrepared; }
    public boolean capturesNiagaraGBuffer() { return capturesNiagaraGBuffer; }
    public boolean capturesSubsurfaceHistory() { return capturesSubsurfaceHistory; }
    public void sceneDecalAo(@Nullable GpuTextureView value) { sceneDecalAo = value; }
    public @Nullable GpuTextureView sceneDecalAo() { return sceneDecalAo; }
    public OpaqueParticleSurfaces.@Nullable Frame particleGBuffer() { return particleGBuffer; }
    public void particleGBuffer(OpaqueParticleSurfaces.Frame frame) { particleGBuffer = frame; }
    public DecalNormalHistory.@Nullable Input decalNormalInput(@Nullable GpuTextureView depth) {
        if (!decalNormalReprojection() || !decalNormals.hasPreviousView()) return null;
        return decalNormals.input(prepareNiagaraSceneVelocity(depth));
    }
    public @Nullable CoverageTargets particleMotionTargets() {
        return particleMotionActive() ? geometryMotion.coverageTargets() : null;
    }
    public @Nullable GpuTextureView particleMotionVectorWorldOffset() {
        return particleMotionActive() ? geometryMotion.motionOffset() : null;
    }
    public GpuTextureView particleMotionDepth(com.mojang.blaze3d.systems.CommandEncoder encoder, RenderTarget scene) {
        if (!particleMotionActive()) throw new IllegalStateException("Particle velocity frame is not prepared");
        return geometryMotion.particleDepth(encoder, scene);
    }

    public boolean hasParticleMotionDepth() { return particleMotionActive() && geometryMotion.hasParticleDepth(); }
    public void snapshotTranslucentMotionPreColorDepth(com.mojang.blaze3d.systems.CommandEncoder encoder, RenderTarget scene) {
        if (hasParticleMotionDepth()) geometryMotion.snapshotTranslucentPreColorDepth(encoder, scene);
    }
    public @Nullable GpuTextureView translucentMotionPreColorDepth() {
        return hasParticleMotionDepth() ? geometryMotion.translucentPreColorDepth() : null;
    }
    public void clearTranslucentMotionPreColorDepth() { geometryMotion.clearTranslucentPreColorDepth(); }
    public boolean executeTranslucentMotionLog(
            com.mojang.blaze3d.systems.CommandEncoder encoder, RenderTarget scene,
            CoverageTargets targets,
            com.mojang.blaze3d.systems.@Nullable ScissorState scissor,
            List<com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.DrawSpec> draws,
            long maxPrimitives,
            java.util.function.BiConsumer<com.mojang.blaze3d.systems.RenderPass,
                    com.kltyton.lumenfx.client.render.temporal.geometry.TranslucentMotionReplayLog.DrawCursor> captureDrawer,
            java.util.function.Consumer<com.mojang.blaze3d.systems.ScissorState> singlePixelOverflowFallback) {
        if (!particleMotionActive()) throw new IllegalStateException("Particle velocity frame is not prepared");
        return geometryMotion.executeTranslucentMotionLog(encoder, scene, targets, scissor, draws,
                maxPrimitives, captureDrawer, singlePixelOverflowFallback);
    }
    public void beginTranslucentStepReplay(com.mojang.blaze3d.systems.CommandEncoder encoder, RenderTarget scene) {
        if (!particleMotionActive()) throw new IllegalStateException("Particle velocity frame is not prepared");
        geometryMotion.beginTranslucentStepReplay(encoder, scene);
    }
    public void runTranslucentStepPrimitive(
            com.mojang.blaze3d.systems.CommandEncoder encoder, RenderTarget scene,
            CoverageTargets targets,
            com.kltyton.lumenfx.effect.render.MaterialDepth.Compare compare, boolean writesColorDepth,
            int instanceBase, int indirectOffsetUints, boolean indirectGuard,
            com.mojang.blaze3d.systems.@Nullable ScissorState scissor,
            java.util.function.BiConsumer<com.mojang.blaze3d.systems.RenderPass, GpuBufferSlice> drawPrimitive) {
        if (!particleMotionActive()) throw new IllegalStateException("Particle velocity frame is not prepared");
        geometryMotion.runTranslucentStepPrimitive(encoder, scene, targets, compare, writesColorDepth,
                instanceBase, indirectOffsetUints, indirectGuard, scissor, drawPrimitive);
    }
    public void recordParticleMotionHistory(boolean valid) { geometryMotion.recordParticleHistory(valid); }
    public void writeTemporalJitter(ByteBuffer data) {
        data.putFloat(jitterX).putFloat(jitterY).putFloat(previousJitterX).putFloat(previousJitterY);
    }
    public Object cameraOwner() { return fogCameraOwner; }

    public void prepareGeometryMotion(Object world, Object cameraOwner) {
        RenderSystem.assertOnRenderThread();
        if ((!capturingNative && !externalSceneOwner()) || !motionFrameOpen) return;
        if (geometryPrepared) throw new IllegalStateException("World geometry was prepared more than once for the current view");
        if (capturingNative) {
            geometryMotion.prepare(MotionHistory.INSTANCE.preparedDraws(), output, world, cameraOwner, cameraCutVersion);
            geometryPrepared = true;
            return;
        }
        GpuTexture externalDepth = ShaderpackCompat.activeSceneDepth();
        if (externalDepth != null) {
            geometryMotion.prepare(MotionHistory.INSTANCE.preparedDraws(), externalMotionDepthView(externalDepth),
                    externalDepth.getWidth(0), externalDepth.getHeight(0), world, cameraOwner, cameraCutVersion);
            geometryPrepared = true;
        } else if (output != null && output.getDepthTextureView() != null) {
            geometryMotion.prepare(MotionHistory.INSTANCE.preparedDraws(), output, world, cameraOwner, cameraCutVersion);
            geometryPrepared = true;
        }
    }

    public void syncExternalMotionDepth(@Nullable GpuTexture depthTexture) {
        RenderSystem.assertOnRenderThread();
        if (!externalSceneOwner() || !motionFrameOpen || !geometryPrepared || depthTexture == null) return;
        geometryMotion.updateSceneDepth(externalMotionDepthView(depthTexture));
    }

    private GpuTextureView externalMotionDepthView(GpuTexture texture) {
        var view = externalMotionDepth;
        if (view != null && view.texture() == texture) return view;
        if (view != null) PhotonWorldRenderState.closeAtFrameEnd(view);
        externalMotionDepth = RenderSystem.getDevice().createTextureView(texture);
        return externalMotionDepth;
    }

    public @Nullable GpuTextureView prepareNiagaraSceneVelocity(@Nullable GpuTextureView sceneDepth) {
        RenderSystem.assertOnRenderThread();
        if (!motionFrameOpen || !geometryPrepared || sceneDepth == null) return null;
        var previous = geometryMotion.previousNdc();
        if (previous.getWidth(0) != sceneDepth.getWidth(0) || previous.getHeight(0) != sceneDepth.getHeight(0)) return null;
        return niagaraVelocityTarget.resolve(geometryMotion.velocityDepth(sceneDepth), previous, geometryMotion.coverageDepth(),
                geometryMotion.hasFrameHistory() && sourceJitterKnown && previousJitterKnown,
                jitterX, jitterY, previousJitterX, previousJitterY);
    }

    private void closeMotionFrame(boolean submitted) {
        if (!motionFrameOpen) return;
        if (submitted) {
            if (viewPaused) {
                geometryMotion.holdFrame();
                MotionHistory.INSTANCE.holdFrame();
            } else {
                geometryMotion.commitFrame();
                MotionHistory.INSTANCE.endFrame();
                previousJitterX = jitterX;
                previousJitterY = jitterY;
                previousJitterKnown = sourceJitterKnown;
            }
        } else {
            geometryMotion.discardFrame();
            MotionHistory.INSTANCE.reset();
        }
        motionFrameOpen = false;
        geometryPrepared = false;
        temporalParticleMotion = false;
    }
    public RenderTarget linearTarget() { return linearWorld; }
    public GpuBufferSlice worldFog() {
        if (worldFog == null) throw new IllegalStateException("The current world view has no fog uniform");
        return worldFog;
    }

    public void captureProjection(Matrix4fc projection) {
        RenderSystem.assertOnRenderThread();
        worldProjection.set(projection);
        unjitteredWorldProjection.set(projection);
        jitterX = jitterY = 0;
        sourceJitterKnown = true;
        projectionCaptured = true;
    }

    public void prepareProjection(Matrix4f projection, boolean paused) {
        viewPaused = paused;
        captureProjection(projection);
    }

    public void copyUnjitteredProjection(Matrix4f destination) {
        if (!projectionCaptured) throw new IllegalStateException("The current world projection has not been submitted");
        destination.set(unjitteredWorldProjection);
    }

    public void copyProjection(Matrix4f destination) {
        RenderSystem.assertOnRenderThread();
        if (!projectionCaptured) throw new IllegalStateException("The current world projection has not been submitted");
        destination.set(worldProjection);
    }

    /** Supplies the projection actually submitted by the host, including its view effects. */
    public void copyProjectionIfAvailable(Matrix4f destination) {
        RenderSystem.assertOnRenderThread();
        if (projectionCaptured) destination.set(worldProjection);
    }

    public void captureCameraProjection(Matrix4fc projection) {
        RenderSystem.assertOnRenderThread();
        baseCameraProjection.set(projection);
        cameraProjectionInverse.set(projection).invert().transform(cameraNearPoint.set(0, 0, 1, 1));
        nearClipCm = Math.abs(cameraNearPoint.z / cameraNearPoint.w) * 100.0f;
        depthBias = projection.m22();
        depthScaleCm = projection.m32() * 100.0f;
        nearClipCaptured = true;
    }

    public void captureFirstPersonView(net.minecraft.client.renderer.state.GameRenderState state) {
        RenderSystem.assertOnRenderThread();
        var camera = state.levelRenderState.cameraRenderState;
        var window = state.windowRenderState;
        firstPersonProjection.setupPerspective(.05f, 100, camera.hudFov, window.width, window.height);
        firstPersonProjection.getMatrix(firstPersonProjectionMatrix);
        firstPersonMaterialView = com.kltyton.lumenfx.client.render.scene.material.FirstPersonMaterialView.fromProjections(
                camera.projectionMatrix, firstPersonProjectionMatrix, camera.viewRotationMatrix, 1,
                state.optionsRenderState.cameraType.isFirstPerson() && !camera.isPanoramicMode);
    }

    public void captureFirstPersonView(com.kltyton.lumenfx.client.render.scene.material.FirstPersonMaterialView view) {
        RenderSystem.assertOnRenderThread();
        firstPersonMaterialView = java.util.Objects.requireNonNull(view, "view");
    }

    public com.kltyton.lumenfx.client.render.scene.material.@Nullable FirstPersonMaterialView firstPersonMaterialView() {
        return firstPersonMaterialView;
    }

    public void copyCameraProjection(Matrix4f destination) {
        if (!nearClipCaptured) throw new IllegalStateException("The current canonical camera projection has not been captured");
        destination.set(baseCameraProjection);
    }

    public float nearClipCm() {
        if (!nearClipCaptured) throw new IllegalStateException("The current camera clipping range has not been captured");
        return nearClipCm;
    }

    public float depthBias() { return depthBias; }
    public float depthScaleCm() { return depthScaleCm; }
    public long cameraCutVersion() { return cameraCutVersion; }
    public void cameraCut() {
        cameraCutVersion++;
        outputFrameIndex = 0;
        previousJitterX = previousJitterY = 0;
        previousJitterKnown = false;
    }
    public long outputFrameIndex() { return outputFrameIndex; }

    public void prepareFogView(Matrix4fc modelView, Vec3 camera, Object world, Object cameraOwner) {
        RenderSystem.assertOnRenderThread();
        if (volumeFogWorld != world) {
            volumeFog.values().forEach(PhotonWorldRenderState::closeAtFrameEnd);
            volumeFog.clear();
            volumeFrames.clear();
            volumeFogWorld = world;
        }
        fogView.prepare(modelView, camera);
        fogCamera = camera;
        fogWorld = world;
        fogCameraOwner = cameraOwner;
        fogViewReady = true;
        if (decalNormalReprojection())
            decalNormals.begin(world, cameraOwner, cameraCutVersion, camera, unjitteredWorldProjection, modelView,
                    linearWorld.width, linearWorld.height);
    }

    public VolumetricFogTarget.Frame prepareSceneFog(VolumetricFog profile) {
        RenderSystem.assertOnRenderThread();
        if (!composing || !fogViewReady) throw new IllegalStateException("The particle fog view has not been prepared");
        var cached = volumeFrames.get(profile);
        if (cached != null) return cached;
        var target = volumeFog.computeIfAbsent(profile, value -> new VolumetricFogTarget(value, fogIntegration()));
        var opaqueDepth = particleOpaqueDepth();
        var frame = target.prepare(fogView, fogCamera, fogWorld, fogCameraOwner, opaqueDepth,
                sceneLights, fogLocalLights, fogLocalShadows, fogLightFunctions,
                SceneDirectionalLight.selectForward(sceneDirectionalLights), forwardDirectionalShadows, fogDirectionalMaterial,
                eyeAdaptationExposure());
        volumeFrames.put(profile, frame);
        return frame;
    }

    public VolumetricFogTarget.Frame volumeFrame(VolumetricFog expected) {
        var frame = volumeFrames.get(expected);
        if (frame == null)
            throw new IllegalStateException("The current source volume fog has not been prepared for this view");
        return frame;
    }

    public void beginComposition() {
        RenderSystem.assertOnRenderThread();
        if (!capturingNative) return;
        surfaceCapture.end();
        capturingNative = false;
        composing = true;
        decodeWorld(output, 1);
        copyDepth(output, linearWorld);
    }

    /** Preserves the first opaque depth stage before geometry from partial-depth readers writes its depth. */
    public void capturePartialDepth(CommandEncoder encoder, RenderTarget target) {
        partialDepth = Objects.requireNonNull(niagaraPartialDepth.copyOf(encoder,
                Objects.requireNonNull(target.getDepthTextureView())), "Cannot copy the native opaque depth attachment");
    }

    public @Nullable GpuTextureView partialDepth(RenderTarget target) {
        if (target != linearWorld || !composing) return null;
        return partialDepth == null ? target.getDepthTextureView() : partialDepth;
    }

    private void decodeWorld(RenderTarget source, int encoding) {
        transferColor(source, linearWorld, encoding, Transfer.DECODE);
    }

    private void transferColor(RenderTarget source, RenderTarget destination, int encoding, Transfer transfer) {
        var target = Objects.requireNonNull(destination.getColorTextureView());
        var input = Objects.requireNonNull(source.getColorTextureView());
        var key = new ColorTransfer(transfer, encoding, target.texture().getFormat());
        var pipeline = colorTransfers.computeIfAbsent(key, WorldHdrCapture::colorTransferPipeline);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        PhotonFullscreenPass.draw("LumenFX particle scene color transfer", pipeline, target,
                pass -> pass.bindTexture("WorldSampler", input, nearest));
    }

    private static RenderPipeline colorTransferPipeline(ColorTransfer key) {
        String shader = key.transfer() == Transfer.DECODE ? "world_to_linear" : "linear_to_world";
        return PhotonFullscreenPass.builder(LumenFx.id("post/" + shader))
                .withLocation(LumenFx.id("pipeline/" + shader + "/" + key.encoding() + "/" + key.format().ordinal()))
                .withShaderDefine("LFX_WORLD_ENCODING", key.encoding())
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("WorldSampler").build())
                .withColorTargetState(PhotonFullscreenPass.opaqueTarget(key.format()))
                .build();
    }

    public void beginExternalComposition(ShaderpackFrame frame, boolean opaquePhase) {
        RenderSystem.assertOnRenderThread();
        if (!externalSceneOwner() || capturingNative || composing || settings == null)
            throw new IllegalStateException("Shaderpack composition requires its prepared frame owner");
        validateExternalFrame(frame);
        unjitteredWorldProjection.set(frame.projectionMatrix());
        boolean jitterAvailable = frame.sourceJitterKnown()
                && Float.isFinite(frame.sourceJitterNdcX())
                && Float.isFinite(frame.sourceJitterNdcY());
        float jx = jitterAvailable ? frame.sourceJitterNdcX() : 0f;
        float jy = jitterAvailable ? frame.sourceJitterNdcY() : 0f;
        worldProjection.translation(jx, jy, 0)
                .mul(unjitteredWorldProjection);
        jitterX = jx;
        jitterY = jy;
        sourceJitterKnown = jitterAvailable;
        projectionCaptured = true;
        if (opaquePhase) {
            partialDepth = null;
            photonOpaqueMask.discardFrame();
        }
        importExternalScene(frame, true);
        if (motionFrameOpen && linearWorld.getDepthTextureView() != null) {
            if (!geometryPrepared) {
                Object motionWorld = fogWorld != null ? fogWorld : frame.worldIdentity();
                Object motionCamera = fogCameraOwner != null ? fogCameraOwner : frame.worldIdentity();
                geometryMotion.prepare(MotionHistory.INSTANCE.preparedDraws(), linearWorld, motionWorld, motionCamera, cameraCutVersion);
                geometryPrepared = true;
            } else {
                geometryMotion.updateSceneDepth(linearWorld.getDepthTextureView());
            }
        }
        shaderpackFrame = frame;
        externalOpaquePhase = opaquePhase;
        composing = true;
    }

    public @Nullable GpuTextureView externalPhotonOpaqueMask() { return photonOpaqueMask.maskView(); }

    /**
     * Returns whether an external opaque depth view must be staged into an owned render-attachment
     * depth texture before Photon opens a raster pass against it (either because the source lacks
     * {@link GpuTexture#USAGE_RENDER_ATTACHMENT}, such as Iris {@code depth1}, or to keep deferred
     * Photon depth writes isolated from the host shaderpack's shared opaque depth buffer).
     */
    public static boolean requiresPhotonOpaqueDepthCopy(int textureUsage, boolean sharedHostDepth) {
        return (textureUsage & GpuTexture.USAGE_RENDER_ATTACHMENT) == 0 || sharedHostDepth;
    }

    private @Nullable GpuTextureView ensurePhotonExternalOpaqueDepth(@Nullable GpuTextureView source) {
        if (source == null) return null;
        GpuTexture sourceTexture = source.texture();
        if (!requiresPhotonOpaqueDepthCopy(sourceTexture.usage(), source == shaderpackOpaqueDepth)) {
            return source;
        }
        int width = sourceTexture.getWidth(0);
        int height = sourceTexture.getHeight(0);
        GpuFormat format = sourceTexture.getFormat();
        if (photonExternalOpaqueDepth == null
                || photonExternalOpaqueDepth.isClosed()
                || photonExternalOpaqueDepth.getFormat() != format
                || photonExternalOpaqueDepth.getWidth(0) != width
                || photonExternalOpaqueDepth.getHeight(0) != height) {
            if (photonExternalOpaqueDepthView != null && !photonExternalOpaqueDepthView.isClosed()) {
                PhotonWorldRenderState.closeAtFrameEnd(photonExternalOpaqueDepthView);
            }
            if (photonExternalOpaqueDepth != null && !photonExternalOpaqueDepth.isClosed()) {
                PhotonWorldRenderState.closeAtFrameEnd(photonExternalOpaqueDepth);
            }
            var device = RenderSystem.getDevice();
            photonExternalOpaqueDepth = device.createTexture(
                    () -> "LumenFX external Photon opaque depth",
                    GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_DST
                            | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
                    format, width, height, 1, 1);
            photonExternalOpaqueDepthView = device.createTextureView(photonExternalOpaqueDepth);
        }
        copyDepth(sourceTexture, photonExternalOpaqueDepth);
        return photonExternalOpaqueDepthView;
    }

    /**
     * Runs Photon {@code .fx} draws against the active shaderpack scene in Photon's unclamped sRGB HDR
     * working space while testing and writing against the imported shaderpack depth buffers.
     */
    public void drawExternalPhoton(Matrix4fc modelView, Runnable draw) {
        RenderSystem.assertOnRenderThread();
        if (!externalSceneOwner() || !composing || linearWorld == null)
            throw new IllegalStateException("External Photon rendering requires an active shaderpack composition");
        ensurePhotonWorld(linearWorld.width, linearWorld.height);
        transferColor(linearWorld, photonWorld, 1, Transfer.ENCODE);
        GpuTextureView opaqueDepth = externalOpaquePhase ? null : ensurePhotonExternalOpaqueDepth(particleOpaqueDepth());
        GpuTextureView linearDepth = linearWorld.getDepthTextureView();
        boolean trackOpaquePhotonMask = externalOpaquePhase && capturesNiagaraGBuffer && linearDepth != null;
        if (trackOpaquePhotonMask) {
            photonOpaqueMask.begin(RenderSystem.getDevice().createCommandEncoder(), linearDepth.texture());
        }
        try {
            PhotonRenderOutput.withExternalOutput(
                    photonWorld.getColorTextureView(),
                    linearDepth,
                    opaqueDepth,
                    modelView,
                    draw);
        } finally {
            if (trackOpaquePhotonMask) {
                photonOpaqueMask.end();
            }
        }
        transferColor(photonWorld, linearWorld, 1, Transfer.DECODE);
    }

    public void finishExternalComposition(ShaderpackFrame frame) {
        finishExternalCompositionStages(frame, EnumSet.allOf(TranslucencyPass.class), true);
    }

    public void finishExternalCompositionStages(
            ShaderpackFrame frame, EnumSet<TranslucencyPass> stages, boolean finalStage) {
        RenderSystem.assertOnRenderThread();
        if (!externalSceneOwner() || !composing)
            throw new IllegalStateException("The shaderpack scene has not been imported");
        RenderTarget scene = composeStages(linearWorld, stages);
        exportExternalScene(frame, scene, depthTargetFor(stages, true));
        if (finalStage) {
            closeMotionFrame(true);
            finishFogFrame();
            composing = false;
        }
    }

    public void finishExternalDeferredComposition(
            ShaderpackFrame frame, EnumSet<TranslucencyPass> stages, boolean finalStage) {
        RenderSystem.assertOnRenderThread();
        if (!externalSceneOwner() || !composing)
            throw new IllegalStateException("The shaderpack scene has not been imported");
        validateExternalFrame(frame);
        shaderpackFrame = frame;
        RenderTarget deferredDepth = depthTargetFor(stages, false);
        if (anyLayerReady(stages)) {
            importExternalScene(frame, false);
            RenderTarget scene = composeStages(linearWorld, stages);
            exportExternalScene(frame, scene, deferredDepth);
        } else if (deferredDepth != null) {
            copyDepth(deferredDepth.getDepthTexture(), frame.depth0());
        }
        if (finalStage) {
            closeMotionFrame(true);
            finishFogFrame();
            composing = false;
        }
    }

    public void finishExternalOpaqueComposition(ShaderpackFrame frame) {
        RenderSystem.assertOnRenderThread();
        if (!externalSceneOwner() || !composing)
            throw new IllegalStateException("The shaderpack scene has not been imported");
        RenderTarget scene = composeStages(linearWorld, EnumSet.allOf(TranslucencyPass.class));
        RenderTarget depthSource = finalDepth();
        exportExternalScene(frame, scene, depthSource);
        copyDepth(depthSource.getDepthTexture(), frame.depth1());
        composing = false;
    }

    private static void validateExternalFrame(ShaderpackFrame frame) {
        if (!frame.available()) {
            throw new IllegalArgumentException("The shaderpack frame is unavailable: " + frame.packName());
        }
        if (frame.sceneColorWidth() <= 0 || frame.sceneColorHeight() <= 0
                || frame.viewportWidth() <= 0 || frame.viewportHeight() <= 0) {
            throw new IllegalArgumentException("The shaderpack scene or camera dimensions are invalid: " + frame.packName());
        }
    }

    private void importExternalScene(ShaderpackFrame frame, boolean importDepth) {
        int sceneWidth = frame.viewportWidth(), sceneHeight = frame.viewportHeight();
        int colorWidth = frame.sceneColorWidth(), colorHeight = frame.sceneColorHeight();
        GpuFormat transferFormat = resolveShaderpackTransferFormat(frame);
        ensureShaderpackTransfer(sceneWidth, sceneHeight, transferFormat);
        if (externalTransfer == null) externalTransfer = new GlSceneTransfer();
        externalTransfer.importColor(frame.sceneColorGlTextureId(), frame.sceneColorGlTarget(),
                colorWidth, colorHeight, encodedWorld.getColorTexture(), sceneWidth, sceneHeight);
        int encoding = frame.colourEncoding().orDefaultForFormat(isFloatingPointFormat(transferFormat)).shaderCode();
        decodeWorld(encodedWorld, encoding);
        if (importDepth) {
            copyDepth(frame.depth0(), linearWorld.getDepthTexture());
        }
    }

    private void exportExternalScene(ShaderpackFrame frame, RenderTarget scene, @Nullable RenderTarget depthSource) {
        int encoding = frame.colourEncoding().orDefaultForFormat(
                isFloatingPointFormat(encodedWorld.getColorTexture().getFormat())).shaderCode();
        transferColor(scene, encodedWorld, encoding, Transfer.ENCODE);
        if (externalTransfer == null) externalTransfer = new GlSceneTransfer();
        externalTransfer.exportColor(encodedWorld.getColorTexture(), encodedWorld.width, encodedWorld.height,
                frame.sceneColorGlTextureId(), frame.sceneColorGlTarget(),
                frame.sceneColorWidth(), frame.sceneColorHeight());
        if (depthSource != null) {
            copyDepth(depthSource.getDepthTexture(), frame.depth0());
        }
    }

    private @Nullable RenderTarget depthTargetFor(EnumSet<TranslucencyPass> stages, boolean includeLinear) {
        if (stages.contains(TranslucencyPass.AFTER_MOTION_BLUR)
                && translucencyDepthStages.contains(TranslucencyPass.AFTER_MOTION_BLUR)
                && afterMotionBlurTranslucency != null) {
            return afterMotionBlurTranslucency;
        }
        if (stages.contains(TranslucencyPass.AFTER_DOF)
                && translucencyDepthStages.contains(TranslucencyPass.AFTER_DOF)
                && translucency != null) {
            return translucency;
        }
        if (stages.contains(TranslucencyPass.BEFORE_DOF)
                && translucencyDepthStages.contains(TranslucencyPass.BEFORE_DOF)
                && beforeDofTranslucency != null) {
            return beforeDofTranslucency;
        }
        return includeLinear && opaqueParticleDepthWritten ? linearWorld : null;
    }

    private boolean anyLayerReady(EnumSet<TranslucencyPass> stages) {
        for (TranslucencyPass stage : stages) {
            if (layerReady(stage)) return true;
        }
        return false;
    }

    private RenderTarget composeStages(RenderTarget initialScene, EnumSet<TranslucencyPass> stages) {
        RenderTarget scene = initialScene;
        if (stages.contains(TranslucencyPass.BEFORE_DOF)) {
            scene = composeTranslucency(scene, TranslucencyPass.BEFORE_DOF);
        }
        if (stages.contains(TranslucencyPass.AFTER_DOF)) {
            scene = composeTranslucency(scene, TranslucencyPass.AFTER_DOF);
        }
        if (stages.contains(TranslucencyPass.AFTER_MOTION_BLUR)) {
            scene = composeTranslucency(scene, TranslucencyPass.AFTER_MOTION_BLUR);
        }
        return scene;
    }

    public void prepareMaterialSceneColor() {
        RenderSystem.assertOnRenderThread();
        if (!composing) throw new IllegalStateException("Material scene color requires an active scene");
        if (!materialSceneColorReady) {
            materialSceneColor.capture(RenderSystem.getDevice().createCommandEncoder(), linearWorld, false);
            materialSceneColorReady = true;
        }
    }

    public GpuTextureView materialSceneColor() {
        if (!materialSceneColorReady) throw new IllegalStateException("Material scene color has not been prepared for this frame");
        return materialSceneColor.view();
    }

    public void resumeNativeTransparency() {
        RenderSystem.assertOnRenderThread();
        if (!composing || externalSceneOwner()) throw new IllegalStateException("Native transparency requires the current native opaque scene");
        encodeNativeScene(linearWorld, output);
        copyDepth(linearWorld, output);
        composing = false;
        capturingNative = true;
    }

    public RenderTarget beginTranslucency(boolean sceneDepthChanged, boolean useSceneDepth, TranslucencyPass stage) {
        RenderSystem.assertOnRenderThread();
        if (!composing) throw new IllegalStateException("Separate translucency requires an active linear scene");
        TextureTarget layer = layer(stage);
        boolean ready = layerReady(stage);
        GpuFormat format = linearWorld.getColorTexture().getFormat();
        if (layer != null && layer.getColorTexture().getFormat() != format) {
            layer.destroyBuffers();
            layer = null;
            ready = false;
        }
        if (layer == null) {
            layer = new TextureTarget("LumenFX " + stage + " translucency", linearWorld.width, linearWorld.height,
                    true, format);
            switch (stage) {
                case BEFORE_DOF -> beforeDofTranslucency = layer;
                case AFTER_DOF -> translucency = layer;
                case AFTER_MOTION_BLUR -> afterMotionBlurTranslucency = layer;
            }
        } else if (layer.width != linearWorld.width || layer.height != linearWorld.height) {
            layer.resize(linearWorld.width, linearWorld.height);
            ready = false;
        }
        if (!ready) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(layer.getColorTexture(), TRANSLUCENCY_CLEAR);
            copyTranslucencyDepth(layer, stage, useSceneDepth);
            switch (stage) {
                case BEFORE_DOF -> beforeDofReady = true;
                case AFTER_DOF -> translucencyReady = true;
                case AFTER_MOTION_BLUR -> afterMotionBlurReady = true;
            }
        } else if (sceneDepthChanged || useSceneDepth) copyTranslucencyDepth(layer, stage, useSceneDepth);
        return layer;
    }

    public RenderTarget beginTranslucencyModulation(TranslucencyPass stage, RenderTarget colorLayer) {
        RenderSystem.assertOnRenderThread();
        if (!composing || colorLayer != layer(stage) || !layerReady(stage))
            throw new IllegalStateException("Modulation requires the current separate translucency layer");
        return modulationTargets.begin(stage, colorLayer);
    }

    private void copyTranslucencyDepth(RenderTarget target, TranslucencyPass stage, boolean useSceneDepth) {
        if (translucencyWrittenDepth != null && stage != TranslucencyPass.AFTER_MOTION_BLUR) {
            if (translucencyWrittenDepth != target) {
                copyDepth(Objects.requireNonNull(translucencyWrittenDepth.getDepthTexture()),
                        Objects.requireNonNull(target.getDepthTexture()));
            }
            return;
        }
        if (useSceneDepth || opaqueParticleDepthWritten) {
            // A layer that can publish depth must begin with every write already made to the host target.
            copyDepth(Objects.requireNonNull(linearWorld.getDepthTexture()),
                    Objects.requireNonNull(target.getDepthTexture()));
            return;
        }
        var source = particleOpaqueDepth().texture();
        copyDepth(source, Objects.requireNonNull(target.getDepthTexture()));
    }

    private GpuTextureView particleOpaqueDepth() {
        // In the opaque callback, the current linear attachment already contains the opaque pass.
        if (shaderpackFrame != null && !externalOpaquePhase) return shaderpackOpaqueDepth(shaderpackFrame.depth1());
        if (output != null && output.getDepthTextureView() != null) {
            var opaque = OpaqueDepthCapture.view(output.getDepthTextureView());
            if (opaque != null) return opaque;
        }
        return Objects.requireNonNull(linearWorld.getDepthTextureView());
    }

    private GpuTextureView shaderpackOpaqueDepth(GpuTexture texture) {
        var view = shaderpackOpaqueDepth;
        if (view != null && view.texture() == texture) return view;
        if (view != null) PhotonWorldRenderState.closeAtFrameEnd(view);
        shaderpackOpaqueDepth = RenderSystem.getDevice().createTextureView(texture);
        return shaderpackOpaqueDepth;
    }

    public void noteTranslucencyDepthWrite(RenderTarget target) {
        translucencyWrittenDepth = target;
        if (target == beforeDofTranslucency) translucencyDepthStages.add(TranslucencyPass.BEFORE_DOF);
        else if (target == translucency) translucencyDepthStages.add(TranslucencyPass.AFTER_DOF);
        else if (target == afterMotionBlurTranslucency) translucencyDepthStages.add(TranslucencyPass.AFTER_MOTION_BLUR);
    }

    public void noteOpaqueParticleDepthWrite() { opaqueParticleDepthWritten = true; }

    private RenderTarget finalDepth() {
        return translucencyWrittenDepth == null ? linearWorld : translucencyWrittenDepth;
    }

    private TextureTarget layer(TranslucencyPass stage) {
        return switch (stage) {
            case BEFORE_DOF -> beforeDofTranslucency;
            case AFTER_DOF -> translucency;
            case AFTER_MOTION_BLUR -> afterMotionBlurTranslucency;
        };
    }

    private boolean layerReady(TranslucencyPass stage) {
        return switch (stage) {
            case BEFORE_DOF -> beforeDofReady;
            case AFTER_DOF -> translucencyReady;
            case AFTER_MOTION_BLUR -> afterMotionBlurReady;
        };
    }

    private RenderTarget composeTranslucency(RenderTarget scene, TranslucencyPass stage) {
        if (!layerReady(stage)) return scene;
        var particles = layer(stage).getColorTextureView();
        var modulation = modulationTargets.color(stage);
        if (modulation == null) {
            PhotonFXLayer.compositeTransmission(particles, scene.getColorTextureView());
            return scene;
        }
        return composeModulatedTranslucency(scene.getColorTextureView(), particles, modulation);
    }

    private RenderTarget composeModulatedTranslucency(GpuTextureView scene, GpuTextureView layer, GpuTextureView modulation) {
        boolean useScratch = composedWorld != null && scene.texture() == composedWorld.getColorTexture();
        TextureTarget destination = useScratch ? compositionScratch : composedWorld;
        GpuFormat format = linearWorld.getColorTexture().getFormat();
        if (destination != null && destination.getColorTexture().getFormat() != format) {
            destination.destroyBuffers();
            destination = null;
        }
        if (destination == null) {
            destination = new TextureTarget("LumenFX modulated particle composition", linearWorld.width, linearWorld.height,
                    false, format);
            if (useScratch) compositionScratch = destination;
            else composedWorld = destination;
        } else if (destination.width != linearWorld.width || destination.height != linearWorld.height)
            destination.resize(linearWorld.width, linearWorld.height);
        var pipeline = modulationComposites.computeIfAbsent(format, WorldHdrCapture::createModulationComposite);
        var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        PhotonFullscreenPass.draw("LumenFX translucency composition", pipeline, destination.getColorTextureView(), pass -> {
            pass.bindTexture("SceneSampler", scene, sampler);
            pass.bindTexture("TranslucencySampler", layer, sampler);
            pass.bindTexture("ModulationSampler", modulation, sampler);
        });
        return destination;
    }

    private static RenderPipeline createModulationComposite(GpuFormat format) {
        return PhotonFullscreenPass.builder(LumenFx.id("post/translucency_composite"))
                .withLocation(LumenFx.id("pipeline/translucency_modulation_" + format.name().toLowerCase(java.util.Locale.ROOT)))
                .withBindGroupLayout(BindGroupLayout.builder().withSampler("SceneSampler")
                        .withSampler("TranslucencySampler").withSampler("ModulationSampler").build())
                .withColorTargetState(PhotonFullscreenPass.opaqueTarget(format)).build();
    }

    public void finishComposition() {
        RenderSystem.assertOnRenderThread();
        if (!composing) return;
        RenderTarget scene = composeStages(linearWorld, EnumSet.allOf(TranslucencyPass.class));
        encodeNativeScene(scene);
        outputFrameIndex++;
        if (translucencyWrittenDepth != null || opaqueParticleDepthWritten) {
            copyDepth(finalDepth(), output);
        }
        decalNormals.finish(sceneSurfaces(), viewPaused);
        closeMotionFrame(true);
        finishFogFrame();
        composing = false;
    }

    private void finishFogFrame() {
        volumeFrames.keySet().forEach(profile -> volumeFog.get(profile).finishFrame());
    }

    private void encodeNativeScene(RenderTarget scene) {
        encodeNativeScene(scene, output);
    }

    private void encodeNativeScene(RenderTarget scene, RenderTarget destination) {
        transferColor(scene, destination, 1, Transfer.ENCODE);
    }

    public void end() {
        volumeFrames.keySet().forEach(profile -> volumeFog.get(profile).abortFrame());
        decalNormals.end();
        partialDepth = null;
        photonOpaqueMask.discardFrame();
        particleGBuffer = null;
        capturesNiagaraGBuffer = false;
        sceneDecalAo = null;
        surfaceCapture.end();
        closeMotionFrame(false);
        MotionHistory.INSTANCE.endFrame();
        capturingNative = false; composing = false; output = null; settings = null; worldFog = null;
        projectionCaptured = false; nearClipCaptured = false; translucencyReady = false;
        firstPersonMaterialView = null;
        beforeDofReady = afterMotionBlurReady = false;
        translucencyWrittenDepth = null;
        translucencyDepthStages.clear();
        opaqueParticleDepthWritten = false;
        modulationTargets.reset();
        fogViewReady = false; volumeFrames.clear(); volumeSceneLighting = false;
        fogCamera = null; fogWorld = fogCameraOwner = null;
        shaderpackFrame = null;
        externalOpaquePhase = false;
        if (shaderpackOpaqueDepth != null) {
            PhotonWorldRenderState.closeAtFrameEnd(shaderpackOpaqueDepth);
            shaderpackOpaqueDepth = null;
        }
        if (externalMotionDepth != null) {
            PhotonWorldRenderState.closeAtFrameEnd(externalMotionDepth);
            externalMotionDepth = null;
        }
        depthCopy.endFrame();
        sceneLights = java.util.List.of();
        sceneComponentLights = List.of();
        sceneDirectionalLights = List.of();
        forwardLightFunctions = null;
        forwardDirectionalShadows = null;
        translucencyDirectionalLights = DirectionalLights.EMPTY;
        translucencyLocalLights = LocalLights.EMPTY;
        fogLocalLights = List.of(); fogLocalShadows = Map.of();
        fogDirectionalMaterial = null;
        fogLightFunctions = null;
        sceneDecals = null;
        materialSceneColorReady = false;
    }

    public void release() {
        RenderSystem.assertOnRenderThread();
        end();
        if (encodedWorld != null) { encodedWorld.destroyBuffers(); encodedWorld = null; }
        if (linearWorld != null) { linearWorld.destroyBuffers(); linearWorld = null; }
        if (photonWorld != null) { photonWorld.destroyBuffers(); photonWorld = null; }
        if (photonExternalOpaqueDepthView != null && !photonExternalOpaqueDepthView.isClosed()) {
            photonExternalOpaqueDepthView.close();
        }
        photonExternalOpaqueDepthView = null;
        if (photonExternalOpaqueDepth != null && !photonExternalOpaqueDepth.isClosed()) {
            photonExternalOpaqueDepth.close();
        }
        photonExternalOpaqueDepth = null;
        if (translucency != null) { translucency.destroyBuffers(); translucency = null; }
        if (beforeDofTranslucency != null) { beforeDofTranslucency.destroyBuffers(); beforeDofTranslucency = null; }
        if (afterMotionBlurTranslucency != null) { afterMotionBlurTranslucency.destroyBuffers(); afterMotionBlurTranslucency = null; }
        if (composedWorld != null) { composedWorld.destroyBuffers(); composedWorld = null; }
        if (compositionScratch != null) { compositionScratch.destroyBuffers(); compositionScratch = null; }
        volumeFog.values().forEach(VolumetricFogTarget::close);
        volumeFog.clear();
        volumeFogWorld = null;
        if (volumeIntegrator != null) { volumeIntegrator.close(); volumeIntegrator = null; }
        geometryMotion.release();
        decalNormals.close();
        surfaceCapture.close();
        niagaraVelocityTarget.close();
        niagaraPartialDepth.close();
        photonOpaqueMask.close();
        materialSceneColor.close();
        depthCopy.close();
        if (externalTransfer != null) { externalTransfer.close(); externalTransfer = null; }
        if (hostSurfaceReconstructor != null) { hostSurfaceReconstructor.close(); hostSurfaceReconstructor = null; }
        MotionHistory.INSTANCE.reset();
        colorTransfers.clear();
        modulationComposites.clear();
        modulationTargets.close();
    }
}
