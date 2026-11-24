package com.kltyton.lumenfx.client.simulation.niagara;

import com.kltyton.lumenfx.client.simulation.niagara.gpu.NiagaraGpuSpawnInfo;

import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.component.mesh.StaticMeshComponentDraw;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCachePlayer;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCacheRecorder;
import com.kltyton.lumenfx.client.simulation.niagara.bounds.NiagaraMeshBounds;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.EmitterFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Sample;
import com.kltyton.lumenfx.api.client.parameter.NiagaraPlatforms;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment;
import com.kltyton.lumenfx.client.simulation.niagara.render.NiagaraEmitterRenderState;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraScriptNumericStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraRendererParameterStores;
import com.kltyton.lumenfx.client.simulation.EmitterInstance;
import com.kltyton.lumenfx.effect.EmitterDefinition;
import com.kltyton.lumenfx.effect.EffectDefinition;
import com.kltyton.lumenfx.effect.render.decal.ParticleDecal;
import com.kltyton.lumenfx.effect.render.light.scene.ScenePointLight;
import com.kltyton.lumenfx.effect.render.light.scene.SceneDirectionalLight;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.Attribute;
import com.kltyton.lumenfx.effect.niagara.NiagaraParticleReadTypes;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.jspecify.annotations.Nullable;

/** One worker owns this emitter's typed buffers and both VM execution contexts. */
public final class NiagaraEmitter implements NiagaraCpuEmitter, NiagaraParticleReadSource {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int DEFAULT_SOURCE_CPU_LIMIT = 1_000_000;
    // Each parsed Spawn program owns its byte arrays, so distinct resource generations have distinct keys.
    private static final Map<NiagaraDefinition.Emitter, NiagaraAllocationHistory> ALLOCATION_HISTORIES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private record Invocation(int count, float start, float interval, int group) { }

    private final NiagaraDefinition.Emitter source;
    private final String spawnIntervalParameter, interpStartParameter, spawnGroupParameter;
    private final NiagaraEmitterBounds emitterBounds;
    private final boolean localSpace;
    private final boolean platformAllowed;
    private final float spawnCountScale;
    private final int maximumParticles;
    private final int allocationEstimate;
    private final @Nullable NiagaraAllocationHistory allocationHistory;
    private final long allocationReporter;
    private int maximumAllocation;
    private final boolean rejectsSourceOverflow;
    private final NiagaraDefinition.Variable executionStateBinding;
    private final List<NiagaraDefinition.Variable> spawnBindings;
    private List<NiagaraGpuSpawnInfo.Entry> channelSpawns = List.of();
    private boolean spawningAllowed = true;
    private final NiagaraEventData eventData;
    private final List<NiagaraEventData.Stream> spawnEvents, updateEvents, allEvents;
    private final EventContext[] eventContexts;
    private final VectorMachine spawn, update;
    private final NiagaraEmitterRenderState renderState;
    private final PersistentParticleIds[] persistentIds;
    private VectorDataSet current, destination;
    private final @Nullable VectorDataSet selfReadPrevious;
    private int[] selfReadPreviousIds = new int[0];
    private int[] eventReadIds = new int[0];
    private final byte[] runtime = new byte[640], oldRuntime = new byte[640];
    private final ByteBuffer runtimeWords = ByteBuffer.wrap(runtime).order(ByteOrder.LITTLE_ENDIAN);
    private final byte[] spawnConstants, updateConstants, defaultSpawnConstants, defaultUpdateConstants;
    private final ByteBuffer spawnWords, updateWords;
    private final NiagaraScriptNumericStore spawnParameters, updateParameters;
    private final List<NiagaraScriptNumericStore> parameterStores;
    private @Nullable NiagaraRendererParameterStores rendererParameters;
    private @Nullable NiagaraParameterStore overrideParameters;
    private int instanceSeed;
    private int sourceSeedOffset;
    private Map<String, EffectHandle.ParameterValue> parameterValues = Map.of();
    private NiagaraCollectionSnapshot collectionValues = NiagaraCollectionSnapshot.EMPTY;
    private float age;
    private int totalSpawned, executionState, tickCount;
    private int frameSpawned, previousFrameSpawned;
    private boolean first = true, terminal, reportedInvalidState;
    private boolean reportedSourceOverflow;
    private boolean releaseFailedBindingComponents;

    private static final class EventContext {
        final NiagaraDefinition.EventHandler definition;
        final VectorProgram program;
        final VectorMachine machine;
        final byte[] constants, defaults;
        final ByteBuffer words;
        final @Nullable NiagaraScriptNumericStore parameters;
        EventContext(NiagaraDefinition.EventHandler definition, NiagaraInterfaces interfaces, String emitterOwner,
                     @Nullable NiagaraParameterStoreSources sources) {
            this.definition = definition;
            program = definition.program();
            if (program == null) { machine = null; constants = defaults = null; words = null; parameters = null; return; }
            machine = new VectorMachine(program, interfaces, emitterOwner, true);
            constants = program.constantData();
            parameters = new NiagaraScriptNumericStore(program, sources, sources == null);
            parameters.restoreConstants(constants);
            defaults = constants.clone();
            words = ByteBuffer.wrap(constants).order(ByteOrder.LITTLE_ENDIAN);
        }
        void reset() {
            if (program == null) return;
            System.arraycopy(defaults, 0, constants, 0, constants.length);
            parameters.reset(); parameters.restoreConstants(constants);
        }
    }
    private record PendingEvent(EventContext context, VectorDataSet data, int[] spawnCounts) { }

    public NiagaraEmitter(NiagaraDefinition.Emitter source, List<EmitterDefinition> renderers,
                          Map<String, NiagaraDefinition.Variable> variables) {
        this(source, renderers, variables, new NiagaraEventData(List.of(source)), new NiagaraInterfaces());
    }

