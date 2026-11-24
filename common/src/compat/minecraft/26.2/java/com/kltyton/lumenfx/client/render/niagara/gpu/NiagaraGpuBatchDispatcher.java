package com.kltyton.lumenfx.client.render.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraShaders;
import com.kltyton.lumenfx.client.render.compute.ShaderBinding;
import com.kltyton.lumenfx.client.render.niagara.debug.NiagaraGpuDebugLines;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraSystem;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition.Provider;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** Consumes a system's queued stateful GPU simulation steps without creating renderer resources. */
public final class NiagaraGpuBatchDispatcher {
    public static final int READ_SCENE_COLOR = 1;
    public static final int READ_SCENE_VELOCITY = 2;
    public static final int READ_GLOBAL_DISTANCE_FIELD = 4;
    public static final int READ_CAPTURED_GBUFFER = 8;
    public static final int READ_PARTIAL_DEPTH = 16;
    public static final int READ_CUSTOM_DEPTH = 64;
    public static final int READ_SCENE_DEPTH = 128;
    public static final int POST_OPAQUE_SCENE = 256;
    public static final int READ_SCREEN_SPACE_AO = 512;
    public static final int READ_ATMOSPHERE_TRANSMITTANCE = 1 << 12;
    public static final int VIEWLESS_STEP_LIMIT = 10;
    public enum TickStage { PRE_INIT_VIEWS, POST_INIT_VIEWS, POST_OPAQUE }

    private record ViewRequirements(int textures, List<NiagaraGpuAsyncTraceBindings.Source> asyncTraces,
                                    boolean postOpaque, boolean earlyView, boolean staticMesh, boolean viewUniform,
                                    boolean defaultCurrentDataChannel) {
        int sceneInputs(NiagaraPlatformEnvironment platform) {
            boolean early = earlyView || staticMesh && platform.staticMeshDistanceFields();
            // Before a component exists, its user override can select either provider.
            boolean mayTrace = !asyncTraces.isEmpty()
                    && (platform.globalDistanceFieldTraceSupported() || platform.hardwareRayTraceSupported());
            return textures | (postOpaque || defaultCurrentDataChannel || mayTrace || needsOpaqueTextures()
                    || viewUniform && !early ? POST_OPAQUE_SCENE : 0);
        }

        boolean needsOpaqueTextures() { return (textures & ~READ_ATMOSPHERE_TRANSMITTANCE) != 0; }
    }

    private static final class Binding {
        final NiagaraGpuPrograms programs;
        final PreparedNiagaraShaders shaders;
        final boolean unsupportedViewlessSceneInput;
        final ViewRequirements viewRequirements;
        final int debugDrawOverride;
        @Nullable NiagaraGpuInterfaces interfaces;
        int lastOutputTick;

        Binding(NiagaraGpuPrograms programs, PreparedNiagaraShaders shaders, JsonObject metadata) {
            this.programs = programs;
            this.shaders = shaders;
            var descriptors = shaders.stages().values().stream().flatMap(variants -> variants.values().stream())
                    .flatMap(stage -> stage.shader().bindings().stream()).toList();
            int drawOverride = -1;
            for (var element : metadata.getAsJsonArray("interfaces")) {
                var binding = element.getAsJsonObject();
                String type = binding.get("class").getAsString();
                if (type.equals("NiagaraDataInterfaceDebugDraw")) {
                    var sourceProperties = binding.get("properties");
                    JsonObject properties = sourceProperties == null || !sourceProperties.isJsonObject()
                            ? null : sourceProperties.getAsJsonObject();
                    long override = properties == null || !properties.has("OverrideMaxLineInstances") ? 0
                            : properties.get("OverrideMaxLineInstances").getAsLong();
                    drawOverride = Math.max(drawOverride, (int)Math.clamp(override, 0, Integer.MAX_VALUE));
                }
            }
            unsupportedViewlessSceneInput = descriptors.stream()
                    .anyMatch(resource -> resource.name().startsWith("SceneTextures")
                            && !(resource instanceof ShaderBinding.SampledTexture sampled
                            && !sampled.array() && !sampled.volume() && !sampled.cube()
                            && (NiagaraGpuInterfaces.sceneDepthBinding(sampled) && sampled.format().hasDepthAspect()
                                || NiagaraSceneInputs.Texture.shaderTexture(sampled.name()) != null)));
            viewRequirements = viewRequirements(shaders, metadata);
            debugDrawOverride = descriptors.stream().anyMatch(resource -> resource.name().equals(NiagaraGpuDebugLines.ARGUMENTS))
                    ? Math.max(0, drawOverride) : -1;
        }
    }

