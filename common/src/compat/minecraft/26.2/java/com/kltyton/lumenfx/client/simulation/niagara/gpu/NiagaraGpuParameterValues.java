package com.kltyton.lumenfx.client.simulation.niagara.gpu;

import com.google.gson.JsonObject;
import com.kltyton.lumenfx.api.client.EffectHandle;
import com.kltyton.lumenfx.client.simulation.niagara.NiagaraParameterValues;
import com.kltyton.lumenfx.client.simulation.niagara.VectorDataSet;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraCollectionJson;
import com.kltyton.lumenfx.client.resource.niagara.ScriptParameterStoreJson;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraPositionParameters;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStore;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraParameterStoreSources;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.NiagaraCollectionBinding;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader.Request;
import java.util.Collections;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** Per-emitter external constants and their previous-frame values. */
public final class NiagaraGpuParameterValues implements AutoCloseable {
    record Binding(String name, String sourceType, String symbol, @Nullable byte[] defaults,
                   @Nullable NiagaraCollectionBinding collection, @Nullable Key key) { }
    private record InterfaceBinding(String binding, Key key, String sourceClass, boolean dynamic) { }
    public static final class InterfaceBindingException extends IllegalStateException {
        InterfaceBindingException(String message) { super(message); }
    }
    public static final class Snapshot {
        private final Map<String, byte[]> current, previous, byName;
        private final Map<String, String> dataInterfaces;
        private final double originX, originY, originZ;
        private Snapshot(Map<String, byte[]> current, Map<String, byte[]> previous, Map<String, byte[]> byName,
                         double originX, double originY, double originZ, Map<String, String> dataInterfaces) {
            this.current = copy(current);
            this.previous = copy(previous);
            this.byName = copyNames(byName);
            this.originX = originX; this.originY = originY; this.originZ = originZ;
            this.dataInterfaces = Map.copyOf(dataInterfaces);
        }
        public Map<String, String> dataInterfaces() { return dataInterfaces; }
        public @Nullable byte[] value(String symbol) {
            boolean old = symbol.startsWith("PREV_");
            byte[] value = (old ? previous : current).get(old ? symbol.substring(5) : symbol);
            return value == null ? null : value.clone();
        }

        public @Nullable byte[] parameter(String name) {
            byte[] value = byName.get(name);
            return value == null ? null : value.clone();
        }
    }

    private final List<Binding> bindings;
    private final List<InterfaceBinding> interfaceBindings;
    private record ObjectReaderBinding(String original, @Nullable Key key, List<Request> requests) { }
    private final List<ObjectReaderBinding> objectReaders;
    private final @Nullable List<String> shaderInterfaceClasses;
    private final @Nullable NiagaraGpuCombinedParameters combined;
    private final @Nullable NiagaraParameterStoreSources ownedSources;
    private boolean sourcesPrepared;
    private long validatedInterfaceRevision = Long.MIN_VALUE;
    private @Nullable Map<String, byte[]> previous;
    private double previousOriginX, previousOriginY, previousOriginZ;