    NiagaraEmitter(NiagaraDefinition.Emitter source, List<EmitterDefinition> renderers,
                          Map<String, NiagaraDefinition.Variable> variables, NiagaraEventData eventData, NiagaraInterfaces interfaces) {
        this(source, renderers, variables, eventData, interfaces, NiagaraPlatforms.snapshot());
    }
    NiagaraEmitter(NiagaraDefinition.Emitter source, List<EmitterDefinition> renderers,
                   Map<String, NiagaraDefinition.Variable> variables, NiagaraEventData eventData, NiagaraInterfaces interfaces,
                   NiagaraPlatformEnvironment platform) {
        this(source, renderers, variables, eventData, interfaces, platform, null);
    }
    NiagaraEmitter(NiagaraDefinition.Emitter source, List<EmitterDefinition> renderers,
                   Map<String, NiagaraDefinition.Variable> variables, NiagaraEventData eventData, NiagaraInterfaces interfaces,
                   NiagaraPlatformEnvironment platform, @Nullable NiagaraParameterStoreSources sources) {
        this.source = source;
        spawnIntervalParameter = source.sourceName() + ".SpawnInterval";
        interpStartParameter = source.sourceName() + ".InterpSpawnStartDt";
        spawnGroupParameter = source.sourceName() + ".SpawnGroup";
        platformAllowed = source.scalability().platforms().active(platform);
        spawnCountScale = source.scalability().spawnScale(platform);
        if (interfaces.emitterBounds(source.sourceName()) == null) interfaces.registerEmitterBounds(source, null);
        emitterBounds = java.util.Objects.requireNonNull(interfaces.emitterBounds(source.sourceName()));
        this.eventData = eventData;
        spawnEvents = source.spawnEvents().stream().map(generator -> eventData.generator(source, generator)).toList();
        updateEvents = source.updateEvents().stream().map(generator -> eventData.generator(source, generator)).toList();
        var uniqueEvents = new java.util.LinkedHashSet<NiagaraEventData.Stream>();
        uniqueEvents.addAll(spawnEvents);
        uniqueEvents.addAll(updateEvents);
        allEvents = List.copyOf(uniqueEvents);
        eventContexts = source.eventHandlers().stream().map(value -> new EventContext(value, interfaces, source.sourceName(), sources)).toArray(EventContext[]::new);
        var legacy = source.maximumParticles() <= 0 && !renderers.isEmpty() ? renderers.getFirst() : null;
        localSpace = legacy == null ? source.localSpace() : legacy.localSpace();
        int storageLimit = legacy == null ? Math.max(0, source.maximumParticles()) : legacy.maximumParticles();
        var simulation = (NiagaraDefinition.CpuSimulation) source.simulation();
        maximumParticles = simulation.maximumParticles(storageLimit);
        var fixedCount = simulation.fixedParticleCount();
        rejectsSourceOverflow = storageLimit == DEFAULT_SOURCE_CPU_LIMIT
                && (fixedCount == null || fixedCount > DEFAULT_SOURCE_CPU_LIMIT);
        allocationEstimate = rejectsSourceOverflow ? simulation.allocationEstimate()
                : Math.min(maximumParticles, simulation.allocationEstimate());
        allocationHistory = simulation.automaticAllocation()
                ? ALLOCATION_HISTORIES.computeIfAbsent(source, unused -> new NiagaraAllocationHistory()) : null;
        allocationReporter = allocationHistory == null ? 0 : NiagaraAllocationHistory.nextReporter();
        maximumAllocation = allocationHistory == null ? allocationEstimate : allocationHistory.estimate();
        executionStateBinding = variables.get(source.sourceName() + ".ExecutionState");
        spawnBindings = source.spawnInfo().stream().map(variables::get).toList();
        instanceSeed = NiagaraSeed.next();
        spawn = new VectorMachine(source.spawn(), interfaces, source.sourceName()); update = new VectorMachine(source.update(), interfaces, source.sourceName());
        boolean requiresIds = source.requiresPersistentIdStorage()
                || source.spawn().instructions().stream().anyMatch(i -> i.opcode() == VectorProgram.Opcode.ACQUIRE_ID || i.opcode() == VectorProgram.Opcode.UPDATE_ID)
                || source.update().instructions().stream().anyMatch(i -> i.opcode() == VectorProgram.Opcode.ACQUIRE_ID || i.opcode() == VectorProgram.Opcode.UPDATE_ID)
                || source.eventHandlers().stream().filter(handler -> handler.program() != null)
                .flatMap(handler -> handler.program().instructions().stream())
                .anyMatch(i -> i.opcode() == VectorProgram.Opcode.ACQUIRE_ID || i.opcode() == VectorProgram.Opcode.UPDATE_ID);
        persistentIds = requiresIds ? new PersistentParticleIds[] {new PersistentParticleIds()} : null;
        int floats = 0, integers = 0, halves = 0;
        for (var attribute : source.attributes()) {
            if (attribute.type() == 0) floats = Math.max(floats, attribute.component() + attribute.count());
            else if (attribute.type() == 1) integers = Math.max(integers, attribute.component() + attribute.count());
            else if (attribute.type() == 2) halves = Math.max(halves, attribute.component() + attribute.count());
            else throw new IllegalArgumentException("Unsupported Niagara particle storage type " + attribute.type());
        }
        current = new VectorDataSet(floats, integers, halves); destination = new VectorDataSet(floats, integers, halves);
        boolean selfRead = source.dependencies().stream().anyMatch(binding -> binding.mode().equals("Self")
                || binding.mode().equals("Other") && SourceNames.equal(source.sourceName(), binding.emitterName()));
        selfReadPrevious = selfRead ? new VectorDataSet(floats, integers, halves) : null;
        renderState = new NiagaraEmitterRenderState(source, renderers, localSpace, interfaces.materialTextures());
        spawnConstants = source.spawn().constantData(); updateConstants = source.update().constantData();
        spawnParameters = new NiagaraScriptNumericStore(source.spawn(), sources, sources == null);
        updateParameters = new NiagaraScriptNumericStore(source.update(), sources, sources == null);
        spawnParameters.restoreConstants(spawnConstants); updateParameters.restoreConstants(updateConstants);
        var stores = new ArrayList<NiagaraScriptNumericStore>(List.of(spawnParameters, updateParameters));
        for (var context : eventContexts) if (context.parameters != null) stores.add(context.parameters);
        parameterStores = List.copyOf(stores);
        if (sources == null) for (var store : parameterStores) store.bindDataSet(List.copyOf(variables.values()));
        defaultSpawnConstants = spawnConstants.clone(); defaultUpdateConstants = updateConstants.clone();
        spawnWords = ByteBuffer.wrap(spawnConstants).order(ByteOrder.LITTLE_ENDIAN);
        updateWords = ByteBuffer.wrap(updateConstants).order(ByteOrder.LITTLE_ENDIAN);
        interfaces.registerParticleEmitter(source.sourceName(), this);
        runtimeWords.putFloat(616, 1f);
        if (!platformAllowed) complete(4);
    }

