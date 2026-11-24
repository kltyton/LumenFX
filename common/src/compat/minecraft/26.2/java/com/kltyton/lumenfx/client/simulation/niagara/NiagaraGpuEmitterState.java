package com.kltyton.lumenfx.client.simulation.niagara;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuEngineParameters;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuParameterValues;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuSpawnInfo;
import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuStageSchedule;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.client.resource.niagara.GpuHostContextJson;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraGpuHostExecutionContext;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraRendererParameterStores;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Game-thread emitter constants and spawn requests for one GPU simulation instance. */
final class NiagaraGpuEmitterState {
    private static final Logger LOGGER = LogUtils.getLogger();
    private boolean spawningAllowed = true;
    sealed interface Step permits Tick, Clear {
        long particleCountFence();
    }
    record Clear(long particleCountFence) implements Step { }
    record Tick(NiagaraGpuEngineParameters engine, NiagaraGpuParameterValues.Snapshot external,
                NiagaraGpuStageSchedule.Parameters stageParameters, NiagaraGpuSpawnInfo spawn,
                int eventSpawns, long allocationRequest, boolean reset, boolean execute,
                double shiftX, double shiftY, double shiftZ, double originX, double originY, double originZ, long particleCountFence,
                List<Integer> rebaseEmitters,
                Map<String, TextureParameter.Snapshot> textureSnapshots) implements Step {
        Tick { textureSnapshots = Map.copyOf(textureSnapshots); }
    }

    private static final int DEFAULT_MAX_FRAME_SPAWNS = 2_000_000;
    private final long allocationId = NiagaraAllocationHistory.nextReporter();
    private final NiagaraDefinition.Emitter source;
    private final boolean platformAllowed;
    private final float spawnCountScale;
    private final NiagaraDefinition.Variable executionState;
    private final List<NiagaraDefinition.Variable> spawnBindings;
    private final NiagaraGpuParameterValues parameters;
    private final Map<String, String> textureInputs;
    private final List<NiagaraGpuHostExecutionContext> hostContexts;
    private final @Nullable NiagaraParameterStoreSources ownedSources;
    private @Nullable NiagaraRendererParameterStores rendererParameters;
    private final NiagaraEventData eventData;
    private final int maximumFrameSpawns;
    private final byte[] current = new byte[640], previous = new byte[640];
    private final ByteBuffer words = ByteBuffer.wrap(current).order(ByteOrder.LITTLE_ENDIAN);
    private int instanceSeed, totalSpawned, countEstimateSpawnBase, state;
    private int sourceSeedOffset;
    private int maximumAllocation;
    private float age;
    private boolean first = true, resetPending = true, terminal;
    private boolean completionClearPending, ticked;
    private boolean reportedInvalidState;
    private boolean firstHostTick = true;
    private volatile long particleCountReadFence = 1, particleCountWriteFence;

    NiagaraGpuEmitterState(NiagaraDefinition.Emitter source, Map<String, NiagaraDefinition.Variable> variables,
                           NiagaraEventData eventData) {
        this(source, variables, eventData, NiagaraPlatforms.snapshot());
    }
    NiagaraGpuEmitterState(NiagaraDefinition.Emitter source, Map<String, NiagaraDefinition.Variable> variables,
                           NiagaraEventData eventData, NiagaraPlatformEnvironment platform) {
        this(source, variables, eventData, platform, null, null);
    }
    NiagaraGpuEmitterState(NiagaraDefinition.Emitter source, Map<String, NiagaraDefinition.Variable> variables,
                           NiagaraEventData eventData, NiagaraPlatformEnvironment platform,
                           @Nullable List<NiagaraDefinition.Variable> systemLayout, @Nullable NiagaraParameterStoreSources sources) {
        this.source = source;
        platformAllowed = source.scalability().platforms().active(platform);
        spawnCountScale = source.scalability().spawnScale(platform);
        this.eventData = eventData;
        executionState = variables.get(source.sourceName() + ".ExecutionState");
        spawnBindings = source.spawnInfo().stream().map(variables::get).toList();
        var simulation = (NiagaraDefinition.GpuSimulation) source.simulation();
        JsonObject program = simulation.metadata();
        var inputs = new LinkedHashMap<String, String>();
        for (var element : program.getAsJsonArray("interfaces")) {
            var binding = element.getAsJsonObject();
            String type = binding.get("class").getAsString();
            if ((type.equals("NiagaraDataInterfaceTexture") || type.equals("NiagaraDataInterface2DArrayTexture")
                    || type.equals("NiagaraDataInterfaceCubeTexture") || type.equals("NiagaraDataInterfaceVolumeTexture"))
                    && binding.has("shader_symbol"))
                inputs.put(SourceNames.canonical(binding.get("compile_name").getAsString()), binding.get("interface_id").getAsString());
        }
        textureInputs = Map.copyOf(inputs);
        NiagaraParameterStoreSources parameterSources = sources == null ? new NiagaraParameterStoreSources(new LinkedHashMap<>()) : sources;
        ownedSources = sources == null ? parameterSources : null;
        var hostDefinitions = simulation.hostContexts();
        if (platformAllowed && hostDefinitions.isEmpty() && program.has("host_contexts"))
            hostDefinitions = GpuHostContextJson.read(program.getAsJsonArray("host_contexts"));
        hostContexts = platformAllowed ? hostDefinitions.stream()
                .map(context -> new NiagaraGpuHostExecutionContext(context, parameterSources, systemLayout)).toList() : List.of();
        parameters = new NiagaraGpuParameterValues(program, simulation.parameterStore(), systemLayout, parameterSources);
        JsonObject settings = program.getAsJsonObject("emitter_settings");
        int configured = settings.has("MaxGPUParticlesSpawnPerFrame")
                ? settings.get("MaxGPUParticlesSpawnPerFrame").getAsInt() : 0;
        maximumFrameSpawns = configured <= 0 ? DEFAULT_MAX_FRAME_SPAWNS : configured;
        instanceSeed = NiagaraSeed.next();
        words.putFloat(616, 1f);
        if (!platformAllowed) complete(4);
    }