    public NiagaraGpuParameterValues(JsonObject program) {
        this(program, null, new LinkedHashMap<>());
    }
    public NiagaraGpuParameterValues(JsonObject program, @Nullable List<NiagaraDefinition.Variable> layout, Map<String, ObjectValue> objects) {
        this(program, null, layout, objects);
    }
    public NiagaraGpuParameterValues(JsonObject program, @Nullable ScriptParameterStoreDefinition definition,
                                     @Nullable List<NiagaraDefinition.Variable> layout, Map<String, ObjectValue> objects) {
        this(program, definition, layout, new NiagaraParameterStoreSources(objects), true);
    }
    public NiagaraGpuParameterValues(JsonObject program, @Nullable ScriptParameterStoreDefinition definition,
                                     @Nullable List<NiagaraDefinition.Variable> layout, NiagaraParameterStoreSources sources) {
        this(program, definition, layout, sources, false);
    }
    private NiagaraGpuParameterValues(JsonObject program, @Nullable ScriptParameterStoreDefinition definition,
                                     @Nullable List<NiagaraDefinition.Variable> layout, NiagaraParameterStoreSources sources, boolean ownsSources) {
        ownedSources = ownsSources ? sources : null;
        if (definition == null && program.has("execution_store")) definition = ScriptParameterStoreJson.read(program.getAsJsonObject("execution_store"));
        combined = definition == null ? null : new NiagaraGpuCombinedParameters(definition, layout, sources);
        shaderInterfaceClasses = combined == null ? null : shaderInterfaceClasses(program);
        var interfaces = new ArrayList<InterfaceBinding>();
        if (combined != null) for (var item : program.getAsJsonArray("interfaces")) {
            JsonObject value = item.getAsJsonObject();
            String type = value.get("class").getAsString();
            boolean externalCurve = switch (type) {
                case "NiagaraDataInterfaceCurve", "NiagaraDataInterfaceVector2DCurve", "NiagaraDataInterfaceVectorCurve",
                        "NiagaraDataInterfaceColorCurve", "NiagaraDataInterfaceVector4Curve" ->
                        value.has("curve_use_unique_buffer") && value.get("curve_use_unique_buffer").getAsBoolean();
                default -> false;
            };
            if (!value.has("interface_key")) continue;
            boolean dynamic = type.equals("NiagaraDataInterfaceTexture") || type.equals("NiagaraDataInterfaceRenderTarget2D")
                    || type.equals("NiagaraDataInterfaceRenderTarget2DArray")
                    || type.equals("NiagaraDataInterfaceRenderTargetVolume")
                    || type.equals("NiagaraDataInterfaceRenderTargetCube")
                    || type.equals("NiagaraDataInterface2DArrayTexture")
                    || type.equals("NiagaraDataInterfaceCubeTexture")
                    || type.equals("NiagaraDataInterfaceVolumeTexture")
                    || type.equals("NiagaraDataInterfaceVolumeCache")
                    || type.equals("NiagaraDataInterfacePhysicsAsset")
                    || type.equals("NiagaraDataInterfaceRigidMeshCollisionQuery")
                    || type.equals("NiagaraDataInterfaceGeometryCollection")
                    || type.equals("NiagaraDataInterfaceChaosDestruction")
                    || type.equals("NiagaraDataInterfaceHairStrands")
                    || type.equals("NiagaraDataInterfacePhysicsField")
                    || type.equals("NiagaraDataInterfaceVelocityGrid")
                    || type.equals("NiagaraDataInterfacePressureGrid")
                    || type.equals("NiagaraDataInterfaceSceneCapture2D")
                    || type.equals("NiagaraDataInterfaceLandscape")
                    || type.equals("NiagaraDataInterfaceVirtualTexture") || type.equals("NiagaraDataInterfaceVirtualTextureSample")
                    || type.startsWith("NiagaraDataInterfaceArray") || type.equals("NiagaraDataInterfaceSimpleCounter")
                    || type.equals("NiagaraDataInterfacePlatformSet") || type.equals("NiagaraDataInterfaceCurlNoise")
                    || type.equals("NiagaraDataInterfaceVectorField") || type.equals("NiagaraDataInterfaceCamera")
                    || type.equals("NiagaraDataInterfaceParticleRead") || type.equals("NiagaraDataInterfaceEmitterProperties")
                    || type.equals("NiagaraDataInterfaceUObjectPropertyReader")
                    || type.equals("NiagaraDataInterfaceActorComponent")
                    || type.equals("NiagaraDataInterfaceAsyncGpuTrace")
                    || type.equals("NiagaraDataInterfaceStaticMesh") || type.equals("NiagaraDataInterfaceSkeletalMesh")
                    || type.equals("NiagaraDataInterfaceExport")
                    || type.equals("NiagaraDataInterfaceDataChannelRead") || type.equals("NiagaraDataInterfaceDataChannelWrite")
                    || type.equals("NiagaraDataInterfaceAudioSpectrum") || type.equals("NiagaraDataInterfaceAudioOscilloscope")
                    || type.equals("NiagaraDataInterfaceSimCacheReader") || type.equals("NiagaraDataInterfaceSparseVolumeTexture")
                    || type.equals("NiagaraDataInterfaceDynamicMesh") || type.equals("NiagaraDataInterfaceMemoryBuffer")
                    || type.equals("NiagaraDataInterfaceNeighborGrid3D") || type.equals("NiagaraDataInterfaceRasterizationGrid3D")
                    || type.equals("NiagaraDataInterfaceConsoleVariable")
                    || value.has("grid2d_collection") || value.has("grid3d_collection") || value.has("data_table")
                    || value.has("int_render_target_2d") || value.has("velocity_grid") || value.has("neighbor_query")
                    || value.has("socket_reader") || value.has("spline") || value.has("property_interface_outputs")
                    || value.has("gaussian_splat_reader") || value.has("gaussian_splat_writer")
                    || type.equals("NiagaraDataInterfaceGaussianSplatReader") || type.equals("NiagaraDataInterfaceGaussianSplatWriter")
                    || type.equals("NiagaraDataInterfaceSpriteRendererInfo") || type.equals("NiagaraDataInterfaceMeshRendererInfo") || externalCurve;
            interfaces.add(new InterfaceBinding(value.get("compile_name").getAsString(),
                    ScriptParameterStoreJson.key(value.getAsJsonObject("interface_key")), type, dynamic));
        }
        interfaceBindings = List.copyOf(interfaces);
        var readers = new ArrayList<ObjectReaderBinding>();
        for (var item : program.getAsJsonArray("interfaces")) {
            var value = item.getAsJsonObject();
            if (!value.has("property_reader_functions")) continue;
            var requests = new ArrayList<Request>();
            for (var element : value.getAsJsonArray("property_reader_functions")) {
                var function = element.getAsJsonObject();
                requests.add(Request.read(function.get("name").getAsString(), function));
            }
            readers.add(new ObjectReaderBinding(value.get("interface_id").getAsString(),
                    value.has("interface_key") ? ScriptParameterStoreJson.key(value.getAsJsonObject("interface_key")) : null,
                    List.copyOf(requests)));
        }
        objectReaders = List.copyOf(readers);
        List<Binding> result = new ArrayList<>();
        for (var item : program.getAsJsonArray("parameter_bindings")) {
            JsonObject value = item.getAsJsonObject();
            String type = value.get("source_type").getAsString();
            if (combined != null && value.get("underlying").getAsInt() == 1) continue;
            if (combined == null && !value.has("shader_symbol") && !type.endsWith(".NiagaraInt32") && !type.endsWith(".NiagaraBool")) continue;
            byte[] defaults = value.has("data") && !value.get("data").isJsonNull()
                    ? encodeDefault(type, Base64.getDecoder().decode(value.get("data").getAsString())) : null;
            result.add(new Binding(value.get("name").getAsString(), type,
                    value.has("shader_symbol") ? value.get("shader_symbol").getAsString() : "", defaults, NiagaraCollectionJson.read(value),
                    combined == null ? null : new Key(value.get("name").getAsString(), type, value.get("underlying").getAsInt(),
                            value.get("type_flags").getAsInt(), Storage.NUMERIC)));
        }
        bindings = List.copyOf(result);
    }