    void bindParameters(NiagaraParameterStore instance, List<NiagaraDefinition.Variable> layout) {
        if (platformAllowed) for (var store : parameterStores) store.bindParameters(instance, layout);
    }
    void prepareParameters(NiagaraFrame frame, EffectHandle.Input input, VectorDataSet system) {
        if (terminal) return;
        spawnParameters.prepareSources(spawnConstants, input.parameters(), frame.collections, frame.originX(), frame.originY(), frame.originZ());
        updateParameters.prepareSources(updateConstants, input.parameters(), frame.collections, frame.originX(), frame.originY(), frame.originZ());
        for (var context : eventContexts) if (context.parameters != null)
            context.parameters.prepareSources(context.constants, input.parameters(), frame.collections, frame.originX(), frame.originY(), frame.originZ());
        if (shouldTick()) for (var store : parameterStores) store.tickRapidIteration();
    }
    void transferParameters(VectorDataSet system) { for (var store : parameterStores) store.transferDataSet(system); }
    void collectDebugParameters(String name, Map<String, NiagaraParameterStore> stores) {
        stores.put(name + " Spawn", spawnParameters.store());
        stores.put(name + " Update", updateParameters.store());
    }
    void registerObjectPropertyRequests(NiagaraInterfaces interfaces) {
        if (platformAllowed) for (var store : parameterStores)
            store.forEachObjectPropertyRequest(null, interfaces::registerObjectPropertyRequest);
    }
    void forEachStaticMeshFunction(BiConsumer<String, String> action) {
        if (platformAllowed) for (var store : parameterStores) store.forEachStaticMeshFunction(null, action);
    }
    void closeParameters() { for (var store : parameterStores) store.close(); }
    void forEachExecutionStore(boolean rebind, Consumer<NiagaraParameterStore> action) {
        if (!platformAllowed) return;
        for (var store : parameterStores) {
            store.refreshUserInterfaces(rebind);
            action.accept(store.store());
        }
    }
    void bindRendererObjects(NiagaraRendererParameterStores renderer, NiagaraParameterStore overrides) {
        rendererParameters = renderer; overrideParameters = overrides;
    }

    @Override public byte[] prepareRuntime(NiagaraFrame frame) {
        collectionValues = frame.collections;
        if (selfReadPrevious != null) {
            selfReadPrevious.copyFrom(current);
            selfReadPreviousIds = persistentIds == null ? new int[0] : persistentIds[0].copyIndex(selfReadPreviousIds);
            previousFrameSpawned = frameSpawned;
        }
        System.arraycopy(runtime, 0, oldRuntime, 0, runtime.length);
        System.arraycopy(frame.current, 0, runtime, 0, 608);
        runtimeWords.putInt(608, disabled() ? 0 : current.count());
        if (!disabled()) {
            runtimeWords.putInt(612, totalSpawned);
            runtimeWords.putFloat(616, spawnCountScale); runtimeWords.putFloat(620, age);
            runtimeWords.putInt(624, NiagaraSeed.offset(source.randomSeed(), sourceSeedOffset));
            runtimeWords.putInt(628, instanceSeed);
        }
        System.arraycopy(frame.previous, 0, oldRuntime, 0, 608);
        if (first) System.arraycopy(runtime, 0, oldRuntime, 0, runtime.length);
        return runtime;
    }

    public List<EmitterInstance.Snapshot> simulate(NiagaraFrame frame, VectorDataSet system,
                                            Map<String, NiagaraDefinition.Variable> variables,
                                            EffectHandle.Input input, double now, boolean execute) {
        return simulate(frame, system, variables, input, now, execute, true);
    }

