package com.kltyton.lumenfx.client.simulation;

import com.kltyton.lumenfx.effect.simulation.UnitySubEmitterGroup;
import com.kltyton.lumenfx.client.render.compute.ComputeRuntime;
import com.kltyton.lumenfx.client.render.niagara.gpu.splat.NiagaraGpuSplatBuilder;
import com.kltyton.lumenfx.client.render.niagara.splat.NiagaraGaussianSplatDraw;

import com.kltyton.lumenfx.client.playback.AttachmentSamples;

import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry;
import com.kltyton.lumenfx.client.render.light.shadow.geometry.ParticleShadowGeometry.GpuSource;
import com.kltyton.lumenfx.client.scene.physics.NiagaraPhysicsFieldWorlds;
import com.kltyton.lumenfx.client.scene.water.NiagaraWaterWorlds;
import com.kltyton.lumenfx.client.scene.physics.NiagaraChaosWorlds;

import com.kltyton.lumenfx.api.client.parameter.NiagaraStreamingVirtualTexture;
import com.kltyton.lumenfx.api.client.parameter.NiagaraRuntimeVirtualTexture;

import com.kltyton.lumenfx.api.client.NiagaraCacheCapture;
import com.kltyton.lumenfx.api.client.NiagaraAgeUpdate;
import com.kltyton.lumenfx.api.client.NiagaraComponentTickSettings;
import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl;
import com.kltyton.lumenfx.api.client.component.NiagaraMovieSceneEvaluation;
import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl.Action;
import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl.MovieScene;
import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl.MovieSceneEnd;
import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl.State;
import com.kltyton.lumenfx.api.client.component.NiagaraComponentControl.Pooling;

import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionWorlds;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources.ObjectRegistry;
import com.kltyton.lumenfx.client.material.collection.MaterialCollectionWorlds;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentDraw;
import com.kltyton.lumenfx.api.client.UnityParticleCollisionEvent;
import com.kltyton.lumenfx.api.client.UnityParticleTriggerEvent;
import com.kltyton.lumenfx.api.client.EffectHandle.Pose;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Component;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs.Velocity;
import com.kltyton.lumenfx.api.client.parameter.NiagaraCollisionWorld;
import com.kltyton.lumenfx.api.client.parameter.UnityCollisionLayers;
import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs.Transform;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.MaterialDefinition;
import com.kltyton.lumenfx.client.render.EffectMeshBuilder;
import com.kltyton.lumenfx.client.render.decal.DecalMaterialSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshDrawSelection;
import com.kltyton.lumenfx.client.render.mesh.MeshView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuBatchDispatcher;
import com.kltyton.lumenfx.client.render.niagara.debug.NiagaraGpuDebugLines;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.debug.NiagaraCpuDebugLines;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshCollector;
import com.kltyton.lumenfx.client.render.mesh.nanite.NaniteInstanceCollector;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuMeshDraw;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuPrograms;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleView;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuParticleBuffers.RenderLease;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRibbonDraw;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuRibbonFrame;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteCollector;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuSpriteDraw;
import com.kltyton.lumenfx.client.render.niagara.gpu.NiagaraGpuView;
import com.kltyton.lumenfx.client.render.volume.NiagaraVolumeInstance;
import com.kltyton.lumenfx.client.simulation.niagara.volume.NiagaraVolumeInstances;
import com.kltyton.lumenfx.client.simulation.niagara.renderer.NiagaraRendererFields.Inputs;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraCameraFrame;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraAgeClock;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraSystem;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraTickClock;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraWorldSystemSimulation;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraWorldSystemSimulation.Phase;
import com.kltyton.lumenfx.client.simulation.niagara.render.NiagaraEmitterRenderState;
import com.kltyton.lumenfx.client.simulation.niagara.component.NiagaraMovieSceneLifetime;
import com.kltyton.lumenfx.client.simulation.niagara.component.NiagaraMovieSceneCachePlayback;
import com.kltyton.lumenfx.client.component.niagara.NiagaraAutoAttachmentState;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.kltyton.lumenfx.client.simulation.niagara.scalability.NiagaraPlatformSetProxies;
import com.kltyton.lumenfx.client.simulation.niagara.decal.NiagaraDecalParticles;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.MinecraftNiagaraCollisionWorld;
import com.kltyton.lumenfx.client.simulation.niagara.light.NiagaraLightParticles;
import com.kltyton.lumenfx.client.simulation.niagara.material.NiagaraMaterialInstances;
import com.kltyton.lumenfx.client.simulation.niagara.material.NiagaraMaterialObjects;
import com.kltyton.lumenfx.client.transform.NiagaraComponentBounds;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.unity.UnityExternalForces.FieldState;
import com.kltyton.lumenfx.effect.unity.UnityRendererBinding;
import com.kltyton.lumenfx.effect.unity.UnityParticleTrail;
import com.kltyton.lumenfx.effect.render.mesh.StaticMeshGeometry;
import com.kltyton.lumenfx.effect.unity.UnitySubEmitter.Event;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentDefaults;
import com.kltyton.lumenfx.effect.render.depth.CustomDepthStencil;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Playback;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack.ReplayMode;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraSystemScalability.Reaction;
import com.kltyton.lumenfx.effect.niagara.NiagaraTiming.Warmup;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.Reference;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import com.kltyton.lumenfx.effect.render.light.ParticleLight;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