    private final NiagaraSystem system;
    private final Binding[] bindings;
    private @Nullable NiagaraGpuAsyncTraces asyncTraces;
    private @Nullable NiagaraGpuAsyncTraces detachedAsyncTraces;
    private @Nullable NiagaraGpuDebugLines debugLines;

    public NiagaraGpuBatchDispatcher(NiagaraSystem system) {
        this.system = system;
        bindings = new Binding[system.emitterCount()];
    }

    public void dispatch(CommandEncoder encoder, NiagaraGpuPrograms programs,
                         Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                         NiagaraGpuView view, @Nullable GpuTextureView sceneDepth) {
        dispatchPrefix(encoder, programs, shaderLookup, view, sceneDepth, false, true, system.pendingGpuSteps());
    }

    public void dispatchManual(CommandEncoder encoder, NiagaraGpuPrograms programs,
                               Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                               NiagaraGpuView view, @Nullable GpuTextureView sceneDepth) {
        dispatchPrefix(encoder, programs, shaderLookup, view, sceneDepth, false, false, system.pendingGpuSteps());
    }

    public static void flushWithoutScene(List<NiagaraGpuBatchDispatcher> dispatchers, CommandEncoder encoder,
                                         NiagaraGpuPrograms programs, Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                         NiagaraGpuView cachedView, Runnable beginFrame) {
        while (true) {
            var prefixes = new ArrayList<Prefix>(dispatchers.size());
            for (var dispatcher : dispatchers) {
                var system = dispatcher.system;
                int steps = Math.min(system.pendingGpuSteps(), VIEWLESS_STEP_LIMIT);
                if (steps > 0) prefixes.add(dispatcher.new Prefix(steps, () -> system.finishGpuPrefix(encoder, steps)));
            }
            if (prefixes.isEmpty()) return;
            beginFrame.run();
            dispatchPrefixes(prefixes, encoder, programs, shaderLookup, cachedView, null, true, false, false);
        }
    }

    public boolean supportsViewlessDispatch(int index, NiagaraGpuPrograms programs,
                                            Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        var gpu = (NiagaraDefinition.GpuSimulation) system.gpuEmitter(index).simulation();
        return !binding(index, programs, shaderLookup, gpu).unsupportedViewlessSceneInput;
    }

    public int sceneReads(NiagaraGpuPrograms programs, Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        int reads = 0;
        var platform = system.platformEnvironment();
        for (int order = 0; order < system.gpuEmitterCount(); order++) {
            int index = system.gpuEmitterIndex(order);
            if (system.isStatelessGpuEmitter(index) || system.lastGpuSimulationTick(index) < 0) continue;
            var gpu = (NiagaraDefinition.GpuSimulation) system.gpuEmitter(index).simulation();
            var view = binding(index, programs, shaderLookup, gpu).viewRequirements;
            reads |= view.textures();
            if (platform.globalDistanceFieldTraceSupported() && requestsAsyncProvider(view, index, platform, Provider.GLOBAL_DISTANCE_FIELD))
                reads |= READ_GLOBAL_DISTANCE_FIELD;
        }
        return reads;
    }

    public static boolean readsPartialDepth(PreparedNiagaraShaders shaders, JsonObject metadata) {
        return (viewRequirements(shaders, metadata).textures() & READ_PARTIAL_DEPTH) != 0;
    }