    @Override public List<EmitterInstance.Snapshot> simulate(NiagaraFrame frame, VectorDataSet system,
                                            Map<String, NiagaraDefinition.Variable> variables,
                                            EffectHandle.Input input, double now, boolean execute, boolean pack) {
        parameterValues = input.parameters();
        boolean ticked = false;
        if (execute) {
            frameSpawned = 0;
            if (!terminal) {
                int state = executionState;
                if (frame.delta >= 1.0e-8f) {
                    if (state == 0 || current.count() > 0) {
                        if (!tickExecutionContexts()) {
                            resetParticleData();
                            complete(4);
                            releaseFailedBindingComponents = true;
                            return List.of();
                        }
                        if (first) for (var store : parameterStores) store.commit();
                        var renderer = rendererParameters;
                        var overrides = overrideParameters;
                        if (renderer != null && overrides != null) renderer.updateExternalObjects(overrides);
                        ticked = true;
                        tickCount++;
                        age += frame.delta;
                        runtimeWords.putFloat(620, age);
                        syncSpawnFrame(system, variables);
                        prepareLegacyParameters(system, variables);
                        boolean processEvents = state == 0 || state == 1 && current.count() != 0;
                        boolean allowBirths = spawningAllowed && frame.attachmentBirthsAllowed;
                        List<PendingEvent> pendingEvents = processEvents && spawningAllowed && eventContexts.length != 0
                                ? collectEvents(state) : List.of();
                        if (processEvents) allEvents.forEach(NiagaraEventData.Stream::begin);
                        if (state == 2) {
                            current.clear(); destination.clear();
                            if (persistentIds != null) persistentIds[0].reset();
                            executionState = 1;
                        } else if (state == 1) {
                            if (current.count() != 0) {
                                prepareIds(current.count());
                                updateExisting(frame, system, variables);
                                beginSpawnIds();
                            }
                            else {
                                current.clear(); destination.clear();
                                if (persistentIds != null) persistentIds[0].reset();
                            }
                        } else {
                            int existing = current.count();
                            long requested = allowBirths ? requestedBirths(system, pendingEvents) : 0;
                            var history = allocationHistory;
                            int estimate = history == null ? allocationEstimate : history.estimate();
                            long required = Math.max(estimate, existing + requested);
                            boolean overflow = rejectsSourceOverflow && required > DEFAULT_SOURCE_CPU_LIMIT;
                            if (overflow && !reportedSourceOverflow)
                                LOGGER.warn("Niagara emitter '{}' requested {} CPU particles above the source limit {}; skipping births",
                                        source.sourceName(), required, DEFAULT_SOURCE_CPU_LIMIT);
                            reportedSourceOverflow = overflow;
                            int allocation = overflow ? existing : (int)Math.min(maximumParticles, required);
                            if (overflow && history != null) history.clear();
                            else if (history != null && allocation > maximumAllocation) {
                                maximumAllocation = allocation;
                                history.record(allocationReporter, allocation);
                            }
                            int spawnCapacity = allowBirths ? (int)Math.min(requested, Math.max(0, allocation - existing)) : 0;
                            if (allocation > 0) destination.reserve(Math.addExact(allocation, 4));
                            prepareIds(allocation);
                            updateExisting(frame, system, variables);
                            beginSpawnIds();
                            int remaining = spawnInfos(system, variables, spawnCapacity);
                            int eventStart = current.count();
                            spawnEvents(pendingEvents, system, variables, remaining, frame.delta);
                            if (processEvents) allEvents.forEach(NiagaraEventData.Stream::publish);
                            processEvents = false;
                            handleSpawnEvents(pendingEvents, eventStart, system, variables);
                        }
                        if (processEvents) allEvents.forEach(NiagaraEventData.Stream::publish);
                        if (!pendingEvents.isEmpty()) handleEveryParticleEvents(pendingEvents, system, variables);
                        for (var context : eventContexts)
                            if (context.parameters != null) context.parameters.commit(context.constants);
                        spawnParameters.commit(spawnConstants); updateParameters.commit(updateConstants);
                    }
                    if (ticked) first = false;
                }
            }
        }
        if (ticked) emitterBounds.postTick(bounds(frame, input));
        if (!pack || terminal) return List.of();
        double emitterAge = renderState.hasVisuals() ? floating(system, variables, source.sourceName() + ".LoopedAge") : 0;
        var store = rendererParameters == null ? null : rendererParameters.renderer();
        return renderState.snapshots(current, frame.originX(), frame.originY(), frame.originZ(), system, variables, input, now, emitterAge, store, frame.engineTime);
    }

    @Override
    public List<EmitterInstance.Snapshot> packCullProxy(NiagaraEmitterRenderState renderer, VectorDataSet system,
            Map<String, NiagaraDefinition.Variable> variables, EffectHandle.Input input, double now) {
        if (terminal || !renderer.hasVisuals()) return List.of();
        double emitterAge = floating(system, variables, source.sourceName() + ".LoopedAge");
        var store = rendererParameters == null ? null : rendererParameters.renderer();
        return renderer.snapshots(current, 0, 0, 0, system, variables, input, now, emitterAge, store);
    }

    void readCache(NiagaraCachePlayer playback, int index, Sample sample, NiagaraFrame frame,
                   EffectHandle.Input input, Vec3 tile, VectorDataSet system) {
        if (!platformAllowed) return;
        if (selfReadPrevious != null) {
            selfReadPrevious.copyFrom(current);
            if (persistentIds != null) selfReadPreviousIds = persistentIds[0].copyIndex(selfReadPreviousIds);
        }
        var saved = playback.readEmitter(index, sample, frame.delta, input.pose(), tile, current);
        executionState = executionState(system);
        terminal = executionState == 3 || executionState == 4;
        if (terminal) renderState.destroyRenderState();
        totalSpawned = saved.totalSpawned();
        frameSpawned = previousFrameSpawned = 0;
        if (persistentIds != null) {
            var particles = saved.particles();
            int tag = particles.acquireTag();
            persistentIds[0].readCache(tag, playback.particleIndex(index, particles, current));
            if (Integer.compareUnsigned(tag, tickCount) > 0) tickCount = tag;
        }
        emitterBounds.readCache(saved.bounds());
    }

    EmitterFrame captureCache(Layout layout) {
        if (terminal) return new EmitterFrame(null, 0, NiagaraCacheRecorder.empty());
        return new EmitterFrame(emitterBounds.snapshot().bounds(), totalSpawned, NiagaraCacheRecorder.snapshot(current, layout,
                persistentIds == null ? 0 : persistentIds[0].tag(), persistentIds == null ? new int[0] : persistentIds[0].indexTable()));
    }

    private boolean tickExecutionContexts() {
        boolean bound = tickContext(spawnParameters, spawn) & tickContext(updateParameters, update);
        for (var context : eventContexts)
            if (context.parameters != null) bound &= tickContext(context.parameters, context.machine);
        return bound;
    }

    private boolean tickContext(NiagaraScriptNumericStore store, VectorMachine machine) {
        try {
            store.tick(machine);
            return true;
        } catch (VectorMachine.InterfaceBindingException failure) {
            LOGGER.error("Could not bind data interfaces for Niagara emitter '{}': {}", source.sourceName(), failure.getMessage());
            return false;
        }
    }

    private void updateExisting(NiagaraFrame frame, VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables) {
        destination.count(0);
        if (current.count() != 0) {
            parameters(source.update(), updateConstants, updateWords, system, variables,
                    new Invocation(current.count(), 0, 0, 0));
            if (updateEvents.isEmpty())
                update.execute(current.count(), new VectorDataSet[] {current}, new VectorDataSet[] {destination}, 0, updateConstants, null, persistentIds);
            else executeWithEvents(update, current.count(), new VectorDataSet[] {current}, destination, 0, updateEvents, updateConstants);
        }
        VectorDataSet swap = current; current = destination; destination = swap;
    }

    private int spawnInfos(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, int remaining) {
        for (var variable : spawnBindings) {
            if (remaining == 0) break;
            remaining -= spawnInfo(system, variables, variable, remaining);
        }
        for (var entry : channelSpawns) {
            int count = Math.min(entry.count(), remaining);
            if (count <= 0) break;
            spawn(system, variables, count, entry.interpolatedStart(), entry.interval(), entry.group());
            remaining -= count;
        }
        return remaining;
    }

