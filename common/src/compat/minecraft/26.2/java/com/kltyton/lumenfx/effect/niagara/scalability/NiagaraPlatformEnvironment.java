package com.kltyton.lumenfx.effect.niagara.scalability;

import com.kltyton.lumenfx.effect.EmitterDefinition.Renderer;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceSettings;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.volume.VolumeTracingSettings;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** One immutable source-platform snapshot is used for a system frame's quality and CVar selection. */
public record NiagaraPlatformEnvironment(int quality, int availableMask, List<String> profileHierarchy,
                                         Map<String, ConsoleValue> consoleValues, float globalSpawnCountScale,
                                         RenderTargetSettings renderTargetSettings, NiagaraAsyncGpuTraceSettings asyncTraceSettings,
                                         ShaderPlatforms shaderPlatforms, Vec3 translucencySortAxis) {
    private static final String SYSTEM_COUNT_SCALE = "fx.NiagaraGlobalSystemCountScale";
    private static final String MIN_SYSTEM_SCALABILITY_DISTANCE = "fx.Niagara.Scalability.MinMaxDistance";
    private static final String UPDATE_ON_SPAWN = "fx.Niagara.SystemSimulation.UpdateOnSpawn";
    private static final String MAX_TICK_SUBSTEPS = "fx.Niagara.SystemSimulation.MaxTickSubsteps";
    private static final String SKIP_TICK_DELTA = "fx.Niagara.SystemSimulation.SkipTickDeltaSeconds";
    private static final String LWC_TILE_RECACHE = "fx.LWCTileRecache";
    private static final String RADIX_SORT_THRESHOLD = "Niagara.RadixSortThreshold";
    private static final String VECTOR_VM_PAGE_KB = "vm.PageSizeInKB";
    private static final String GPU_SORTING = "FX.AllowGPUSorting";
    private static final String GPU_CULLING = "Niagara.GPUCulling";
    private static final String COMPUTE_SHADERS = "fx.NiagaraAllowComputeShaders";
    private static final String CPU_TO_GPU_SORT = "Niagara.GPUSorting.CPUToGPUThreshold";
    private static final String CPU_TO_GPU_CULL = "Niagara.GPUCulling.CPUToGPUThreshold";
    private static final String DEFAULT_SORT_PRECISION = "NiagaraSettings.DefaultSortPrecision";
    private static final String TRANSLUCENCY_SORT_POLICY = "r.TranslucentSortPolicy";
    private static final Vec3 DEFAULT_TRANSLUCENCY_SORT_AXIS = new Vec3(0, -1, 0);
    private static final String SPRITE_RENDERING = "fx.EnableNiagaraSpriteRendering";
    private static final String MESH_RENDERING = "fx.EnableNiagaraMeshRendering";
    private static final String LIGHT_RENDERING = "fx.EnableNiagaraLightRendering";
    private static final String MOBILE_HDR = "r.MobileHDR";
    private static final String MOBILE_SHADING_PATH = "r.Mobile.ShadingPath";
    private static final String MOBILE_DEFERRED_OPENGL = "r.Mobile.AllowDeferredShadingOpenGL";
    private static final String MOBILE_FORWARD_LOCAL_LIGHTS = "r.Mobile.Forward.EnableLocalLights";
    private static final String MOBILE_FORWARD_PARTICLE_LIGHTS = "r.Mobile.Forward.EnableParticleLights";
    private static final String COMPONENT_POOL_INACTIVE_TIME = "fx.Niagara.ComponentRenderPoolInactiveTimeLimit";
    private static final String TARGET_RESOLUTION = "fx.Niagara.RenderTarget.ResolutionMultiplier";
    private static final String TARGET_IGNORE_COOKED = "fx.Niagara.RenderTarget.IgnoreCookedOut";
    private static final String VOLUME_CACHE_ENABLED = "fx.Niagara.RenderTargetVolume.SimCacheEnabled";
    private static final String VOLUME_CACHE_STORAGE = "fx.Niagara.RenderTargetVolume.SimCacheDataStorageMode";
    public enum VolumeCacheStorage { DISABLED, DENSE, SPARSE }
    private static final String COLLISION_CPU_ENABLED = "fx.Niagara.Collision.CPUEnabled";
    private static final String GLOBAL_DISTANCE_FIELD_TRACE = "fx.Niagara.AsyncGpuTrace.GlobalSdfEnabled";
    private static final String HARDWARE_RAY_TRACE = "fx.Niagara.AsyncGpuTrace.HWRayTraceEnabled";
    private static final String FIELD_CLIPMAP_COUNT = "r.AOGlobalDistanceField.NumClipmaps";
    private static final String FIELD_RESOLUTION = "r.AOGlobalDFResolution";
    private static final String FIELD_EXTENT_EXPONENT = "r.AOGlobalDFClipmapDistanceExponent";
    private static final String FIELD_MIP_FACTOR = "r.AOGlobalDistanceField.MipFactor";
    private static final String STATIC_MESH_INLINE_LODS = "fx.Niagara.NDIStaticMesh.UseInlineLODsOnly";
    private static final String STATIC_MESH_DISTANCE_FIELDS = "NiagaraSettings.NDIStaticMesh_AllowDistanceFields";
    private static final String EXPORT_READBACK_LIMIT = "fx.Niagara.NDIExport.GPUMaxReadbackCount";
    private static final String DATA_CHANNELS_ENABLED = "fx.Niagara.DataChannels.Enabled";
    private static final String DATA_CHANNELS_PREVIOUS = "fx.Niagara.DataChannels.ForceReadPrevFrame";
    private static final String DATA_CHANNELS_READ_GROUP = "fx.Niagara.DataChannels.ForceReadTickGroup";
    private static final String WORLD_TICK_PASSES = "fx.Niagara.WorldManager.UseTickPasses";
    private static final String WORLD_SPAWN_PER_TICK_GROUP = "fx.Niagara.WorldManager.SpawnPerTickGroup";
    private static final String DATA_CHANNELS_RETAIN_GPU = "fx.Niagara.Rollback.DataChannels.RetainGPUDataIfPendingCpuUpdate";
    private static final String DATA_CHANNELS_AUTO_UPLOAD = "fx.Niagara.DataChannels.AutoUploadGPUSpawnData";
    public record RenderTargetSettings(int defaultFormat, int overrideFormat) {
        public static final RenderTargetSettings DEFAULT = new RenderTargetSettings(6, -1);
        public RenderTargetSettings {
            if (defaultFormat < 0 || defaultFormat > 10 || overrideFormat < -1 || overrideFormat > 10)
                throw new IllegalArgumentException("Invalid source render target format settings");
        }
    }
    /** Feature-level shader format and the source RHI's maximum format may differ. */
    public record ShaderPlatforms(String featureLevel, String maximum, MobileShadingPath mobileShadingPath) {
        public enum MobileShadingPath { PROJECT_SETTING, FORWARD, DEFERRED }
        public static final ShaderPlatforms WINDOWS_SM5 = new ShaderPlatforms("PCD3D_SM5", "PCD3D_SM5");

        public ShaderPlatforms(String featureLevel, String maximum) {
            this(featureLevel, maximum, MobileShadingPath.PROJECT_SETTING);
        }

        public ShaderPlatforms {
            if (featureLevel.isBlank() || maximum.isBlank())
                throw new IllegalArgumentException("Source shader format cannot be blank");
            Objects.requireNonNull(mobileShadingPath);
        }

        boolean mobileFeatureLevel() {
            return mobileShadingPath != MobileShadingPath.PROJECT_SETTING || featureLevel.contains("ES3_1")
                    || featureLevel.contains("ES31") || featureLevel.endsWith("METAL_SIM");
        }

        boolean openGlFeatureLevel() {
            return featureLevel.contains("GLSL") || featureLevel.contains("OPENGL");
        }
    }
    public enum ConsoleType { BOOL, INT, FLOAT }
    public enum TranslucentSortPolicy { DISTANCE, PROJECTED_Z, ALONG_AXIS }
    public record ConsoleValue(ConsoleType type, int bits) {
        public ConsoleValue { Objects.requireNonNull(type); }
        public static ConsoleValue bool(boolean value) { return new ConsoleValue(ConsoleType.BOOL, value ? 1 : 0); }
        public static ConsoleValue integer(int value) { return new ConsoleValue(ConsoleType.INT, value); }
        public static ConsoleValue floating(float value) { return new ConsoleValue(ConsoleType.FLOAT, Float.floatToRawIntBits(value)); }
    }
    public static final NiagaraPlatformEnvironment WINDOWS_DEFAULT = new NiagaraPlatformEnvironment(3, -1, List.of("Windows"), Map.of());
    public NiagaraPlatformEnvironment(int quality, int availableMask, List<String> profileHierarchy,
                                     Map<String, ConsoleValue> consoleValues) {
        this(quality, availableMask, profileHierarchy, consoleValues, 1f);
    }
    public NiagaraPlatformEnvironment(int quality, int availableMask, List<String> profileHierarchy,
                                     Map<String, ConsoleValue> consoleValues, float globalSpawnCountScale) {
        this(quality, availableMask, profileHierarchy, consoleValues, globalSpawnCountScale, RenderTargetSettings.DEFAULT);
    }
    public NiagaraPlatformEnvironment(int quality, int availableMask, List<String> profileHierarchy,
                                     Map<String, ConsoleValue> consoleValues, float globalSpawnCountScale,
                                     RenderTargetSettings renderTargetSettings) {
        this(quality, availableMask, profileHierarchy, consoleValues, globalSpawnCountScale, renderTargetSettings,
                NiagaraAsyncGpuTraceSettings.DEFAULT);
    }
    public NiagaraPlatformEnvironment(int quality, int availableMask, List<String> profileHierarchy,
                                     Map<String, ConsoleValue> consoleValues, float globalSpawnCountScale,
                                     RenderTargetSettings renderTargetSettings, NiagaraAsyncGpuTraceSettings asyncTraceSettings) {
        this(quality, availableMask, profileHierarchy, consoleValues, globalSpawnCountScale, renderTargetSettings,
                asyncTraceSettings, ShaderPlatforms.WINDOWS_SM5);
    }
    /** The source project axis uses Unreal X/Y/Z and is read without normalizing it. */
    public NiagaraPlatformEnvironment(int quality, int availableMask, List<String> profileHierarchy,
                                     Map<String, ConsoleValue> consoleValues, float globalSpawnCountScale,
                                     RenderTargetSettings renderTargetSettings, NiagaraAsyncGpuTraceSettings asyncTraceSettings,
                                     ShaderPlatforms shaderPlatforms) {
        this(quality, availableMask, profileHierarchy, consoleValues, globalSpawnCountScale, renderTargetSettings,
                asyncTraceSettings, shaderPlatforms, DEFAULT_TRANSLUCENCY_SORT_AXIS);
    }
    public NiagaraPlatformEnvironment {
        if (quality < 0 || quality >= 32 || profileHierarchy.isEmpty()) throw new IllegalArgumentException("Invalid Niagara platform quality/profile");
        Objects.requireNonNull(renderTargetSettings);
        Objects.requireNonNull(asyncTraceSettings);
        Objects.requireNonNull(shaderPlatforms);
        if (!Double.isFinite(translucencySortAxis.lengthSqr()))
            throw new IllegalArgumentException("Invalid Unreal translucent sort axis");
        profileHierarchy = List.copyOf(profileHierarchy);
        Map<String, ConsoleValue> values = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        values.put("fx.Niagara.QualityLevel.Min", ConsoleValue.integer(-1));
        values.put("fx.Niagara.QualityLevel.Max", ConsoleValue.integer(-1));
        values.put("fx.Niagara.AllowRuntimeScalabilityChanges", ConsoleValue.integer(0));
        values.put("fx.Niagara.LegacyDeviceProfile", ConsoleValue.integer(-1));
        values.put("fx.Niagara.QualityLevel", ConsoleValue.integer(quality));
        values.put("fx.NiagaraStateless.ComputeManager.UseCache", ConsoleValue.bool(true));
        values.put("fx.NiagaraStateless.ComputeManager.CPUThreshold", ConsoleValue.integer(0));
        values.put("fx.NiagaraStateless.Feature.ExecuteGPU", ConsoleValue.bool(true));
        values.put("fx.NiagaraStateless.Feature.ExecuteCPU", ConsoleValue.bool(true));
        values.put("fx.LastRenderTimeSafetyBias", ConsoleValue.floating(.1f));
        values.put("r.CustomDepth", ConsoleValue.integer(1));
        values.put("r.CustomDepth.Order", ConsoleValue.integer(2));
        values.put("r.CustomDepthTemporalAAJitter", ConsoleValue.integer(1));
        values.put("r.VelocityOutputPass", ConsoleValue.integer(0));
        values.put("r.Decal.NormalReprojectionEnabled", ConsoleValue.integer(0));
        values.put("r.Decal.NormalReprojectionThresholdLow", ConsoleValue.floating(.990f));
        values.put("r.Decal.NormalReprojectionThresholdHigh", ConsoleValue.floating(.995f));
        values.put("NiagaraSettings.DefaultGpuTranslucentLatency", ConsoleValue.integer(0));
        values.put(RADIX_SORT_THRESHOLD, ConsoleValue.integer(400));
        values.put(VECTOR_VM_PAGE_KB, ConsoleValue.integer(64));
        values.put(GPU_SORTING, ConsoleValue.integer(1));
        values.put(GPU_CULLING, ConsoleValue.integer(1));
        values.put(COMPUTE_SHADERS, ConsoleValue.integer(1));
        values.put(CPU_TO_GPU_SORT, ConsoleValue.integer(-1));
        values.put(CPU_TO_GPU_CULL, ConsoleValue.integer(0));
        values.put(DEFAULT_SORT_PRECISION, ConsoleValue.integer(0));
        values.put(TRANSLUCENCY_SORT_POLICY, ConsoleValue.integer(0));
        values.put(SPRITE_RENDERING, ConsoleValue.bool(true));
        values.put(MESH_RENDERING, ConsoleValue.bool(true));
        values.put(LIGHT_RENDERING, ConsoleValue.bool(true));
        values.put(MOBILE_HDR, ConsoleValue.integer(1));
        values.put(MOBILE_SHADING_PATH, ConsoleValue.integer(0));
        values.put(MOBILE_DEFERRED_OPENGL, ConsoleValue.integer(1));
        values.put(MOBILE_FORWARD_LOCAL_LIGHTS, ConsoleValue.integer(1));
        values.put(MOBILE_FORWARD_PARTICLE_LIGHTS, ConsoleValue.bool(false));
        values.put(COMPONENT_POOL_INACTIVE_TIME, ConsoleValue.floating(5f));
        values.put(SYSTEM_COUNT_SCALE, ConsoleValue.floating(1f));
        values.put(MIN_SYSTEM_SCALABILITY_DISTANCE, ConsoleValue.floating(1f));
        values.put("fx.Niagara.Scalability.DistanceCulling", ConsoleValue.integer(1));
        values.put("fx.Niagara.Scalability.InstanceCountCulling", ConsoleValue.integer(1));
        values.put("fx.Niagara.Scalability.VisibilityCulling", ConsoleValue.integer(1));
        values.put("fx.Niagara.AllowCullProxies", ConsoleValue.integer(1));
        values.put("fx.Niagara.Scalability.GlobalBudgetCulling", ConsoleValue.integer(1));
        values.put("fx.Niagara.UseGlobalFXBudget", ConsoleValue.bool(true));
        values.put("fx.Budget.Enabled", ConsoleValue.bool(false));
        values.put("fx.Budget.HistoryFrames", ConsoleValue.integer(60));
        values.put("fx.Budget.GameThread", ConsoleValue.floating(2));
        values.put("fx.Budget.GameThreadConcurrent", ConsoleValue.floating(2));
        values.put("fx.Budget.RenderThread", ConsoleValue.floating(2));
        values.put("fx.Budget.AdjustedUsageDecayRate", ConsoleValue.floating(.1f));
        values.put("fx.Budget.AdjustedUsageMax", ConsoleValue.floating(2));
        values.put("fx.Budget.Debug.GameThreadTimeOverride", ConsoleValue.floating(-1));
        values.put("fx.Budget.Debug.GameThreadConcurrentTimeOverride", ConsoleValue.floating(-1));
        values.put("fx.Budget.Debug.RenderThreadTimeOverride", ConsoleValue.floating(-1));
        values.put("fx.Niagara.AllowVisibilityCullingForDynamicBounds", ConsoleValue.integer(1));
        values.put("fx.Niagara.Scalability.CanPreventCullingOnPlayerFX", ConsoleValue.integer(1));
        values.put("fx.NiagaraScalabilityUpdateTime_Low", ConsoleValue.floating(1f));
        values.put("fx.NiagaraScalabilityUpdateTime_Medium", ConsoleValue.floating(.5f));
        values.put("fx.NiagaraScalabilityUpdateTime_High", ConsoleValue.floating(.25f));
        values.put("fx.Niagara.ForceLastTickGroup", ConsoleValue.integer(0));
        values.put("fx.ScalabilityMaxUpdatesPerFrame", ConsoleValue.integer(50));
        values.put("fx.Niagara.Scalability.ApplyInstanceCountsRigidly", ConsoleValue.integer(0));
        values.put(UPDATE_ON_SPAWN, ConsoleValue.integer(1));
        values.put(MAX_TICK_SUBSTEPS, ConsoleValue.integer(100));
        values.put(SKIP_TICK_DELTA, ConsoleValue.floating(0f));
        values.put(LWC_TILE_RECACHE, ConsoleValue.floating(2f));
        values.put(TARGET_RESOLUTION, ConsoleValue.floating(1f));
        values.put(TARGET_IGNORE_COOKED, ConsoleValue.integer(1));
        values.put(VOLUME_CACHE_ENABLED, ConsoleValue.integer(1));
        values.put(VOLUME_CACHE_STORAGE, ConsoleValue.integer(1));
        values.put(COLLISION_CPU_ENABLED, ConsoleValue.integer(1));
        values.put(GLOBAL_DISTANCE_FIELD_TRACE, ConsoleValue.integer(1));
        values.put(HARDWARE_RAY_TRACE, ConsoleValue.integer(1));
        values.put(FIELD_CLIPMAP_COUNT, ConsoleValue.integer(4));
        values.put(FIELD_RESOLUTION, ConsoleValue.integer(128));
        values.put(FIELD_EXTENT_EXPONENT, ConsoleValue.floating(2f));
        values.put(FIELD_MIP_FACTOR, ConsoleValue.integer(4));
        values.put("r.Streaming.UseNewMetrics", ConsoleValue.integer(1));
        values.put("r.Streaming.HiddenPrimitiveScale", ConsoleValue.floating(.5f));
        values.put("r.Streaming.MinMipForSplitRequest", ConsoleValue.integer(10));
        values.put(STATIC_MESH_INLINE_LODS, ConsoleValue.integer(2));
        values.put(STATIC_MESH_DISTANCE_FIELDS, ConsoleValue.bool(false));
        values.put(EXPORT_READBACK_LIMIT, ConsoleValue.integer(1000));
        values.put(DATA_CHANNELS_ENABLED, ConsoleValue.bool(true));
        values.put(DATA_CHANNELS_PREVIOUS, ConsoleValue.bool(false));
        values.put(DATA_CHANNELS_READ_GROUP, ConsoleValue.integer(-1));
        values.put(WORLD_TICK_PASSES, ConsoleValue.bool(false));
        values.put(WORLD_SPAWN_PER_TICK_GROUP, ConsoleValue.integer(1));
        values.put("fx.Niagara.Water.DepthQuerySupported", ConsoleValue.integer(0));
        values.put("r.Water.OceanFallbackDepth", ConsoleValue.floating(3000f));
        values.put("r.AnisotropicMaterials", ConsoleValue.integer(1));
        values.put("r.LightFunctionAtlas", ConsoleValue.integer(1));
        values.put("r.LightFunctionAtlas.Size", ConsoleValue.integer(4));
        values.put("r.LightFunctionAtlas.SlotResolution", ConsoleValue.integer(128));
        values.put("r.LightFunctionAtlas.MaxLightCount", ConsoleValue.integer(-1));
        values.put("r.LightFunctionAtlas.Format", ConsoleValue.integer(0));
        values.put("r.VolumetricFog.UsesLightFunctionAtlas", ConsoleValue.integer(1));
        values.put("r.VolumetricFog.LightFunction.DirectionalLightSupersampleScale", ConsoleValue.floating(2f));
        values.put("r.SkylightIntensityMultiplier", ConsoleValue.floating(1f));
        values.put("r.SkyAtmosphere", ConsoleValue.integer(1));
        values.put("r.SkyAtmosphere.TransmittanceLUT", ConsoleValue.integer(1));
        values.put("r.SkyAtmosphere.TransmittanceLUT.SampleCount", ConsoleValue.floating(10f));
        values.put("r.SkyAtmosphere.TransmittanceLUT.UseSmallFormat", ConsoleValue.integer(0));
        values.put("r.SkyAtmosphere.TransmittanceLUT.Width", ConsoleValue.floating(256f));
        values.put("r.SkyAtmosphere.TransmittanceLUT.Height", ConsoleValue.floating(64f));
        values.put("r.SkyAtmosphere.MultiScatteringLUT.Width", ConsoleValue.floating(32f));
        values.put("r.SkyAtmosphere.MultiScatteringLUT.Height", ConsoleValue.floating(32f));
        values.put("r.SkyAtmosphere.MultiScatteringLUT.SampleCount", ConsoleValue.floating(15f));
        values.put("r.SkyAtmosphere.MultiScatteringLUT.HighQuality", ConsoleValue.floating(0f));
        values.put("r.SkyAtmosphere.DistantSkyLightLUT", ConsoleValue.integer(1));
        values.put("r.SkyAtmosphere.DistantSkyLightLUT.Altitude", ConsoleValue.floating(6f));
        values.put("r.SkyAtmosphere.FastSkyLUT", ConsoleValue.integer(1));
        values.put("r.SkyAtmosphere.FastSkyLUT.Width", ConsoleValue.integer(192));
        values.put("r.SkyAtmosphere.FastSkyLUT.Height", ConsoleValue.integer(104));
        values.put("r.SkyAtmosphere.FastSkyLUT.SampleCountMin", ConsoleValue.floating(4f));
        values.put("r.SkyAtmosphere.FastSkyLUT.SampleCountMax", ConsoleValue.floating(32f));
        values.put("r.SkyAtmosphere.FastSkyLUT.DistanceToSampleCountMax", ConsoleValue.floating(150f));
        values.put("r.SkyAtmosphere.SampleCountMin", ConsoleValue.floating(2f));
        values.put("r.SkyAtmosphere.SampleCountMax", ConsoleValue.floating(32f));
        values.put("r.SkyAtmosphere.DistanceToSampleCountMax", ConsoleValue.floating(150f));
        values.put("r.SkyAtmosphere.AerialPerspectiveLUT.Width", ConsoleValue.floating(32f));
        values.put("r.SkyAtmosphere.AerialPerspectiveLUT.DepthResolution", ConsoleValue.floating(16f));
        values.put("r.SkyAtmosphere.AerialPerspectiveLUT.Depth", ConsoleValue.floating(96f));
        values.put("r.SkyAtmosphere.AerialPerspectiveLUT.SampleCountMaxPerSlice", ConsoleValue.floating(2f));
        values.put("r.SupportStationarySkylight", ConsoleValue.integer(1));
        values.put("r.SupportAllShaderPermutations", ConsoleValue.integer(0));
        values.put("r.ReflectionEnvironmentLightmapMixing", ConsoleValue.integer(1));
        values.put("r.ForwardShading", ConsoleValue.integer(0));
        values.put("r.ForwardShading.ForceSkyLightCubemapBlending", ConsoleValue.integer(0));
        values.put("r.ReflectionEnvironmentLightmapMixBasedOnRoughness", ConsoleValue.integer(1));
        values.put("r.ReflectionEnvironmentBeginMixingRoughness", ConsoleValue.floating(.1f));
        values.put("r.ReflectionEnvironmentEndMixingRoughness", ConsoleValue.floating(.3f));
        values.put("r.ReflectionEnvironmentLightmapMixLargestWeight", ConsoleValue.integer(10000));
        values.put("r.Deferred.UsesLightFunctionAtlas", ConsoleValue.integer(1));
        values.put("r.Translucent.UsesLightFunctionAtlas", ConsoleValue.integer(0));
        values.put("r.Translucent.UsesIESProfiles", ConsoleValue.integer(0));
        values.put("r.Translucent.UsesRectLights", ConsoleValue.integer(0));
        values.put("r.VT.AnisotropicFiltering", ConsoleValue.integer(0));
        values.put("r.Nanite.MaxPixelsPerEdge", ConsoleValue.floating(1.0f));
        values.put("r.Nanite.ViewMeshLODBias.Enable", ConsoleValue.integer(1));
        values.put("r.Nanite.ViewMeshLODBias.Offset", ConsoleValue.floating(0));
        values.put("r.Nanite.ViewMeshLODBias.Min", ConsoleValue.floating(-2));
        values.put("r.Nanite.MinPixelsPerEdgeHW", ConsoleValue.floating(32.0f));
        values.put("r.Nanite.ComputeRasterization", ConsoleValue.integer(1));
        values.put("r.Nanite.ProgrammableRaster", ConsoleValue.integer(1));
        values.put("r.Nanite.AllowMaskedMaterials", ConsoleValue.integer(1));
        values.put("r.Nanite.Culling.HZB", ConsoleValue.integer(1));
        values.put("r.Nanite.Culling.Frustum", ConsoleValue.integer(1));
        values.put("r.Nanite.PrimeHZB", ConsoleValue.integer(0));
        values.put("r.Nanite.PrimeHZB.DrawOnlyRTFarField", ConsoleValue.bool(true));
        values.put("r.Nanite.PrimeHZB.RenderSizeBias", ConsoleValue.integer(2));
        values.put("r.Nanite.PrimeHZB.SceneDepthBias", ConsoleValue.floating(150));
        values.put("r.Nanite.PrimeHZB.MaxPixelsPerEdgeMultiplier", ConsoleValue.floating(32));
        values.put("r.Nanite.PrimeHZB.SampleNonNanite", ConsoleValue.bool(false));
        values.put("r.Nanite.Culling.TwoPass", ConsoleValue.integer(1));
        values.put("r.Nanite.Streaming.StreamingPoolSize", ConsoleValue.integer(512));
        values.put("r.Nanite.Streaming.NumInitialRootPages", ConsoleValue.integer(2048));
        values.put("r.Nanite.Streaming.DynamicallyGrowAllocations", ConsoleValue.integer(1));
        values.put("r.Nanite.Streaming.Debug.StreamingPoolResize", ConsoleValue.integer(1));
        values.put("r.Nanite.Streaming.Debug.ExplicitRequests", ConsoleValue.integer(1));
        values.put("r.Nanite.Streaming.Debug.GPURequests", ConsoleValue.integer(1));
        values.put("r.Nanite.Streaming.Debug.Prefetch", ConsoleValue.integer(1));
        values.put("r.Nanite.Streaming.MaxPendingPages", ConsoleValue.integer(128));
        values.put("r.Nanite.Streaming.MaxPageInstallsPerFrame", ConsoleValue.integer(128));
        values.put("r.Nanite.Streaming.GPURequestsBufferMinSize", ConsoleValue.integer(64 * 1024));
        values.put("r.Nanite.Streaming.GPURequestsBufferMaxSize", ConsoleValue.integer(1024 * 1024));
        values.put("r.Nanite.Streaming.QualityScale.MinPoolPercentage", ConsoleValue.floating(70));
        values.put("r.Nanite.Streaming.QualityScale.MaxPoolPercentage", ConsoleValue.floating(85));
        values.put("r.Nanite.Streaming.QualityScale.MinQuality", ConsoleValue.floating(0.3f));
        values.put("r.Nanite.MaxVisibleClusters", ConsoleValue.integer(4 * 1048576));
        values.put("r.Nanite.MaxNodes", ConsoleValue.integer(2 * 1048576));
        values.put("r.Shadow.Nanite", ConsoleValue.integer(1));
        values.put("r.Shadow.NaniteUseHZB", ConsoleValue.integer(1));
        values.put("r.Shadow.NaniteUpdateStreaming", ConsoleValue.integer(1));
        values.put("r.Shadow.NaniteLODBias", ConsoleValue.floating(1.0f));
        values.put("r.AllowStaticLighting", ConsoleValue.integer(1));
        values.put("r.Shadow.CSM.MaxCascades", ConsoleValue.integer(10));
        values.put("r.Shadow.UnbuiltPreviewInGame", ConsoleValue.integer(1));
        values.put("r.Shadow.UnbuiltWholeSceneDynamicShadowRadius", ConsoleValue.floating(200000f));
        values.put("r.Shadow.UnbuiltNumWholeSceneDynamicShadowCascades", ConsoleValue.integer(4));
        values.put("r.Shadow.MaxCSMResolution", ConsoleValue.integer(2048));
        values.put("r.Shadow.DistanceScale", ConsoleValue.floating(1f));
        values.put("r.Shadow.CSM.TransitionScale", ConsoleValue.floating(1f));
        values.put("r.Shadow.MaxNumFarShadowCascades", ConsoleValue.integer(10));
        values.put("r.Shadow.FarShadowDistanceOverride", ConsoleValue.floating(0f));
        values.put("r.Shadow.CSMShadowDistanceFadeoutMultiplier", ConsoleValue.floating(1f));
        values.put("r.Shadow.CSMDepthBias", ConsoleValue.floating(10f));
        values.put("r.Shadow.CSMSlopeScaleDepthBias", ConsoleValue.floating(3f));
        values.put("r.Shadow.ShadowMaxSlopeScaleDepthBias", ConsoleValue.floating(1f));
        values.put("r.RectLightAtlas.MaxResolution", ConsoleValue.integer(4096));
        values.put("r.RectLightAtlas.MaxTextureRatio", ConsoleValue.integer(2));
        values.put("r.RectLightAtlas.Format", ConsoleValue.integer(0));
        values.put("r.RectLightAtlas.FilterQuality", ConsoleValue.integer(0));
        values.put("r.RectLightAtlas.ForceUpdate", ConsoleValue.integer(0));
        values.put("r.RectLightAtlas.ResetOnChange", ConsoleValue.integer(0));
        values.put("r.IESAtlas.Resolution", ConsoleValue.integer(256));
        values.put("r.IESAtlas.MaxProfileCount", ConsoleValue.integer(32));
        values.put("r.IESAtlas.ForceUpdate", ConsoleValue.integer(0));
        values.put("r.TranslucencyLightingVolume", ConsoleValue.integer(1));
        values.put("r.TranslucencyLightingVolume.Dim", ConsoleValue.integer(64));
        values.put("r.TranslucencyLightingVolume.InnerDistance", ConsoleValue.floating(1500f));
        values.put("r.TranslucencyLightingVolume.OuterDistance", ConsoleValue.floating(5000f));
        values.put("r.TranslucencyLightingVolume.AdaptToPerspective", ConsoleValue.integer(0));
        values.put("r.TranslucencyLightingVolume.MinFOV", ConsoleValue.floating(45f));
        values.put("r.TranslucencyLightingVolume.FOVSnapFactor", ConsoleValue.floating(10f));
        values.put("r.TranslucencyLightingVolume.Blur", ConsoleValue.integer(1));
        values.put("r.TranslucencyLightingVolume.AccurateRectLights", ConsoleValue.integer(1));
        values.put("r.TranslucencyLightingVolume.InjectDirectionalLightCSM", ConsoleValue.integer(1));
        values.put("r.TranslucencyLightingVolume.PositionOffsetRadius", ConsoleValue.floating(0f));
        values.put("r.TranslucencyLightingVolume.Temporal", ConsoleValue.bool(false));
        values.put("r.TranslucencyLightingVolume.Temporal.HistoryWeight", ConsoleValue.floating(.9f));
        values.put("r.Forward.LightGridPixelSize", ConsoleValue.integer(64));
        values.put("r.Forward.LightGridSizeZ", ConsoleValue.integer(32));
        values.put("r.Forward.MaxCulledLightsPerCell", ConsoleValue.integer(32));
        values.put("r.Forward.LightLinkedListCulling", ConsoleValue.integer(1));
        values.put("r.Forward.LightGridDebug.RectLightBounds", ConsoleValue.integer(1));
        values.put("r.LightCulling.MaxDistanceOverrideKilometers", ConsoleValue.floating(-1f));
        values.put("r.ClearCoatNormal", ConsoleValue.integer(0));
        values.put("r.IrisNormal", ConsoleValue.integer(0));
        values.put("r.GBufferFormat", ConsoleValue.integer(1));
        values.put("r.SSS.SubSurfaceColorAsTansmittanceAtDistance", ConsoleValue.floating(0.15f));
        values.put("r.SubsurfaceScattering", ConsoleValue.integer(1));
        values.put("r.SSS.Scale", ConsoleValue.floating(1f));
        values.put("r.SSS.HalfRes", ConsoleValue.integer(1));
        values.put("r.SSS.HalfRes.ForceSeparable", ConsoleValue.integer(1));
        values.put("r.SSS.Burley.Quality", ConsoleValue.integer(1));
        values.put("r.SSS.Burley.NumSamplesOverride", ConsoleValue.integer(0));
        values.put("r.SSS.Burley.EnableProfileIdCache", ConsoleValue.integer(1));
        values.put("r.SSS.Burley.MinGenerateMipsTileCount", ConsoleValue.integer(4000));
        values.put("r.SSS.Burley.BilateralFilterKernelFunctionType", ConsoleValue.integer(1));
        values.put("r.SSS.Filter", ConsoleValue.integer(1));
        values.put("r.SSS.SampleSet", ConsoleValue.integer(2));
        values.put("r.Shading.EnergyConservation", ConsoleValue.integer(1));
        values.put("r.Shading.EnergyConservation.RuntimeGeneration", ConsoleValue.integer(0));
        values.put("r.Shading.EnergyConservation.Resolution", ConsoleValue.integer(32));
        values.put("r.Shading.EnergyConservation.Format", ConsoleValue.integer(1));
        values.put("r.Shading.EnergyPreservation", ConsoleValue.integer(1));
        values.put("r.Substrate.ShadingQuality", ConsoleValue.integer(0));
        values.put("r.Substrate.Experimental.ToonGIReflection", ConsoleValue.integer(0));
        values.put("r.Substrate.Experimental.ToonUnifiedDiffuse", ConsoleValue.integer(0));
        values.put("r.Material.EnergyConservation", ConsoleValue.integer(0));
        values.put("r.Material.RoughDiffuse", ConsoleValue.integer(0));
        values.put("r.Substrate.RoughDiffuse", ConsoleValue.integer(0));
        values.put("r.AmbientOcclusionLevels", ConsoleValue.integer(-1));
        values.putAll(NiagaraRibbonScalability.CONSOLE_DEFAULTS);
        values.put("r.AmbientOcclusionMaxQuality", ConsoleValue.floating(100f));
        values.put("r.AmbientOcclusionMipLevelFactor", ConsoleValue.floating(0.5f));
        values.put("r.AmbientOcclusion.Method", ConsoleValue.integer(0));
        values.put("r.GTAO.UseNormals", ConsoleValue.integer(0));
        values.put("r.GTAO.FilterWidth", ConsoleValue.integer(5));
        values.put("r.GTAO.ThicknessBlend", ConsoleValue.floating(0.5f));
        values.put("r.GTAO.FalloffEnd", ConsoleValue.floating(200f));
        values.put("r.GTAO.NumAngles", ConsoleValue.floating(2f));
        values.put("r.GTAO.PauseJitter", ConsoleValue.integer(0));
        values.put("r.GTAO.Downsample", ConsoleValue.integer(0));
        values.put("r.GTAO.TemporalFilter", ConsoleValue.integer(1));
        values.put("r.GTAO.SpatialFilter", ConsoleValue.integer(1));
        values.put("r.HairStrands.SimulationMaxDelay", ConsoleValue.integer(4));
        values.put("r.HairStrands.SimulationRestUpdate", ConsoleValue.integer(0));
        values.put("fx.Niagara.NDISpline.GDisableLUTs", ConsoleValue.bool(false));
        values.put("SplineComponent.UseSplineCurves", ConsoleValue.bool(true));
        values.put("fx.MaxNiagaraNeighborQueryCells", ConsoleValue.integer(512 * 512 * 512));
        values.put("fx.Niagara.DebugDraw.Enabled", ConsoleValue.integer(1));
        values.put("fx.Niagara.GpuComputeDebug.DrawDebugEnabled", ConsoleValue.integer(1));
        values.put("fx.Niagara.GpuComputeDebug.MaxLineInstances", ConsoleValue.integer(4096));
        values.put("fx.Niagara.GpuComputeDebug.OccludedLineColorScale", ConsoleValue.floating(.05f));
        values.put("r.GaussianSplat", ConsoleValue.integer(1));
        values.put("r.GaussianSplat.NumSplats", ConsoleValue.integer(-1));
        values.put("r.GaussianSplat.Debug", ConsoleValue.integer(0));
        values.put("r.GaussianSplat.TileSize", ConsoleValue.integer(8));
        values.put("r.GaussianSplat.MaxSplatOverlap", ConsoleValue.integer(16));
        values.put("r.GaussianSplat.HardwareRasterization", ConsoleValue.integer(1));
        values.put("r.GaussianSplat.Debug.GPUDrivenPipeline", ConsoleValue.integer(1));
        values.put("r.GaussianSplat.BlueNoise", ConsoleValue.integer(1));
        values.put("r.GaussianSplat.Depth", ConsoleValue.integer(0));
        values.put("r.GaussianSplat.StochasticDepthFormat", ConsoleValue.integer(1));
        values.put("r.GaussianSplat.Velocity", ConsoleValue.integer(0));
        values.put("r.GaussianSplat.HardwareRasterization.OpacityThreshold", ConsoleValue.floating(0.001f));
        values.put("r.GaussianSplat.Shading.OpacityThreshold", ConsoleValue.floating(0.0f));
        values.put("r.GaussianSplat.Debug.FrustumCull", ConsoleValue.integer(1));
        values.put("r.GaussianSplat.Debug.ClusterCulling", ConsoleValue.integer(1));
        values.put("r.GaussianSplat.ExpectedDepthScale", ConsoleValue.floating(100.0f));
        values.put("r.GaussianSplat.SortQuality", ConsoleValue.integer(3));
        values.put("r.GaussianSplat.Falloff", ConsoleValue.integer(1));
        values.put("r.GaussianSplat.DownsampleFactor", ConsoleValue.integer(1));
        values.put("r.GaussianSplat.GatherShadedSplats", ConsoleValue.integer(-1));
        values.put("r.GaussianSplat.VertexAttributeFetch", ConsoleValue.integer(-1));
        values.put("r.GaussianSplat.EmulateGLES31", ConsoleValue.integer(0));
        values.put("r.GaussianSplat.LOD", ConsoleValue.integer(-1));
        values.put("r.GaussianSplat.ErrorThreshold", ConsoleValue.floating(1.0f));
        values.put("fx.Niagara.GaussianSplatWriter.Enabled", ConsoleValue.bool(true));
        values.put("fx.Niagara.GaussianSplatWriter.MaxNumSplats", ConsoleValue.integer(4 * 1024 * 1024));
        values.put("fx.Niagara.SplatRenderer.Enabled", ConsoleValue.bool(true));
        values.put("fx.Niagara.SplatRenderer.MaxSplatsPerEmitter", ConsoleValue.integer(4 * 1024 * 1024));
        values.put("fx.Niagara.SplatRenderer.LogSubmissions", ConsoleValue.integer(0));
        values.put(DATA_CHANNELS_RETAIN_GPU, ConsoleValue.bool(true));
        values.put(DATA_CHANNELS_AUTO_UPLOAD, ConsoleValue.bool(true));
        values.putAll(VolumeTracingSettings.CONSOLE_DEFAULTS);
        for (var entry : consoleValues.entrySet()) {
            ConsoleValue declared = values.get(entry.getKey()), value = Objects.requireNonNull(entry.getValue());
            if (declared != null && declared.type() != value.type())
                throw new IllegalArgumentException("Source console variable type differs from its registered type: " + entry.getKey());
            values.put(entry.getKey(), value);
        }
        values.put("fx.Niagara.QualityLevel", ConsoleValue.integer(quality));
        consoleValues = Collections.unmodifiableMap(values);
    }
    public @Nullable ConsoleValue console(String name) { return consoleValues.get(name); }
    public VolumeCacheStorage volumeCacheStorage() {
        if (consoleValues.get(VOLUME_CACHE_ENABLED).bits() == 0) return VolumeCacheStorage.DISABLED;
        return switch (consoleValues.get(VOLUME_CACHE_STORAGE).bits()) {
            case 0 -> VolumeCacheStorage.DENSE;
            case 1 -> VolumeCacheStorage.SPARSE;
            default -> VolumeCacheStorage.DISABLED;
        };
    }
    public int radixSortThreshold() { return consoleValues.get(RADIX_SORT_THRESHOLD).bits(); }
    public long vectorVmPageBytes() { return (long)consoleValues.get(VECTOR_VM_PAGE_KB).bits() << 10; }
    public boolean defaultHighSortPrecision() { return consoleValues.get(DEFAULT_SORT_PRECISION).bits() == 1; }
    public boolean sortOrCullCpuParticlesOnGpu(boolean needsSort, boolean needsCull, int count) {
        return sortOrCullCpuParticlesOnGpu(needsSort, needsCull, count, false);
    }

    public boolean sortOrCullCpuParticlesOnGpu(boolean needsSort, boolean needsCull, int count, boolean perParticleLods) {
        if (!canSortOrCullParticlesOnGpu(needsSort, needsCull)) return false;
        if (perParticleLods) return true;
        int sort = consoleValues.get(CPU_TO_GPU_SORT).bits();
        int cull = consoleValues.get(CPU_TO_GPU_CULL).bits();
        return (sort >= 0 && count >= sort) || (cull >= 0 && count >= cull);
    }

    public boolean canSortOrCullParticlesOnGpu(boolean needsSort, boolean needsCull) {
        return consoleValues.get(GPU_SORTING).bits() != 0 && (needsSort || needsCull
                && consoleValues.get(COMPUTE_SHADERS).bits() != 0 && consoleValues.get(GPU_CULLING).bits() != 0);
    }
    public TranslucentSortPolicy translucentSortPolicy() {
        return switch (consoleValues.get(TRANSLUCENCY_SORT_POLICY).bits()) {
            case 0 -> TranslucentSortPolicy.DISTANCE;
            case 1 -> TranslucentSortPolicy.PROJECTED_Z;
            case 2 -> TranslucentSortPolicy.ALONG_AXIS;
            default -> throw new IllegalArgumentException("Invalid r.TranslucentSortPolicy");
        };
    }
    public boolean renders(Renderer renderer) {
        return switch (renderer) {
            case SPRITE -> consoleValues.get(SPRITE_RENDERING).bits() != 0;
            case MESH -> consoleValues.get(MESH_RENDERING).bits() != 0;
            case LIGHT -> consoleValues.get(LIGHT_RENDERING).bits() != 0 && supportsSimpleLights();
            case RIBBON -> NiagaraRibbonScalability.renderingEnabled(this);
            default -> true;
        };
    }

    private boolean supportsSimpleLights() {
        if (!shaderPlatforms.mobileFeatureLevel()) return true;
        boolean deferred = switch (shaderPlatforms.mobileShadingPath()) {
            case PROJECT_SETTING -> consoleValues.get(MOBILE_SHADING_PATH).bits() != 0;
            case FORWARD -> false;
            case DEFERRED -> true;
        };
        boolean deferredAllowed = !shaderPlatforms.openGlFeatureLevel() || consoleValues.get(MOBILE_DEFERRED_OPENGL).bits() != 0;
        if (deferred && deferredAllowed && consoleValues.get(MOBILE_HDR).bits() == 1) return true;
        return consoleValues.get(MOBILE_FORWARD_LOCAL_LIGHTS).bits() > 0
                && consoleValues.get(MOBILE_FORWARD_PARTICLE_LIGHTS).bits() != 0;
    }
    public float componentPoolInactiveSeconds() {
        return Float.intBitsToFloat(consoleValues.get(COMPONENT_POOL_INACTIVE_TIME).bits());
    }
    public float globalSystemCountScale() { return Float.intBitsToFloat(consoleValues.get(SYSTEM_COUNT_SCALE).bits()); }
    public float minimumSystemScalabilityDistance() {
        return Float.intBitsToFloat(consoleValues.get(MIN_SYSTEM_SCALABILITY_DISTANCE).bits());
    }
    public float lastRenderTimeSafetyBias() {
        return Float.intBitsToFloat(consoleValues.get("fx.LastRenderTimeSafetyBias").bits());
    }
    public int systemUpdateOnSpawn() { return consoleValues.get(UPDATE_ON_SPAWN).bits(); }
    public int maxSystemTickSubsteps() { return Math.max(0, consoleValues.get(MAX_TICK_SUBSTEPS).bits()); }
    public boolean skipsSystemTick(float delta) {
        float threshold = Float.intBitsToFloat(consoleValues.get(SKIP_TICK_DELTA).bits());
        return threshold > 0 && delta <= threshold;
    }
    public float lwcTileRecache() { return Float.intBitsToFloat(consoleValues.get(LWC_TILE_RECACHE).bits()); }
    public float renderTargetResolutionMultiplier() { return Float.intBitsToFloat(consoleValues.get(TARGET_RESOLUTION).bits()); }
    public boolean renderTargetIgnoreCookedOut() { return consoleValues.get(TARGET_IGNORE_COOKED).bits() != 0; }
    public boolean collisionCpuEnabled() { return consoleValues.get(COLLISION_CPU_ENABLED).bits() != 0; }
    public boolean globalDistanceFieldTraceEnabled() { return consoleValues.get(GLOBAL_DISTANCE_FIELD_TRACE).bits() != 0; }
    public boolean hardwareRayTraceEnabled() { return consoleValues.get(HARDWARE_RAY_TRACE).bits() != 0; }
    public boolean globalDistanceFieldTraceSupported() {
        var generation = console("r.GenerateMeshDistanceFields");
        if (!globalDistanceFieldTraceEnabled() || generation != null && generation.bits() == 0) return false;
        var coexistence = console("r.DistanceFields.SupportEvenIfHardwareRayTracingSupported");
        return coexistence == null || coexistence.bits() != 0 || !hardwareRayTracingAllowed();
    }
    public boolean hardwareRayTraceSupported() {
        var enabled = console("r.Raytracing.Enable");
        if (enabled == null) enabled = console("r.RayTracing.Enable");
        return hardwareRayTraceEnabled() && hardwareRayTracingAllowed() && (enabled == null || enabled.bits() != 0);
    }
    private boolean hardwareRayTracingAllowed() {
        var project = console("r.RayTracing");
        // The standalone host has no hardware ray scene until a project provides one.
        return project != null && project.bits() != 0;
    }
    public int distanceFieldClipmapCount() { return Math.clamp(consoleValues.get(FIELD_CLIPMAP_COUNT).bits(), 0, 6); }
    public int distanceFieldResolution() { return consoleValues.get(FIELD_RESOLUTION).bits(); }
    public float distanceFieldExtentExponent() { return Float.intBitsToFloat(consoleValues.get(FIELD_EXTENT_EXPONENT).bits()); }
    public int distanceFieldMipFactor() { return Math.clamp(consoleValues.get(FIELD_MIP_FACTOR).bits(), 1, 8); }
    public boolean renderAssetStreamingUsesBoxDistance() { return consoleValues.get("r.Streaming.UseNewMetrics").bits() != 0; }
    public float hiddenPrimitiveStreamingScale() {
        return renderAssetStreamingUsesBoxDistance() ? Float.intBitsToFloat(consoleValues.get("r.Streaming.HiddenPrimitiveScale").bits()) : 1;
    }
    public int streamingSplitThreshold() { return consoleValues.get("r.Streaming.MinMipForSplitRequest").bits(); }
    public int staticMeshInlineLodMode() { return consoleValues.get(STATIC_MESH_INLINE_LODS).bits(); }
    public boolean staticMeshDistanceFields() { return consoleValues.get(STATIC_MESH_DISTANCE_FIELDS).bits() != 0; }
    public boolean staticLightingAllowed() { return consoleValues.get("r.AllowStaticLighting").bits() != 0; }
    public int particleExportReadbackLimit() { return consoleValues.get(EXPORT_READBACK_LIMIT).bits(); }
    public boolean dataChannelsEnabled() { return consoleValues.get(DATA_CHANNELS_ENABLED).bits() != 0; }
    public boolean dataChannelsForcePrevious() { return consoleValues.get(DATA_CHANNELS_PREVIOUS).bits() != 0; }
    public int dataChannelsForcedReadTickGroup() { return consoleValues.get(DATA_CHANNELS_READ_GROUP).bits(); }
    public boolean worldUsesTickPasses() { return consoleValues.get(WORLD_TICK_PASSES).bits() != 0; }
    public boolean worldSpawnsPerTickGroup() { return consoleValues.get(WORLD_SPAWN_PER_TICK_GROUP).bits() != 0; }
    public boolean splineCpuLutsDisabled() { return consoleValues.get("fx.Niagara.NDISpline.GDisableLUTs").bits() != 0; }
    public boolean splineUsesLegacyCurves() { return consoleValues.get("SplineComponent.UseSplineCurves").bits() != 0; }
    public long neighborQueryCellMaximum() {
        return Math.min(Integer.toUnsignedLong(consoleValues.get("fx.MaxNiagaraNeighborQueryCells").bits()), Integer.MAX_VALUE);
    }
    public boolean dataChannelsRetainGpu() { return consoleValues.get(DATA_CHANNELS_RETAIN_GPU).bits() != 0; }
    public boolean dataChannelsAutoUploadGpuSpawns() { return consoleValues.get(DATA_CHANNELS_AUTO_UPLOAD).bits() != 0; }
    public int statelessFeatures() {
        return (consoleValues.get("fx.NiagaraStateless.Feature.ExecuteGPU").bits() != 0 ? 1 : 0)
                | (consoleValues.get("fx.NiagaraStateless.Feature.ExecuteCPU").bits() != 0 ? 2 : 0);
    }
    public boolean statelessUseCache() { return consoleValues.get("fx.NiagaraStateless.ComputeManager.UseCache").bits() != 0; }
    public int statelessCpuThreshold() { return consoleValues.get("fx.NiagaraStateless.ComputeManager.CPUThreshold").bits(); }
    public boolean gaussianSplatEnabled() {
        var value = console("r.GaussianSplat");
        return value == null || value.bits() != 0;
    }
    public int availableQualityMask() {
        if (consoleValues.get("fx.Niagara.AllowRuntimeScalabilityChanges").bits() != 0) return availableMask;
        int minimum = consoleValues.get("fx.Niagara.QualityLevel.Min").bits();
        int maximum = consoleValues.get("fx.Niagara.QualityLevel.Max").bits();
        if (minimum == -1 && maximum == -1) return availableMask;
        int mask = 0;
        minimum = minimum == -1 ? 0 : minimum;
        maximum = maximum == -1 ? 31 : maximum;
        for (int quality = 0; quality < 32; quality++) if (quality >= minimum && quality <= maximum) mask |= 1 << quality;
        return availableMask & mask;
    }
}