    public TickStage tickStage(NiagaraGpuPrograms programs, Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        if (system.requiresCurrentGpuDataChannel()) return TickStage.POST_OPAQUE;
        var platform = system.platformEnvironment();
        boolean scene = false, earlyView = false, viewUniform = false;
        for (int order = 0; order < system.gpuEmitterCount(); order++) {
            int index = system.gpuEmitterIndex(order);
            if (system.isStatelessGpuEmitter(index) || system.lastGpuSimulationTick(index) < 0) continue;
            var gpu = (NiagaraDefinition.GpuSimulation) system.gpuEmitter(index).simulation();
            var view = binding(index, programs, shaderLookup, gpu).viewRequirements;
            scene |= view.postOpaque() || view.needsOpaqueTextures()
                    || platform.globalDistanceFieldTraceSupported() && requestsAsyncProvider(view, index, platform, Provider.GLOBAL_DISTANCE_FIELD)
                    || platform.hardwareRayTraceSupported() && requestsAsyncProvider(view, index, platform, Provider.HARDWARE_RAY_TRACING);
            earlyView |= view.earlyView() || view.staticMesh() && platform.staticMeshDistanceFields();
            viewUniform |= view.viewUniform();
        }
        if (scene) return TickStage.POST_OPAQUE;
        if (earlyView) return TickStage.POST_INIT_VIEWS;
        return viewUniform ? TickStage.POST_OPAQUE : TickStage.PRE_INIT_VIEWS;
    }

    private boolean requestsAsyncProvider(ViewRequirements view, int emitter, NiagaraPlatformEnvironment platform, Provider type) {
        if (view.asyncTraces().isEmpty()) return false;
        var settings = platform.asyncTraceSettings();
        for (int step = 0; step < system.pendingGpuTicks(emitter); step++) {
            if (!system.gpuTickExecutes(emitter, step)) continue;
            for (var source : view.asyncTraces()) {
                var active = system.gpuAsyncTraceDefinition(emitter, step, source.key(), source.original(), source.definition());
                if (active != null && settings.requests(active.provider(), type)) return true;
            }
        }
        return false;
    }

    public static int opaqueInputs(PreparedNiagaraShaders shaders, JsonObject metadata, NiagaraPlatformEnvironment platform) {
        return viewRequirements(shaders, metadata).sceneInputs(platform);
    }

    private static ViewRequirements viewRequirements(PreparedNiagaraShaders shaders, JsonObject metadata) {
        var asyncTraces = new ArrayList<NiagaraGpuAsyncTraceBindings.Source>();
        boolean postOpaque = false;
        boolean earlyView = false, staticMesh = false, defaultCurrentDataChannel = false;
        for (var element : metadata.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            switch (binding.get("class").getAsString()) {
                case "NiagaraDataInterfaceGBuffer", "NiagaraDataInterfaceCollisionQuery", "NiagaraDataInterfaceOcclusion" ->
                        postOpaque = true;
                case "NiagaraDataInterfaceCamera", "NiagaraDataInterfaceRigidMeshCollisionQuery" -> earlyView = true;
                case "NiagaraDataInterfaceStaticMesh" -> staticMesh = true;
                case "NiagaraDataInterfaceAsyncGpuTrace" -> {
                    if (binding.has("shader_symbol")) asyncTraces.add(NiagaraGpuAsyncTraceBindings.Source.read(binding));
                }
                case "NiagaraDataInterfaceStaticMeshIndirect" -> {
                    if (binding.get("properties") instanceof JsonObject properties
                            && properties.get("CompiledData") instanceof JsonObject compiled
                            && compiled.has("bRequiresDistanceFields"))
                        earlyView |= compiled.get("bRequiresDistanceFields").getAsBoolean();
                }
                case "NiagaraDataInterfaceDataChannelRead" -> {
                    var channel = binding.getAsJsonObject("data_channel");
                    defaultCurrentDataChannel |= channel != null && channel.has("read_current") && channel.get("read_current").getAsBoolean();
                }
                default -> { }
            }
        }
        int textures = 0;
        boolean viewUniform = false;
        var velocities = velocityAliases(metadata);
        for (var stage : shaders.stages().values()) for (var variant : stage.values()) {
            viewUniform |= variant.viewUniform();
            for (var descriptor : variant.shader().bindings()) textures |= opaqueInputs(descriptor, velocities);
        }
        return new ViewRequirements(textures, List.copyOf(asyncTraces), postOpaque, earlyView, staticMesh, viewUniform,
                defaultCurrentDataChannel);
    }