    private long requestedBirths(VectorDataSet system, List<PendingEvent> events) {
        long total = 0;
        for (var variable : spawnBindings) total += Math.max(0, system.integer(variable.intStart(), 0));
        for (var spawn : channelSpawns) total += Math.max(0, spawn.count());
        for (var event : events) for (int count : event.spawnCounts()) total += count;
        return total;
    }

    void dataChannelSpawns(List<NiagaraGpuSpawnInfo.Entry> value) { channelSpawns = value; }
    void spawningAllowed(boolean allowed) { spawningAllowed = allowed; }
    boolean isActiveForDataChannel() { return !terminal && executionState == 0; }

    private int spawnInfo(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                          NiagaraDefinition.Variable variable, int remaining) {
        int count = Math.min(system.integer(variable.intStart(), 0), remaining);
        if (count <= 0) return 0;
        spawn(system, variables, count, system.floating(variable.floatStart(), 0),
                system.floating(variable.floatStart() + 1, 0), system.integer(variable.intStart() + 1, 0));
        return count;
    }

    private void spawn(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables, int count,
                       float interpolationStart, float interval, int group) {
        runtimeWords.putInt(612, totalSpawned);
        parameters(source.spawn(), spawnConstants, spawnWords, system, variables,
                new Invocation(count, interpolationStart, interval, group));
        totalSpawned += count;
        int before = current.count();
        if (spawnEvents.isEmpty())
            spawn.execute(count, new VectorDataSet[0], new VectorDataSet[] {current}, current.count(), spawnConstants, null, persistentIds);
        else executeWithEvents(spawn, count, new VectorDataSet[0], current, current.count(), spawnEvents, spawnConstants);
        frameSpawned += current.count() - before;
    }

    private void executeWithEvents(VectorMachine machine, int count, VectorDataSet[] inputs, VectorDataSet primary,
                                   int primaryOffset, List<NiagaraEventData.Stream> events, byte[] constants) {
        VectorDataSet[] outputs = new VectorDataSet[events.size() + 1];
        int[] outputOffsets = new int[outputs.length];
        outputs[0] = primary;
        outputOffsets[0] = primaryOffset;
        for (int i = 0; i < events.size(); i++) {
            outputs[i + 1] = events.get(i).destination();
            outputOffsets[i + 1] = outputs[i + 1].count();
        }
        machine.execute(count, inputs, new int[inputs.length], outputs, outputOffsets, constants, null, persistentIds);
    }

    private List<PendingEvent> collectEvents(int state) {
        List<PendingEvent> pending = new ArrayList<>();
        for (EventContext context : eventContexts) {
            VectorDataSet data = eventData.source(source, context.definition);
            if (data == null) continue;
            pending.add(new PendingEvent(context, data, NiagaraEventSpawns.collect(context.definition, data, state == 0)));
        }
        return pending;
    }

    private void spawnEvents(List<PendingEvent> pending, VectorDataSet system,
                             Map<String, NiagaraDefinition.Variable> variables, int remaining, float delta) {
        for (PendingEvent event : pending) {
            for (int i = 0; i < event.spawnCounts().length; i++) {
                int count = Math.min(event.spawnCounts()[i], remaining);
                if (count > 0) {
                    int before = current.count();
                    spawn(system, variables, count, delta * .5f, 0, 0);
                    int actual = current.count() - before;
                    event.spawnCounts()[i] = actual;
                    remaining -= actual;
                } else event.spawnCounts()[i] = 0;
            }
        }
    }

    private void handleSpawnEvents(List<PendingEvent> pending, int eventStart, VectorDataSet system,
                                   Map<String, NiagaraDefinition.Variable> variables) {
        boolean any = false;
        for (PendingEvent event : pending) for (int count : event.spawnCounts()) any |= count > 0;
        if (!any) return;
        destination.copyFrom(current);
        destination.count(eventStart);
        prepareEventParticleRead();
        for (PendingEvent event : pending) {
            EventContext context = event.context();
            for (int i = 0; i < event.spawnCounts().length; i++) {
                int count = event.spawnCounts()[i];
                if (count <= 0) continue;
                if (context.machine == null) throw new IllegalStateException("Missing Niagara event script for " + context.definition.sourceEventName());
                parameters(context.program, context.constants, context.words, system, variables,
                        new Invocation(count, 0, 0, 0), context.defaults, context.parameters);
                context.machine.execute(count, new VectorDataSet[] {current, event.data()}, new int[] {eventStart, i},
                        new VectorDataSet[] {destination}, new int[] {eventStart}, context.constants, null, persistentIds);
                eventStart = destination.count();
            }
        }
        VectorDataSet swap = current; current = destination; destination = swap;
    }

    private void handleEveryParticleEvents(List<PendingEvent> pending, VectorDataSet system,
                                           Map<String, NiagaraDefinition.Variable> variables) {
        for (PendingEvent event : pending) {
            EventContext context = event.context();
            if (!context.definition.executionMode().equals("EveryParticle") || context.machine == null) continue;
            for (int row = 0; row < event.data().count(); row++) {
                int count = current.count();
                if (count == 0) break;
                destination.count(0);
                parameters(context.program, context.constants, context.words, system, variables,
                        new Invocation(count, 0, 0, 0), context.defaults, context.parameters);
                prepareEventParticleRead();
                if (persistentIds != null) persistentIds[0].resetIdTable();
                context.machine.execute(count, new VectorDataSet[] {current, event.data()}, new int[] {0, row},
                        new VectorDataSet[] {destination}, new int[] {0}, context.constants, null, persistentIds);
                VectorDataSet swap = current; current = destination; destination = swap;
            }
        }
    }

    private void prepareIds(int capacity) {
        if (persistentIds != null) persistentIds[0].begin(capacity, tickCount);
    }

    private void beginSpawnIds() {
        // Update scripts can still query the previous step's acquired IDs. Spawn starts a new list.
        if (persistentIds != null) persistentIds[0].beginSpawning();
    }

    private void syncSpawnFrame(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables) {
        parameters(source.spawn(), spawnConstants, spawnWords, system, variables, null);
    }

