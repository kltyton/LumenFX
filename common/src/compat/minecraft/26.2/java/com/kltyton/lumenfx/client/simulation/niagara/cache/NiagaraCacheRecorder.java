package com.kltyton.lumenfx.client.simulation.niagara.cache;

import com.kltyton.lumenfx.api.client.NiagaraCacheCapture;
import com.kltyton.lumenfx.api.client.NiagaraCacheCapture.Options;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.DisabledSimulation;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.GpuSimulation;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.StatelessSimulation;
import com.kltyton.lumenfx.effect.niagara.NiagaraSystemState;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheDebugData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.VolumeTargets;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Buffer;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.CaptureMode;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.EmitterFrame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Field;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Frame;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Layout;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Range;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache.Transform;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheInterfaceData.Key;
import com.kltyton.lumenfx.client.simulation.niagara.cache.NiagaraCacheInterfaceRecorder.Value;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.scalability.NiagaraPlatformEnvironment.VolumeCacheStorage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/** Records completed datasets and resolves particle correspondence once all GPU copies have arrived. */
public final class NiagaraCacheRecorder {
    private static final Range ABSENT = new Range(-1, 0);
    private static final Buffer EMPTY = new Buffer(0, 0, new int[0], new int[0], new short[0], new int[0], new int[0]);
    private final String sourceSystem;
    private final NiagaraCacheCapture request;
    private final @Nullable NiagaraSimulationCache append;
    private final VolumeCacheStorage volumeCacheStorage;
    private final Layout system;
    private final List<Layout> emitters;
    private final NiagaraCacheAttributeSelection attributes;
    private final NiagaraCacheAttributeSelection.Projection systemProjection;
    private final List<NiagaraCacheAttributeSelection.Projection> emitterProjections;
    private final NiagaraSystemState systemState;
    private final List<NiagaraCacheDebugData.Parameter> staticDebugValues;
    private final @Nullable NiagaraCacheDebugCapture debugCapture;
    private record RecordingFrame(Frame particles, Map<Key, Value> interfaces,
                                  NiagaraCacheDebugData.@Nullable Frame debug) { }
    private final List<CompletableFuture<RecordingFrame>> frames = new ArrayList<>();
    private float start, end;
    private int previousTick = -1, observed;
    private int lastRenderTick = -1, idleFrames;
    private boolean finished;

    public NiagaraCacheRecorder(NiagaraDefinition definition, NiagaraCacheCapture request) {
        this(definition, request, VolumeCacheStorage.SPARSE);
    }

    public NiagaraCacheRecorder(NiagaraDefinition definition, NiagaraCacheCapture request, VolumeCacheStorage volumeCacheStorage) {
        this.request = request;
        sourceSystem = Objects.requireNonNull(definition.sourceSystem(), "Niagara cache capture requires the original system asset identity");
        var existing = request.appendSource();
        append = existing != null && SourceNames.equal(existing.sourceSystem(), sourceSystem) ? existing : null;
        // BeginAppend reuses existing DI storage, so the begin-write switch applies only to new storage.
        this.volumeCacheStorage = volumeCacheStorage == VolumeCacheStorage.DISABLED && append != null
                && append.interfaces().stream().anyMatch(data -> data.frames() instanceof VolumeTargets)
                ? VolumeCacheStorage.SPARSE : volumeCacheStorage;
        var options = request.options();
        systemState = definition.timing().systemState();
        var debugParameters = definition.debugParameters();
        staticDebugValues = debugParameters == null ? List.of() : debugParameters.staticValues();
        debugCapture = append == null && options.includeDebugData() ? new NiagaraCacheDebugCapture() : null;
        var variables = new ArrayList<Field>();
        for (var field : definition.systemLayout()) {
            boolean local = definition.emitters().stream().anyMatch(emitter -> emitter.localSpace()
                    && SourceNames.canonical(field.name()).startsWith(SourceNames.canonical(emitter.sourceName()) + "."));
            variables.add(new Field(field.name(), field.sourceType(), new Range(field.floatStart(), field.floatCount()),
                    new Range(field.intStart(), field.intCount()), new Range(field.halfStart(), field.halfCount()),
                    options.rebase() && !local && SourceNames.equal(field.sourceType(), NiagaraDefinition.POSITION_TYPE), false));
        }
        system = layout(sourceSystem, false, false, variables, options);
        if (append != null && append.emitterLayouts().size() != definition.emitters().size())
            throw new IllegalArgumentException("Niagara cache append requires the original emitter list");
        var layouts = new ArrayList<Layout>(definition.emitters().size());
        for (int index = 0; index < definition.emitters().size(); index++) {
            boolean captureAnalytic = append == null ? options.includeDebugData() : !append.emitterLayouts().get(index).name().equals("none");
            layouts.add(emitterLayout(definition.emitters().get(index), options, captureAnalytic));
        }
        emitters = List.copyOf(layouts);
        attributes = new NiagaraCacheAttributeSelection(definition, options);
        systemProjection = append == null ? attributes.layout(system, null)
                : NiagaraCacheAttributeSelection.Projection.reuse(system, append.systemLayout());
        var projections = new ArrayList<NiagaraCacheAttributeSelection.Projection>(emitters.size());
        for (int index = 0; index < emitters.size(); index++) {
            var raw = emitters.get(index);
            projections.add(append == null ? attributes.layout(raw, definition.emitters().get(index))
                    : NiagaraCacheAttributeSelection.Projection.reuse(raw, append.emitterLayouts().get(index)));
        }
        emitterProjections = List.copyOf(projections);
    }