    private static List<String> velocityAliases(JsonObject metadata) {
        var names = new ArrayList<String>();
        for (var element : metadata.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            if ("NiagaraDataInterfaceGBuffer".equals(binding.get("class").getAsString()) && binding.has("shader_symbol"))
                names.add(binding.get("shader_symbol").getAsString() + "_VelocityTexture");
        }
        return names;
    }

    private static int opaqueInputs(ShaderBinding descriptor, List<String> velocities) {
        if (!(descriptor instanceof ShaderBinding.SampledTexture sampled)) return 0;
        if (NiagaraGpuInterfaces.atmosphereTransmittanceBinding(sampled)) return READ_ATMOSPHERE_TRANSMITTANCE;
        if (NiagaraGpuInterfaces.sceneDepthBinding(sampled)) return READ_SCENE_DEPTH;
        if (sampled.volume() && sampled.name().startsWith("GlobalDistanceField")) return READ_GLOBAL_DISTANCE_FIELD;
        var field = NiagaraSceneInputs.Texture.shaderTexture(sampled.name());
        if (field == null) {
            for (String name : velocities)
                if (sampled.name().equals(name) || sampled.name().startsWith(name + "_")) return READ_SCENE_VELOCITY;
            return 0;
        }
        return switch (field) {
            case SCENE_COLOR -> READ_SCENE_COLOR;
            case GBUFFER_A, GBUFFER_B, GBUFFER_C, GBUFFER_D, GBUFFER_F,
                    PARTICLE_DIFFUSE, PARTICLE_F0, PARTICLE_SOURCE_MATERIAL -> READ_CAPTURED_GBUFFER;
            case PARTIAL_DEPTH -> READ_PARTIAL_DEPTH;
            case GBUFFER_VELOCITY -> READ_SCENE_VELOCITY;
            case CUSTOM_DEPTH, CUSTOM_STENCIL -> READ_CUSTOM_DEPTH;
            case SCREEN_SPACE_AO -> READ_SCREEN_SPACE_AO;
            case GBUFFER_E, GBUFFER_SGGX -> POST_OPAQUE_SCENE;
        };
    }

    public record Submission(NiagaraGpuBatchDispatcher dispatcher, Runnable submitted) { }

    public static void dispatchBatch(List<Submission> submissions, CommandEncoder encoder, NiagaraGpuPrograms programs,
                                     Function<Identifier, PreparedNiagaraShaders> shaderLookup, NiagaraGpuView view,
                                     @Nullable GpuTextureView sceneDepth) {
        if (submissions.isEmpty()) return;
        var prefixes = submissions.stream().map(submission -> submission.dispatcher().new Prefix(
                submission.dispatcher().system.pendingGpuSteps(), submission.submitted())).toList();
        dispatchPrefixes(prefixes, encoder, programs, shaderLookup, view, sceneDepth, false, true,
                view.renderFrameSequence() != Long.MIN_VALUE);
    }

    private void dispatchPrefix(CommandEncoder encoder, NiagaraGpuPrograms programs,
                                Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                                NiagaraGpuView view, @Nullable GpuTextureView sceneDepth, boolean outsideScene,
                                boolean renderedFrame, int tickCount) {
        dispatchPrefixes(List.of(new Prefix(tickCount, () -> { })), encoder, programs, shaderLookup, view, sceneDepth,
                outsideScene, renderedFrame, false);
    }