    private void prepareLegacyParameters(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables) {
        if (first && !spawnParameters.modern()) spawnParameters.commit(spawnConstants);
        if (!updateParameters.modern() && source.update().interpolated()) {
            parameters(source.update(), updateConstants, updateWords, system, variables, null);
            if (first) updateParameters.commit(updateConstants);
        }
        for (var context : eventContexts) {
            if (context.program == null || context.parameters.modern() || !context.program.interpolated()) continue;
            parameters(context.program, context.constants, context.words, system, variables, null,
                    context.defaults, context.parameters);
            if (first) context.parameters.commit(context.constants);
        }
    }

    private void parameters(VectorProgram program, byte[] result, ByteBuffer values, VectorDataSet system,
                            Map<String, NiagaraDefinition.Variable> variables, Invocation invocation) {
        parameters(program, result, values, system, variables, invocation,
                program == source.spawn() ? defaultSpawnConstants : defaultUpdateConstants,
                program == source.spawn() ? spawnParameters : updateParameters);
    }

    private void parameters(VectorProgram program, byte[] result, ByteBuffer values, VectorDataSet system,
                            Map<String, NiagaraDefinition.Variable> variables, Invocation invocation,
                            byte[] defaults, NiagaraScriptNumericStore parameterStore) {
        System.arraycopy(runtime, 0, result, 0, 640);
        boolean modern = parameterStore.restoreConstants(result);
        if (!modern) NiagaraParameterValues.restore(program, result, defaults);
        for (var parameter : program.parameters()) {
            int offset = 640 + parameter.offset();
            String name = parameter.name();
            NiagaraDefinition.Variable variable = variables.get(name);
            if (variable == null || !parameter.sourceType().isEmpty() && !variable.sourceType().isEmpty()
                    && !parameter.sourceType().equals(variable.sourceType())) {
                variable = null;
                for (var candidate : variables.values()) {
                    if (SourceNames.equal(candidate.name(), name) && (parameter.sourceType().isEmpty()
                            || candidate.sourceType().isEmpty() || parameter.sourceType().equals(candidate.sourceType()))) {
                        variable = candidate;
                        break;
                    }
                }
            }
            if (SourceNames.equal(name, "Engine.ExecutionCount") && (!modern || SourceNames.equal(parameter.sourceType(), "/Script/Niagara.NiagaraInt32"))) {
                if (invocation != null) values.putInt(offset, invocation.count());
            } else if (modern ? parameterStore == spawnParameters && SourceNames.equal(name, spawnIntervalParameter)
                    && SourceNames.equal(parameter.sourceType(), "/Script/Niagara.NiagaraFloat") : name.endsWith(".SpawnInterval")) {
                if (invocation != null) values.putFloat(offset, invocation.interval());
            } else if (modern ? parameterStore == spawnParameters && SourceNames.equal(name, interpStartParameter)
                    && SourceNames.equal(parameter.sourceType(), "/Script/Niagara.NiagaraFloat") : name.endsWith(".InterpSpawnStartDt")) {
                if (invocation != null) values.putFloat(offset, invocation.start());
            } else if (modern ? parameterStore == spawnParameters && SourceNames.equal(name, spawnGroupParameter)
                    && SourceNames.equal(parameter.sourceType(), "/Script/Niagara.NiagaraInt32") : name.endsWith(".SpawnGroup")) {
                if (invocation != null) values.putInt(offset, invocation.group());
            } else if (!modern && variable != null) {
                if (variable.floatCount() > 0 && variable.intCount() == 0 && variable.halfCount() == 0
                        && parameter.bytes() == variable.floatCount() * Float.BYTES) {
                    for (int component = 0; component < variable.floatCount(); component++)
                        values.putFloat(offset + component * Float.BYTES, system.floating(variable.floatStart() + component, 0));
                } else if (variable.intCount() > 0 && variable.floatCount() == 0 && variable.halfCount() == 0
                        && parameter.bytes() == variable.intCount() * Integer.BYTES) {
                    for (int component = 0; component < variable.intCount(); component++)
                        values.putInt(offset + component * Integer.BYTES, system.integer(variable.intStart() + component, 0));
                } else if (variable.halfCount() > 0 && variable.floatCount() == 0 && variable.intCount() == 0
                        && parameter.bytes() == variable.halfCount() * Short.BYTES) {
                    for (int component = 0; component < variable.halfCount(); component++)
                        values.putShort(offset + component * Short.BYTES,
                                (short)system.component(2, variable.halfStart() + component)[0]);
                }
                else throw new IllegalArgumentException("Niagara parameter requires a typed component binding: " + name);
            }
        }
        if (!modern) {
            NiagaraParameterValues.write(program, result, parameterValues,
                    runtimeWords.getFloat(592) * NiagaraFrame.TILE_SIZE, runtimeWords.getFloat(596) * NiagaraFrame.TILE_SIZE,
                    runtimeWords.getFloat(600) * NiagaraFrame.TILE_SIZE);
            NiagaraParameterValues.writeCollections(program, result, collectionValues,
                    runtimeWords.getFloat(592) * NiagaraFrame.TILE_SIZE, runtimeWords.getFloat(596) * NiagaraFrame.TILE_SIZE,
                    runtimeWords.getFloat(600) * NiagaraFrame.TILE_SIZE);
        }
        if (program.interpolated()) {
            int offset = 640 + program.parameterBytes();
            System.arraycopy(oldRuntime, 0, result, offset, 640);
        }
        if (modern) parameterStore.capture(result);
    }

    private int executionState(VectorDataSet system) {
        int state = system.integer(executionStateBinding.intStart(), 0);
        if (state >= 0 && state < 5) return state;
        if (!reportedInvalidState) {
            LOGGER.error("Invalid Niagara execution state {} for emitter {}; using Inactive", state, source.sourceName());
            reportedInvalidState = true;
        }
        return 1;
    }

    void transferExecutionState(VectorDataSet system) {
        if (terminal) return;
        int state = executionState(system);
        if (state == 3 || state == 4) complete(state);
        else executionState = state;
    }

    void activateExecution() {
        if (!disabled()) {
            executionState = 0;
            terminal = false;
        }
    }

