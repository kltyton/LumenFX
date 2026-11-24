package com.kltyton.lumenfx.client.simulation.niagara.parameter.store;

import com.kltyton.lumenfx.api.client.parameter.NiagaraSplineInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.spline.NiagaraSplineSourceJson;
import com.kltyton.lumenfx.api.client.geometry.NiagaraGeometryCollectionInputs.RestCollection;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.geometry.GeometryCollectionAssetJson;
import com.kltyton.lumenfx.client.resource.material.MaterialCollectionJson;
import com.kltyton.lumenfx.effect.material.collection.MaterialCollectionDefinition;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;

import com.kltyton.lumenfx.api.client.parameter.NiagaraAudioBackend.Overrides;
import com.kltyton.lumenfx.client.resource.niagara.NiagaraJson;
import com.kltyton.lumenfx.effect.niagara.audio.NiagaraAudioPlayerDefinition;

import com.kltyton.lumenfx.effect.niagara.collision.NiagaraAsyncGpuTraceDefinition;
import java.util.function.Function;
import com.kltyton.lumenfx.effect.niagara.table.NiagaraDataTableAsset;

import com.kltyton.lumenfx.api.client.parameter.ObjectPropertyInputs;
import com.kltyton.lumenfx.api.client.parameter.ActorComponentInputs;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraActorComponent;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader;
import com.google.gson.JsonObject;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition.MutableInterfaceInstance;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.VectorFieldBinding;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.EmitterBinding;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.store.NiagaraDataInterfaceObject.AsyncTraceSnapshot;
import com.kltyton.lumenfx.client.resource.shader.PreparedNiagaraCurves;
import com.kltyton.lumenfx.effect.render.sprite.SpriteRendererInformation;
import com.kltyton.lumenfx.effect.render.mesh.MeshRendererInformation;
import com.kltyton.lumenfx.api.client.parameter.TextureParameter;
import com.kltyton.lumenfx.api.client.parameter.ParameterCollectionValues.ObjectParameter;
import com.kltyton.lumenfx.client.simulation.niagara.parameter.NiagaraCollectionSnapshot;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.ObjectValue;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.Reference;
import com.kltyton.lumenfx.effect.niagara.parameter.store.ScriptParameterStoreDefinition.Source;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.function.BiConsumer;

/** Template sources stay bound to templates; execution instances clone values before their own bindings are added. */
public final class NiagaraParameterStoreSources implements AutoCloseable {
    /** Component object identities outlive an individual system simulation and its parameter bindings. */
    public static final class ObjectRegistry implements AutoCloseable {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final Map<NiagaraParameterStoreSources, Set<String>> owners = new IdentityHashMap<>();
        @Override public void close() { values.clear(); owners.clear(); }
    }
    private final Map<ScriptParameterStoreDefinition, NiagaraParameterStore> templates = new IdentityHashMap<>();
    private final Map<String, NiagaraParameterStore> sources = new LinkedHashMap<>();
    private final Map<String, ObjectValue> objects;
    private final ObjectRegistry registry;
    private final boolean ownsRegistry;
    private final Map<String, Object> runtimeObjects;
    private final Map<Identifier, NiagaraSimulationCache> cacheAssets;
    private final Map<Identifier, SparseVolumeTextureAsset> sparseVolumeAssets;
    private record SavedObject(ObjectPropertyInputs.@Nullable Source properties, ActorComponentInputs.@Nullable Binding actor,
                               @Nullable Overrides audio, NiagaraSplineInputs.@Nullable Actor spline, @Nullable RestCollection geometry) { }
    private final Map<String, SavedObject> savedObjects = new LinkedHashMap<>();
    private final Set<String> referencedObjects = new HashSet<>();
    private final Map<NiagaraParameterStore, List<CollectionObject>> collectionObjects = new IdentityHashMap<>();
    private NiagaraCollectionSnapshot collections = NiagaraCollectionSnapshot.EMPTY;
    private static final class CollectionObject {
        final Reference reference;
        @Nullable Object selection;
        @Nullable ObjectValue value;
        boolean component;
        CollectionObject(Reference reference) {
            this.reference = reference;
            selection = value = reference.value();
        }
    }
    private record CollectionUpdate(NiagaraParameterStore store, CollectionObject binding,
                                    @Nullable Object selection, @Nullable ObjectValue value, @Nullable Object object,
                                    boolean component) { }
    private record NonTextureObject(ObjectValue source) { }
    private record MaterialCollectionObject(ObjectValue source, MaterialCollectionDefinition definition) { }
    private record DataTableObject(ObjectValue source, NiagaraDataTableAsset asset) { }