    public @Nullable NiagaraParameterStore store() { return combined == null ? null : combined.store(); }
    public void refreshUserInterfaces(boolean rebind) { if (combined != null) combined.refreshUserInterfaces(rebind); }
    public void bindInstance(NiagaraParameterStore instance) { if (combined != null) combined.bindInstance(instance); }
    public void tickRapidIteration() { if (combined != null) combined.tickRapidIteration(); }
    public void validateInterfaces() {
        if (combined == null) return;
        combined.refreshUserInterfaces(false);
        var store = combined.store();
        long revision = store.interfacesRevision();
        if (validatedInterfaceRevision == revision) return;
        var expected = shaderInterfaceClasses;
        if (expected != null) {
            if (expected.size() != store.dataInterfaceCount())
                throw new InterfaceBindingException("GPU shader declares " + expected.size()
                        + " interface slots, execution store has " + store.dataInterfaceCount());
            for (int index = 0; index < expected.size(); index++)
                validateInterface(store.dataInterface(index), expected.get(index), "slot " + index);
        } else {
            for (var binding : interfaceBindings)
                validateInterface(store.dataInterface(binding.key()), binding.sourceClass(),
                        binding.key().name() + " (" + binding.binding() + ")");
        }
        validatedInterfaceRevision = revision;
    }