    void rebaseParticles(NiagaraFrame frame) {
        var data = current;
        for (var attribute : source.attributes())
            if (attribute.sourceType().equals(NiagaraDefinition.POSITION_TYPE) && attribute.count() == 3)
                for (int row = 0; row < data.count(); row++) {
                    int p = attribute.component();
                    data.floating(p, row, data.floating(p, row) + (float)frame.shiftX);
                    data.floating(p + 1, row, data.floating(p + 1, row) + (float)frame.shiftY);
                    data.floating(p + 2, row, data.floating(p + 2, row) + (float)frame.shiftZ);
                }
    }

    @Override public int count() { return current.count(); }

    @Nullable NiagaraBounds bounds(NiagaraFrame frame, EffectHandle.Input input) {
        if (source.boundsMode().equals("Fixed")) return source.fixedBounds();
        if (!source.boundsMode().equals("Dynamic") || current.count() == 0 || !renderState.hasVisuals()) return null;
        NiagaraBounds bounds = renderState.effectBounds(current);
        NiagaraBounds geometry = geometryBounds(input);
        if (geometry != null) bounds = bounds == null ? geometry : bounds.union(geometry);
        return bounds == null ? null : boundsToLocal(bounds, frame, input);
    }

    private @Nullable NiagaraBounds geometryBounds(EffectHandle.Input input) {
        NiagaraDefinition.Attribute position = attribute("Position");
        if (position == null || position.count() != 3 || position.type() != 0 && position.type() != 2) return null;
        NiagaraDefinition.Attribute spriteSize = attribute("SpriteSize"), ribbonWidth = attribute("RibbonWidth");
        boolean sprites = renderState.rendersParticles(EmitterDefinition.Renderer.SPRITE);
        boolean ribbons = renderState.rendersParticles(EmitterDefinition.Renderer.RIBBON);
        var meshBounds = renderState.meshBounds();
        if (!sprites && !ribbons && meshBounds.isEmpty()) return null;
        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        float maxSprite = spriteSize == null ? 50 : Float.NEGATIVE_INFINITY;
        float maxRibbon = ribbonWidth == null ? 50 : Float.NEGATIVE_INFINITY;
        NiagaraDefinition.Attribute meshScale = attribute("Scale");
        boolean hasScale = !meshBounds.isEmpty() && meshScale != null && meshScale.count() == 3
                && (meshScale.type() == 0 || meshScale.type() == 2);
        float maxScaleX = hasScale ? Float.NEGATIVE_INFINITY : 1;
        float maxScaleY = maxScaleX, maxScaleZ = maxScaleX;
        for (int row = 0; row < current.count(); row++) {
            float x = component(position, row, 0), y = component(position, row, 1), z = component(position, row, 2);
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            if (hasScale) {
                maxScaleX = Math.max(maxScaleX, component(meshScale, row, 0));
                maxScaleY = Math.max(maxScaleY, component(meshScale, row, 1));
                maxScaleZ = Math.max(maxScaleZ, component(meshScale, row, 2));
            }
            if (sprites && spriteSize != null && spriteSize.count() == 2 && (spriteSize.type() == 0 || spriteSize.type() == 2))
                maxSprite = Math.max(maxSprite, Math.max(component(spriteSize, row, 0), component(spriteSize, row, 1)));
            if (ribbons && ribbonWidth != null && ribbonWidth.count() == 1 && (ribbonWidth.type() == 0 || ribbonWidth.type() == 2))
                maxRibbon = Math.max(maxRibbon, component(ribbonWidth, row, 0));
        }
        if (sprites && !Float.isFinite(maxSprite)) maxSprite = 50;
        if (ribbons && !Float.isFinite(maxRibbon)) maxRibbon = 50;
        double radius = 1.e-4;
        if (sprites) radius = Math.max(radius, Math.abs(maxSprite) < 1.e-4f ? 1 : maxSprite);
        if (ribbons) radius = Math.max(radius, Math.abs(maxRibbon) < 1.e-4f ? 1 : maxRibbon);
        Vec3 expansion = new Vec3(radius, radius, radius);
        if (!meshBounds.isEmpty()) {
            var pose = input.pose();
            var component = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.source(pose, 0, 0, 0, true, new org.joml.Matrix4d());
            if (localSpace) com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverse(component, component);
            Vec3 particleScale = new Vec3(maxScaleX, maxScaleY, maxScaleZ);
            for (var mesh : meshBounds)
                expansion = NiagaraMeshBounds.maximum(expansion, mesh.expansion(particleScale, localSpace, component));
        }
        return new NiagaraBounds(minX - expansion.x, minY - expansion.y, minZ - expansion.z,
                maxX + expansion.x, maxY + expansion.y, maxZ + expansion.z);
    }

    private NiagaraBounds boundsToLocal(NiagaraBounds bounds, NiagaraFrame frame, EffectHandle.Input input) {
        if (localSpace) return bounds;
        var pose = input.pose();
        var inverse = com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.source(pose, frame.originX(), frame.originY(),
                frame.originZ(), true, new org.joml.Matrix4d());
        com.kltyton.lumenfx.client.transform.NiagaraComponentMatrices.inverse(inverse, inverse);
        double lx = Double.POSITIVE_INFINITY, ly = lx, lz = lx;
        double hx = Double.NEGATIVE_INFINITY, hy = hx, hz = hx;
        for (int corner = 0; corner < 8; corner++) {
            var v = new org.joml.Vector3d((corner & 1) == 0 ? bounds.minX() : bounds.maxX(),
                    (corner & 2) == 0 ? bounds.minY() : bounds.maxY(), (corner & 4) == 0 ? bounds.minZ() : bounds.maxZ());
            inverse.transformPosition(v);
            lx = Math.min(lx, v.x); ly = Math.min(ly, v.y); lz = Math.min(lz, v.z);
            hx = Math.max(hx, v.x); hy = Math.max(hy, v.y); hz = Math.max(hz, v.z);
        }
        return new NiagaraBounds(lx, ly, lz, hx, hy, hz);
    }

    private @Nullable Attribute attribute(String name) {
        for (var value : source.attributes())
            if (value.name().equals(name) || value.name().equals("Particles." + name)) return value;
        return null;
    }