    public NiagaraCacheCapture request() { return request; }
    public VolumeCacheStorage volumeCacheStorage() { return volumeCacheStorage; }
    public boolean finished() { return finished; }
    public Layout emitterLayout(int index) { return emitters.get(index); }
    public boolean capturesInterface(Key key) {
        return append == null ? attributes.capturesInterface(key.name()) : append.interfaces().stream().anyMatch(data -> data.key().equals(key));
    }
    public boolean capturesDebug() { return debugCapture != null; }
    public NiagaraCacheDebugData.Frame snapshotDebug(Map<String, NiagaraParameterStore> stores, NiagaraParameterStoreSources sources) {
        return Objects.requireNonNull(debugCapture).snapshot(stores, sources, staticDebugValues);
    }
    public static Buffer empty() { return EMPTY; }

    public boolean observe(int tick) {
        if (finished || request.result().isDone()) return false;
        if (request.finishing()) { finish(); return false; }
        if (tick == previousTick) return false;
        if (tick < previousTick) {
            finished = true;
            request.result().completeExceptionally(new IllegalStateException("Niagara system reset during cache capture"));
            return false;
        }
        previousTick = tick;
        return request.currentFrame() || observed++ % request.options().captureRate() == 0;
    }

    public void finishRenderOpportunity() {
        int timeout = request.options().timeoutFrames();
        if (finished || request.result().isDone() || request.currentFrame() || timeout == 0) return;
        if (lastRenderTick != previousTick) {
            lastRenderTick = previousTick;
            idleFrames = 0;
        } else if (idleFrames++ > timeout) finish();
    }