    private static void validateInterface(@Nullable Object value, String expected, String slot) {
        if (value != null && !(value instanceof NiagaraDataInterfaceObject))
            throw new IllegalStateException("Invalid object in GPU interface " + slot);
        String actual = value instanceof NiagaraDataInterfaceObject object ? object.sourceClassName() : null;
        if (actual == null || !SourceNames.equal(expected, actual))
            throw new InterfaceBindingException("GPU interface " + slot + " expects " + expected
                    + ", found " + (actual == null ? "no bound class" : actual));
    }

    private static @Nullable List<String> shaderInterfaceClasses(JsonObject program) {
        var metadata = program.getAsJsonObject("parameter_metadata");
        if (metadata == null || !metadata.has("DataInterfaceParamInfo")) return null;
        var classes = new ArrayList<String>();
        for (var entry : metadata.getAsJsonArray("DataInterfaceParamInfo"))
            classes.add(entry.getAsJsonObject().get("DIClassName").getAsString());
        return List.copyOf(classes);
    }
    public void prepareSources(Map<String, EffectHandle.ParameterValue> values, NiagaraCollectionSnapshot collections,
                               double x, double y, double z) {
        if (combined == null) return;
        combined.prepare(bindings, values, collections, x, y, z);
        sourcesPrepared = true;
    }

    public Snapshot snapshot(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                             Map<String, EffectHandle.ParameterValue> overrides) {
        return snapshot(system, variables, overrides, 0, 0, 0);
    }
    public Snapshot snapshot(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                             Map<String, EffectHandle.ParameterValue> overrides, double originX, double originY, double originZ) {
        return snapshot(system, variables, overrides, originX, originY, originZ, NiagaraCollectionSnapshot.EMPTY);
    }
    public Snapshot snapshot(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                             Map<String, EffectHandle.ParameterValue> overrides, double originX, double originY, double originZ,
                             NiagaraCollectionSnapshot collections) {
        return snapshot(system, variables, overrides, originX, originY, originZ, collections, true);
    }
    public Snapshot snapshot(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                             Map<String, EffectHandle.ParameterValue> overrides, double originX, double originY, double originZ,
                             NiagaraCollectionSnapshot collections, boolean contextTick) {
        return snapshot(system, variables, overrides, originX, originY, originZ, collections, contextTick, true);
    }