    @Nullable NiagaraParameterStore combinedParameters() { return platformAllowed ? parameters.store() : null; }
    void collectDebugParameters(String name, Map<String, NiagaraParameterStore> stores) {
        var combined = combinedParameters();
        if (combined != null) stores.put(name + " GPUContext", combined);
        for (var context : hostContexts) {
            String usage = context.usage();
            String kind = usage.substring(usage.lastIndexOf(':') + 1);
            switch (kind) {
                case "ParticleSpawnScript", "ParticleSpawnScriptInterpolated" -> stores.put(name + " Spawn", context.store());
                case "ParticleUpdateScript" -> stores.put(name + " Update", context.store());
                default -> { }
            }
        }
    }
    void refreshUserInterfaces(boolean rebind) { if (platformAllowed) parameters.refreshUserInterfaces(rebind); }
    Map<String, String> dataInterfaceSelections(Set<String> compileNames) {
        return platformAllowed ? parameters.dataInterfaceSelections(compileNames) : Map.of();
    }
    void registerObjectPropertyRequests(NiagaraInterfaces interfaces) {
        if (platformAllowed) parameters.forEachObjectPropertyRequest(interfaces::registerObjectPropertyRequest);
    }
    void forEachHostExecutionStore(boolean rebind, Consumer<NiagaraParameterStore> action) {
        for (var context : hostContexts) {
            context.refreshUserInterfaces(rebind);
            action.accept(context.store());
        }
    }
    void bindParameters(NiagaraParameterStore instance, NiagaraRendererParameterStores renderer) {
        rendererParameters = renderer;
        for (var context : hostContexts) context.bindInstance(instance);
        if (platformAllowed) parameters.bindInstance(instance);
    }
    void prepareParameters(NiagaraFrame frame, EffectHandle.Input input, VectorDataSet system) {
        if (!terminal) {
            if (shouldTick()) {
                if (hostContexts.isEmpty()) parameters.tickRapidIteration();
                else for (var context : hostContexts) context.tickRapidIteration();
            }
            for (var context : hostContexts) context.prepareCollections(frame.collections, frame.originX(), frame.originY(), frame.originZ());
            parameters.prepareSources(input.parameters(), frame.collections, frame.originX(), frame.originY(), frame.originZ());
        }
    }
    void transferHostParameters(VectorDataSet system, List<NiagaraDefinition.Variable> layout) {
        for (var context : hostContexts) context.transferDataSet(system, layout);
    }
    void closeParameters() {
        for (var context : hostContexts) context.close();
        parameters.close();
        if (ownedSources != null) ownedSources.close();
    }

    byte[] prepareRuntime(NiagaraFrame frame, int estimatedParticles) {
        System.arraycopy(current, 0, previous, 0, current.length);
        System.arraycopy(frame.current, 0, current, 0, frame.current.length);
        System.arraycopy(frame.previous, 0, previous, 0, frame.previous.length);
        words.putInt(608, disabled() ? 0 : estimatedParticles);
        if (!disabled()) {
            words.putInt(612, totalSpawned);
            words.putFloat(616, spawnCountScale);
            words.putFloat(620, age);
            words.putInt(624, NiagaraSeed.offset(source.randomSeed(), sourceSeedOffset));
            words.putInt(628, instanceSeed);
        }
        if (first) System.arraycopy(current, 0, previous, 0, current.length);
        return current;
    }