    private static void dispatchPrefixes(List<Prefix> prefixes, CommandEncoder encoder, NiagaraGpuPrograms programs,
                                         Function<Identifier, PreparedNiagaraShaders> shaderLookup, NiagaraGpuView view,
                                         @Nullable GpuTextureView sceneDepth, boolean outsideScene, boolean renderedFrame,
                                         boolean worldFrame) {
        RenderSystem.assertOnRenderThread();
        try (var resources = new PrefixResources(prefixes)) {
            try (var groups = new NiagaraGpuDispatchGroups()) {
                if (outsideScene) {
                    // UE's synthetic views still run the same three compute stages.
                    // Proxies within a stage start together and may overlap their dispatch groups.
                    var stagePrefixes = new EnumMap<TickStage, List<Prefix>>(TickStage.class);
                    for (var prefix : prefixes)
                        stagePrefixes.computeIfAbsent(prefix.tickStage(programs, shaderLookup), key -> new ArrayList<>()).add(prefix);
                    int firstGroup = 0;
                    for (var stage : TickStage.values()) {
                        var staged = stagePrefixes.get(stage);
                        if (staged == null) continue;
                        int stageEnd = firstGroup;
                        for (var prefix : staged)
                            stageEnd = Math.max(stageEnd, prefix.prepare(groups, encoder, programs, shaderLookup, view, sceneDepth,
                                    true, renderedFrame, worldFrame, resources.traceFrames, firstGroup));
                        firstGroup = stageEnd;
                    }
                } else {
                    for (var prefix : prefixes)
                        prefix.prepare(groups, encoder, programs, shaderLookup, view, sceneDepth,
                                false, renderedFrame, worldFrame, resources.traceFrames, 0);
                }
                groups.execute();
                for (var entry : resources.traceFrames.entrySet()) {
                    var traces = entry.getKey();
                    var submission = entry.getValue();
                    if (submission.deferred()) {
                        traces.deferSubmission(submission.platform());
                    } else {
                        var scene = outsideScene ? NiagaraSceneInputs.EMPTY.withGlobalDistanceField(view.sceneInputs().globalDistanceField())
                                : view.sceneInputs();
                        traces.finishFrame(encoder, view, scene, submission.platform());
                    }
                    for (var prefix : prefixes) if (prefix.traces == traces) prefix.publish();
                }
            } catch (RuntimeException | Error failure) {
                for (var traces : resources.traceFrames.keySet()) traces.abortFrame();
                for (var prefix : prefixes) if (!prefix.complete) {
                    try { prefix.failed(failure); }
                    catch (RuntimeException | Error cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        }
    }

    private record TraceSubmission(NiagaraPlatformEnvironment platform, boolean deferred) { }

    private static final class PrefixResources implements AutoCloseable {
        final List<Prefix> prefixes;
        final Map<NiagaraGpuAsyncTraces, TraceSubmission> traceFrames = new LinkedHashMap<>();

        PrefixResources(List<Prefix> prefixes) { this.prefixes = prefixes; }

        @Override public void close() {
            for (var traces : traceFrames.keySet()) traces.abortSubmission();
            Throwable failure = null;
            for (var prefix : prefixes) {
                try { prefix.releaseScene(); }
                catch (RuntimeException | Error cleanup) {
                    if (failure == null) failure = cleanup;
                    else if (failure != cleanup) failure.addSuppressed(cleanup);
                }
            }
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        }
    }

    private final class Prefix {
        final int tickCount;
        final Runnable submitted;
        @Nullable NiagaraGpuAsyncTraces traces;
        boolean complete;

        Prefix(int tickCount, Runnable submitted) {
            this.tickCount = tickCount;
            this.submitted = submitted;
        }

        TickStage tickStage(NiagaraGpuPrograms programs, Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
            return NiagaraGpuBatchDispatcher.this.tickStage(programs, shaderLookup);
        }

        int prepare(NiagaraGpuDispatchGroups groups, CommandEncoder encoder, NiagaraGpuPrograms programs,
                     Function<Identifier, PreparedNiagaraShaders> shaderLookup, NiagaraGpuView view,
                     @Nullable GpuTextureView sceneDepth, boolean outsideScene, boolean renderedFrame, boolean worldFrame,
                     Map<NiagaraGpuAsyncTraces, TraceSubmission> traceFrames, int initialGroup) {
            system.restoreLiveGpuCache(encoder, programs, shaderLookup);
            int lastSimulationTick = -1;
            int debugLineCapacity = -1;
            var environment = system.platformEnvironment();
            boolean deferTraces = false;
            for (int order = 0; order < system.gpuEmitterCount(); order++) {
                int index = system.gpuEmitterIndex(order);
                if (system.isStatelessGpuEmitter(index)) continue;
                int lastEmitterTick = system.lastGpuSimulationTick(index, tickCount);
                if (lastEmitterTick < 0) continue;
                var emitter = system.gpuEmitter(index);
                var gpu = (NiagaraDefinition.GpuSimulation) emitter.simulation();
                var binding = binding(index, programs, shaderLookup, gpu);
                var interfaces = binding.interfaces;
                if (interfaces == null) {
                    interfaces = system.gpuInterfaces(shaderId(gpu), programs, binding.shaders, gpu.metadata(), emitter.sourceName());
                    binding.interfaces = interfaces;
                }
                interfaces.platformPublication(system.platformPublication());
                interfaces.view(view);
                interfaces.sceneInputs(sceneDepth, outsideScene);
                if (binding.debugDrawOverride >= 0) {
                    if (debugLines == null) debugLines = new NiagaraGpuDebugLines();
                    interfaces.debugLines(debugLines);
                    int limit = binding.debugDrawOverride == 0
                            ? Objects.requireNonNull(environment.console(NiagaraGpuDebugLines.MAX_LINES)).bits()
                            : binding.debugDrawOverride;
                    debugLineCapacity = Math.max(debugLineCapacity, Math.max(0, limit));
                }
                if (binding.shaders.asyncTraceShaders() != null) {
                    var world = system.gpuDispatchWorld();
                    if ((worldFrame || outsideScene) && world != null) {
                        traces = programs.asyncTraces(world);
                        deferTraces = worldFrame;
                    } else if (renderedFrame) {
                        if (asyncTraces == null) asyncTraces = new NiagaraGpuAsyncTraces(programs);
                        traces = asyncTraces;
                    } else {
                        if (detachedAsyncTraces == null) detachedAsyncTraces = new NiagaraGpuAsyncTraces(programs);
                        traces = detachedAsyncTraces;
                    }
                    interfaces.asyncTraceRegistry(traces);
                }
                binding.lastOutputTick = system.planGpuEmitter(index, binding.shaders, tickCount);
                lastSimulationTick = Math.max(lastSimulationTick, lastEmitterTick);
            }
            if (traces != null && !traceFrames.containsKey(traces)) {
                traces.beginFrame(renderedFrame ? view.renderFrameSequence() : Long.MIN_VALUE,
                        outsideScene ? NiagaraSceneInputs.EMPTY : view.sceneInputs(), environment);
                traceFrames.put(traces, new TraceSubmission(environment, deferTraces));
            }
            if (debugLineCapacity >= 0 && debugLines != null) debugLines.begin(encoder, programs, view, debugLineCapacity,
                    Objects.requireNonNull(environment.console(NiagaraGpuDebugLines.ENABLED)).bits() != 0);
            int group = initialGroup;
            for (int tick = 0; tick < tickCount; tick++) {
                final int step = tick;
                final boolean finalTick = tick == lastSimulationTick;
                int layer = -1;
                int overlapStart = group;
                int emitterEnd = group;
                var emitters = new ArrayList<NiagaraGpuDispatchGroups.EmitterTick>();
                var skipped = new ArrayList<Integer>();
                for (int order = 0; order < system.gpuEmitterCount(); order++) {
                    int index = system.gpuEmitterIndex(order);
                    if (system.isStatelessGpuEmitter(index) || step >= system.pendingGpuTicks(index)) continue;
                    if (!system.gpuTickExecutes(index, step)) {
                        skipped.add(index);
                        continue;
                    }
                    var binding = bindings[index];
                    var stages = system.plannedGpuStages(index, step);
                    int emitterLayer = system.gpuEmitterGroup(index);
                    if (!stages.isEmpty() && emitterLayer != layer) {
                        overlapStart = emitterEnd;
                        layer = emitterLayer;
                    }
                    if (!stages.isEmpty())
                        emitterEnd = Math.addExact(overlapStart, stages.stream().mapToInt(stage -> stage.iterations()).sum());
                    emitters.add(new NiagaraGpuDispatchGroups.EmitterTick(overlapStart, stages, () ->
                            system.beginGpuEmitterTick(index, encoder, programs, binding.shaders,
                                    Objects.requireNonNull(binding.interfaces), 0, system.gpuAllocationEstimate(index, programs),
                                    true, finalTick, step == binding.lastOutputTick)));
                }
                group = groups.addTick(group, emitters, () -> {
                    system.applyGpuTileRebase(encoder, programs, step);
                    return system.activateGpuTargetStep(step);
                }, () -> {
                    for (int index : skipped) system.consumeUnsubmittedGpuTick(index);
                });
            }
            if (traces == null) groups.onComplete(Math.max(0, group - 1), this::publish);
            return group;
        }

        void publish() {
            complete = true;
            submitted.run();
        }

        void failed(Throwable failure) { system.gpuSubmissionFailed(failure); }

        void releaseScene() {
            for (var binding : bindings)
                if (binding != null && binding.interfaces != null) binding.interfaces.releaseSceneView();
        }
    }

    public NiagaraGpuParticleBuffers particles(int index, NiagaraGpuPrograms programs,
                                               Function<Identifier, PreparedNiagaraShaders> shaderLookup) {
        var gpu = (NiagaraDefinition.GpuSimulation) system.gpuEmitter(index).simulation();
        var binding = binding(index, programs, shaderLookup, gpu);
        return system.gpuParticles(index, programs, binding.shaders, system.gpuAllocationEstimate(index, programs));
    }

    private Binding binding(int index, NiagaraGpuPrograms programs, Function<Identifier, PreparedNiagaraShaders> shaderLookup,
                            NiagaraDefinition.GpuSimulation gpu) {
        var current = bindings[index];
        if (current == null) {
            Identifier id = shaderId(gpu);
            var shaders = shaderLookup.apply(id);
            if (shaders == null) throw new IllegalArgumentException("Missing Niagara GPU shader " + id);
            current = new Binding(programs, shaders, gpu.metadata());
            bindings[index] = current;
        } else if (current.programs != programs) throw new IllegalStateException("Stale Niagara GPU emitter resources");
        return current;
    }

    public static Identifier shaderId(NiagaraDefinition.GpuSimulation gpu) {
        return Identifier.fromNamespaceAndPath(gpu.hlslSource().getNamespace(), gpu.shaderPipeline());
    }

    public NiagaraGpuDebugLines.@Nullable Frame debugLines() { return debugLines == null ? null : debugLines.frame(); }

    public void reset() {
        try {
            for (int index = 0; index < bindings.length; index++) {
                var binding = bindings[index];
                bindings[index] = null;
                if (binding != null && binding.interfaces != null) binding.interfaces.close();
            }
        } finally {
            if (debugLines != null) { debugLines.close(); debugLines = null; }
            if (asyncTraces != null) {
                asyncTraces.close();
                asyncTraces = null;
            }
            if (detachedAsyncTraces != null) {
                detachedAsyncTraces.close();
                detachedAsyncTraces = null;
            }
        }
    }
}