    public @Nullable String sourceIdentity(Object value) {
        if (value instanceof NonTextureObject stored) return stored.source().identity();
        if (value instanceof MaterialCollectionObject stored) return stored.source().identity();
        if (value instanceof DataTableObject stored) return stored.source().identity();
        for (var entry : runtimeObjects.entrySet()) if (entry.getValue() == value) return entry.getKey();
        return null;
    }

    public NiagaraParameterStoreSources(Map<String, ObjectValue> objects) { this(objects, null); }
    public NiagaraParameterStoreSources(Map<String, ObjectValue> objects, @Nullable ObjectRegistry registry) {
        this(objects, registry, Map.of());
    }
    public NiagaraParameterStoreSources(Map<String, ObjectValue> objects, @Nullable ObjectRegistry registry,
                                        Map<Identifier, NiagaraSimulationCache> cacheAssets) {
        this(objects, registry, cacheAssets, Map.of());
    }
    public NiagaraParameterStoreSources(Map<String, ObjectValue> objects, @Nullable ObjectRegistry registry,
                                        Map<Identifier, NiagaraSimulationCache> cacheAssets,
                                        Map<Identifier, SparseVolumeTextureAsset> sparseVolumeAssets) {
        this.objects = objects;
        this.cacheAssets = cacheAssets;
        this.sparseVolumeAssets = sparseVolumeAssets;
        ownsRegistry = registry == null;
        this.registry = registry == null ? new ObjectRegistry() : registry;
        runtimeObjects = this.registry.values;
        this.registry.owners.put(this, referencedObjects);
    }

    public void initializeInstance(NiagaraParameterStore destination, ScriptParameterStoreDefinition definition) {
        destination.copyValuesFrom(template(definition));
        var objects = definition.references().stream()
                .filter(reference -> reference.collection() != null && reference.key().storage() != Storage.NUMERIC)
                .map(CollectionObject::new).toList();
        if (objects.isEmpty()) collectionObjects.remove(destination);
        else {
            collectionObjects.put(destination, objects);
            var updates = new ArrayList<CollectionUpdate>();
            prepareCollectionObjects(destination, objects, collections, updates);
            applyCollectionUpdates(updates);
        }
    }

    public void releaseInstance(NiagaraParameterStore store) {
        collectionObjects.remove(store);
    }

    public boolean collectionSnapshot(NiagaraCollectionSnapshot snapshot) {
        if (collections.world() == snapshot.world() && collections.component() == snapshot.component()) {
            collections = snapshot;
            return false;
        }
        var updates = new ArrayList<CollectionUpdate>();
        collectionObjects.forEach((store, bindings) -> prepareCollectionObjects(store, bindings, snapshot, updates));
        boolean changedInterfaces = applyCollectionUpdates(updates);
        if (!updates.isEmpty()) finishObjectInitialization();
        collections = snapshot;
        return changedInterfaces;
    }

    public Set<String> collectionInterfaceNames() {
        Set<String> names = new HashSet<>();
        for (var bindings : collectionObjects.values()) for (var binding : bindings) {
            Key key = binding.reference.key();
            if (key.storage() == Storage.INTERFACE) names.add(SourceNames.canonical(key.name()));
        }
        return names;
    }

    private void prepareCollectionObjects(NiagaraParameterStore store, List<CollectionObject> bindings,
                                          NiagaraCollectionSnapshot snapshot, List<CollectionUpdate> updates) {
        for (var binding : bindings) {
            var reference = binding.reference;
            var collection = reference.collection();
            var key = reference.key();
            boolean component = snapshot.component().contains(collection.path());
            var values = component ? snapshot.component() : collection.systemOverride() ? null : snapshot.world();
            ObjectParameter input = values == null ? null : values.getObject(collection.path(), key.name(), key.sourceType());
            ObjectValue value = input != null ? input.value() : component ? collection.baseDefault() : reference.value();
            Object selection = input != null ? input : value;
            if (selection == binding.selection && (key.storage() != Storage.INTERFACE || component == binding.component)) continue;
            Object object;
            if (key.storage() == Storage.INTERFACE) {
                if (input != null && input.texture() != null)
                    throw new IllegalArgumentException("Texture value cannot bind to a Niagara collection DI: " + key.name());
                object = input == null ? materialize(value, Storage.INTERFACE, false) : interfaceObject(key, value, false);
            } else {
                object = input != null && input.texture() != null ? input.texture() : materialize(value, Storage.OBJECT, false);
            }
            updates.add(new CollectionUpdate(store, binding, selection, value, object, component));
        }
    }