    public Snapshot snapshot(VectorDataSet system, Map<String, NiagaraDefinition.Variable> variables,
                             Map<String, EffectHandle.ParameterValue> overrides, double originX, double originY, double originZ,
                             NiagaraCollectionSnapshot collections, boolean contextTick, boolean dispatch) {
        if (combined != null) {
            try {
                if (!sourcesPrepared) prepareSources(overrides, collections, originX, originY, originZ);
                if (dispatch) validateInterfaces();
                combined.transfer(system, variables, contextTick);
                return combinedSnapshot(combined, originX, originY, originZ, dispatch);
            } finally { sourcesPrepared = false; }
        }
        Map<String, byte[]> current = new LinkedHashMap<>();
        Map<String, byte[]> byName = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (Binding binding : bindings) {
            byte[] value = binding.defaults() == null ? null : binding.defaults().clone();
            NiagaraDefinition.Variable variable = variables.get(binding.name());
            if (variable != null && !variable.sourceType().equals(binding.sourceType())) variable = null;
            if (variable == null) for (var entry : variables.entrySet())
                if (SourceNames.equal(entry.getKey(), binding.name()) && entry.getValue().sourceType().equals(binding.sourceType())) { variable = entry.getValue(); break; }
            if (variable != null) value = systemValue(binding, variable, system);
            EffectHandle.ParameterValue override = overrides.get(binding.name());
            if (override == null) for (var entry : overrides.entrySet())
                if (SourceNames.equal(entry.getKey(), binding.name())) { override = entry.getValue(); break; }
            if (override != null) value = overrideValue(binding, override, originX, originY, originZ);
            NiagaraCollectionBinding collection = binding.collection();
            if (collection != null) {
                boolean component = collections.component().contains(collection.path());
                if (collection.hasDefault(component)) {
                    byte[] source = new byte[collection.bytes()];
                    collection.copyDefault(source, 0, component, originX, originY, originZ);
                    value = encodeDefault(binding.sourceType(), source);
                }
                EffectHandle.ParameterValue replacement = component ? collections.component().get(collection.path(), binding.name(), binding.sourceType())
                        : collection.systemOverride() ? null : collections.world().get(collection.path(), binding.name(), binding.sourceType());
                if (replacement != null) value = overrideValue(binding, replacement, originX, originY, originZ);
            }
            if (value != null) {
                if (byName.putIfAbsent(binding.name(), value) != null)
                    throw new IllegalArgumentException("Duplicate Niagara GPU parameter " + binding.name());
                if (!binding.symbol().isEmpty() && current.putIfAbsent(binding.symbol(), value) != null)
                    throw new IllegalArgumentException("Duplicate Niagara GPU parameter symbol " + binding.symbol());
            }
        }
        Map<String, byte[]> old = previous;
        if (old != null && (previousOriginX != originX || previousOriginY != originY || previousOriginZ != originZ)) {
            old = new LinkedHashMap<>(old);
            for (Binding binding : bindings) if (binding.sourceType().endsWith(".NiagaraPosition") && !binding.symbol().isEmpty()) {
                byte[] data = old.get(binding.symbol());
                if (data == null) continue;
                if (data.length != 12) throw new IllegalArgumentException("Niagara GPU position must contain twelve bytes");
                data = data.clone();
                NiagaraPositionParameters.rebase(data, 0,
                        previousOriginX - originX, previousOriginY - originY, previousOriginZ - originZ);
                old.put(binding.symbol(), data);
            }
        }
        return new Snapshot(current, old == null ? current : old, byName, originX, originY, originZ, Map.of());
    }

    private Snapshot combinedSnapshot(NiagaraGpuCombinedParameters combined, double x, double y, double z, boolean dispatch) {
        Map<String, byte[]> current = new LinkedHashMap<>(), old = new LinkedHashMap<>();
        Map<String, byte[]> byName = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        for (var binding : bindings) {
            Key key = binding.key();
            if (key == null) throw new IllegalStateException("GPU combined binding has no execution key");
            byte[] value = encodeDefault(binding.sourceType(), combined.read(key));
            byName.putIfAbsent(binding.name(), value);
            if (binding.symbol().isEmpty()) continue;
            if (current.putIfAbsent(binding.symbol(), value) != null)
                throw new IllegalArgumentException("Duplicate Niagara GPU parameter symbol " + binding.symbol());
            byte[] previousValue = previous == null ? value : encodeDefault(binding.sourceType(), combined.readPrevious(key));
            if (previous != null && binding.sourceType().endsWith(".NiagaraPosition"))
                NiagaraPositionParameters.rebase(previousValue, 0, previousOriginX - x, previousOriginY - y, previousOriginZ - z);
            old.put(binding.symbol(), previousValue);
        }
        return new Snapshot(current, old, byName, x, y, z, dispatch ? dataInterfaceSelections(null) : Map.of());
    }

    public Map<String, String> dataInterfaceSelections(@Nullable Set<String> compileNames) {
        if (combined == null) return Map.of();
        Map<String, String> interfaces = new LinkedHashMap<>();
        for (var binding : interfaceBindings) {
            String name = SourceNames.canonical(binding.binding());
            if (!binding.dynamic() || compileNames != null && !compileNames.contains(name)) continue;
            Object value = combined.store().dataInterface(binding.key());
            if (!(value instanceof NiagaraDataInterfaceObject object))
                throw new IllegalStateException("GPU data interface is not bound: " + binding.key().name());
            interfaces.put(name, object.identity());
        }
        return Map.copyOf(interfaces);
    }