    public void append(float age, int tick, Transform transform, @Nullable NiagaraBounds bounds, VectorDataSet systems,
                       List<CompletableFuture<EmitterFrame>> particles, Map<Key, CompletableFuture<Value>> interfaces,
                       NiagaraCacheDebugData.@Nullable Frame debug) {
        if (finished || request.result().isDone()) return;
        if (particles.size() != emitters.size()) throw new IllegalArgumentException("Niagara capture lacks emitter datasets");
        if (frames.isEmpty()) start = age;
        end = age;
        float rounded = NiagaraSimulationCache.quantizedAge(age);
        var row = snapshot(systems, system, 0, new int[0]);
        var pending = List.copyOf(particles);
        var remaining = new ArrayList<CompletableFuture<?>>(particles.size() + interfaces.size());
        remaining.addAll(pending);
        remaining.addAll(interfaces.values());
        frames.add(CompletableFuture.allOf(remaining.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            var data = new LinkedHashMap<Key, Value>();
            interfaces.forEach((key, value) -> data.put(key, value.join()));
            return new RecordingFrame(new Frame(rounded, tick, transform, bounds, row, pending.stream().map(CompletableFuture::join).toList()),
                    Map.copyOf(data), debug);
        }));
        int limit = request.currentFrame() ? 1 : request.options().frameLimit();
        if (limit > 0 && frames.size() == limit) finish();
    }

    public void finish() {
        if (finished || request.result().isDone()) return;
        finished = true;
        if (frames.isEmpty()) {
            request.result().completeExceptionally(new IllegalStateException("No completed Niagara simulation frame was captured"));
            return;
        }
        var pending = List.copyOf(frames);
        frames.clear();
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenApplyAsync(ignored -> {
            var captures = pending.stream().map(CompletableFuture::join).toList();
            var debug = debugCapture == null ? null : new NiagaraCacheDebugData(systemState,
                    captures.stream().map(frame -> Objects.requireNonNull(frame.debug())).toList());
            CaptureMode mode = append == null ? switch (request.options().attributes().mode()) {
                case ALL -> CaptureMode.ALL;
                case RENDERING_ONLY -> CaptureMode.RENDERING_ONLY;
                case EXPLICIT -> CaptureMode.EXPLICIT;
            } : append.captureMode();
            var captured = new NiagaraSimulationCache(sourceSystem, start, end - start, interpolation(),
                    append == null ? request.options().extrapolate() : append.extrapolation(), systemProjection.output(),
                    emitterProjections.stream().map(NiagaraCacheAttributeSelection.Projection::output).toList(),
                    correspondence(captures.stream().map(RecordingFrame::particles).toList()).stream().map(this::project).toList(),
                    NiagaraCacheInterfaceRecorder.collect(captures.stream().map(RecordingFrame::interfaces).toList()), debug, mode);
            return append == null ? captured : NiagaraCacheAppend.merge(append, captured);
        }, Util.backgroundExecutor()).whenComplete((cache, failure) -> {
            if (failure == null) request.result().complete(cache);
            else request.result().completeExceptionally(failure);
        });
    }

    private Frame project(Frame frame) {
        var particles = new ArrayList<EmitterFrame>(emitterProjections.size());
        for (int index = 0; index < emitterProjections.size(); index++) {
            var source = frame.emitters().get(index);
            particles.add(new EmitterFrame(source.bounds(), source.totalSpawned(), emitterProjections.get(index).project(source.particles())));
        }
        return new Frame(frame.age(), frame.tick(), frame.transform(), frame.bounds(), systemProjection.project(frame.system()), particles);
    }

    public static Buffer snapshot(VectorDataSet source, Layout layout, int tag, int[] ids) {
        int count = source.count();
        int[] floats = new int[Math.multiplyExact(count, layout.floatCount())];
        int[] integers = new int[Math.multiplyExact(count, layout.integerCount())];
        short[] halves = new short[Math.multiplyExact(count, layout.halfCount())];
        for (int column = 0; column < layout.floatCount(); column++)
            System.arraycopy(source.component(0, column), 0, floats, column * count, count);
        for (int column = 0; column < layout.integerCount(); column++)
            System.arraycopy(source.component(1, column), 0, integers, column * count, count);
        for (int column = 0; column < layout.halfCount(); column++) for (int row = 0; row < count; row++)
            halves[column * count + row] = (short)source.component(2, column)[row];
        var captureIds = layout.captureIds(integers, count);
        return new Buffer(count, tag, floats, integers, halves, ids, new int[0], captureIds);
    }

    private static Layout emitterLayout(NiagaraDefinition.Emitter emitter, Options options, boolean captureAnalytic) {
        if (emitter.simulation() instanceof DisabledSimulation || emitter.simulation() instanceof StatelessSimulation && !captureAnalytic)
            return new Layout("None", false, false, 0, 0, 0, -1, List.of());
        boolean local = emitter.localSpace() || emitter.simulation() instanceof StatelessSimulation;
        boolean gpu = emitter.simulation() instanceof GpuSimulation
                || emitter.simulation() instanceof StatelessSimulation analytic && analytic.target().equals("GPUComputeSim");
        var variables = new LinkedHashMap<List<String>, Field>();
        boolean uniqueIds = emitter.attributes().stream().anyMatch(attribute -> attribute.type() == 1 && attribute.count() == 1
                && SourceNames.equal(attribute.name(), "UniqueID") && SourceNames.equal(attribute.sourceType(), "/Script/Niagara.NiagaraInt32"));
        for (var attribute : emitter.attributes()) {
            String name = SourceNames.canonical(attribute.name()), type = SourceNames.canonical(attribute.sourceType());
            var key = List.of(name, type);
            var range = new Range(attribute.component(), attribute.count());
            boolean position = SourceNames.equal(type, NiagaraDefinition.POSITION_TYPE);
            var prior = variables.getOrDefault(key, new Field(name, type, ABSENT, ABSENT, ABSENT, options.rebase() && !local && position,
                    options.interpolate() && uniqueIds && (position || interpolates(type, options.interpolateAllTypes()))));
            variables.put(key, new Field(name, type, attribute.type() == 0 ? range : prior.floats(),
                    attribute.type() == 1 ? range : prior.integers(), attribute.type() == 2 ? range : prior.halves(),
                    prior.rebase(), prior.interpolate()));
        }
        return layout(emitter.name(), local, gpu, List.copyOf(variables.values()), options);
    }

    private static boolean interpolates(String type, boolean all) {
        return type.equals("/script/coreuobject.quat4f") || all && switch (type) {
            case "/script/coreuobject.vector2f", "/script/coreuobject.vector3f", "/script/coreuobject.vector4f",
                 "/script/coreuobject.linearcolor", "/script/niagara.niagarafloat" -> true;
            default -> false;
        };
    }

    private static Layout layout(String name, boolean local, boolean gpu, List<Field> fields, Options options) {
        int floats = 0, integers = 0, halves = 0, velocity = -1;
        for (var field : fields) {
            floats = Math.max(floats, field.floats().start() + field.floats().count());
            integers = Math.max(integers, field.integers().start() + field.integers().count());
            halves = Math.max(halves, field.halves().start() + field.halves().count());
            if (options.extrapolate() && field.name().equals("velocity") && field.floats().count() == 3) velocity = field.floats().start();
        }
        return new Layout(name, local, gpu, floats, integers, halves, velocity, fields);
    }

    private List<Frame> correspondence(List<Frame> frames) {
        if (!interpolation()) return frames;
        var result = new ArrayList<Frame>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            Frame first = frames.get(index), next = index + 1 < frames.size() ? frames.get(index + 1) : null;
            var data = new ArrayList<EmitterFrame>(emitters.size());
            for (int emitter = 0; emitter < emitters.size(); emitter++) {
                var stored = first.emitters().get(emitter);
                var target = next == null ? null : next.emitters().get(emitter).particles();
                data.add(new EmitterFrame(stored.bounds(), stored.totalSpawned(),
                        match(emitters.get(emitter), stored.particles(), target)));
            }
            result.add(new Frame(first.age(), first.tick(), first.transform(), first.bounds(),
                    match(system, first.system(), next == null ? null : next.system()), data));
        }
        return List.copyOf(result);
    }

    private boolean interpolation() { return append == null ? request.options().interpolate() : append.interpolation(); }

    static Buffer match(Layout layout, Buffer first, @Nullable Buffer next) {
        int[] firstIds = captureIds(layout, first);
        if (firstIds.length == 0) {
            if (first.nextRowCount() == 0) return first;
            int[] unmapped = new int[first.count()];
            Arrays.fill(unmapped, -1);
            return first.withNextRows(unmapped);
        }
        int[] nextIds = next == null ? new int[0] : captureIds(layout, next);
        var indices = new HashMap<Integer, Integer>();
        for (int row = 0; row < nextIds.length; row++) indices.put(nextIds[row], row);
        var rows = new int[first.count()];
        for (int row = 0; row < firstIds.length; row++) rows[row] = indices.getOrDefault(firstIds[row], -1);
        return first.withCaptureIds(firstIds).withNextRows(rows);
    }

    private static int[] captureIds(Layout layout, Buffer data) {
        var saved = data.captureIds();
        if (saved.length != 0 || data.count() == 0) return saved;
        int id = layout.uniqueIdComponent();
        if (id < 0) return saved;
        var values = new int[data.count()];
        for (int row = 0; row < data.count(); row++) values[row] = data.word(1, id, row);
        return values;
    }
}