    private static boolean applyCollectionUpdates(List<CollectionUpdate> updates) {
        boolean changedInterfaces = false;
        for (var update : updates) {
            Key key = update.binding().reference.key();
            if (key.storage() == Storage.INTERFACE) {
                update.store().setDataInterface(key, update.object());
                changedInterfaces = true;
            } else update.store().setObject(key, update.object());
            update.binding().selection = update.selection();
            update.binding().value = update.value();
            update.binding().component = update.component();
        }
        return changedInterfaces;
    }

    public static void resolveUserInterfaces(ScriptParameterStoreDefinition definition, NiagaraParameterStore instance,
                                             NiagaraParameterStore destination) {
        for (var binding : definition.userInterfaces())
            destination.setDataInterface(binding.target(), instance.dataInterface(binding.source()));
    }

    private NiagaraParameterStore template(ScriptParameterStoreDefinition definition) {
        var existing = templates.get(definition);
        if (existing != null) return existing;
        var result = new NiagaraParameterStore(definition.layout());
        result.setNumericData(definition.data());
        for (int index = 0; index < definition.interfaceValues().size(); index++)
            result.setDataInterface(index, materialize(definition.interfaceValues().get(index), Storage.INTERFACE));
        for (int index = 0; index < definition.objectValues().size(); index++)
            result.setObject(index, materialize(definition.objectValues().get(index), Storage.OBJECT));
        for (var reference : definition.references()) {
            Object value = materialize(reference.value(), reference.key().storage());
            if (reference.key().storage() == Storage.INTERFACE) result.setDataInterface(reference.key(), value);
            else result.setObject(reference.key(), value);
        }
        templates.put(definition, result);
        bind(definition.initialSources(), result);
        return result;
    }

    public void register(VectorProgram program) {
        for (var function : program.external()) if (function.interfaceKey() != null) {
            String identity = SourceNames.canonical(function.interfaceId());
            referencedObjects.add(identity);
            var object = (NiagaraDataInterfaceObject)runtimeObjects.computeIfAbsent(identity,
                    ignored -> new NiagaraDataInterfaceObject(function.interfaceId()));
            object.register(function);
        }
    }
    public void registerMutable(List<MutableInterfaceInstance> definitions) {
        for (var definition : definitions) {
            String identity = SourceNames.canonical(definition.interfaceId());
            referencedObjects.add(identity);
            var object = (NiagaraDataInterfaceObject)runtimeObjects.computeIfAbsent(identity,
                    ignored -> new NiagaraDataInterfaceObject(definition.interfaceId()));
            object.configure(definition);
        }
    }