    @Nullable Step prepareTick(NiagaraFrame frame, VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                     EffectHandle.Input input, boolean execute, boolean submitGpuTick,
                     Map<String, TextureParameter.Snapshot> textureSnapshots, List<NiagaraGpuSpawnInfo.Entry> channelSpawns) {
        ticked = false;
        if (terminal) {
            if (!completionClearPending) return null;
            completionClearPending = false;
            return clearStep();
        }
        if (execute && frame.delta >= 1.0e-8f && shouldTick()) {
            for (var context : hostContexts) context.tick();
            parameters.validateInterfaces();
            ticked = true;
            if (firstHostTick) {
                for (var context : hostContexts) context.postTick();
                firstHostTick = false;
            }
            age += frame.delta;
            words.putFloat(620, age);
            if (state == 2) {
                state = 1;
                return clearStep();
            }
        }
        boolean shouldExecute = execute && submitGpuTick && (!first || ticked) && frame.delta >= 1.0e-8f;
        List<NiagaraGpuSpawnInfo.Entry> entries = new ArrayList<>(spawnBindings.size());
        for (var variable : spawnBindings) {
            entries.add(new NiagaraGpuSpawnInfo.Entry(system.integer(variable.intStart(), 0),
                    system.floating(variable.floatStart(), 0), system.floating(variable.floatStart() + 1, 0),
                    system.integer(variable.intStart() + 1, 0)));
        }
        entries.addAll(channelSpawns);
        boolean allowBirths = state == 0 && frame.attachmentBirthsAllowed;
        NiagaraGpuSpawnInfo spawn = NiagaraGpuSpawnInfo.build(entries, maximumFrameSpawns,
                shouldExecute && spawningAllowed && allowBirths);
        if (spawn.limit() == NiagaraGpuSpawnInfo.Limit.FRAME_SPAWNS)
            LOGGER.warn("Niagara GPU emitter '{}' exceeded its per-frame spawn limit {} at spawn group {}",
                    source.sourceName(), maximumFrameSpawns, spawn.rejectedEntry());
        else if (spawn.limit() == NiagaraGpuSpawnInfo.Limit.GROUPS)
            LOGGER.warn("Niagara GPU emitter '{}' exceeded its {} spawn groups at group {}",
                    source.sourceName(), NiagaraGpuSpawnInfo.MAX_GROUPS, spawn.rejectedEntry());
        long requestedEvents = 0;
        if (shouldExecute && ticked && spawningAllowed) {
            for (var handler : source.eventHandlers()) {
                VectorDataSet data = eventData.source(source, handler);
                if (data != null) requestedEvents += NiagaraEventSpawns.total(handler, data, allowBirths);
            }
        }
        int eventSpawns = (int)Math.min(Integer.MAX_VALUE, requestedEvents);
        long allocationRequest = Math.max(0, words.getInt(608)) + spawn.requestedCount() + requestedEvents;
        NiagaraGpuParameterValues.Snapshot external = parameters.snapshot(system, variables, input.parameters(), frame.originX(),
                frame.originY(), frame.originZ(), frame.collections, ticked, shouldExecute);
        var renderer = rendererParameters;
        boolean bound;
        Map<String, Integer> stageSnapshot, boolSnapshot;
        if (parameters.store() != null && renderer != null) {
            bound = true;
            stageSnapshot = renderer.integers(false); boolSnapshot = renderer.integers(true);
        } else {
            bound = false;
            Map<String, Integer> stageBindings = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
            for (var variable : variables.values()) {
                if (variable.intCount() == 1 && variable.floatCount() == 0 && variable.halfCount() == 0)
                    stageBindings.put(variable.name(), system.integer(variable.intStart(), 0));
            }
            for (var entry : input.parameters().entrySet()) {
                var value = entry.getValue();
                if (value.type().components() == 1 && (value.type().storage() == 1 || value.type().storage() == 2))
                    stageBindings.put(entry.getKey(), (int) value.words()[0]);
            }
            stageSnapshot = Collections.unmodifiableMap(stageBindings); boolSnapshot = Map.of();
        }
        NiagaraGpuStageSchedule.Parameters stageValues = new NiagaraGpuStageSchedule.Parameters() {
            @Override public @Nullable Integer integer(String name) {
                Integer value = stageSnapshot.get(name);
                if (value != null || bound) return value;
                byte[] raw = external.parameter(name);
                if (raw == null) return null;
                if (raw.length != Integer.BYTES) throw new IllegalArgumentException("Invalid Niagara stage integer " + name);
                return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getInt();
            }
            @Override public @Nullable Boolean bool(String name) {
                Integer value = bound ? boolSnapshot.get(name) : integer(name);
                return value == null ? null : value != 0;
            }
        };
        boolean reset = resetPending;
        if (shouldExecute) {
            if (reset) particleCountReadFence++;
            totalSpawned += spawn.totalSpawnedDelta();
            if (ticked) first = false;
            resetPending = false;
            if (ticked) parameters.commit(external);
        }
        if (ticked) for (var context : hostContexts) context.postTick();
        return new Tick(new NiagaraGpuEngineParameters(current, previous), external, stageValues, spawn,
                eventSpawns, allocationRequest, reset, shouldExecute,
                frame.shiftX, frame.shiftY, frame.shiftZ, frame.originX(), frame.originY(), frame.originZ(),
                particleCountReadFence, frame.gpuRebaseEmitters, textureSnapshots);
    }