    public void forEachObjectPropertyRequest(BiConsumer<String, Request> consumer) {
        for (var reader : objectReaders) {
            String identity = reader.original();
            if (combined != null && reader.key() != null) {
                var value = combined.store().dataInterface(reader.key());
                if (!(value instanceof NiagaraDataInterfaceObject object) || object.propertyReader() == null) continue;
                identity = object.identity();
            }
            for (var request : reader.requests()) consumer.accept(identity, request);
        }
    }

    public void commit(Snapshot snapshot) {
        if (combined != null) combined.commit();
        previous = copy(snapshot.current);
        previousOriginX = snapshot.originX; previousOriginY = snapshot.originY; previousOriginZ = snapshot.originZ;
    }
    public void reset() {
        previous = null;
        sourcesPrepared = false;
        validatedInterfaceRevision = Long.MIN_VALUE;
        if (combined != null) combined.reset();
    }
    @Override public void close() {
        if (combined != null) combined.close();
        if (ownedSources != null) ownedSources.close();
        previous = null; sourcesPrepared = false;
    }

    private static byte[] systemValue(Binding binding, NiagaraDefinition.Variable variable, VectorDataSet data) {
        if (!binding.sourceType().equals(variable.sourceType()))
            throw new IllegalArgumentException("Niagara GPU system parameter type mismatch: " + binding.name());
        int storage, start, count;
        if (variable.floatCount() > 0 && variable.intCount() == 0 && variable.halfCount() == 0) {
            storage = 0; start = variable.floatStart(); count = variable.floatCount();
        } else if (variable.intCount() > 0 && variable.floatCount() == 0 && variable.halfCount() == 0) {
            storage = 1; start = variable.intStart(); count = variable.intCount();
        } else if (variable.halfCount() > 0 && variable.floatCount() == 0 && variable.intCount() == 0) {
            storage = 2; start = variable.halfStart(); count = variable.halfCount();
        } else throw new IllegalArgumentException("Unsupported Niagara GPU system parameter layout: " + binding.name());
        ByteBuffer result = ByteBuffer.allocate(Math.multiplyExact(count, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            int word = data.component(storage, start + i)[0];
            result.putInt(storage == 2 ? Float.floatToRawIntBits(Float.float16ToFloat((short)word)) : word);
        }
        return result.array();
    }

    private static byte[] overrideValue(Binding binding, EffectHandle.ParameterValue value, double originX, double originY, double originZ) {
        int storage = value.type().storage(), components = value.type().components();
        if (!NiagaraParameterValues.matches(binding.sourceType(), storage, components))
            throw new IllegalArgumentException("Niagara GPU parameter type mismatch: " + binding.name());
        ByteBuffer result = ByteBuffer.allocate(Math.multiplyExact(components, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        if (storage == 4) {
            NiagaraPositionParameters.write(value, result, 0, originX, originY, originZ);
            return result.array();
        }
        for (long word : value.words())
            result.putInt(storage == 3 ? Float.floatToRawIntBits(Float.float16ToFloat((short)word))
                    : storage == 2 ? word == 0 ? 0 : -1 : (int)word);
        return result.array();
    }

    private static byte[] encodeDefault(String type, byte[] source) {
        if (!type.contains(".NiagaraHalf")) return source;
        if ((source.length & 1) != 0) throw new IllegalArgumentException("Invalid Niagara GPU half parameter");
        ByteBuffer input = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer output = ByteBuffer.allocate(Math.multiplyExact(source.length / 2, Integer.BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        while (input.hasRemaining()) output.putFloat(Float.float16ToFloat(input.getShort()));
        return output.array();
    }

    private static Map<String, byte[]> copy(Map<String, byte[]> source) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, value.clone()));
        return Map.copyOf(result);
    }
    private static Map<String, byte[]> copyNames(Map<String, byte[]> source) {
        Map<String, byte[]> result = new TreeMap<>(SourceNames.ASCII_CASE_INSENSITIVE);
        source.forEach((key, value) -> result.put(key, value.clone()));
        return Collections.unmodifiableMap(result);
    }
}