    private @Nullable Object materialize(@Nullable ObjectValue value, Storage storage) {
        return materialize(value, storage, true);
    }
    private @Nullable Object materialize(@Nullable ObjectValue value, Storage storage, boolean retain) {
        if (value == null) return null;
        String identity = SourceNames.canonical(value.identity());
        if (retain) referencedObjects.add(identity);
        ObjectValue definition = retain ? objects.computeIfAbsent(identity, ignored -> value) : objects.getOrDefault(identity, value);
        var data = definition.source();
        if (data.has("property_fields") || data.has("actor_component_binding") || data.has("audio_settings")
                || data.has("saved_spline") || data.has("saved_geometry_collection"))
            savedObjects.computeIfAbsent(identity, ignored -> new SavedObject(
                    data.has("property_fields") ? NiagaraObjectPropertyReader.readSavedFields(data.getAsJsonObject("property_fields")) : null,
                    data.has("actor_component_binding") ? NiagaraActorComponent.readSavedBinding(data.getAsJsonObject("actor_component_binding")) : null,
                    data.has("audio_settings") ? NiagaraAudioPlayerDefinition.readOverrides(data.getAsJsonObject("audio_settings"), NiagaraJson::niagaraCurve) : null,
                    data.has("saved_spline") ? NiagaraSplineSourceJson.read(data.getAsJsonObject("saved_spline")) : null,
                    GeometryCollectionAssetJson.read(data.get("saved_geometry_collection"))));
        Object result = runtimeObjects.computeIfAbsent(identity, ignored -> {
            if (storage == Storage.INTERFACE || definition.curves() != null || data.has("gpu_curve_defaults") || data.has("curl")
                    || data.has("object_property_reader") || data.has("property_interface") || data.has("actor_component") || data.has("async_gpu_trace")
                    || data.has("static_mesh_reader")
                    || data.has("skeletal_mesh_reader")
                    || data.has("particle_export")
                    || data.has("data_channel")
                    || data.has("audio_player")
                    || data.has("audio_analysis")
                    || data.has("sim_cache_reader")
                    || data.has("memory_buffer")
                    || data.has("dynamic_mesh")
                    || data.has("neighbor_grid") || data.has("rasterization_grid") || data.has("velocity_grid") || data.has("water")
                    || data.has("grid3d_collection")
                    || data.has("grid2d_collection") || data.has("data_table") || data.has("int_render_target_2d")
                    || data.has("gaussian_splat_reader") || data.has("gaussian_splat_writer")
                    || data.has("socket_reader") || data.has("spline") || data.has("material_instance_dynamic") || data.has("neighbor_query")
                    || data.has("debug_draw")
                    || data.has("material_collection_writer")
                    || data.has("sparse_volume_texture")
                    || data.has("volume_cache")
                    || data.has("physics_asset")
                    || data.has("rigid_mesh_collision")
                    || data.has("geometry_collection")
                    || data.has("scene_capture_2d")
                    || data.has("landscape")
                    || data.has("virtual_texture")
                    || data.has("class") && "NiagaraDataInterfaceCollisionQuery".equals(data.get("class").getAsString())
                    || data.has("class") && "NiagaraDataInterfaceConsoleVariable".equals(data.get("class").getAsString())
                    || data.has("class") && "NiagaraDataInterfaceRibbon".equals(data.get("class").getAsString())
                    || data.has("vector_field_defaults") || data.has("camera") || data.has("sprite_info") || data.has("mesh_info")
                    || data.has("emitter_binding_source") || data.has("mutable_data") || data.has("platform_set")
                    || data.has("texture_2d_defaults") || data.has("texture_array_defaults") || data.has("texture_cube_defaults")
                    || data.has("texture_volume_defaults")
                    || data.has("render_target_2d_defaults")
                    || data.has("render_target_array_defaults") || data.has("render_target_volume_defaults") || data.has("render_target_cube_defaults"))
                return new NiagaraDataInterfaceObject(definition.identity());
            if (data.has("sim_cache_asset")) {
                var id = Identifier.parse(data.getAsJsonObject("sim_cache_asset").get("asset").getAsString());
                return Objects.requireNonNull(cacheAssets.get(id), "Missing prepared Niagara simulation cache " + id);
            }
            if (data.has("sparse_volume_asset")) {
                var id = Identifier.parse(data.getAsJsonObject("sparse_volume_asset").get("asset").getAsString());
                return Objects.requireNonNull(sparseVolumeAssets.get(id), "Missing prepared Niagara sparse volume " + id);
            }
            if (data.has("material_collection_asset"))
                return new MaterialCollectionObject(definition, MaterialCollectionJson.read(data.getAsJsonObject("material_collection_asset")));
            if (data.has("data_table_asset"))
                return new DataTableObject(definition, NiagaraDataTableAsset.read(data.getAsJsonObject("data_table_asset")));
            if (data.has("runtime_texture")) return TextureParameter.resource(Identifier.parse(data.get("runtime_texture").getAsString()));
            if (data.has("texture_cast") && !data.get("texture_cast").getAsBoolean()) return new NonTextureObject(definition);
            return definition;
        });
        var curves = definition.curves();
        if (result instanceof NiagaraDataInterfaceObject object) object.sourceType(data);
        if (result instanceof NiagaraDataInterfaceObject object && data.has("mutable_data"))
            object.configureMutableDefaults(data.get("class").getAsString(), data.getAsJsonObject("mutable_data"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("texture_2d_defaults")) object.configureTexture(data.getAsJsonObject("texture_2d_defaults"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("texture_array_defaults"))
            object.configureTexture(data.getAsJsonObject("texture_array_defaults"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("texture_cube_defaults"))
            object.configureTexture(data.getAsJsonObject("texture_cube_defaults"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("texture_volume_defaults"))
            object.configureTexture(data.getAsJsonObject("texture_volume_defaults"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("render_target_2d_defaults")) object.configureRenderTarget(data.getAsJsonObject("render_target_2d_defaults"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("render_target_array_defaults"))
            object.configureDimensionalTarget(data.getAsJsonObject("render_target_array_defaults"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("render_target_volume_defaults"))
            object.configureDimensionalTarget(data.getAsJsonObject("render_target_volume_defaults"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("render_target_cube_defaults"))
            object.configureDimensionalTarget(data.getAsJsonObject("render_target_cube_defaults"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("platform_set")) object.configurePlatform(data.getAsJsonObject("platform_set"));
        if (result instanceof NiagaraDataInterfaceObject object && curves != null)
            object.configureCurves(data.get("class").getAsString(), curves);
        if (result instanceof NiagaraDataInterfaceObject object && data.has("gpu_curve_defaults"))
            object.configureGpuCurve(data.get("class").getAsString(), data.getAsJsonObject("gpu_curve_defaults"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("curl")) object.configureCurl(data.getAsJsonObject("curl"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("camera")) object.configureCamera(data.getAsJsonObject("camera"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("object_property_reader"))
            object.configurePropertyReader(data.getAsJsonObject("object_property_reader"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("property_interface"))
            object.configurePropertyInterface(data.getAsJsonObject("property_interface"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("actor_component"))
            object.configureActorComponent(data.getAsJsonObject("actor_component"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("async_gpu_trace"))
            object.configureAsyncGpuTrace(data.getAsJsonObject("async_gpu_trace"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("static_mesh_reader"))
            object.configureStaticMeshReader(data.getAsJsonObject("static_mesh_reader"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("skeletal_mesh_reader"))
            object.configureSkeletalMeshReader(data.getAsJsonObject("skeletal_mesh_reader"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("particle_export"))
            object.configureParticleExport(data.getAsJsonObject("particle_export"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("data_channel"))
            object.configureDataChannel(data.getAsJsonObject("data_channel"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("audio_player"))
            object.configureAudioPlayer(data.getAsJsonObject("audio_player"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("audio_analysis"))
            object.configureAudioAnalysis(data.getAsJsonObject("audio_analysis"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("sim_cache_reader"))
            object.configureSimCacheReader(data.getAsJsonObject("sim_cache_reader"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("memory_buffer"))
            object.configureMemoryBuffer(data.getAsJsonObject("memory_buffer"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("dynamic_mesh"))
            object.configureDynamicMesh(data.getAsJsonObject("dynamic_mesh"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("neighbor_grid"))
            object.configureNeighborGrid(data.getAsJsonObject("neighbor_grid"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("rasterization_grid"))
            object.configureRasterizationGrid(data.getAsJsonObject("rasterization_grid"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("velocity_grid"))
            object.configureVelocityGrid(data.getAsJsonObject("velocity_grid"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("grid3d_collection"))
            object.configureGrid3DCollection(data.getAsJsonObject("grid3d_collection"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("grid2d_collection"))
            object.configureGrid2DCollection(data.getAsJsonObject("grid2d_collection"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("data_table"))
            object.configureDataTable(data.getAsJsonObject("data_table"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("gaussian_splat_reader"))
            object.configureGaussianSplatReader(data.getAsJsonObject("gaussian_splat_reader"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("gaussian_splat_writer"))
            object.configureGaussianSplatWriter(data.getAsJsonObject("gaussian_splat_writer"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("int_render_target_2d"))
            object.configureIntRenderTarget2D(data.getAsJsonObject("int_render_target_2d"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("socket_reader"))
            object.configureSocketReader(data.getAsJsonObject("socket_reader"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("spline"))
            object.configureSpline(data.getAsJsonObject("spline"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("int_distribution"))
            object.configureIntDistribution(data.getAsJsonObject("int_distribution"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("water"))
            object.configureWater(data.getAsJsonObject("water"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("neighbor_query"))
            object.configureNeighborQuery(data.getAsJsonObject("neighbor_query"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("debug_draw"))
            object.configureDebugDraw(data.getAsJsonObject("debug_draw"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("material_instance_dynamic"))
            object.configureDynamicMaterial(data.getAsJsonObject("material_instance_dynamic"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("material_collection_writer"))
            object.configureMaterialCollectionWriter(data.getAsJsonObject("material_collection_writer"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("sparse_volume_texture"))
            object.configureSparseVolume(data.getAsJsonObject("sparse_volume_texture"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("volume_cache"))
            object.configureVolumeCache(data.getAsJsonObject("volume_cache"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("physics_asset"))
            object.configurePhysicsAsset(data.getAsJsonObject("physics_asset"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("rigid_mesh_collision"))
            object.configureRigidMeshCollision(data.getAsJsonObject("rigid_mesh_collision"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("geometry_collection"))
            object.configureGeometryCollection(data.getAsJsonObject("geometry_collection"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("chaos_destruction"))
            object.configureChaosDestruction(data.getAsJsonObject("chaos_destruction"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("hair_strands"))
            object.configureHairStrands(data.getAsJsonObject("hair_strands"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("scene_capture_2d"))
            object.configureSceneCapture(data.getAsJsonObject("scene_capture_2d"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("landscape"))
            object.configureLandscape(data.getAsJsonObject("landscape"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("virtual_texture"))
            object.configureVirtualTexture(data.getAsJsonObject("virtual_texture"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("sprite_info")) object.configureSprite(data.getAsJsonObject("sprite_info"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("mesh_info")) object.configureMesh(data.getAsJsonObject("mesh_info"));
        if (result instanceof NiagaraDataInterfaceObject object && data.has("emitter_binding_source"))
            object.configureEmitter(data.get("class").getAsString(), data);
        if (result instanceof NiagaraDataInterfaceObject object && data.has("vector_field_defaults")) {
            if (!SourceNames.equal(data.get("class").getAsString(), "NiagaraDataInterfaceVectorField"))
                throw new IllegalArgumentException("Vector field defaults have a different source class: " + identity);
            object.configureVectorField(data.getAsJsonObject("vector_field_defaults"), definition.field());
        }
        return result;
    }
    @Nullable NiagaraDataInterfaceObject interfaceObject(Key key, @Nullable ObjectValue value) {
        return interfaceObject(key, value, true);
    }

    private @Nullable NiagaraDataInterfaceObject interfaceObject(Key key, @Nullable ObjectValue value, boolean retain) {
        if (value == null) return null;
        if (!NiagaraDataInterfaceObject.matchesPreparedType(key, value.source()))
            throw new IllegalArgumentException("Prepared DI class differs from the exposed parameter: " + key.name());
        Object result = materialize(value, Storage.INTERFACE, retain);
        if (!(result instanceof NiagaraDataInterfaceObject object) || !object.matchesSourceType(key))
            throw new IllegalArgumentException("Prepared DI class differs from the exposed parameter: " + key.name());
        return object;
    }

    public void finishObjectInitialization() {
        Set<String> live = new HashSet<>();
        for (var owner : registry.owners.keySet()) {
            live.addAll(owner.referencedObjects);
            for (var bindings : owner.collectionObjects.values()) for (var binding : bindings)
                if (binding.value != null) live.add(SourceNames.canonical(binding.value.identity()));
        }
        runtimeObjects.keySet().retainAll(live);
        savedObjects.keySet().retainAll(live);
    }

    public void forEachPlatformObject(BiConsumer<String, JsonObject> action) {
        for (String identity : referencedObjects) {
            Object value = runtimeObjects.get(identity);
            if (value instanceof NiagaraDataInterfaceObject object) {
                var definition = object.platformDefinition();
                if (definition != null) action.accept(object.identity(), definition);
            }
        }
    }
    public byte[] curlParameters(String identity) {
        Object value = runtimeObjects.get(SourceNames.canonical(identity));
        if (!(value instanceof NiagaraDataInterfaceObject object)) throw new IllegalStateException("Curl object is not initialized: " + identity);
        return object.curlParameters();
    }
    public @Nullable VectorFieldBinding vectorField(String identity) {
        Object value = runtimeObjects.get(SourceNames.canonical(identity));
        if (!(value instanceof NiagaraDataInterfaceObject object)) throw new IllegalStateException("Vector field object is not initialized: " + identity);
        return object.vectorFieldBinding();
    }
    public PreparedNiagaraCurves.@Nullable Curve gpuCurve(String identity) {
        Object value = runtimeObjects.get(SourceNames.canonical(identity));
        if (!(value instanceof NiagaraDataInterfaceObject object)) throw new IllegalStateException("GPU curve object is not initialized: " + identity);
        return object.gpuCurve();
    }
    public List<NiagaraDataInterfaceObject> cameras() {
        var result = new ArrayList<NiagaraDataInterfaceObject>();
        for (Object value : runtimeObjects.values()) if (value instanceof NiagaraDataInterfaceObject object && object.camera() != null)
            result.add(object);
        return List.copyOf(result);
    }
    public @Nullable SpriteRendererInformation spriteInformation(String identity) {
        Object value = runtimeObjects.get(SourceNames.canonical(identity));
        if (!(value instanceof NiagaraDataInterfaceObject object)) throw new IllegalStateException("Sprite info object is not initialized: " + identity);
        return object.spriteInformation();
    }
    public @Nullable MeshRendererInformation meshInformation(String identity) {
        Object value = runtimeObjects.get(SourceNames.canonical(identity));
        if (!(value instanceof NiagaraDataInterfaceObject object)) throw new IllegalStateException("Mesh info object is not initialized: " + identity);
        return object.meshInformation();
    }
    public @Nullable EmitterBinding emitterBinding(String identity) {
        Object value = runtimeObjects.get(SourceNames.canonical(identity));
        if (!(value instanceof NiagaraDataInterfaceObject object)) throw new IllegalStateException("Emitter binding object is not initialized: " + identity);
        return object.emitterBinding();
    }
    public @Nullable NiagaraAsyncGpuTraceDefinition asyncGpuTrace(String identity) {
        Object value = runtimeObjects.get(SourceNames.canonical(identity));
        return value instanceof NiagaraDataInterfaceObject object ? object.asyncGpuTrace() : null;
    }
    public Map<String, AsyncTraceSnapshot> captureAsyncGpuTraces(
            Function<NiagaraDataInterfaceObject, @Nullable NiagaraAsyncGpuTraceDefinition> definitions) {
        var snapshots = new HashMap<String, AsyncTraceSnapshot>();
        runtimeObjects.forEach((identity, value) -> {
            if (value instanceof NiagaraDataInterfaceObject object) {
                var definition = definitions.apply(object);
                if (definition == null) return;
                String loadedPath = object.loadedSourcePath();
                Object proxy = loadedPath == null ? object : SourceNames.canonical(loadedPath);
                snapshots.put(identity, new AsyncTraceSnapshot(proxy, definition));
            }
        });
        return Map.copyOf(snapshots);
    }
    @Nullable Object defaultDataInterface(ScriptParameterStoreDefinition definition, Key key) {
        return template(definition).dataInterface(key);
    }
    public @Nullable Object defaultObject(ScriptParameterStoreDefinition definition, Key key) { return template(definition).object(key); }
    @Nullable Object objectParameter(@Nullable ObjectValue value) { return materialize(value, Storage.OBJECT); }
    public static @Nullable String objectIdentity(Object value) {
        if (value instanceof ObjectValue source) return source.identity();
        if (value instanceof NonTextureObject object) return object.source().identity();
        if (value instanceof MaterialCollectionObject object) return object.source().identity();
        if (value instanceof DataTableObject object) return object.source().identity();
        if (value instanceof NiagaraDataInterfaceObject object) return object.identity();
        if (value instanceof TextureParameter.Resource texture) return texture.id().toString();
        return null;
    }
    public static @Nullable MaterialCollectionDefinition materialCollectionAsset(@Nullable Object value) {
        return value instanceof MaterialCollectionObject collection ? collection.definition() : null;
    }
    public static @Nullable NiagaraDataTableAsset dataTableAsset(@Nullable Object value) {
        return value instanceof DataTableObject table ? table.asset() : null;
    }
    public ObjectPropertyInputs.@Nullable Source savedPropertyObject(Object value) {
        var saved = savedObject(value);
        return saved == null ? null : saved.properties();
    }
    public ActorComponentInputs.@Nullable Binding savedActorComponent(Object value) {
        var saved = savedObject(value);
        return saved == null ? null : saved.actor();
    }
    public @Nullable Overrides savedAudioSettings(Object value) {
        var saved = savedObject(value);
        return saved == null ? null : saved.audio();
    }
    public NiagaraSplineInputs.@Nullable Actor savedSpline(Object value) {
        var saved = savedObject(value);
        return saved == null ? null : saved.spline();
    }
    public @Nullable RestCollection savedGeometryCollection(Object value) {
        var saved = savedObject(value);
        return saved == null ? null : saved.geometry();
    }

    private @Nullable SavedObject savedObject(Object value) {
        String identity = objectIdentity(value);
        return identity == null ? null : savedObjects.get(SourceNames.canonical(identity));
    }
    public static TextureParameter texture(@Nullable Object value) {
        if (value == null || value instanceof NonTextureObject || value instanceof MaterialCollectionObject
                || value instanceof DataTableObject || value instanceof NiagaraDataInterfaceObject)
            return TextureParameter.unbound();
        if (value instanceof TextureParameter texture) return texture;
        if (value instanceof ObjectValue source)
            throw new IllegalStateException("Texture object requires its runtime resource or source class ancestry: " + source.identity());
        throw new IllegalArgumentException("Object does not provide the source texture contract: " + value.getClass().getName());
    }

    public static String sourceObjectPath(@Nullable Object value) {
        if (value instanceof NonTextureObject nonTexture) value = nonTexture.source();
        if (value instanceof ObjectValue source) {
            var data = source.source();
            return data.has("path") ? data.get("path").getAsString() : source.identity();
        }
        return value instanceof TextureParameter.Resource resource ? resource.id().toString() : "";
    }

    public static TextureParameter dimensionalTexture(@Nullable Object value, Map<String, TextureParameter> published,
                                                       TextureParameter.Dimension dimension) {
        if (value instanceof TextureParameter texture) return texture;
        if (value instanceof ObjectValue source) {
            var data = source.source();
            String type = data.has("class") ? data.get("class").getAsString() : "";
            boolean compatible = switch (dimension) {
                case ARRAY -> type.equals("Texture2DArray") || type.equals("TextureRenderTarget2DArray");
                case CUBE -> type.equals("TextureCube") || type.equals("TextureRenderTargetCube");
                case CUBE_ARRAY -> type.equals("TextureCubeArray");
                case VOLUME -> type.equals("VolumeTexture") || type.equals("TextureRenderTargetVolume");
                case D2 -> false;
            };
            if (compatible) {
                if (data.has("path")) {
                    var selected = published.get(data.get("path").getAsString());
                    if (selected != null) return selected;
                }
                return published.getOrDefault(source.identity(), TextureParameter.unbound());
            }
        }
        return TextureParameter.unbound();
    }

    private NiagaraParameterStore source(Source definition) {
        String identity = SourceNames.canonical(definition.identity());
        var existing = sources.get(identity);
        if (existing != null) return existing;
        var result = template(definition.definition());
        sources.put(identity, result);
        return result;
    }

    public void bind(List<Source> definitions, NiagaraParameterStore destination) {
        for (var definition : definitions) {
            var source = source(definition);
            var offsets = definition.boundOffsets();
            if (offsets == null) source.bindTo(destination);
            else source.bindTo(destination, offsets);
        }
    }
    public void unbind(List<Source> definitions, NiagaraParameterStore destination) {
        for (var definition : definitions) source(definition).unbind(destination);
    }
    public void tick(List<Source> definitions) {
        for (var definition : definitions) if (definition.tick()) source(definition).tick();
    }
    public void tickAll(List<Source> definitions) {
        for (var definition : definitions) source(definition).tick();
    }

    @Override public void close() {
        savedObjects.clear();
        for (var store : templates.values()) store.close();
        templates.clear(); sources.clear();
        collectionObjects.clear();
        registry.owners.remove(this);
        referencedObjects.clear();
        if (ownsRegistry) registry.close();
    }
}