    int estimatedCount(int publishedCount) {
        return particleCountReadFence <= particleCountWriteFence ? publishedCount : totalSpawned - countEstimateSpawnBase;
    }

    void releaseCountFence(long fence) { particleCountWriteFence = fence; }

    void collectTextureInputs(Tick tick, Set<String> identities) {
        var selections = tick.external().dataInterfaces();
        textureInputs.forEach((key, original) -> identities.add(selections.getOrDefault(key, original)));
    }

    void spawningAllowed(boolean allowed) { spawningAllowed = allowed; }
    boolean complete() { return terminal; }
    int totalSpawned() { return totalSpawned; }
    void restoreCache(VectorDataSet system, int particles, int spawned) {
        if (!platformAllowed) return;
        int recordedState = system.integer(executionState.intStart(), 0);
        state = recordedState >= 0 && recordedState < 5 ? recordedState : 1;
        terminal = state == 3 || state == 4;
        completionClearPending = false;
        totalSpawned = countEstimateSpawnBase = spawned;
        particleCountWriteFence = particleCountReadFence;
        words.putInt(608, particles);
        words.putInt(612, totalSpawned);
        resetPending = false;
    }
    boolean disabled() { return state == 4; }
    boolean isActiveForDataChannel() { return !terminal && state == 0; }
    boolean hasGpuContext() { return platformAllowed; }
    boolean shouldTick() {
        return !terminal && (state == 0 || words.getInt(608) > 0);
    }
    void transferExecutionState(VectorDataSet system) {
        if (terminal) return;
        int requestedState = system.integer(executionState.intStart(), 0);
        if (requestedState < 0 || requestedState >= 5) {
            if (!reportedInvalidState) {
                LOGGER.error("Invalid Niagara execution state {} for emitter {}; using Inactive", requestedState, source.sourceName());
                reportedInvalidState = true;
            }
            requestedState = 1;
        }
        if (requestedState == 3 || requestedState == 4) complete(requestedState);
        else state = requestedState;
    }
    void activateExecution() {
        if (!disabled()) {
            state = 0;
            terminal = false;
        }
    }
    boolean ticked() { return ticked; }

    private Clear clearStep() {
        particleCountReadFence++;
        countEstimateSpawnBase = totalSpawned;
        words.putInt(608, 0);
        return new Clear(particleCountReadFence);
    }
    boolean done(int publishedCount) { return terminal || state != 0 && estimatedCount(publishedCount) == 0; }
    long allocationId() { return allocationId; }
    boolean recordAllocation(int count) {
        if (count <= maximumAllocation) return false;
        maximumAllocation = count;
        return true;
    }
    void initializeAllocation(int count) { maximumAllocation = count; }

    void complete(int executionState) {
        if (disabled() && executionState != 4) return;
        if (!terminal && executionState == 3) completionClearPending = true;
        state = executionState;
        terminal = true;
    }

    Clear disableForInterfaceBinding(NiagaraGpuParameterValues.InterfaceBindingException failure) {
        LOGGER.error("Could not bind data interfaces for Niagara GPU emitter '{}': {}", source.sourceName(), failure.getMessage());
        ticked = false;
        complete(4);
        return clearStep();
    }

    void sourceSeedOffset(int offset) { sourceSeedOffset = offset; }

    void reset() {
        resetSimulation(true);
    }

    void resetSimulation(boolean killExisting) {
        if (!platformAllowed || !killExisting && disabled()) return;
        age = 0;
        state = 0;
        first = true;
        if (killExisting) {
            particleCountReadFence++;
            totalSpawned = 0;
            countEstimateSpawnBase = 0;
            resetPending = true;
        }
        terminal = false;
        completionClearPending = ticked = false;
        instanceSeed = NiagaraSeed.next();
        parameters.reset();
        for (var context : hostContexts) context.reset();
        firstHostTick = true;
    }
}