public final class EffectInstance implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final AtomicLong NEXT_UNITY_INSTANCE = new AtomicLong(1);
    private final long unitySortInstanceId = NEXT_UNITY_INSTANCE.getAndIncrement();
    private final EffectDefinition definition;
    private final NiagaraComponentDefaults componentDefaults;
    private final NiagaraAutoAttachmentState autoAttachment;
    private EffectHandle handle;
    private final @Nullable ClientLevel level;
    private @Nullable NiagaraComponentControl componentControl;
    private State componentState;
    private boolean componentRetirePending;
    private boolean poolReturnRequested;
    private boolean manualReleasePending;
    private boolean pooledLease;
    private boolean pooledNeedsRecreation;
    private @Nullable Double pooledRenderTime;
    private long componentActivation;
    private boolean movieSceneWantsEmission = true;
    private @Nullable PreAnimatedComponent preAnimatedComponent;
    private NiagaraAgeUpdate.@Nullable Mode preAnimatedCacheAgeMode;
    private final @Nullable NiagaraCollisionWorld defaultCollisionWorld;
    private final List<EmitterFrame> frameEmitters;
    private NiagaraSystem niagara;
    private final ObjectRegistry parameterObjects = new ObjectRegistry();
    private Map<Key, Reference> dataInterfaceParameters;
    private final @Nullable NiagaraPlatformSetProxies platformSets;
    private final NiagaraMaterialObjects dynamicMaterialObjects;
    private long seed;
    private final GpuEmitterRuntime[] gpuEmitters;
    private final Map<String, NiagaraStreamingVirtualTexture> virtualTextures;
    private final Map<String, NiagaraRuntimeVirtualTexture> runtimeVirtualTextures;
    private boolean sharedCullProxy;
    private List<EffectInstance> cullProxyTargets = List.of();
    private List<CullProxyDrawState> cullProxyViews = List.of();
    private @Nullable EffectInstance cullProxySource;
    private @Nullable CullProxyDrawState cullProxyDraw;
    private boolean pendingHostFinish;
    private boolean pendingComponentBoundsUpdate;
    private EffectHandle.Input frameInput;
    private @Nullable Pose previousRenderedPose;
    private double frameTime;
    private boolean frameEmitting;
    private Map<String, Integer> frameUnityManualRequests = Map.of();
    private boolean simulationPaused;
    private boolean componentRenderingEnabled;
    private final boolean niagaraHasDeferredDecals;
    private enum ViewRender { ALL, SHADOW_ONLY, NONE }
    private ViewRender ownerViewRender = ViewRender.ALL;
    private @Nullable Reaction scalabilityCull;
    private long visibilityEpoch;
    private final AttachmentSamples attachmentSamples = new AttachmentSamples();
    private boolean frameAttachmentSampled;
    private @Nullable Vec3 frameUnityPhysicsVelocity;
    private UnityCollisionLayers frameUnityCollisionLayers = UnityCollisionLayers.EMPTY;
    private Map<Identifier, StaticMeshGeometry> frameUnityEmissionGeometry = Map.of();
    private Map<String, FieldState> frameUnityForceFields = Map.of();
    private Map<String, List<com.kltyton.lumenfx.effect.unity.UnityTrigger.Collider>> frameUnityTriggerColliders = Map.of();
    private int warmupTicks;
    private int warmupRemaining;
    private float warmupDelta;
    private NiagaraTickClock.@Nullable Plan warmupPlan;
    private int warmupStep;
    private NiagaraTickClock.Plan tickPlan = new NiagaraTickClock.Plan(1, 0, 0, false);
    private final NiagaraAgeClock ageClock = new NiagaraAgeClock();
    private NiagaraAgeClock.@Nullable Step ageStep;
    private NiagaraAgeUpdate ageUpdate = NiagaraAgeUpdate.DEFAULT;
    private long seekStarted;
    private int tickStepLimit = 1;
    private boolean seeking;
    private boolean packCurrentStep = true;

    public EffectInstance(EffectDefinition definition, EffectHandle handle, long seed) {
        this(definition, handle, seed, currentClientLevel());
    }

    private static @Nullable ClientLevel currentClientLevel() {
        Minecraft client = Minecraft.getInstance();
        return client == null ? null : client.level;
    }

    public EffectInstance(EffectDefinition definition, EffectHandle handle, long seed, @Nullable ClientLevel level) {
        this(definition, handle, seed, level, Map.of(), Map.of());
    }

    public EffectInstance(EffectDefinition definition, EffectHandle handle, long seed, @Nullable ClientLevel level,
                          Map<String, NiagaraStreamingVirtualTexture> virtualTextures,
                          Map<String, NiagaraRuntimeVirtualTexture> runtimeVirtualTextures) {
        this(definition, handle, seed, level, virtualTextures, runtimeVirtualTextures, new NiagaraMaterialObjects());
    }

    public EffectInstance(EffectDefinition definition, EffectHandle handle, long seed, @Nullable ClientLevel level,
                          Map<String, NiagaraStreamingVirtualTexture> virtualTextures,
                          Map<String, NiagaraRuntimeVirtualTexture> runtimeVirtualTextures,
                          NiagaraMaterialObjects dynamicMaterialObjects) {
        this.definition = definition;
        this.dynamicMaterialObjects = Objects.requireNonNull(dynamicMaterialObjects);
        componentDefaults = definition.niagara() == null ? NiagaraComponentDefaults.DEFAULT : definition.niagara().component();
        autoAttachment = new NiagaraAutoAttachmentState(componentDefaults.autoAttachment(), handle.inputWithoutNiagaraAttachment().pose());
        componentRenderingEnabled = componentDefaults.renderingEnabled();
        niagaraHasDeferredDecals = definition.niagara() != null && definition.emitters().stream()
                .anyMatch(renderer -> renderer.renderer() == EmitterDefinition.Renderer.DECAL);
        if (!componentDefaults.drawInGame())
            ownerViewRender = componentDefaults.castHiddenShadow() ? ViewRender.SHADOW_ONLY : ViewRender.NONE;
        this.handle = handle;
        componentControl = handle.niagaraComponent();
        componentState = componentControl == null ? State.ACTIVE : State.INACTIVE;
        this.level = level;
        this.virtualTextures = virtualTextures;
        this.runtimeVirtualTextures = runtimeVirtualTextures;
        defaultCollisionWorld = level == null || definition.niagara() == null ? null : new MinecraftNiagaraCollisionWorld(level);
        this.seed = seed;
        SplittableRandom random = new SplittableRandom(seed);
        var platformPublication = NiagaraPlatforms.publication();
        dataInterfaceParameters = handle.dataInterfaceParameters();
        var playback = handle.niagaraCache();
        if (definition.niagara() == null && (!dataInterfaceParameters.isEmpty() || !handle.objectParameters().isEmpty() || playback != null))
            throw new IllegalArgumentException("Niagara parameters and cache playback require a Niagara effect");
        platformSets = definition.niagara() == null ? null : new NiagaraPlatformSetProxies(platformPublication);
        niagara = definition.niagara() == null || componentControl != null ? null
                : new NiagaraSystem(definition, seed, platformPublication, Objects.requireNonNull(platformSets), parameterObjects,
                        dataInterfaceParameters, playback, handle.objectParameters());
        if (niagara != null) niagara.dynamicMaterialObjects(dynamicMaterialObjects);
        initializeWarmup();
        if (definition.niagara() == null) {
            var bindings = definition.unityRenderers();
            var secondary = new HashMap<String, List<EmitterDefinition>>();
            for (var renderer : definition.emitters()) {
                var binding = bindings.get(renderer.name());
                if (binding != null && binding.source() != null)
                    secondary.computeIfAbsent(binding.source(), name -> new ArrayList<>()).add(renderer);
            }
            var frames = new ArrayList<EmitterFrame>();
            var systems = new LinkedHashMap<String, EmitterFrame>();
            for (var renderer : definition.emitters()) {
                var binding = bindings.get(renderer.name());
                if (binding != null && binding.source() != null) continue;
                var otherRenderers = secondary.getOrDefault(renderer.name(), List.of());
                var trail = hasPerParticleTrail(binding) ? binding : otherRenderers.stream()
                        .map(other -> bindings.get(other.name())).filter(EffectInstance::hasPerParticleTrail)
                        .findFirst().orElse(null);
                long emitterSeed = random.nextLong();
                if (binding != null && binding.randomSeed() != null) emitterSeed = binding.randomSeed();
                var emitter = new EmitterInstance(renderer, emitterSeed, trail, binding);
                var frame = new EmitterFrame(emitter, binding, trail, otherRenderers, renderer, emitterSeed ^ 0x57ba94cbd2fa1273L);
                frames.add(frame);
                systems.put(renderer.name(), frame);
            }
            for (var source : frames) {
                if (source.unityRenderer == null) continue;
                for (var link : source.unityRenderer.subEmitters()) {
                    var target = systems.get(link.target());
                    Objects.requireNonNull(target.unity).addParent(Objects.requireNonNull(source.unity));
                }
            }
            frameEmitters = List.copyOf(frames);
        } else {
            frameEmitters = IntStream.range(0, definition.niagara().emitters().size()).mapToObj(EmitterFrame::new).toList();
        }
        int emitterCount = definition.niagara() == null ? 0 : definition.niagara().emitters().size();
        gpuEmitters = new GpuEmitterRuntime[emitterCount];
        if (niagara != null) {
            niagara.virtualTextureAssets(virtualTextures, runtimeVirtualTextures);
            Objects.requireNonNull(platformSets).retainObjects(niagara.platformObjectIdentities());
            handle.publishArrayResults(niagara.arrayResults(), Map.of());
        }
    }

    public List<EmitterFrame> prepareFrame(double now, float delta, Vec3 camera) {
        return prepareFrame(now, delta, camera, null);
    }

    public void tickPlan(NiagaraTickClock.Plan plan) { tickPlan = plan; }
    public void resolveFrameTickPlan(boolean postActorSpawn) {
        if (niagara != null && !simulationPaused && !niagara.completed()) {
            if ((postActorSpawn && (niagara.requiresSoloSystemSimulation() || niagara.initialized()))
                    || niagara.hasPendingCurrentCapture(handle.niagaraCacheCapture()))
                tickPlan = new NiagaraTickClock.Plan(0, tickPlan.engineDelta(), tickPlan.systemDelta(), tickPlan.fixed());
            else tickPlan = ageStep == null ? niagara.planFrame(frameTime, tickPlan) : niagara.planAgeTick(ageStep);
        }
        tickStepLimit = tickPlan.steps();
    }
    public int simulationSteps() { return simulationPaused ? 0 : tickStepLimit; }
    public boolean usesNonOverlappingNiagaraPass() { return niagara != null && niagara.usesNonOverlappingTickPass(); }
    public boolean requiresCurrentGpuDataChannel() { return niagara != null && niagara.requiresCurrentGpuDataChannel(); }
    private boolean gpuFrameDispatched;
    private Phase selectedHostPhase = Phase.Render;
    private ComponentTickGroup selectedTickGroup = ComponentTickGroup.TG_LastDemotable;
    private ComponentTickGroup requestedTickGroup = ComponentTickGroup.TG_LastDemotable;

    public void beginSimulationFrame(double now) {
        captureCurrentCache();
        captureInput(now);
        if (niagara != null) {
            var tickSettings = handle.niagaraTickSettings();
            var behavior = tickSettings.behavior() == null ? componentDefaults.tickBehavior() : tickSettings.behavior();
            requestedTickGroup = hostTickGroup(behavior, tickSettings);
            selectedTickGroup = niagara.scheduleHostTickGroup(requestedTickGroup);
        }
        prepareComponentTimeline(false);
        beginGpuBatch();
        pendingComponentBoundsUpdate = niagara != null && componentState != State.INACTIVE;
    }

    public boolean beginHostSimulationFrame(double now, boolean cameraUpdated, Supplier<NiagaraTickClock.Plan> plan) {
        return beginHostSimulationFrame(now, cameraUpdated, null, null, plan);
    }

    public boolean beginHostSimulationFrame(double now, boolean cameraUpdated, @Nullable NiagaraGpuPrograms programs,
                                            @Nullable Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                            Supplier<NiagaraTickClock.Plan> plan) {
        return beginHostSimulationFrame(now, cameraUpdated, programs, shaderLookup, plan, false);
    }

    public boolean beginHostSimulationFrame(double now, boolean cameraUpdated, @Nullable NiagaraGpuPrograms programs,
                                            @Nullable Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                            Supplier<NiagaraTickClock.Plan> plan, boolean postActorSpawn) {
        return beginHostSimulationFrame(now, cameraUpdated, programs, shaderLookup, plan, postActorSpawn, null);
    }

    public boolean beginHostSimulationFrame(double now, boolean cameraUpdated, @Nullable NiagaraGpuPrograms programs,
                                            @Nullable Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                            Supplier<NiagaraTickClock.Plan> plan, boolean postActorSpawn,
                                            @Nullable Phase spawnBeforeGroup) {
        if (niagara == null || niagara.completed() || componentState == State.INACTIVE) return false;
        captureCurrentCache();
        var capture = handle.niagaraCacheCapture();
        if (niagara.hasPendingCurrentCapture(capture) || niagara.hasPendingCacheAdvance(capture)) return false;
        var input = handle.input();
        var tickSettings = handle.niagaraTickSettings();
        var behavior = tickSettings.behavior() == null ? componentDefaults.tickBehavior() : tickSettings.behavior();
        if (input.renderTracked() && behavior == NiagaraTickBehavior.UsePrereqs) return false;
        boolean paused = handle.niagaraPaused() || scalabilityPaused();
        if (!paused) captureCollections();
        else capturePausedCacheSelection();
        var requestedGroup = hostTickGroup(behavior, tickSettings);
        var tickGroup = niagara.scheduleHostTickGroup(requestedGroup);
        Phase desired = Phase.forTickGroup(tickGroup);
        if (niagara.completed() || desired == Phase.Render || !cameraUpdated && desired == Phase.AfterCamera) return false;
        if (spawnBeforeGroup != null && desired.compareTo(spawnBeforeGroup) >= 0) return false;
        boolean deferGpu = niagara.hasStatefulGpuEmitters();
        if (deferGpu && (programs == null || shaderLookup == null || !niagara.canDeferGpu(input, programs, shaderLookup))) return false;
        tickPlan(plan.get());
        selectedHostPhase = desired;
        selectedTickGroup = tickGroup;
        requestedTickGroup = requestedGroup;
        setFrameInput(now, paused, tickSettings);
        prepareComponentTimeline(postActorSpawn);
        if (!paused) niagara.beginGpuBatch(gpuBatchCapacity(), deferGpu);
        return true;
    }

    private ComponentTickGroup hostTickGroup(NiagaraTickBehavior behavior, NiagaraComponentTickSettings settings) {
        var system = Objects.requireNonNull(niagara);
        var source = Objects.requireNonNull(definition.niagara());
        if (Objects.requireNonNull(system.platformEnvironment().console("fx.Niagara.ForceLastTickGroup")).bits() != 0)
            return ComponentTickGroup.TG_LastDemotable;
        if (!source.timing().requireCurrentFrameData()) return ComponentTickGroup.TG_PrePhysics;
        return switch (behavior) {
            case UsePrereqs -> system.hostPrerequisiteTickGroup();
            case ForceTickFirst -> ComponentTickGroup.TG_PrePhysics;
            case ForceTickLast -> ComponentTickGroup.TG_LastDemotable;
            case UseComponentTickGroup -> {
                var group = settings.tickGroup() == null ? componentDefaults.tickGroup() : settings.tickGroup();
                yield group.ordinal() > ComponentTickGroup.TG_LastDemotable.ordinal() ? ComponentTickGroup.TG_LastDemotable : group;
            }
        };
    }

    public ComponentTickGroup selectedTickGroup() { return selectedTickGroup; }

    public void trackWorldSimulation(NiagaraWorldSystemSimulation world, Phase phase, boolean postActorSpawn) {
        if (niagara != null) {
            Phase scheduled = postActorSpawn ? selectedHostPhase : phase;
            world.track(scheduled, selectedTickGroup, requestedTickGroup, niagara, simulationPaused);
        }
    }

    private void beginGpuBatch() {
        if (niagara != null && !simulationPaused) niagara.beginGpuBatch(gpuBatchCapacity());
    }

    private int gpuBatchCapacity() {
        int normalSteps = niagara.maximumFrameSteps();
        int warmupSteps = Math.multiplyExact(warmupRemaining, normalSteps);
        int simulationSteps = ageStep != null && ageStep.multiTick() ? ageStep.count() : tickPlan.steps();
        return Math.addExact(warmupSteps, Math.max(normalSteps, simulationSteps));
    }

    private void initializeWarmup() {
        warmupPlan = null;
        warmupRemaining = warmupTicks = 0;
        if (niagara == null || niagara.playingCache()) return;
        Warmup warmup = handle.niagaraWarmup();
        if (warmup == null) warmup = componentDefaults.warmup();
        if (warmup == null || warmup.ticks() == -1) warmup = Objects.requireNonNull(definition.niagara()).timing().warmup();
        warmupDelta = warmup.delta();
        warmupRemaining = warmupTicks = warmup.activeTicks();
    }

    private void prepareComponentTimeline(boolean postActorSpawn) {
        ageStep = null;
        if (niagara == null || postActorSpawn || simulationPaused || niagara.completed()) return;
        var capture = handle.niagaraCacheCapture();
        if (niagara.hasPendingCurrentCapture(capture) || niagara.hasPendingCacheAdvance(capture)) return;
        ageUpdate = handle.niagaraAgeUpdate();
        seeking = false;
        boolean controlledAge = !handle.stopping() && ageUpdate.mode() != NiagaraAgeUpdate.Mode.TICK_DELTA_TIME;
        if (niagara.playingCache()) {
            var playback = handle.niagaraCache();
            if (controlledAge && playback != null && playback.age() == null) {
                if (niagara.initialized() && ageUpdate.desiredAge() < niagara.componentAge()) resetComponentSimulation();
                niagara.cacheAge(ageUpdate.desiredAge());
            }
            return;
        }
        if (controlledAge && niagara.requiresSoloSystemSimulation())
            ageStep = ageClock.plan(ageUpdate, niagara.componentAge(), Objects.requireNonNull(definition.niagara()).timing());
        if ((ageStep != null && ageStep.reset())
                || (frameEmitting && niagara.requiresWorldTileReset(frameTime, frameInput.pose(), tickPlan, ageStep)))
            resetComponentSimulation();
    }

    private void resetComponentSimulation() {
        closeGpuRenderers();
        niagara.resetComponentSimulation();
        niagara.previousRendererPose(frameInput == null ? handle.input().pose() : frameInput.pose());
        previousRenderedPose = null;
        pendingHostFinish = false;
        warmupPlan = null;
        warmupRemaining = warmupTicks;
    }

    private void captureInput(double now) {
        boolean paused = definition.niagara() != null && (componentState == State.INACTIVE || handle.niagaraPaused() || scalabilityPaused());
        if (!paused) captureCollections();
        else capturePausedCacheSelection();
        setFrameInput(now, paused, handle.niagaraTickSettings());
    }

    private void setFrameInput(double now, boolean paused, NiagaraComponentTickSettings tickSettings) {
        gpuFrameDispatched = false;
        simulationPaused = paused;
        Boolean renderingEnabled = handle.niagaraRenderingEnabled();
        componentRenderingEnabled = renderingEnabled == null ? componentDefaults.renderingEnabled() : renderingEnabled;
        autoAttachment.update(handle, componentState != State.INACTIVE, componentActivation);
        frameInput = componentRenderInput(handle.input());
        frameTime = now;
        if (niagara != null) {
            niagara.componentTickSettings(tickSettings);
            niagara.emitterOverrides(handle.niagaraEmitterOverrides());
            niagara.ownerVelocity(handle.niagaraOwnerVelocity(frameInput));
            niagara.componentOwnerVisibility(handle);
            niagara.previousRendererPose(previousRenderedPose == null ? frameInput.pose() : previousRenderedPose);
        }
        frameAttachmentSampled = !paused && attachmentSamples.permitsEmission(frameInput.sequence(), frameInput.renderTracked(), now);
        if (niagara != null) niagara.attachmentBirthsAllowed(!frameInput.renderTracked() || frameAttachmentSampled);
        if (definition.niagara() == null) {
            var attached = handle.actorComponentInputs().attached();
            var physics = attached == null ? null : attached.physicsVelocity();
            frameUnityPhysicsVelocity = physics == null ? null : new Vec3(physics.x() * .01, physics.z() * .01, physics.y() * .01);
            frameUnityCollisionLayers = handle.unityCollisionLayers();
            frameUnityEmissionGeometry = handle.unityEmissionGeometry();
            frameUnityForceFields = handle.unityForceFields();
            frameUnityTriggerColliders = handle.unityTriggerColliders();
            var requests = handle.consumeUnitySubEmitterRequests();
            if (requests.isEmpty()) frameUnityManualRequests = Map.of();
            else {
                var counts = new HashMap<String, Integer>();
                for (String name : requests) {
                    var source = definition.unityRenderers().get(name);
                    if (source == null || source.subEmitters().stream().noneMatch(link -> link.event() == Event.MANUAL)) {
                        LOGGER.warn("Effect has no manual Unity sub-emitter links for {}", name);
                        continue;
                    }
                    counts.merge(name, 1, Integer::sum);
                }
                frameUnityManualRequests = Map.copyOf(counts);
            }
        }
        frameEmitting = (niagara != null ? !paused : frameAttachmentSampled)
                && componentState == State.ACTIVE && movieSceneWantsEmission
                && scalabilityCull == null && !handle.stopping();
    }

    private EffectHandle.Input componentRenderInput(EffectHandle.Input input) {
        var lighting = handle.primitiveLightingOverride();
        var customDepth = handle.customDepthStencilOverride();
        var translucencySort = handle.niagaraTranslucencySortOverride();
        return input.withRendererDefaults(definition.rendererObjects())
                .withPrimitiveLighting(lighting == null ? definition.primitiveLighting() : lighting)
                .withCustomDepthStencil(customDepth == null ? definition.customDepthStencil() : customDepth)
                .withCustomPrimitiveData(handle.customPrimitiveData())
                .withRenderInMainPass(componentDefaults.renderInMainPass())
                .withNiagaraTranslucencySort(translucencySort == null ? componentDefaults.translucencySort() : translucencySort);
    }

    public void captureViewActor(@Nullable UUID viewActor) {
        var override = handle.niagaraOwnerVisibility();
        var visibility = override == null ? componentDefaults.ownerVisibility() : override;
        var viewRender = componentDefaults.drawInGame()
                && (definition.niagara() == null || visibility.visibleTo(handle.niagaraViewOwners(), viewActor))
                ? ViewRender.ALL : componentDefaults.castHiddenShadow() ? ViewRender.SHADOW_ONLY : ViewRender.NONE;
        if (ownerViewRender != viewRender) {
            ownerViewRender = viewRender;
            visibilityEpoch++;
        }
    }

    private static EffectHandle.Input shadowOnly(EffectHandle.Input input) {
        return input.withCustomDepthStencil(CustomDepthStencil.DISABLED).withRenderInMainPass(false);
    }

    private boolean drawsForView(EmitterDefinition definition, EffectHandle.Input input, ViewRender viewRender) {
        // The deferred decal has its own scene proxy and is not subject to Niagara primitive owner flags.
        if (definition.renderer() == EmitterDefinition.Renderer.DECAL) return componentDefaults.drawInGame();
        return viewRender == ViewRender.ALL
                || viewRender == ViewRender.SHADOW_ONLY && ParticleShadowGeometry.casts(definition, input);
    }

    private boolean hasViewOutput(ViewRender viewRender) {
        return viewRender != ViewRender.NONE || niagaraHasDeferredDecals && componentDefaults.drawInGame();
    }

    public boolean beginWarmupStep() {
        if (niagara == null || simulationPaused) return false;
        if (warmupPlan != null) return true;
        while (warmupRemaining > 0) {
            warmupRemaining--;
            var plan = niagara.planManualTick(warmupDelta);
            if (plan.steps() == 0) {
                if (warmupRemaining == 0) niagara.resetWarmupAge();
                continue;
            }
            warmupPlan = plan;
            warmupStep = 0;
            return true;
        }
        return false;
    }

    public List<EmitterFrame> prepareWarmupFrame(double now, double engineTime, double realTime, Vec3 camera,
                                                NiagaraCameraFrame cameraFrame) {
        var plan = warmupPlan;
        if (niagara == null || plan == null) throw new IllegalStateException("Effect has no pending Niagara warmup step");
        niagara.prepareManualStep(now, engineTime, realTime, plan.systemDelta(), plan.engineDelta(),
                warmupStep, plan.steps(), frameInput, camera, frameEmitting, cameraFrame);
        return frameEmitters;
    }

    public List<EmitterFrame> prepareSimulationStep(double now, double engineTime, double realTime, Vec3 camera,
                                                   NiagaraCameraFrame cameraFrame, int step) {
        return prepareSimulationStep(now, engineTime, realTime, camera, cameraFrame, step, null, false);
    }

    public List<EmitterFrame> prepareSimulationStep(double now, double engineTime, double realTime, Vec3 camera,
                                                   @Nullable NiagaraCameraFrame cameraFrame, int step,
                                                   @Nullable NiagaraWorldSystemSimulation shared, boolean postActorSpawn) {
        if (step == 0 && needsAgeRenderPack() && ageUpdate.maximumSimulationTime() > 0) seekStarted = System.nanoTime();
        packCurrentStep = !needsAgeRenderPack() && step == tickPlan.steps() - 1;
        if (niagara != null) {
            if (tickPlan.fixed() || ageStep != null) niagara.prepareManualStep(now, engineTime, realTime, tickPlan.systemDelta(), tickPlan.engineDelta(),
                    step, tickPlan.steps(), frameInput, camera, frameEmitting, cameraFrame);
            else if (shared == null || niagara.requiresSoloSystemSimulation())
                niagara.prepare(now, engineTime, realTime, tickPlan.systemDelta(), frameInput, camera, frameEmitting, cameraFrame);
            else shared.prepareStep(niagara, now, engineTime, realTime, tickPlan.systemDelta(), frameInput,
                    camera, frameEmitting, cameraFrame, postActorSpawn);
        }
        return frameEmitters;
    }

    public @Nullable NiagaraCacheCapture manualCacheRequest() {
        var capture = handle.niagaraCacheCapture();
        return niagara != null && niagara.hasPendingCacheAdvance(capture) ? capture : null;
    }

    public boolean beginManualCache(NiagaraCacheCapture capture) {
        return niagara.beginManualCache(capture);
    }

    public boolean canAdvanceManualCache() { return !simulationPaused && !niagara.completed(); }

    public List<EmitterFrame> prepareManualCacheStep(NiagaraCacheCapture capture, double now, double engineTime,
                                                    double realTime, Vec3 camera, @Nullable NiagaraCameraFrame cameraFrame) {
        niagara.beginGpuBatch(1);
        niagara.prepareCacheStep(now, engineTime, realTime, capture.advanceDelta(), frameInput, camera, frameEmitting, cameraFrame);
        return frameEmitters;
    }

    public void flushManualCacheGpu(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                   Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                   NiagaraGpuView view, GpuTextureView depth) {
        niagara.gpuDispatcher().dispatchManual(encoder, programs, shaderLookup, view, depth);
        niagara.finishGpuFrame(encoder);
    }

    public void captureManualCache(NiagaraCacheCapture capture, @Nullable CommandEncoder encoder,
                                   @Nullable NiagaraGpuPrograms programs) {
        niagara.captureManualCache(capture, level, encoder, programs);
    }

    public void finishManualCache(NiagaraCacheCapture capture) { niagara.finishManualCache(capture); }
    public void endManualCacheFrame() { niagara.endManualCacheFrame(); }

    public List<EmitterFrame> prepareRenderFrame(double now) {
        if (niagara != null) niagara.prepareRenderOnly(now, frameInput);
        return packPreparedFrame();
    }

    public List<EmitterFrame> packPreparedFrame() {
        if (niagara != null && frameInput.renderTracked()) {
            var pose = handle.input().pose();
            if (!frameInput.pose().equals(pose)) {
                frameInput = frameInput.withPose(pose);
                niagara.prepareRenderOnly(frameTime, frameInput);
            }
        }
        packCurrentStep = true;
        return definition.niagara() == null || niagara != null && shouldRenderNiagara() ? frameEmitters : List.of();
    }

    public boolean needsAgeRenderPack() { return ageStep != null && ageStep.multiTick() && tickStepLimit > 0; }

    public void applyComponentRequests() {
        if (componentControl == null || pendingHostFinish || !handle.isAlive()) return;
        if (componentRetirePending) {
            resetComponentSimulation();
            componentRetirePending = false;
        }
        var requests = componentControl.takeRequests();
        if (handle.stopping()) {
            if (componentState == State.INACTIVE) {
                if (componentControl.pooling() == Pooling.AutoRelease || manualReleasePending) returnComponentToPool();
                else handle.invalidate();
            }
            return;
        }
        for (var request : requests) {
            switch (request) {
                case Action action -> applyComponentAction(action);
                case MovieScene scene -> evaluateMovieScene(scene);
                case MovieSceneEnd end -> restoreMovieScene();
            }
            if (!handle.isAlive() || handle.stopping()) break;
        }
    }

    private void applyComponentAction(Action action) {
        switch (action) {
            case ACTIVATE -> activateComponent(false, false);
            case ACTIVATE_RESET -> activateComponent(true, false);
            case REINITIALIZE -> activateComponent(true, true);
            case DEACTIVATE -> deactivateComponent(false);
            case DEACTIVATE_IMMEDIATE -> deactivateComponent(true);
            case RELEASE_TO_POOL -> releaseToPool();
        }
    }

    private record PreAnimatedComponent(State state, boolean forceSolo, @Nullable Boolean rendering,
            @Nullable Boolean allowScalability, NiagaraAgeUpdate ageUpdate, boolean requestedActive) { }

    private void evaluateMovieScene(MovieScene request) {
        var track = request.track() == null ? componentDefaults.lifeCycleTrack() : request.track();
        var cacheTrack = request.cacheTrack() == null ? componentDefaults.cacheTrack() : request.cacheTrack();
        if (track == null && cacheTrack == null) throw new IllegalStateException("Niagara component has no saved MovieScene track");
        if (cacheTrack != null && !request.context().recordingCacheTrack() && evaluateMovieSceneCache(cacheTrack, request.context())) return;
        if (track == null) return;
        if (preAnimatedComponent == null)
            preAnimatedComponent = new PreAnimatedComponent(componentState, handle.niagaraTickSettings().forceSolo(),
                    handle.niagaraRenderingEnabled(), handle.niagaraAllowScalability(), handle.niagaraAgeUpdate(), movieSceneWantsEmission);
        var frame = NiagaraMovieSceneLifetime.evaluate(track, request.context());
        handle.setNiagaraForceSolo(true);
        handle.setNiagaraAllowScalability(frame.allowScalability());
        switch (frame.action()) {
            case NONE -> { }
            case RESTART -> {
                deactivateComponent(true);
                if (handle.isAlive()) activateComponent(false, false);
            }
            case DEACTIVATE_IMMEDIATELY -> deactivateComponent(true);
            case REQUEST_ACTIVE -> {
                if (componentState == State.INACTIVE) activateComponent(false, false);
                movieSceneWantsEmission = true;
            }
            case REQUEST_INACTIVE -> {
                if (componentState != State.INACTIVE) movieSceneWantsEmission = false;
            }
        }
        handle.setNiagaraRenderingEnabled(frame.rendering());
        var previous = handle.niagaraAgeUpdate();
        float desired = componentState == State.INACTIVE || niagara.completed() || frame.desiredAge() == null
                ? previous.desiredAge() : frame.desiredAge();
        handle.setNiagaraAgeUpdate(frame.ageUpdate(previous, desired));
    }

    private boolean evaluateMovieSceneCache(NiagaraMovieSceneCacheTrack track, NiagaraMovieSceneEvaluation context) {
        var selection = NiagaraMovieSceneCachePlayback.select(track, context);
        if (!selection.anyValidCache()) {
            handle.setNiagaraCache(null, null);
            return false;
        }
        var section = selection.section();
        if (section == null) {
            if (selection.replayMode() == ReplayMode.DisplayCacheOnly) deactivateComponent(true);
            else handle.setNiagaraCache(null, null);
            return true;
        }
        var cache = section.cache();
        if (cache == null) {
            handle.setNiagaraCache(null, null);
            deactivateComponent(true);
            return true;
        }
        var age = handle.niagaraAgeUpdate();
        if (preAnimatedCacheAgeMode == null) preAnimatedCacheAgeMode = age.mode();
        handle.setNiagaraCache(cache, null);
        handle.setNiagaraAgeUpdate(new NiagaraAgeUpdate(NiagaraAgeUpdate.Mode.DESIRED_AGE,
                NiagaraMovieSceneCachePlayback.age(section, context), age.seekDelta(), age.lockToSeekDelta(),
                age.maximumSimulationTime(), age.renderWhileSeeking()));
        if (componentState == State.INACTIVE) activateComponent(true, false);
        return true;
    }

    private void restoreMovieScene() {
        if (preAnimatedCacheAgeMode != null) {
            var age = handle.niagaraAgeUpdate();
            handle.setNiagaraAgeUpdate(new NiagaraAgeUpdate(preAnimatedCacheAgeMode, age.desiredAge(),
                    age.seekDelta(), age.lockToSeekDelta(), age.maximumSimulationTime(), age.renderWhileSeeking()));
            handle.setNiagaraCache(null, null);
            preAnimatedCacheAgeMode = null;
        }
        var previous = preAnimatedComponent;
        if (previous == null) return;
        preAnimatedComponent = null;
        if (previous.state() == State.INACTIVE) {
            if (componentState != State.INACTIVE) {
                niagara.reactivateKeepingParticles();
                setComponentState(State.DRAINING);
            }
        } else {
            activateComponent(false, false);
            if (previous.state() == State.DRAINING) setComponentState(State.DRAINING);
        }
        handle.setNiagaraForceSolo(previous.forceSolo());
        handle.setNiagaraRenderingEnabled(previous.rendering());
        handle.setNiagaraAllowScalability(previous.allowScalability());
        handle.setNiagaraAgeUpdate(previous.ageUpdate());
        movieSceneWantsEmission = previous.requestedActive();
    }

    private void activateComponent(boolean reset, boolean reinitialize) {
        if (!reset && scalabilityCull != null) return;
        if (!reset && componentState == State.ACTIVE && movieSceneWantsEmission && niagara != null
                && scalabilityCull == null && !niagara.completed()) return;
        if (reset && niagara != null) niagara.resetComponentBounds();
        visibilityEpoch++;
        useCullProxy(null);
        scalabilityCull = null;
        movieSceneWantsEmission = true;
        handle.setNiagaraPaused(false);
        if (niagara == null) {
            createNiagara(NiagaraPlatforms.publication(), handle.dataInterfaceParameters(), handle.niagaraCache());
        } else if (reinitialize || pooledNeedsRecreation) {
            var interfaces = handle.dataInterfaceParameters();
            recreateNiagara(NiagaraPlatforms.publication(), interfaces, handle.niagaraCache());
        } else {
            if (niagara.completed()) {
                dataInterfaceParameters = handle.dataInterfaceParameters();
                niagara.dataInterfaceInputs(dataInterfaceParameters);
                niagara.objectParameterInputs(handle.objectParameters());
            }
            if (niagara.initialized() || niagara.completed()) niagara.reactivateKeepingParticles();
            niagara.resetRenderTime();
            initializeWarmup();
        }
        pooledNeedsRecreation = false;
        if (pooledRenderTime != null) {
            niagara.renderedAt(pooledRenderTime);
            pooledRenderTime = null;
        }
        // Activation can be requested after the frame's ordinary input capture (for
        // example by a MovieScene traversal or a component-pool reuse). Bind the
        // current overrides and cache callbacks before this instance is prepared.
        captureCollections();
        componentActivation++;
        setComponentState(State.ACTIVE);
        autoAttachment.update(handle, true, componentActivation);
        if (frameInput != null) frameInput = componentRenderInput(handle.input());
    }

    private void deactivateComponent(boolean immediate) {
        if (componentState == State.INACTIVE) {
            if (scalabilityCull != null) {
                visibilityEpoch++;
                useCullProxy(null);
                scalabilityCull = null;
                handle.setNiagaraPaused(false);
                closeGpuRenderers();
                completeComponent(true, true);
            }
            return;
        }
        boolean alreadyCulled = scalabilityCull == Reaction.DeactivateImmediateResume
                || scalabilityCull == Reaction.DeactivateResume && niagara.finished(true);
        visibilityEpoch++;
        useCullProxy(null);
        scalabilityCull = null;
        handle.setNiagaraPaused(false);
        if (immediate || alreadyCulled || !niagara.initialized()) {
            resetComponentSimulation();
            completeComponent(true);
        } else setComponentState(State.DRAINING);
    }

    private void releaseToPool() {
        if (!pooledLease || manualReleasePending) return;
        manualReleasePending = true;
        if (componentState == State.INACTIVE) {
            returnComponentToPool();
            return;
        }
        deactivateComponent(false);
    }

    private void returnComponentToPool() {
        poolReturnRequested = pooledLease;
        handle.invalidate();
    }

    private void setComponentState(State state) {
        componentState = state;
        if (componentControl != null) componentControl.publish(state);
    }

    private boolean autoAttachmentEnabled() {
        Boolean override = handle.niagaraAutoManageAttachment();
        return override == null ? componentDefaults.autoAttachment().enabled() : override;
    }

    private void completeComponent(boolean external) {
        completeComponent(external, false);
    }

    private void completeComponent(boolean external, boolean allowInactive) {
        if (componentState == State.INACTIVE && !allowInactive) return;
        autoAttachment.complete(handle, autoAttachmentEnabled(), componentDefaults.autoDestroy());
        setComponentState(State.INACTIVE);
        var control = Objects.requireNonNull(componentControl);
        try { control.publishCompletion(handle, external); }
        finally {
            if (control.pooling() == Pooling.AutoRelease || manualReleasePending) returnComponentToPool();
            else if (handle.stopping() || (control.pooling() == Pooling.None ? componentDefaults.autoDestroy() : !pooledLease))
                handle.invalidate();
        }
    }

    public boolean isSharedCullProxy() { return sharedCullProxy; }

    private List<EmitterDefinition> renderDefinitions(NiagaraDefinition.Emitter emitter) {
        return emitter.renderers().stream().map(renderer -> definition.niagaraRenderer(renderer.name())).toList();
    }

    public EffectInstance createCullProxy(float maximumDistance) {
        var proxyHandle = new EffectHandle(Vec3.ZERO, null, componentDefaults.customPrimitiveData());
        var parameters = handle.input();
        parameters.parameters().forEach(proxyHandle::setParameter);
        parameters.arrayParameters().forEach(proxyHandle::setArrayParameter);
        parameters.rendererParameters().forEach(proxyHandle::setRendererParameter);
        parameters.textureParameters().forEach(proxyHandle::setTextureParameter);
        handle.dataInterfaceParameters().forEach((key, value) -> proxyHandle.setDataInterfaceParameter(key, value.value()));
        var timing = Objects.requireNonNull(definition.niagara()).timing();
        proxyHandle.setNiagaraWarmup(new Warmup(timing.warmupTicks(), timing.warmupDelta()));
        proxyHandle.setNiagaraRandomSeedOffset(0);
        proxyHandle.setNiagaraAllowScalability(false);
        var proxy = new EffectInstance(definition, proxyHandle, seed, level, virtualTextures, runtimeVirtualTextures, dynamicMaterialObjects);
        proxy.sharedCullProxy = true;
        proxy.niagara.cullProxyLod(maximumDistance);
        return proxy;
    }

    public void cullProxyTargets(List<EffectInstance> targets) {
        for (var target : cullProxyTargets)
            if (!targets.contains(target) && target.cullProxySource == this) target.useCullProxy(null);
        cullProxyTargets = List.copyOf(targets);
        if (targets.isEmpty()) cullProxyViews = List.of();
        for (var target : cullProxyTargets) target.useCullProxy(this);
        handle.setNiagaraPaused(targets.isEmpty());
    }

    private void detachCullProxyTarget(EffectInstance target) {
        if (!sharedCullProxy || !cullProxyTargets.contains(target)) return;
        cullProxyTargets = cullProxyTargets.stream().filter(value -> value != target).toList();
        var draw = target.cullProxyDraw;
        if (draw != null) cullProxyViews = cullProxyViews.stream().filter(value -> value != draw).toList();
        handle.setNiagaraPaused(cullProxyTargets.isEmpty());
    }

    private void useCullProxy(@Nullable EffectInstance source) {
        if (cullProxySource == source) return;
        var previous = cullProxySource;
        if (previous != null) previous.detachCullProxyTarget(this);
        cullProxySource = source;
        visibilityEpoch++;
        clearCullProxyDraw();
    }

    private void clearCullProxyDraw() {
        if (cullProxyDraw == null) return;
        cullProxyDraw.close();
        cullProxyDraw = null;
    }

    public void prepareCullProxyViews(List<EmitterFrame> output) {
        if (cullProxyTargets.isEmpty()) {
            cullProxyViews = List.of();
            return;
        }
        var views = new ArrayList<CullProxyDrawState>();
        for (var target : cullProxyTargets) {
            if (target.cullProxySource != this || target.handle == null || !target.handle.isAlive()) {
                if (target.cullProxySource == this) target.useCullProxy(null);
                else detachCullProxyTarget(target);
                continue;
            }
            if (niagara == null || !target.hasNiagaraVisibilityProxy() || !target.componentRenderingEnabled
                    || !target.hasViewOutput(target.ownerViewRender)) continue;
            var draw = target.cullProxyDraw;
            if (draw == null || draw.system != niagara) {
                target.clearCullProxyDraw();
                draw = new CullProxyDrawState(niagara, target.niagara.renderIdentity());
                target.cullProxyDraw = draw;
            }
            var primitive = target.componentRenderInput(target.handle.input());
            draw.update(niagara.gpuInput(), primitive, target.previousRenderedPose, target.ownerViewRender);
            output.addAll(draw.cpuFrames);
            views.add(draw);
        }
        cullProxyViews = List.copyOf(views);
    }

    private boolean shouldRenderNiagara() {
        return !sharedCullProxy && cullProxySource == null && componentState != State.INACTIVE && componentRenderingEnabled
                && scalabilityCull != Reaction.DeactivateImmediateResume
                && (!seeking || ageUpdate.renderWhileSeeking());
    }

    public boolean allowsScalability() {
        Boolean override = handle.niagaraAllowScalability();
        return componentState == State.ACTIVE && !handle.niagaraPaused() && scalabilityCull != Reaction.Deactivate
                && (override == null ? componentDefaults.allowScalability() : override);
    }

    public long componentActivation() { return componentActivation; }

    public boolean countsForScalability() {
        return handle.isAlive() && niagara != null && componentState != State.INACTIVE
                && !niagara.finished(handle.stopping() || scalabilityCull == Reaction.Deactivate || scalabilityCull == Reaction.DeactivateResume)
                && scalabilityCull != Reaction.DeactivateImmediateResume;
    }

    public boolean scalabilitySuspended() { return scalabilityCull != null; }

    public float scalabilityAge() { return Objects.requireNonNull(niagara).age(); }
    public NiagaraBounds scalabilityBounds() {
        if (cullProxySource != null) return cullProxySource.niagara.rendererBounds();
        return Objects.requireNonNull(niagara).rendererBounds();
    }

    /** Returns native component bounds in world space, including an inactive auto-attach parent when published. */
    public NiagaraBounds scalabilityWorldBounds(Pose fallbackPose) {
        NiagaraBounds parentBounds = autoAttachment.inactiveParentBounds(handle, componentState == State.INACTIVE);
        return parentBounds != null ? parentBounds : NiagaraComponentBounds.world(fallbackPose, scalabilityBounds());
    }

    public EffectHandle.Pose scalabilityPose() {
        return cullProxySource != null || frameInput == null ? handle.input().pose() : frameInput.pose();
    }

    public boolean hasNiagaraVisibilityProxy() {
        // The shared simulation draws through the target primitive. An immediate cull
        // removes that primitive until the target resumes, unlike a scalability pause.
        if (sharedCullProxy || !handle.isAlive() || componentState == State.INACTIVE
                || scalabilityCull == Reaction.DeactivateImmediateResume) return false;
        if (cullProxySource != null) return cullProxySource.niagara.initialized() && !cullProxySource.niagara.completed();
        return niagara != null && niagara.initialized() && !niagara.completed();
    }

    public boolean hasVisibleNiagaraProxy() {
        return ownerViewRender == ViewRender.ALL && hasNiagaraVisibilityProxy()
                && (cullProxySource == null ? shouldRenderNiagara() : componentRenderingEnabled);
    }
    public boolean canOccludeNiagaraProxy() {
        var override = handle.niagaraOcclusionMode();
        var mode = override == null ? componentDefaults.occlusionMode() : override;
        return switch (mode) {
            case AlwaysEnabled -> true;
            case AlwaysDisabled -> false;
            case Default -> !(cullProxySource != null ? componentRenderInput(handle.input()).customDepthStencil()
                    : frameInput == null ? definition.customDepthStencil() : frameInput.customDepthStencil()).enabled();
        };
    }
    public long visibilityEpoch() { return visibilityEpoch; }
    public void requestStaticMeshLods(MeshView view, Vec3 camera, double time) {
        if (sharedCullProxy || componentState == State.INACTIVE) return;
        if (definition.emitters().stream().noneMatch(renderer -> renderer.renderer() == EmitterDefinition.Renderer.MESH)) return;
        var source = cullProxySource == null ? niagara : cullProxySource.niagara;
        var input = source == null ? (frameInput == null ? handle.input() : frameInput) : source.gpuInput();
        var pose = cullProxySource == null ? input.pose() : handle.input().pose();
        var bounds = NiagaraComponentBounds.world(pose, source == null ? NiagaraBounds.DEFAULT : source.rendererBounds());
        var system = definition.niagara();
        if (system != null && system.initialStreamingBounds() != null)
            bounds = bounds.union(NiagaraComponentBounds.world(pose, system.initialStreamingBounds()));
        Vec3 centre = new Vec3((bounds.minX() + bounds.maxX()) * .005, (bounds.minZ() + bounds.maxZ()) * .005,
                (bounds.minY() + bounds.maxY()) * .005);
        boolean recentlyRendered = niagara == null || niagara.renderedSince(time - .5);
        if (system == null) {
            for (var renderer : definition.emitters())
                if (renderer.renderer() == EmitterDefinition.Renderer.MESH)
                    MeshDrawSelection.requestStreamingLods(definition, renderer, input, view, camera, centre, recentlyRendered);
            return;
        }
        var platform = NiagaraPlatforms.snapshot();
        for (var emitter : system.emitters()) {
            if (emitter.simulation() instanceof NiagaraDefinition.DisabledSimulation || !emitter.scalability().platforms().active(platform)) continue;
            for (var renderer : renderDefinitions(emitter))
                if (renderer.renderer() == EmitterDefinition.Renderer.MESH)
                    MeshDrawSelection.requestStreamingLods(definition, renderer, input, view, camera, centre, recentlyRendered);
        }
    }

    public void markNiagaraRendered(double time) { if (niagara != null) niagara.renderedAt(time); }
    public float timeSinceNiagaraRendered(double time) { return Objects.requireNonNull(niagara).timeSinceRendered(time); }

    public void scalabilityView(float distance, float maximum, int significanceIndex) {
        if (niagara == null) return;
        niagara.scalabilityLod(distance, maximum);
        niagara.significanceIndex(significanceIndex);
    }

    public void scalabilitySignificance(int index) { Objects.requireNonNull(niagara).significanceIndex(index); }

    public void applyScalabilityCull(@Nullable Reaction next) {
        if (niagara == null || scalabilityCull == next || scalabilityCull == Reaction.Deactivate) return;
        var previous = scalabilityCull;
        scalabilityCull = next;
        if (next == Reaction.DeactivateImmediate) {
            if (componentControl == null) handle.invalidate();
            else if (componentState != State.INACTIVE) {
                // Niagara's scalability completion stops and clears the system, but
                // deliberately suppresses OnSystemFinished and pool auto-release.
                visibilityEpoch++;
                closeGpuRenderers();
                niagara.completeForPool();
                setComponentState(State.INACTIVE);
            }
        } else if (next == Reaction.DeactivateImmediateResume) {
            visibilityEpoch++;
            niagara.resetComponentSimulation();
            closeGpuRenderers();
            warmupRemaining = 0;
            warmupPlan = null;
            if (componentControl != null) componentControl.publish(State.INACTIVE);
        } else if (next == null && (previous == Reaction.DeactivateResume || previous == Reaction.DeactivateImmediateResume)) {
            visibilityEpoch++;
            if (previous == Reaction.DeactivateResume) niagara.reactivateKeepingParticles();
            niagara.resetRenderTime();
            initializeWarmup();
            if (componentControl != null) componentControl.publish(componentState);
        }
    }

    private boolean scalabilityPaused() {
        return scalabilityCull == Reaction.PauseResume || scalabilityCull == Reaction.DeactivateImmediateResume;
    }

    public void finishSimulationStep() { finishSimulationStep(Minecraft.getInstance().level, null); }
    public void finishSimulationStep(ClientLevel level) { finishSimulationStep(level, null); }
    public void finishSimulationStep(ClientLevel level, @Nullable NiagaraGpuPrograms programs) {
        if (niagara == null) return;
        niagara.finish(level, programs);
        if (niagara.completed()) closeGpuRenderers();
    }

    public void finishFrameSimulationStep(ClientLevel level, @Nullable NiagaraGpuPrograms programs, int step) {
        finishSimulationStep(level, programs);
        if (!needsAgeRenderPack()) return;
        niagara.advanceAgeMultiTick(tickPlan.systemDelta());
        if (step + 1 < tickPlan.steps() && ageUpdate.maximumSimulationTime() > 0
                && (System.nanoTime() - seekStarted) * 1.0e-9 >= ageUpdate.maximumSimulationTime()) {
            tickStepLimit = step + 1;
            seeking = true;
        }
    }

    public void finishWarmupStep() {
        finishWarmupStep(null);
    }
    public void finishWarmupStep(@Nullable NiagaraGpuPrograms programs) {
        var plan = warmupPlan;
        if (niagara == null || plan == null) throw new IllegalStateException("Effect has no pending Niagara warmup step");
        finishSimulationStep(Minecraft.getInstance().level, programs);
        if (niagara.finished(false)) {
            warmupPlan = null;
            warmupRemaining = 0;
        } else if (++warmupStep == plan.steps()) warmupPlan = null;
        if (warmupPlan == null && warmupRemaining == 0) niagara.resetWarmupAge();
    }

    public List<EmitterFrame> prepareFrame(double now, float delta, Vec3 camera, NiagaraCameraFrame cameraFrame) {
        return prepareFrame(now, now, now, delta, camera, cameraFrame);
    }

    public List<EmitterFrame> prepareFrame(double now, double engineTime, double realTime, float delta, Vec3 camera, NiagaraCameraFrame cameraFrame) {
        packCurrentStep = true;
        captureInput(now);
        if (niagara != null) {
            if (simulationPaused) niagara.prepareRenderOnly(now, frameInput);
            else niagara.prepare(now, engineTime, realTime, niagara.componentDelta(delta), frameInput, camera,
                    frameEmitting, cameraFrame);
        }
        return definition.niagara() != null && componentState == State.INACTIVE ? List.of() : frameEmitters;
    }

    private void captureCollections() {
        if (niagara == null) return;
        var publication = NiagaraPlatforms.publication();
        var interfaces = handle.dataInterfaceParameters();
        var playback = handle.niagaraCache();
        niagara.objectParameterInputs(handle.objectParameters());
        if (!niagara.completed()) {
            if (!niagara.applyPlatformPublication(publication) || interfaces != dataInterfaceParameters)
                recreateNiagara(publication, interfaces, playback);
            else applyCacheSelection(publication, interfaces, playback);
        }
        niagara.propertyInterfaceInputs(handle.niagaraPropertyInterfaceInputs());
        niagara.componentOwnerVisibility(handle);
        niagara.simCacheReaderInputs(handle.niagaraSimCacheReaderInputs());
        niagara.sparseVolumeInputs(handle.niagaraSparseVolumeInputs());
        niagara.materialCollectionInputs(handle.niagaraMaterialCollectionInputs());
        var component = handle.collectionOverrides();
        var materials = MaterialCollectionWorlds.snapshot(level, niagara.materialCollections());
        var world = NiagaraCollectionWorlds.prepareFrame(level, niagara.collectionBindings(), component, materials);
        niagara.collectionSnapshot(new NiagaraCollectionSnapshot(world, component, materials));
        niagara.objectPropertyInputs(handle.objectPropertyInputs(), handle.objectReaderRemaps());
        niagara.actorComponentInputs(handle.actorComponentInputs(), this::localPlayerRoot);
        niagara.socketInputs(handle.niagaraSocketInputs());
        niagara.splineInputs(handle.niagaraSplineInputs());
        niagara.intDistributions(handle.niagaraIntDistributions());
        niagara.movieRenderSample(handle.niagaraMovieRenderSample());
        Integer seedOffset = handle.niagaraRandomSeedOffset();
        niagara.randomSeedOffset(seedOffset == null ? componentDefaults.randomSeedOffset() : seedOffset);
        var collisionWorld = handle.niagaraCollisionWorld();
        niagara.collisionWorld(collisionWorld == null ? defaultCollisionWorld : collisionWorld);
        niagara.staticMeshInputs(handle.niagaraStaticMeshInputs());
        niagara.skeletalMeshInputs(handle.niagaraSkeletalMeshInputs());
        niagara.rigidMeshInputs(handle.niagaraRigidMeshInputs());
        niagara.geometryCollectionInputs(handle.niagaraGeometryCollectionInputs());
        niagara.groomInputs(handle.niagaraGroomInputs());
        niagara.physicsField(NiagaraPhysicsFieldWorlds.snapshot(level));
        var chaosDemand = NiagaraChaosWorlds.demand(level);
        if (chaosDemand != null) niagara.requestChaosEvents(chaosDemand);
        niagara.chaosScene(NiagaraChaosWorlds.snapshot(level));
        niagara.waterSources(NiagaraWaterWorlds.snapshot(level), handle.niagaraWaterSources());
        niagara.sceneCaptureInputs(handle.niagaraSceneCaptureInputs());
        niagara.landscapeInputs(handle.niagaraLandscapeInputs());
        niagara.virtualTextureInputs(handle.niagaraVirtualTextureInputs());
        niagara.particleExportHandlers(handle.niagaraParticleExports());
        niagara.dataChannelWorld(level, handle);
        niagara.recordChannelCache(handle.niagaraCacheCapture());
        niagara.systemFixedBounds(handle.niagaraSystemFixedBounds());
        niagara.audioInputs(handle.niagaraAudioBackend(), handle.niagaraAudioOverrides());
    }

    private void capturePausedCacheSelection() {
        if (niagara == null || niagara.completed() || componentState == State.INACTIVE) return;
        applyCacheSelection(NiagaraPlatforms.publication(), dataInterfaceParameters, handle.niagaraCache());
    }

    private void applyCacheSelection(NiagaraPlatforms.Publication publication, Map<Key, Reference> interfaces,
                                     @Nullable Playback playback) {
        boolean wasPlaying = niagara.playingCache();
        if (!niagara.changeCache(playback)) recreateNiagara(publication, interfaces, playback);
        else if (!wasPlaying && niagara.playingCache()) initializeWarmup();
    }

    private void recreateNiagara(NiagaraPlatforms.Publication publication, Map<Key, Reference> interfaces,
                                 @Nullable Playback playback) {
        closeGpuRenderers();
        niagara.close();
        niagara = null;
        try {
            createNiagara(publication, interfaces, playback);
        } catch (RuntimeException | Error failure) {
            handle.invalidate();
            throw failure;
        }
    }

    private void createNiagara(NiagaraPlatforms.Publication publication, Map<Key, Reference> interfaces,
                               @Nullable Playback playback) {
        niagara = new NiagaraSystem(definition, seed, publication, Objects.requireNonNull(platformSets),
                parameterObjects, interfaces, playback, handle.objectParameters());
        niagara.dynamicMaterialObjects(dynamicMaterialObjects);
        initializeWarmup();
        niagara.virtualTextureAssets(virtualTextures, runtimeVirtualTextures);
        previousRenderedPose = null;
        dataInterfaceParameters = interfaces;
        Objects.requireNonNull(platformSets).retainObjects(niagara.platformObjectIdentities());
        handle.publishArrayResults(niagara.arrayResults(), Map.of());
    }

    private @Nullable Component localPlayerRoot(int index) {
        var player = Minecraft.getInstance().player;
        if (index != 0 || player == null || player.level() != level) return null;
        var position = player.position();
        var motion = player.getDeltaMovement();
        double velocityScale = 100.0 * player.level().tickRateManager().tickrate();
        // Minecraft yaw zero points along +Z. In source axes that direction is +Y.
        double halfYaw = Math.toRadians(player.getYRot() + 90.0) * 0.5;
        var transform = new Transform(position.x * 100, position.z * 100, position.y * 100,
                0, 0, Math.sin(halfYaw), Math.cos(halfYaw), 1, 1, 1);
        return new Component(transform, transform,
                new Velocity(motion.x * velocityScale, motion.z * velocityScale, motion.y * velocityScale), null);
    }

    /** Called by the client thread after every emitter job in this frame has joined. */
    public void publishSimulationInputs() {
        if (niagara != null) {
            niagara.publishEmitterBounds();
            if (componentState != State.INACTIVE) niagara.publishDynamicMaterialBindings(definition, frameInput);
            if (pendingComponentBoundsUpdate) {
                niagara.updateComponentBounds(tickPlan.engineDelta());
                pendingComponentBoundsUpdate = false;
            }
        }
    }

    public void finishFrame() { finishFrame(false); }

    public void finishPreparedFrame() { finishFrame(true); }

    private void finishFrame(boolean hasRenderReaders) {
        pendingHostFinish = false;
        if (niagara != null) {
            if (componentState == State.INACTIVE) return;
            if (hasRenderReaders) niagara.finishForRender(level);
            else {
                niagara.finish();
                if (niagara.completed()) closeGpuRenderers();
            }
            if (pendingComponentBoundsUpdate) {
                niagara.updateComponentBounds(tickPlan.engineDelta());
                pendingComponentBoundsUpdate = false;
            }
            handle.publishArrayResults(niagara.arrayResults(), frameInput.arrayParameters());
            boolean stopping = handle.stopping();
            boolean retain = !stopping && (scalabilityCull == Reaction.DeactivateResume
                    || scalabilityCull == Reaction.DeactivateImmediateResume);
            boolean draining = stopping || componentState == State.DRAINING || !movieSceneWantsEmission
                    || scalabilityCull == Reaction.Deactivate || scalabilityCull == Reaction.DeactivateResume;
            boolean finished = !sharedCullProxy && niagara.finished(draining);
            if (finished && retain && componentControl != null) componentControl.publish(State.INACTIVE);
            if (finished && !retain) {
                if (componentControl == null) {
                    autoAttachment.complete(handle, autoAttachmentEnabled(), componentDefaults.autoDestroy());
                    handle.invalidate();
                }
                else if (!stopping && scalabilityCull == Reaction.Deactivate) {
                    closeGpuRenderers();
                    niagara.completeForPool();
                    setComponentState(State.INACTIVE);
                }
                else {
                    componentRetirePending = !niagara.completed();
                    completeComponent(false);
                }
            }
            return;
        }
        if (definition.niagara() != null) return;
        for (EmitterFrame emitter : frameEmitters) emitter.publishUnityEvents(level);
        for (EmitterFrame emitter : frameEmitters) if (!emitter.unityFinished()) return;
        handle.invalidate();
    }

    public void retirePreparedFrame() {
        if (niagara != null && niagara.completed()) {
            try { niagara.retireRenderedState(); }
            finally { closeGpuRenderers(); }
        }
    }

    public void finishHostSimulationFrame() {
        pendingHostFinish = pendingGpuSteps() != 0;
        pendingComponentBoundsUpdate = niagara != null && componentState != State.INACTIVE;
        if (!pendingHostFinish) {
            finishFrame();
            captureLiveCache();
        }
    }

    public boolean pendingHostFinish() { return pendingHostFinish; }

    public int pendingGpuSteps() { return niagara == null ? 0 : niagara.pendingGpuSteps(); }

    public static void flushGpuWithoutScene(List<EffectInstance> instances, CommandEncoder encoder, NiagaraGpuPrograms programs,
                                            Function<Identifier, PreparedNiagaraShaders> shaderLookup, NiagaraGpuView cachedView,
                                            Runnable beginFrame) {
        List<EffectInstance> active = new ArrayList<>();
        List<NiagaraGpuBatchDispatcher> dispatchers = new ArrayList<>();
        for (var instance : instances) {
            var system = instance.niagara;
            if (system == null || !instance.handle.isAlive() || !instance.pendingHostFinish || system.pendingGpuSteps() == 0) continue;
            active.add(instance);
            dispatchers.add(system.gpuDispatcher());
        }
        NiagaraGpuBatchDispatcher.flushWithoutScene(dispatchers, encoder, programs, shaderLookup, cachedView, beginFrame);
        for (var instance : active) if (instance.pendingHostFinish) {
            instance.finishFrame();
            instance.captureLiveCache();
        }
    }

    public void finishRenderFrame(boolean holdHistory) {
        if (!holdHistory) previousRenderedPose = cullProxySource == null ? frameInput.pose() : handle.input().pose();
        if (niagara != null) niagara.finishRenderFrame(holdHistory);
        captureLiveCache();
        if (niagara != null) niagara.finishCacheRenderOpportunity();
    }
    private void captureLiveCache() {
        var capture = handle.niagaraCacheCapture();
        if (niagara != null) niagara.captureLiveCache(capture, level);
        else if (capture != null && (!(componentControl != null && handle.isAlive() && !handle.stopping()) || capture.finishing()))
            capture.result().completeExceptionally(new IllegalStateException("Effect has no Niagara simulation"));
    }

    private void captureCurrentCache() {
        var capture = handle.niagaraCacheCapture();
        if (niagara != null && capture != null && capture.advanceDelta() == 0 && niagara.hasPendingCurrentCapture(capture))
            niagara.captureLiveCache(capture, level);
    }
    public void collectPointLights(List<ScenePointLight> output) {
        if (niagara != null) niagara.collectPointLights(definition, output);
        else for (var emitter : frameEmitters) output.addAll(emitter.unitySceneLights);
    }
    public void collectDirectionalLights(List<SceneDirectionalLight> output) {
        if (niagara != null) niagara.collectDirectionalLights(definition, output);
    }
    public void collectDecals(List<ParticleDecal> output) {
        if (niagara != null) niagara.collectDecals(definition, output);
    }
    public void collectComponentMeshes(@Nullable UUID viewActor, List<StaticMeshComponentDraw> output) {
        if (niagara != null) niagara.collectComponentMeshes(definition, viewActor, output);
    }

    public final class EmitterFrame {
        private final @Nullable UnityRendererBinding unityRenderer;
        private final List<EmitterDefinition> secondary;
        private final @Nullable UnitySubEmitterGroup<EmitterInstance> unity;
        private final List<UnityParticleTriggerEvent> triggerEvents = new ArrayList<>();
        private final List<UnityParticleCollisionEvent> collisionEvents = new ArrayList<>();
        private final List<UnityParticleCollisionEvent.ForceImpact> colliderForces = new ArrayList<>();
        private final List<ScenePointLight> unitySceneLights = new ArrayList<>();
        private int firstUnityLight;
        private final int nativeIndex;
        private final @Nullable CullProxyDrawState proxyDraw;
        private EmitterFrame(EmitterInstance emitter, @Nullable UnityRendererBinding unityRenderer,
                             @Nullable UnityRendererBinding trailRenderer,
                             List<EmitterDefinition> secondary, EmitterDefinition unityDefinition, long seed) {
            this.unityRenderer = unityRenderer;
            this.secondary = List.copyOf(secondary);
            unity = new UnitySubEmitterGroup<>(emitter, unityRenderer == null ? null : unityRenderer.transform(), seed,
                    (childSeed, start, capacity) -> new EmitterInstance(unityDefinition, childSeed, trailRenderer,
                            unityRenderer, start, capacity));
            nativeIndex = -1;
            proxyDraw = null;
        }
        private EmitterFrame(int nativeIndex) { this(nativeIndex, null); }
        private EmitterFrame(int nativeIndex, @Nullable CullProxyDrawState proxyDraw) {
            this.nativeIndex = nativeIndex;
            this.proxyDraw = proxyDraw;
            unityRenderer = null;
            unity = null;
            secondary = List.of();
        }
        public int group() {
            if (unity != null) return unity.group();
            return proxyDraw != null || niagara == null ? 0 : niagara.emitterGroup(nativeIndex);
        }
        public int estimatedWorkload() {
            if (niagara != null) return niagara.emitterEstimatedWorkload(nativeIndex);
            return 32;
        }
        public boolean requiresOwnerThread() {
            return niagara == null ? Objects.requireNonNull(unity).usesWorldCollision()
                    : proxyDraw == null && niagara.emitterUsesSynchronousCollision(nativeIndex);
        }
        public void updateSimulation() {
            if (niagara == null) throw new IllegalStateException("Warmup requires a Niagara system");
            if (niagara.isGpuEmitter(nativeIndex)) niagara.prepareGpuEmitter(nativeIndex);
            else niagara.updateEmitter(nativeIndex, false);
        }
        public void updateForStep(EffectMeshBuilder builder) {
            if (proxyDraw != null || packCurrentStep) updateAndPack(builder);
            else updateSimulation();
        }
        public void updateAndPack(EffectMeshBuilder builder) {
            if (proxyDraw != null) {
                var renderer = Objects.requireNonNull(proxyDraw.cpu[nativeIndex]);
                for (var snapshot : niagara.packCullProxyEmitter(nativeIndex, renderer, proxyDraw.input,
                        proxyDraw.primitive, proxyDraw.previous)) packNiagara(snapshot, builder, proxyDraw.viewRender);
            } else if (niagara == null) {
                updateUnity(builder);
            }
            else if (!niagara.hasEmitterRendererState(nativeIndex)) return;
            else if (niagara.isGpuEmitter(nativeIndex)) niagara.prepareGpuEmitter(nativeIndex);
            else
                for (var snapshot : niagara.updateEmitter(nativeIndex, shouldRenderNiagara() && hasViewOutput(ownerViewRender)))
                    packNiagara(snapshot, builder, ownerViewRender);
        }

        private void packNiagara(EmitterInstance.Snapshot snapshot, EffectMeshBuilder builder, ViewRender viewRender) {
            if (!drawsForView(snapshot.definition(), snapshot.input(), viewRender)) return;
            boolean onlyShadow = viewRender == ViewRender.SHADOW_ONLY
                    && snapshot.definition().renderer() != EmitterDefinition.Renderer.DECAL;
            builder.add(definition, onlyShadow ? snapshot.withRenderInput(shadowOnly(snapshot.input())) : snapshot);
        }

        private void updateUnity(EffectMeshBuilder builder) {
            triggerEvents.clear();
            collisionEvents.clear();
            colliderForces.clear();
            unitySceneLights.clear();
            firstUnityLight = builder.lights().size();
            var frame = new UnitySubEmitterGroup.Frame(frameTime, frameInput.pose(), frameEmitting, handle.stopping(),
                    frameAttachmentSampled, frameUnityPhysicsVelocity, level, frameUnityCollisionLayers,
                    frameUnityEmissionGeometry, frameUnityTriggerColliders, frameUnityManualRequests, frameUnityForceFields);
            var rootPose = frameInput.pose();
            Objects.requireNonNull(unity).simulate(frame, (simulation, step) ->
                    packUnity(builder, simulation, simulation.snapshot(step, frameInput.withPose(step.pose()),
                            rootPose, unitySortInstanceId)));
        }

        private void packUnity(EffectMeshBuilder builder, EmitterInstance simulation, EmitterInstance.Snapshot snapshot) {
            triggerEvents.addAll(simulation.triggerEvents());
            simulation.appendCollisionEvents(collisionEvents);
            int forcesStart = colliderForces.size();
            simulation.appendColliderForces(colliderForces);
            if (colliderForces.size() > forcesStart) {
                var id = handle.effectId();
                for (int i = forcesStart; i < colliderForces.size(); i++) {
                    colliderForces.set(i, colliderForces.get(i).withEffect(id, seed));
                }
            }
            simulation.appendLights(definition, snapshot, builder.lights(), firstUnityLight, unitySceneLights);
            if (snapshot.definition().renderer() != EmitterDefinition.Renderer.SIMULATION)
                builder.add(definition, snapshot.withUnityRenderer(snapshot.definition(), unityRenderer,
                        simulation.trailVertices(snapshot, unityRenderer)));
            for (var renderer : secondary) {
                var binding = definition.unityRenderers().get(renderer.name());
                builder.add(definition, snapshot.withUnityRenderer(renderer, binding, simulation.trailVertices(snapshot, binding)));
            }
        }

        private boolean unityFinished() {
            return Objects.requireNonNull(unity).finished(frameTime, handle.stopping());
        }

        private void publishUnityEvents(ClientLevel level) {
            if (triggerEvents.isEmpty() && collisionEvents.isEmpty() && colliderForces.isEmpty()) return;
            try {
                if (!triggerEvents.isEmpty()) handle.publishUnityTriggerEvents(triggerEvents);
                if (!collisionEvents.isEmpty()) handle.publishUnityCollisionEvents(collisionEvents);
                if (!colliderForces.isEmpty()) {
                    handle.publishUnityColliderForces(colliderForces);
                    com.kltyton.lumenfx.client.VfxEngine.INSTANCE.dispatchUnityColliderForces(level, colliderForces);
                }
            } finally {
                triggerEvents.clear();
                collisionEvents.clear();
                colliderForces.clear();
            }
        }
    }

    private static boolean hasPerParticleTrail(@Nullable UnityRendererBinding binding) {
        return binding != null && binding.particleTrail() != null
                && binding.particleTrail().mode() == UnityParticleTrail.Mode.PER_PARTICLE;
    }

    public EffectDefinition definition() { return definition; }
    public EffectHandle handle() { return handle; }
    public boolean needsCameraInterface() { return niagara != null && componentState != State.INACTIVE && niagara.needsCamera(); }
    public boolean hasGpuEmitters() {
        if (niagara == null || niagara.completed() || componentState == State.INACTIVE) return false;
        for (int i = 0; i < gpuEmitters.length; i++) if (niagara.isGpuEmitter(i)) return true;
        return false;
    }
    public int sceneReads(NiagaraGpuPrograms programs, Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        return hasGpuEmitters() && !niagara.playingCache() ? niagara.gpuDispatcher().sceneReads(programs, shaderLookup) : 0;
    }
    public enum GpuRenderPhase { ALL, BEFORE_POST_OPAQUE, AFTER_POST_OPAQUE }

    private final Map<Integer, RenderLease> previousGpuRenderData = new HashMap<>();
    private final Set<NiagaraGpuMeshDraw> postOpaqueMeshDraws = new HashSet<>();

    private final class CullProxyDrawState implements AutoCloseable {
        final NiagaraSystem system;
        final long primitive;
        final NiagaraEmitterRenderState[] cpu;
        final List<EmitterFrame> cpuFrames;
        final GpuEmitterRuntime[] gpu;
        EffectHandle.Input input;
        Pose previous;

        CullProxyDrawState(NiagaraSystem system, long primitive) {
            this.system = system;
            this.primitive = primitive;
            cpu = new NiagaraEmitterRenderState[system.emitterCount()];
            gpu = new GpuEmitterRuntime[cpu.length];
            var frames = new ArrayList<EmitterFrame>();
            var sources = Objects.requireNonNull(definition.niagara()).emitters();
            for (int i = 0; i < cpu.length; i++) {
                if (system.isGpuEmitter(i)) continue;
                cpu[i] = system.createCullProxyCpuRenderer(i, renderDefinitions(sources.get(i)));
                if (cpu[i] != null && cpu[i].hasVisuals()) frames.add(new EmitterFrame(i, this));
            }
            cpuFrames = List.copyOf(frames);
        }

        ViewRender viewRender = ViewRender.ALL;

        void update(EffectHandle.Input source, EffectHandle.Input target, @Nullable Pose previous, ViewRender viewRender) {
            this.viewRender = viewRender;
            input = new EffectHandle.Input(target.pose(), source.beamEnd(), source.sequence(), target.renderTracked(),
                    target.red(), target.green(), target.blue(), target.alpha(), source.parameters(), source.arrayParameters(),
                    target.actorPosition(), target.actorPositionExplicit(), source.rendererParameters(), target.receivesDecals(),
                    source.textureParameters(), target.firstPerson(), target.primitiveLighting(), target.customDepthStencil(),
                    target.customPrimitiveData(), null, target.niagaraTranslucencySort(),
                    target.renderInMainPass());
            this.previous = previous == null ? input.pose() : previous;
        }

        @Override public void close() {
            for (var renderer : cpu) if (renderer != null) renderer.close();
            for (var renderer : gpu) if (renderer != null) renderer.close();
        }
    }

    public boolean requiresPostOpaque(NiagaraGpuPrograms programs, Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        return gpuTickStage(programs, shaderLookup) == NiagaraGpuBatchDispatcher.TickStage.POST_OPAQUE;
    }

    public NiagaraGpuBatchDispatcher.@Nullable TickStage gpuTickStage(NiagaraGpuPrograms programs,
            Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        return hasGpuEmitters() && !niagara.playingCache() && pendingGpuSteps() != 0
                ? niagara.gpuDispatcher().tickStage(programs, shaderLookup) : null;
    }

    public void retainPreviousGpuRenderData(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                            Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        if (!previousGpuRenderData.isEmpty()) throw new IllegalStateException("Niagara view already retains renderer data");
        niagara.restoreLiveGpuCache(encoder, programs, shaderLookup);
        var dispatcher = niagara.gpuDispatcher();
        for (int order = 0; order < niagara.gpuEmitterCount(); order++) {
            int emitter = niagara.gpuEmitterIndex(order);
            if (!niagara.isStatelessGpuEmitter(emitter) && !niagara.gpuEmitterComplete(emitter))
                previousGpuRenderData.put(emitter, dispatcher.particles(emitter, programs, shaderLookup).retainRenderData());
        }
    }

    public void releasePreviousGpuRenderData() {
        Throwable failure = null;
        for (var lease : previousGpuRenderData.values()) {
            try { lease.close(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        previousGpuRenderData.clear();
        postOpaqueMeshDraws.clear();
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
    private final Map<Integer, NiagaraGpuParticleView> analyticCacheViews = new HashMap<>();
    private @Nullable NiagaraCacheCapture analyticCapture;

    public void finishGpuFrame(CommandEncoder encoder) {
        try {
            if (niagara != null) {
                niagara.finishGpuFrame(encoder);
                var capture = handle.niagaraCacheCapture();
                if (capture != null && capture == analyticCapture)
                    niagara.captureLiveCache(capture, level, encoder, analyticCacheViews);
            }
        } finally {
            analyticCapture = null;
            analyticCacheViews.clear();
            gpuFrameDispatched = false;
        }
    }

    public void abandonPreparedGpuFrame() {
        try {
            if (niagara == null || niagara.completed()) return;
            if (gpuFrameDispatched) {
                finishGpuFrame(RenderSystem.getDevice().createCommandEncoder());
                if (pendingHostFinish) finishPreparedFrame();
            } else if (niagara.pendingGpuSteps() != 0) {
                niagara.deferUnsubmittedGpuPrefix();
            }
        } finally {
            analyticCapture = null;
            analyticCacheViews.clear();
            gpuFrameDispatched = false;
        }
    }

    public void dispatchPreparedGpu(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                    Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                    NiagaraGpuView view, @Nullable GpuTextureView depth) {
        if (gpuFrameDispatched || niagara == null || !niagara.initialized() || niagara.completed()) return;
        if (niagara.playingCache()) niagara.uploadCachedGpuInterfaces(encoder, programs);
        else niagara.gpuDispatcher().dispatch(encoder, programs, shaderLookup, view, depth);
        gpuFrameDispatched = true;
    }

    public static void dispatchPreparedGpu(List<EffectInstance> instances, CommandEncoder encoder, NiagaraGpuPrograms programs,
                                           Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                           NiagaraGpuView view, @Nullable GpuTextureView depth) {
        var submissions = new ArrayList<NiagaraGpuBatchDispatcher.Submission>();
        for (var instance : instances) {
            var system = instance.niagara;
            if (instance.gpuFrameDispatched || system == null || !system.initialized() || system.completed()) continue;
            if (system.playingCache()) {
                system.uploadCachedGpuInterfaces(encoder, programs);
                instance.gpuFrameDispatched = true;
            } else {
                submissions.add(new NiagaraGpuBatchDispatcher.Submission(system.gpuDispatcher(),
                        () -> instance.gpuFrameDispatched = true));
            }
        }
        NiagaraGpuBatchDispatcher.dispatchBatch(submissions, encoder, programs, shaderLookup, view, depth);
    }
    public void prepareRenderTargetResources() { if (niagara != null) niagara.prepareRenderTargetResources(); }
    public void prepareRenderTargetResources(@Nullable NiagaraGpuPrograms programs) { if (niagara != null) niagara.prepareRenderTargetResources(programs); }
    public NiagaraGpuDebugLines.@Nullable Frame debugLines() { return niagara == null ? null : niagara.debugLines(); }

    public List<NiagaraGaussianSplatDraw> gaussianSplatDraws() {
        return gaussianSplatDraws(null);
    }

    public List<NiagaraGaussianSplatDraw> gaussianSplatDraws(@Nullable ComputeRuntime runtime) {
        if (niagara == null || frameInput == null) return List.of();
        boolean ownSplatsVisible = shouldRenderNiagara() && ownerViewRender == ViewRender.ALL;
        var writerDraws = ownSplatsVisible ? niagara.gaussianSplatDraws(runtime) : List.<NiagaraGaussianSplatDraw>of();
        List<NiagaraGaussianSplatDraw> draws = null;
        if (ownSplatsVisible) {
            draws = addGpuSplatDraws(draws, gpuEmitters);
        }
        for (var proxy : cullProxyViews) {
            if (proxy.viewRender == ViewRender.ALL) draws = addGpuSplatDraws(draws, proxy.gpu);
        }
        if (draws == null || draws.isEmpty()) return writerDraws;
        if (writerDraws.isEmpty()) return List.copyOf(draws);
        var combined = new ArrayList<NiagaraGaussianSplatDraw>(writerDraws.size() + draws.size());
        combined.addAll(writerDraws);
        combined.addAll(draws);
        return List.copyOf(combined);
    }

    private List<NiagaraGaussianSplatDraw> addGpuSplatDraws(@Nullable List<NiagaraGaussianSplatDraw> draws,
                                                            GpuEmitterRuntime[] runtimes) {
        if (niagara == null || niagara.completed()) return draws;
        for (int i = 0; i < runtimes.length; i++) {
            var runtime = runtimes[i];
            if (runtime == null || niagara.gpuEmitterComplete(i)) continue;
            for (var splat : runtime.splats) {
                if (splat == null) continue;
                var draw = splat.currentDraw();
                if (draw == null) continue;
                if (draws == null) draws = new ArrayList<>();
                draws.add(draw);
            }
        }
        return draws;
    }
    public NiagaraCpuDebugLines.@Nullable Snapshot cpuDebugLines() { return niagara == null ? null : niagara.cpuDebugLines(); }

    public boolean hasGpuRibbons() {
        if (niagara == null) return false;
        for (int i = 0; i < gpuEmitters.length; i++) {
            if (!niagara.isGpuEmitter(i)) continue;
            for (var renderer : niagara.gpuEmitter(i).renderers())
                if (definition.emitters().stream().anyMatch(value -> value.name().equals(renderer.name())
                        && value.renderer() == EmitterDefinition.Renderer.RIBBON)) return true;
        }
        return false;
    }
    public double gpuSortDepth(Vec3 camera) {
        return gpuSortDepth(camera, null);
    }

    public double gpuSortDepth(Vec3 camera, @Nullable MeshView view) {
        if (niagara == null) throw new IllegalStateException("Effect has no Niagara system");
        return niagara.gpuSortDepth(camera, view);
    }

    /** Prepares every analytic renderer request before the shared kernel batch and any particle sorting. */
    public void prepareAnalyticRenderers(CommandEncoder encoder, NiagaraGpuPrograms programs) {
        if (niagara == null || !niagara.hasRendererState() || niagara.completed() || niagara.playingCache()) return;
        analyticCacheViews.clear();
        analyticCapture = handle.niagaraCacheCapture();
        for (int order = 0; order < niagara.gpuEmitterCount(); order++) {
            int index = niagara.gpuEmitterIndex(order);
            if (!niagara.isStatelessGpuEmitter(index)) continue;
            niagara.prepareStatelessRenderState(index);
            if (niagara.gpuEmitterComplete(index)) continue;
            GpuEmitterRuntime runtime = gpuEmitters[index];
            if (runtime == null) {
                runtime = new GpuEmitterRuntime(Identifier.fromNamespaceAndPath("lumenfx", "niagara_stateless"),
                        programs, niagara.gpuEmitter(index));
                gpuEmitters[index] = runtime;
            } else if (runtime.programs != programs) throw new IllegalStateException("Stale analytic GPU renderer resources");
            for (int renderer = 0; renderer < runtime.renderers.size(); renderer++) {
                var kind = runtime.renderers.get(renderer).renderer();
                runtime.analyticParticles[renderer] = kind == EmitterDefinition.Renderer.SPRITE
                        || kind == EmitterDefinition.Renderer.MESH || kind == EmitterDefinition.Renderer.RIBBON
                        || kind == EmitterDefinition.Renderer.SPLAT
                        ? niagara.prepareStatelessGpuParticles(index, encoder, programs) : null;
            }
            if (analyticCapture != null && niagara.capturesAnalyticGpu(analyticCapture, index)) {
                var particles = niagara.prepareStatelessGpuParticles(index, encoder, programs);
                if (particles != null) analyticCacheViews.put(index, particles);
            }
            runtime.analyticFrame = programs.statelessData().frame();
        }
    }

    /** Runs prepared GPU emitters and appends their renderer work for the current view. */
    public void appendGpuRenderers(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                 Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                 Vec3 camera, Quaternionf orientation, double depth, boolean defaultHighPrecision,
                                 boolean stereo, boolean postOpaque, float lodDistanceFactor,
                                 NiagaraGpuView view, RenderTarget scene,
                                 List<NiagaraGpuSpriteCollector.Task> output, List<NiagaraGpuRibbonFrame> ribbonOutput) {
        appendGpuRenderers(encoder, programs, shaderLookup, camera, orientation, depth, defaultHighPrecision, stereo,
                postOpaque, lodDistanceFactor, view, scene, output, ribbonOutput, null, null);
    }

    public void appendGpuRenderers(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                 Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                 Vec3 camera, Quaternionf orientation, double depth, boolean defaultHighPrecision,
                                 boolean stereo, boolean postOpaque, float lodDistanceFactor,
                                 NiagaraGpuView view, RenderTarget scene,
                                 List<NiagaraGpuSpriteCollector.Task> output, List<NiagaraGpuRibbonFrame> ribbonOutput,
                                 @Nullable MeshView meshView,
                                 @Nullable List<NiagaraGpuMeshCollector.Task> meshOutput) {
        appendGpuRenderers(encoder, programs, shaderLookup, camera, orientation, depth, defaultHighPrecision, stereo,
                postOpaque, lodDistanceFactor, view, scene, output, ribbonOutput, meshView, meshOutput, null);
    }

    public void appendGpuRenderers(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                 Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                 Vec3 camera, Quaternionf orientation, double depth, boolean defaultHighPrecision,
                                 boolean stereo, boolean postOpaque, float lodDistanceFactor,
                                 NiagaraGpuView view, RenderTarget scene,
                                 List<NiagaraGpuSpriteCollector.Task> output, List<NiagaraGpuRibbonFrame> ribbonOutput,
                                 @Nullable MeshView meshView,
                                 @Nullable List<NiagaraGpuMeshCollector.Task> meshOutput,
                                 @Nullable List<ParticleLight> lightOutput) {
        appendGpuRenderers(encoder, programs, shaderLookup, camera, orientation, depth, defaultHighPrecision, stereo,
                postOpaque, lodDistanceFactor, view, scene, output, ribbonOutput, meshView, meshOutput, lightOutput, null);
    }

    public void appendGpuRenderers(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                 Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                 Vec3 camera, Quaternionf orientation, double depth, boolean defaultHighPrecision,
                                 boolean stereo, boolean postOpaque, float lodDistanceFactor,
                                 NiagaraGpuView view, RenderTarget scene,
                                 List<NiagaraGpuSpriteCollector.Task> output, List<NiagaraGpuRibbonFrame> ribbonOutput,
                                 @Nullable MeshView meshView,
                                 @Nullable List<NiagaraGpuMeshCollector.Task> meshOutput,
                                 @Nullable List<ParticleLight> lightOutput,
                                 @Nullable List<ParticleDecal> decalOutput) {
        appendGpuRenderers(encoder, programs, shaderLookup, camera, orientation, depth, defaultHighPrecision, stereo, postOpaque,
                lodDistanceFactor, view, scene, output, ribbonOutput, meshView, meshOutput, lightOutput, decalOutput, null);
    }

    public void appendGpuRenderers(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                 Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                 Vec3 camera, Quaternionf orientation, double depth, boolean defaultHighPrecision,
                                 boolean stereo, boolean postOpaque, float lodDistanceFactor,
                                 NiagaraGpuView view, RenderTarget scene,
                                 List<NiagaraGpuSpriteCollector.Task> output, List<NiagaraGpuRibbonFrame> ribbonOutput,
                                 @Nullable MeshView meshView, @Nullable List<NiagaraGpuMeshCollector.Task> meshOutput,
                                 @Nullable List<ParticleLight> lightOutput, @Nullable List<ParticleDecal> decalOutput,
                                 @Nullable List<NiagaraVolumeInstance> volumeOutput) {
        appendGpuRenderers(encoder, programs, shaderLookup, camera, orientation, depth, defaultHighPrecision, stereo, postOpaque,
                lodDistanceFactor, view, scene, output, ribbonOutput, meshView, meshOutput, lightOutput, decalOutput, volumeOutput, null);
    }

    public void appendGpuRenderers(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                 Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                 Vec3 camera, Quaternionf orientation, double depth, boolean defaultHighPrecision,
                                 boolean stereo, boolean postOpaque, float lodDistanceFactor,
                                 NiagaraGpuView view, RenderTarget scene,
                                 List<NiagaraGpuSpriteCollector.Task> output, List<NiagaraGpuRibbonFrame> ribbonOutput,
                                 @Nullable MeshView meshView, @Nullable List<NiagaraGpuMeshCollector.Task> meshOutput,
                                 @Nullable List<ParticleLight> lightOutput, @Nullable List<ParticleDecal> decalOutput,
                                 @Nullable List<NiagaraVolumeInstance> volumeOutput, @Nullable List<GpuSource> shadowOutput) {
        appendGpuRenderers(encoder, programs, shaderLookup, camera, orientation, depth, defaultHighPrecision, stereo,
                postOpaque, lodDistanceFactor, view, scene, output, ribbonOutput, meshView, meshOutput,
                lightOutput, decalOutput, volumeOutput, shadowOutput, GpuRenderPhase.ALL, false);
    }

    public void appendGpuRenderers(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                 Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                 Vec3 camera, Quaternionf orientation, double depth, boolean defaultHighPrecision,
                                 boolean stereo, boolean postOpaque, float lodDistanceFactor,
                                 NiagaraGpuView view, RenderTarget scene,
                                 List<NiagaraGpuSpriteCollector.Task> output, List<NiagaraGpuRibbonFrame> ribbonOutput,
                                 @Nullable MeshView meshView, @Nullable List<NiagaraGpuMeshCollector.Task> meshOutput,
                                 @Nullable List<ParticleLight> lightOutput, @Nullable List<ParticleDecal> decalOutput,
                                 @Nullable List<NiagaraVolumeInstance> volumeOutput, @Nullable List<GpuSource> shadowOutput,
                                 GpuRenderPhase phase, boolean viewSupportsLowLatency) {
        appendGpuRenderers(encoder, programs, shaderLookup, camera, orientation, depth, defaultHighPrecision, stereo,
                postOpaque, lodDistanceFactor, view, scene, output, ribbonOutput, meshView, meshOutput, lightOutput,
                decalOutput, volumeOutput, shadowOutput, phase, viewSupportsLowLatency, null);
    }

    public void appendGpuRenderers(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                 Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                 Vec3 camera, Quaternionf orientation, double depth, boolean defaultHighPrecision,
                                 boolean stereo, boolean postOpaque, float lodDistanceFactor,
                                 NiagaraGpuView view, RenderTarget scene,
                                 List<NiagaraGpuSpriteCollector.Task> output, List<NiagaraGpuRibbonFrame> ribbonOutput,
                                 @Nullable MeshView meshView, @Nullable List<NiagaraGpuMeshCollector.Task> meshOutput,
                                 @Nullable List<ParticleLight> lightOutput, @Nullable List<ParticleDecal> decalOutput,
                                 @Nullable List<NiagaraVolumeInstance> volumeOutput, @Nullable List<GpuSource> shadowOutput,
                                 GpuRenderPhase phase, boolean viewSupportsLowLatency,
                                 @Nullable List<NaniteInstanceCollector.GpuInput> naniteOutput) {
        if (niagara == null || !niagara.hasRendererState() || niagara.completed()) return;
        boolean cached = niagara.playingCache();
        boolean afterOpaque = phase == GpuRenderPhase.AFTER_POST_OPAQUE;
        for (int order = 0; !afterOpaque && !cached && order < niagara.gpuEmitterCount(); order++) {
            int index = niagara.gpuEmitterIndex(order);
            if (!niagara.isStatelessGpuEmitter(index)) continue;
            niagara.prepareStatelessRenderState(index);
            if (niagara.gpuEmitterComplete(index)) continue;
            GpuEmitterRuntime runtime = gpuEmitters[index];
            if (runtime == null || runtime.programs != programs || runtime.analyticFrame != programs.statelessData().frame())
                throw new IllegalStateException("Analytic renderer requests were not prepared for this frame");
        }
        var dispatcher = cached ? null : niagara.gpuDispatcher();
        if (phase != GpuRenderPhase.BEFORE_POST_OPAQUE)
            dispatchPreparedGpu(encoder, programs, shaderLookup, view, scene.getDepthTextureView());
        boolean renderOriginal = shouldRenderNiagara() && hasViewOutput(ownerViewRender);
        var proxyViews = cullProxyViews;
        if (!renderOriginal && proxyViews.isEmpty()) return;
        boolean highSortPrecision = defaultHighPrecision || niagara.platformEnvironment().defaultHighSortPrecision();
        var drawView = new NiagaraGpuDrawView(camera, orientation, highSortPrecision, stereo, postOpaque,
                lodDistanceFactor, meshView, phase, viewSupportsLowLatency);
        var queues = new NiagaraGpuDrawQueues(output, ribbonOutput, meshOutput, lightOutput, decalOutput, volumeOutput, shadowOutput, naniteOutput);
        var sourceOrigin = new Vec3(niagara.gpuOriginX(), niagara.gpuOriginY(), niagara.gpuOriginZ());
        for (int order = 0; order < niagara.gpuEmitterCount(); order++) {
            int index = niagara.gpuEmitterIndex(order);
            if (!niagara.hasEmitterRendererState(index) || niagara.gpuEmitterComplete(index)) continue;
            var emitter = niagara.gpuEmitter(index);
            boolean analytic = !cached && niagara.isStatelessGpuEmitter(index);
            if (afterOpaque && analytic) continue;
            NiagaraGpuParticleView ordinaryParticles = cached ? niagara.cachedGpuParticles(index, encoder, programs)
                    : analytic ? null : phase == GpuRenderPhase.BEFORE_POST_OPAQUE ? previousGpuRenderData.get(index)
                    : Objects.requireNonNull(dispatcher).particles(index, programs, shaderLookup);
            var particleOrigin = analytic || cached ? sourceOrigin : niagara.gpuRenderOrigin(index);
            var analyticParticles = analytic ? Objects.requireNonNull(gpuEmitters[index]).analyticParticles : null;
            if (renderOriginal) {
                var runtime = gpuRenderer(index, programs, shaderLookup, emitter, gpuEmitters, false);
                appendGpuEmitterDraw(encoder, programs, index, emitter, runtime, ordinaryParticles, analyticParticles,
                        sourceOrigin, particleOrigin, niagara.gpuInput(), niagara.previousRendererPose(),
                        niagara.renderIdentity(), depth, drawView, queues, ownerViewRender);
            }
            if (!proxyViews.isEmpty()) {
                var drawEmitter = emitter.inLocalSpace();
                for (var proxy : proxyViews) {
                    var runtime = gpuRenderer(index, programs, shaderLookup, drawEmitter, proxy.gpu, true);
                    double proxyDepth = MeshDrawSelection.depth(proxy.input.pose(), niagara.rendererBounds(), camera, meshView);
                    appendGpuEmitterDraw(encoder, programs, index, drawEmitter, runtime, ordinaryParticles, analyticParticles,
                            sourceOrigin, Vec3.ZERO, proxy.input, proxy.previous, proxy.primitive, proxyDepth, drawView, queues,
                            proxy.viewRender);
                }
            }
        }
    }

    private record NiagaraGpuDrawView(Vec3 camera, Quaternionf orientation, boolean defaultHighPrecision,
                                      boolean stereo, boolean postOpaque, float lodDistanceFactor,
                                      @Nullable MeshView meshView, GpuRenderPhase phase, boolean lowLatency) { }

    private record NiagaraGpuDrawQueues(List<NiagaraGpuSpriteCollector.Task> sprites, List<NiagaraGpuRibbonFrame> ribbons,
            @Nullable List<NiagaraGpuMeshCollector.Task> meshes, @Nullable List<ParticleLight> lights,
            @Nullable List<ParticleDecal> decals, @Nullable List<NiagaraVolumeInstance> volumes, @Nullable List<GpuSource> shadows,
            @Nullable List<NaniteInstanceCollector.GpuInput> nanite) { }

    private GpuEmitterRuntime gpuRenderer(int index, NiagaraGpuPrograms programs,
                                         Function<Identifier, PreparedNiagaraShaders> shaderLookup, NiagaraDefinition.Emitter emitter,
                                         GpuEmitterRuntime[] renderers, boolean cullProxy) {
        var runtime = renderers[index];
        if (runtime != null) {
            if (runtime.programs != programs) throw new IllegalStateException("Stale Niagara GPU renderer resources");
            return runtime;
        }
        var gpu = emitter.simulation() instanceof NiagaraDefinition.GpuSimulation source ? source : null;
        var id = gpu != null ? NiagaraGpuBatchDispatcher.shaderId(gpu)
                : Identifier.fromNamespaceAndPath("lumenfx", "niagara_stateless");
        var shaders = gpu == null ? null : shaderLookup.apply(id);
        boolean partialDepth = gpu != null && shaders != null
                && NiagaraGpuBatchDispatcher.readsPartialDepth(shaders, gpu.metadata());
        runtime = new GpuEmitterRuntime(id, programs, emitter, cullProxy, partialDepth);
        renderers[index] = runtime;
        return runtime;
    }

    private void appendGpuEmitterDraw(CommandEncoder encoder, NiagaraGpuPrograms programs, int index,
            NiagaraDefinition.Emitter emitter, GpuEmitterRuntime runtime, @Nullable NiagaraGpuParticleView ordinaryParticles,
            NiagaraGpuParticleView @Nullable [] analyticParticles, Vec3 sourceOrigin, Vec3 particleOrigin,
            EffectHandle.Input input, Pose previousPose, long primitive, double depth, NiagaraGpuDrawView view,
            NiagaraGpuDrawQueues queues, ViewRender viewRender) {
        var phase = view.phase();
        var geometryInput = viewRender == ViewRender.SHADOW_ONLY ? shadowOnly(input) : input;
        boolean afterOpaque = phase == GpuRenderPhase.AFTER_POST_OPAQUE;
        boolean analytic = analyticParticles != null;
        var camera = view.camera();
        var orientation = view.orientation();
        var meshView = view.meshView();
        var meshOutput = queues.meshes();
        var lightOutput = queues.lights();
        var decalOutput = queues.decals();
        var volumeOutput = queues.volumes();
        var shadowOutput = queues.shadows();
        boolean viewSupportsLowLatency = view.lowLatency();
        boolean defaultHighPrecision = view.defaultHighPrecision();
        boolean stereo = view.stereo();
        boolean postOpaque = view.postOpaque();
        float lodDistanceFactor = view.lodDistanceFactor();
        boolean componentVelocity = niagara.rendererVelocity(input.pose(), previousPose);
        NiagaraBounds componentBounds = niagara.rendererBounds();
        var platform = NiagaraPlatforms.snapshot();
        for (int rendererIndex = 0; rendererIndex < emitter.renderers().size(); rendererIndex++) {
            if (!afterOpaque && runtime.splats[rendererIndex] != null) runtime.splats[rendererIndex].clearFrame();
            var renderer = emitter.renderers().get(rendererIndex);
            EmitterDefinition renderDefinition = runtime.renderers.get(rendererIndex);
            if (runtime.cullProxy && !renderer.allowInCullProxies()) continue;
            if (renderDefinition.renderer() == EmitterDefinition.Renderer.COMPONENT || !platform.renders(renderDefinition.renderer())) continue;
            if (!supportsGpuRenderSource(renderDefinition.renderer(), renderer)) continue;
            if (!drawsForView(renderDefinition, input, viewRender)) continue;
            var rendererInput = renderDefinition.renderer() == EmitterDefinition.Renderer.DECAL ? input : geometryInput;
            if (afterOpaque && renderDefinition.renderer() != EmitterDefinition.Renderer.SPRITE
                    && renderDefinition.renderer() != EmitterDefinition.Renderer.MESH
                    && renderDefinition.renderer() != EmitterDefinition.Renderer.SPLAT) continue;
            boolean rendererLowLatency = !analytic && phase != GpuRenderPhase.ALL
                    && viewSupportsLowLatency && usesCurrentTranslucentData(renderer);
            if (afterOpaque && !rendererLowLatency) continue;
            if (renderDefinition.renderer().usesMaterial()) runtime.materials[rendererIndex].beginFrame(
                    niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput,
                    sourceOrigin.x, sourceOrigin.y, sourceOrigin.z, niagara.materialEngineValues(index));
            var rendererStore = niagara.rendererStore(index);
            if (renderDefinition.renderer() == EmitterDefinition.Renderer.VOLUME) {
                if (!renderer.sourceMode().equals("Emitter")) continue;
                if (volumeOutput == null) throw new IllegalArgumentException("Niagara volumes require their output queue");
                if (NiagaraGpuSpriteDraw.hostVisible(renderer, niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput,
                        rendererStore, emitter.rendererBindings()))
                    runtime.volumes[rendererIndex].append(volumeOutput, definition, renderDefinition,
                            new Inputs(niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput, rendererStore),
                            null, niagara.rendererBounds(), sourceOrigin, runtime.materials[rendererIndex]::material);
                continue;
            }
            if (renderDefinition.renderer() == EmitterDefinition.Renderer.LIGHT) {
                if (lightOutput == null) throw new IllegalArgumentException("Light rendering requires its output queue");
                if (NiagaraGpuSpriteDraw.hostVisible(renderer, niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput,
                        rendererStore, emitter.rendererBindings())) {
                    var settings = Objects.requireNonNull(renderDefinition.lightSettings());
                    runtime.lights[rendererIndex].append(settings, null, niagara.gpuSystemData(), niagara.gpuVariables(),
                            rendererInput, rendererStore, primitive, sourceOrigin.x, sourceOrigin.y, sourceOrigin.z,
                            settings.defaultInverseExposureBlend(), lightOutput);
                }
                continue;
            }
            if (renderDefinition.renderer() == EmitterDefinition.Renderer.DECAL) {
                if (decalOutput == null) throw new IllegalArgumentException("Decal rendering requires its output queue");
                if (NiagaraGpuSpriteDraw.hostVisible(renderer, niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput,
                        rendererStore, emitter.rendererBindings())) {
                    var settings = Objects.requireNonNull(renderDefinition.decalSettings());
                    var material = DecalMaterialSelection.resolve(settings, definition,
                            definition.materials().get(renderDefinition.material()), rendererInput);
                    runtime.decals[rendererIndex].append(settings, runtime.materials[rendererIndex].material(material), null,
                            new Inputs(
                                    niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput, rendererStore),
                            primitive, sourceOrigin, niagara.lastUpdateEngineTime(), decalOutput);
                }
                continue;
            }
            NiagaraGpuParticleView particles = analytic
                    ? analyticParticles[rendererIndex] : ordinaryParticles;
            if (particles == null) continue;
            if (renderDefinition.renderer() == EmitterDefinition.Renderer.SPLAT) {
                if (afterOpaque == rendererLowLatency
                        && NiagaraGpuSpriteDraw.hostVisible(renderer, niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput,
                                rendererStore, emitter.rendererBindings())) {
                    // The GPU component box is component-local. A world-space splat
                    // draw culls that box in the published particle buffer's simulation tile.
                    var splatBounds = emitter.localSpace() ? componentBounds
                            : NiagaraComponentBounds.relative(rendererInput.pose(), componentBounds, particleOrigin);
                    runtime.splats[rendererIndex].buildGpu(encoder, Objects.requireNonNull(renderDefinition.splatSettings()),
                            particles, niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput, rendererStore,
                            particleOrigin.x, particleOrigin.y, particleOrigin.z, splatBounds);
                }
                continue;
            }
            boolean castsShadow = shadowOutput != null && ParticleShadowGeometry.casts(renderDefinition, rendererInput);
            if (renderDefinition.renderer() == EmitterDefinition.Renderer.RIBBON) {
                if (!NiagaraGpuSpriteDraw.hostVisible(renderer, niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput,
                        rendererStore, emitter.rendererBindings()))
                    continue;
                var material = runtime.materials[rendererIndex].material(definition.materials().get(renderDefinition.material()),
                        renderer.surfaceMaterialBinding());
                double originX = particleOrigin.x, originY = particleOrigin.y, originZ = particleOrigin.z, time = frameTime;
                float viewDistance = NiagaraComponentDefaults.RENDERER_LOD_DISTANCE_OVERRIDE;
                var frame = runtime.ribbons[rendererIndex].prepare(encoder, particles, emitter, renderer, renderDefinition, material,
                        rendererInput, camera, originX, originY, originZ, time, depth,
                        viewDistance, lodDistanceFactor, stereo, previousPose, componentVelocity, componentBounds,
                        niagara.ribbonSettings(index, Objects.requireNonNull(renderDefinition.ribbonSettings())));
                if (frame != null) queues.ribbons().add(frame);
                if (frame != null && castsShadow && ParticleShadowGeometry.casts(material)) {
                    var shadow = runtime.ribbons[rendererIndex].pointShadow(frame, stereo);
                    shadowOutput.add((commands, shadowView, draws) ->
                            draws.ribbons().add(shadow.prepare(commands, shadowView.ribbonLodDistanceFactor())));
                }
                continue;
            }
            if (renderDefinition.renderer() == EmitterDefinition.Renderer.MESH) {
                if (meshView == null || meshOutput == null)
                    throw new IllegalArgumentException("Mesh rendering requires its output queue and captured projection view");
                var draw = runtime.meshes[rendererIndex];
                if (afterOpaque && !postOpaqueMeshDraws.contains(draw)) continue;
                var task = new NiagaraGpuMeshCollector.RendererTask(
                                draw, particles, emitter, renderer, definition, renderDefinition,
                                niagara.gpuSystemData(), niagara.gpuVariables(),
                                niagara.meshDrawInput(index, renderDefinition, rendererInput), rendererStore,
                                camera, orientation,
                                particleOrigin.x, particleOrigin.y, particleOrigin.z, frameTime, depth,
                                defaultHighPrecision, stereo, postOpaque, meshView, niagara::rendererBounds, previousPose,
                                runtime.materials[rendererIndex]::material, name -> niagara.dynamicMeshRenderSource(encoder, programs, name));
                if (phase == GpuRenderPhase.BEFORE_POST_OPAQUE && rendererLowLatency
                        && NiagaraGpuMeshCollector.usesOnlyMaterials(task, material -> canDrawLate(material, rendererInput))) {
                    postOpaqueMeshDraws.add(draw);
                    continue;
                }
                if (queues.nanite() != null && renderDefinition.meshSettings().nanite() != null)
                    queues.nanite().add(new NaniteInstanceCollector.GpuInput(task,
                            niagara.naniteInstanceFloats(index, renderDefinition, true), niagara.naniteRendererValues(index)));
                NiagaraGpuMeshCollector.append(meshOutput, task);
                if (castsShadow) {
                    var shadow = runtime.shadowMesh(rendererIndex);
                    shadowOutput.add((commands, shadowView, draws) -> NiagaraGpuMeshCollector.append(draws.meshes(),
                            task.forPointShadow(shadow, shadowView)));
                }
                continue;
            }
            if (renderDefinition.renderer() != EmitterDefinition.Renderer.SPRITE)
                throw new IllegalArgumentException("Niagara GPU renderer requires its matching GPU renderer: " + renderDefinition.renderer());
            var material = runtime.materials[rendererIndex].material(definition.materials().get(renderDefinition.material()), renderer.surfaceMaterialBinding());
            if (afterOpaque != (rendererLowLatency && canDrawLate(material, rendererInput))) continue;
            var task = new NiagaraGpuSpriteCollector.Task(runtime.draws[rendererIndex], particles, emitter, renderer,
                    renderDefinition, material, niagara.gpuSystemData(), niagara.gpuVariables(), rendererInput, rendererStore,
                    camera, orientation, particleOrigin.x, particleOrigin.y, particleOrigin.z, frameTime, depth,
                    defaultHighPrecision, stereo, postOpaque, previousPose, componentVelocity, componentBounds);
            queues.sprites().add(task);
            if (castsShadow && ParticleShadowGeometry.casts(task.material())) {
                var shadow = runtime.shadowSprite(rendererIndex);
                shadowOutput.add((commands, shadowView, draws) -> draws.sprites().add(task.forPointShadow(shadow, shadowView)));
            }
        }
    }

    private boolean usesCurrentTranslucentData(NiagaraDefinition.Renderer renderer) {
        return switch (renderer.gpuTranslucentLatency()) {
            case Immediate -> true;
            case Latent -> false;
            case ProjectDefault -> Objects.requireNonNull(niagara.platformEnvironment()
                    .console("NiagaraSettings.DefaultGpuTranslucentLatency")).bits() == 0;
        };
    }

    private static boolean canDrawLate(MaterialDefinition material, EffectHandle.Input input) {
        return material.blend().translucent() && material.decalSettings() == null && !material.lightFunction()
                && material.pointShadowCoverage() == MaterialDefinition.PointShadowCoverage.NONE
                && (!input.customDepthStencil().enabled() || !material.writesCustomDepth());
    }

    private static boolean supportsGpuRenderSource(EmitterDefinition.Renderer kind, NiagaraDefinition.Renderer source) {
        return switch (kind) {
            case LIGHT, DECAL -> source.sourceMode().equals("Emitter");
            default -> true;
        };
    }

    private final class GpuEmitterRuntime implements AutoCloseable {
        final NiagaraGpuPrograms programs;
        final boolean cullProxy;
        final List<EmitterDefinition> renderers;
        final NiagaraGpuSpriteDraw[] draws;
        final NiagaraGpuRibbonDraw[] ribbons;
        final NiagaraGpuMeshDraw[] meshes;
        final NiagaraGpuSplatBuilder[] splats;
        final NiagaraGpuSpriteDraw[] shadowSprites;
        final NiagaraGpuMeshDraw[] shadowMeshes;
        final Identifier id;
        final NiagaraLightParticles[] lights;
        final NiagaraDecalParticles[] decals;
        final NiagaraVolumeInstances[] volumes;
        final NiagaraMaterialInstances[] materials;
        final NiagaraGpuParticleView[] analyticParticles;
        long analyticFrame;
        GpuEmitterRuntime(Identifier id, NiagaraGpuPrograms programs, NiagaraDefinition.Emitter emitter) {
            this(id, programs, emitter, false, false);
        }

        GpuEmitterRuntime(Identifier id, NiagaraGpuPrograms programs, NiagaraDefinition.Emitter emitter,
                          boolean cullProxy, boolean partialDepth) {
            this.id = id;
            this.programs = programs;
            this.cullProxy = cullProxy;
            var definitions = renderDefinitions(emitter);
            renderers = cullProxy ? definitions.stream().map(EmitterDefinition::inLocalSpace).toList() : definitions;
            draws = new NiagaraGpuSpriteDraw[renderers.size()];
            ribbons = new NiagaraGpuRibbonDraw[renderers.size()];
            meshes = new NiagaraGpuMeshDraw[renderers.size()];
            splats = new NiagaraGpuSplatBuilder[renderers.size()];
            shadowSprites = new NiagaraGpuSpriteDraw[renderers.size()];
            shadowMeshes = new NiagaraGpuMeshDraw[renderers.size()];
            lights = new NiagaraLightParticles[renderers.size()];
            decals = new NiagaraDecalParticles[renderers.size()];
            volumes = new NiagaraVolumeInstances[renderers.size()];
            materials = new NiagaraMaterialInstances[renderers.size()];
            analyticParticles = new NiagaraGpuParticleView[renderers.size()];
            try {
                for (int i = 0; i < draws.length; i++) {
                    EmitterDefinition value = renderers.get(i);
                    var source = emitter.renderers().get(i);
                    if (!supportsGpuRenderSource(value.renderer(), source)) continue;
                    if (value.renderer().usesMaterial()) materials[i] = new NiagaraMaterialInstances(null, niagara.materialTextures());
                    if (value.renderer() == EmitterDefinition.Renderer.SPRITE)
                        draws[i] = new NiagaraGpuSpriteDraw(id + "." + i, programs, partialDepth);
                    else if (value.renderer() == EmitterDefinition.Renderer.RIBBON)
                        ribbons[i] = new NiagaraGpuRibbonDraw(id + "." + i, programs,
                                Objects.requireNonNull(value.ribbonSettings()).shape(), partialDepth);
                    else if (value.renderer() == EmitterDefinition.Renderer.MESH)
                        meshes[i] = new NiagaraGpuMeshDraw(id + "." + i, programs, partialDepth);
                    else if (value.renderer() == EmitterDefinition.Renderer.VOLUME)
                        volumes[i] = new NiagaraVolumeInstances(emitter, source);
                    else if (value.renderer() == EmitterDefinition.Renderer.LIGHT)
                        lights[i] = new NiagaraLightParticles(emitter, source);
                    else if (value.renderer() == EmitterDefinition.Renderer.DECAL)
                        decals[i] = new NiagaraDecalParticles(emitter, source);
                    else if (value.renderer() == EmitterDefinition.Renderer.SPLAT)
                        splats[i] = new NiagaraGpuSplatBuilder(id + "." + i, programs, emitter, source);
                }
            } catch (RuntimeException | Error error) {
                for (var splat : splats) if (splat != null) splat.close();
                for (var draw : draws) if (draw != null) draw.close();
                for (var ribbon : ribbons) if (ribbon != null) ribbon.close();
                for (var mesh : meshes) if (mesh != null) mesh.close();
                throw error;
            }
        }

        NiagaraGpuSpriteDraw shadowSprite(int index) {
            if (shadowSprites[index] == null) shadowSprites[index] = new NiagaraGpuSpriteDraw(id + ".Shadow." + index, programs);
            return shadowSprites[index];
        }

        NiagaraGpuMeshDraw shadowMesh(int index) {
            if (shadowMeshes[index] == null) shadowMeshes[index] = new NiagaraGpuMeshDraw(id + ".Shadow." + index, programs);
            return shadowMeshes[index];
        }

        @Override public void close() {
            for (var mesh : shadowMeshes) if (mesh != null) mesh.close();
            for (var draw : shadowSprites) if (draw != null) draw.close();
            for (var splat : splats) if (splat != null) splat.close();
            for (var mesh : meshes) if (mesh != null) mesh.close();
            for (var ribbon : ribbons) if (ribbon != null) ribbon.close();
            for (var draw : draws) if (draw != null) draw.close();
        }
    }

    private void closeGpuRenderers() {
        for (int i = 0; i < gpuEmitters.length; i++) {
            if (gpuEmitters[i] != null) { gpuEmitters[i].close(); gpuEmitters[i] = null; }
        }
    }

    public void pooledLease(boolean value) { pooledLease = value; }

    public boolean poolReturnRequested() { return poolReturnRequested; }

    public void parkForPool() {
        if (!poolReturnRequested || handle.isAlive() || componentState != State.INACTIVE)
            throw new IllegalStateException("Niagara component is still leased");
        close();
        handle = null;
        componentControl = null;
        dataInterfaceParameters = Map.of();
        frameInput = null;
        preAnimatedComponent = null;
        preAnimatedCacheAgeMode = null;
        previousRenderedPose = null;
        scalabilityCull = null;
        warmupPlan = null;
        analyticCapture = null;
        analyticCacheViews.clear();
        visibilityEpoch++;
    }

    public void parkPrimedComponent() {
        if (handle == null || componentState != State.INACTIVE || componentActivation != 0)
            throw new IllegalStateException("Only an unused Niagara component can enter the primed pool");
        if (niagara == null) createNiagara(NiagaraPlatforms.publication(), handle.dataInterfaceParameters(), handle.niagaraCache());
        niagara.completeForPool();
        handle.invalidate();
        handle = null;
        componentControl = null;
        poolReturnRequested = true;
    }

    public void reusePooledComponent(EffectHandle next, long newSeed, double worldTime) {
        if (handle != null || !poolReturnRequested)
            throw new IllegalStateException("Niagara component is already leased");
        boolean seedChangesLayout = seed != newSeed && Objects.requireNonNull(definition.niagara()).emitters().stream()
                .anyMatch(emitter -> emitter.simulation() instanceof NiagaraDefinition.StatelessSimulation);
        handle = next;
        componentControl = Objects.requireNonNull(next.niagaraComponent());
        autoAttachment.reset(next.inputWithoutNiagaraAttachment().pose());
        componentState = State.INACTIVE;
        poolReturnRequested = false;
        manualReleasePending = false;
        componentRetirePending = false;
        componentActivation = 0;
        pooledLease = true;
        movieSceneWantsEmission = true;
        pendingHostFinish = false;
        pendingComponentBoundsUpdate = false;
        if (niagara != null) niagara.resetComponentBounds();
        frameTime = worldTime;
        frameEmitting = false;
        simulationPaused = false;
        componentRenderingEnabled = componentDefaults.renderingEnabled();
        attachmentSamples.reset();
        tickPlan = new NiagaraTickClock.Plan(1, 0, 0, false);
        ageClock.reset();
        ageStep = null;
        ageUpdate = NiagaraAgeUpdate.DEFAULT;
        seekStarted = 0;
        tickStepLimit = 1;
        seeking = false;
        packCurrentStep = true;
        selectedHostPhase = Phase.Render;
        selectedTickGroup = ComponentTickGroup.TG_LastDemotable;
        gpuFrameDispatched = false;
        seed = newSeed;
        pooledRenderTime = worldTime;
        try {
            var publication = NiagaraPlatforms.publication();
            var playback = next.niagaraCache();
            pooledNeedsRecreation = niagara != null && (seedChangesLayout || playback != null || !niagara.applyPlatformPublication(publication));
            if (niagara != null) {
                initializeWarmup();
                next.publishArrayResults(niagara.arrayResults(), Map.of());
            }
            if (niagara != null) niagara.renderedAt(worldTime);
        } catch (RuntimeException | Error failure) {
            next.invalidate();
            throw failure;
        }
    }

    @Override public void close() {
        try (var objects = parameterObjects; var simulation = niagara) {
            if (handle != null) retireFrameResources();
        } finally { niagara = null; }
    }

    private void retireFrameResources() {
        Throwable failure = null;
        for (Runnable cleanup : List.<Runnable>of(this::releasePreviousGpuRenderData, () -> {
            var capture = handle.niagaraCacheCapture();
            if (capture != null) {
                capture.finish();
                captureLiveCache();
            }
        }, () -> autoAttachment.close(handle, autoAttachmentEnabled(), componentDefaults.autoDestroy()), () -> {
            if (sharedCullProxy) cullProxyTargets(List.of());
        }, () -> useCullProxy(null), this::closeGpuRenderers)) {
            try { cleanup.run(); }
            catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