    private float component(NiagaraDefinition.Attribute attribute, int row, int component) {
        return attribute.type() == 2 ? current.half(attribute.component() + component, row)
                : current.floating(attribute.component() + component, row);
    }
    @Override public void complete(int state) {
        if (terminal && disabled()) return;
        executionState = state;
        terminal = true;
        if (state != 4) resetParticleData();
    }
    private void resetParticleData() {
        current.clear();
        destination.clear();
        if (persistentIds != null) persistentIds[0].reset();
        allEvents.forEach(NiagaraEventData.Stream::reset);
    }
    @Override public boolean complete() { return terminal; }
    boolean disabled() { return executionState == 4; }
    @Override public boolean shouldTick() {
        return !terminal && (executionState == 0 || current.count() > 0);
    }
    @Override public boolean done() { return terminal || (executionState != 0 && current.count() == 0); }
    @Override public void postSystemTick(ClientLevel level, NiagaraFrame frame,
                        EffectHandle.Input input, VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                        double realTime) {
        if (releaseFailedBindingComponents) {
            destroyComponentRenderState();
            releaseFailedBindingComponents = false;
        }
        if (!terminal) renderState.postTick(level, current, frameSpawned, frame.originX(), frame.originY(), frame.originZ(),
                input, system, variables, realTime, frame.platform.componentPoolInactiveSeconds(), frame.ownerVisibility, frame.componentHost,
                rendererParameters == null ? null : rendererParameters.renderer());
    }
    @Override public void closeComponents() { renderState.close(); }
    @Override public void destroyComponentRenderState() { renderState.destroyRenderState(); }
    @Override public void updateComponentAttachments(EffectHandle.Pose pose) { renderState.updateAttachment(pose); }
    @Override public void collectPointLights(EffectDefinition effect, List<ScenePointLight> output) {
        renderState.collectPointLights(effect, output);
    }
    @Override public void collectDirectionalLights(EffectDefinition effect, List<SceneDirectionalLight> output) {
        renderState.collectDirectionalLights(effect, output);
    }
    @Override public void finishComponentFrame(boolean paused) { renderState.finishFrame(paused); }
    @Override public void collectDecals(EffectDefinition effect, List<ParticleDecal> output) {
        renderState.collectDecals(effect, output);
    }
    @Override public void collectComponentMeshes(EffectDefinition effect, @Nullable UUID viewActor,
                                                 List<StaticMeshComponentDraw> output) {
        renderState.collectMeshes(effect, viewActor, output);
    }
    void sourceSeedOffset(int offset) { sourceSeedOffset = offset; }

    @Override public void reset() {
        resetSimulation(true);
    }

    void resetSimulation(boolean killExisting) {
        if (!platformAllowed || !killExisting && disabled()) return;
        renderState.reset();
        releaseFailedBindingComponents = false;
        reportedSourceOverflow = false;
        age = 0; tickCount = 0; first = true; terminal = false; executionState = 0;
        if (killExisting) {
            current.clear();
            destination.clear();
            totalSpawned = 0;
            if (persistentIds != null) persistentIds[0].reset();
            allEvents.forEach(NiagaraEventData.Stream::reset);
        }
        if (selfReadPrevious != null) selfReadPrevious.clear();
        selfReadPreviousIds = new int[0];
        eventReadIds = new int[0];
        frameSpawned = previousFrameSpawned = 0;
        for (EventContext context : eventContexts) context.reset();
        instanceSeed = NiagaraSeed.next();
        System.arraycopy(source.spawn().constantData(), 0, spawnConstants, 0, spawnConstants.length);
        System.arraycopy(source.update().constantData(), 0, updateConstants, 0, updateConstants.length);
        spawnParameters.reset(); updateParameters.reset();
        spawnParameters.restoreConstants(spawnConstants); updateParameters.restoreConstants(updateConstants);
    }
    @Override public VectorDataSet particleReadData(boolean self) {
        if (self && selfReadPrevious == null) throw new IllegalStateException("Missing Niagara self-read snapshot");
        return self ? selfReadPrevious : current;
    }
    @Override public int[] particleReadIndex(boolean self) {
        return self ? selfReadPreviousIds : persistentIds == null ? new int[0] : persistentIds[0].indexTable();
    }
    private void prepareEventParticleRead() {
        if (persistentIds != null) eventReadIds = persistentIds[0].copyIndex(eventReadIds);
    }
    @Override public int[] eventParticleReadIndex() { return eventReadIds; }
    @Override public int spawnedParticleCount() { return persistentIds == null ? 0 : persistentIds[0].spawnedCount(); }
    @Override public int spawnedParticleId(int index) { return persistentIds[0].spawnedAt(index); }
    @Override public int spawnedParticleTag() { return persistentIds == null ? 0 : persistentIds[0].tag(); }
    @Override public int frameSpawned(boolean self) { return self ? previousFrameSpawned : frameSpawned; }
    @Override public boolean particleReadLocalSpace() { return localSpace; }
    @Override public String particleReadName() { return source.sourceName(); }
    @Override public @Nullable Attribute particleReadAttribute(String name, String type) {
        return NiagaraParticleReadTypes.resolve(source.attributes(), name, type);
    }

    @Override public int particleIndex(VectorDataSet data, int[] table, int id, int acquireTag) {
        if (id < 0 || id >= table.length) return -1;
        int row = table[id];
        var attribute = particleReadAttribute("ID", "ID");
        if (row < 0 || row >= data.count() || attribute == null) return -1;
        return data.integer(attribute.component(), row) == id && data.integer(attribute.component() + 1, row) == acquireTag ? row : -1;
    }
    static int integer(VectorDataSet data, Map<String, NiagaraDefinition.Variable> variables, String name) {
        var variable = required(variables, name);
        return data.integer(variable.intStart(), 0);
    }
    static float floating(VectorDataSet data, Map<String, NiagaraDefinition.Variable> variables, String name) {
        var variable = required(variables, name);
        return data.floating(variable.floatStart(), 0);
    }
    private static NiagaraDefinition.Variable required(Map<String, NiagaraDefinition.Variable> variables, String name) {
        var value = variables.get(name);
        if (value == null) throw new IllegalArgumentException("Missing Niagara system output " + name);
        return value;
    }
}
