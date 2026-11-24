package com.kltyton.lumenfx.client.resource.niagara;

import com.kltyton.lumenfx.effect.niagara.interfaces.splat.NiagaraGaussianSplatAsset;

import com.kltyton.lumenfx.api.client.component.PrimitiveOwnerVisibility;
import com.kltyton.lumenfx.api.client.parameter.NiagaraAutoAttachmentParameters;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentAttachment;
import com.kltyton.lumenfx.effect.niagara.component.NiagaraComponentPoolSettings;
import com.kltyton.lumenfx.api.client.NiagaraAgeUpdate;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack.ReplayMode;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraMovieSceneCacheTrack.StretchMode;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraSystemLifeCycleTrack;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraSystemLifeCycleTrack.EndBehavior;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraSystemLifeCycleTrack.EvaluateBehavior;
import com.kltyton.lumenfx.effect.niagara.sequence.NiagaraSystemLifeCycleTrack.FrameRate;
import com.kltyton.lumenfx.effect.niagara.NiagaraSystemState;
import com.kltyton.lumenfx.effect.niagara.NiagaraTiming;
import com.kltyton.lumenfx.effect.niagara.NiagaraLwcSettings;
import com.kltyton.lumenfx.effect.niagara.NiagaraComponentDefaults;
import com.kltyton.lumenfx.effect.niagara.component.NiagaraTranslucencySort;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior;
import com.kltyton.lumenfx.effect.niagara.NiagaraTickBehavior.ComponentTickGroup;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraCacheDebugData;
import com.kltyton.lumenfx.effect.niagara.cache.NiagaraDebugParameters;
import com.kltyton.lumenfx.effect.niagara.interfaces.debug.NiagaraPersistentDebugShape;
import com.kltyton.lumenfx.effect.niagara.interfaces.volume.NiagaraVolumeCacheAsset;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Key;
import com.kltyton.lumenfx.effect.niagara.parameter.store.NiagaraParameterLayout.Storage;

import com.kltyton.lumenfx.effect.niagara.cache.NiagaraSimulationCache;
import com.kltyton.lumenfx.effect.render.volume.SparseVolumeTextureAsset;
import java.util.Set;

import com.kltyton.lumenfx.effect.render.mesh.StaticMeshAsset;
import com.kltyton.lumenfx.effect.render.mesh.SkeletalMeshAsset;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition;
import com.kltyton.lumenfx.effect.niagara.channel.NiagaraDataChannelDefinition.Channel;

import com.kltyton.lumenfx.client.simulation.niagara.interfaces.NiagaraObjectPropertyReader;
import com.kltyton.lumenfx.client.simulation.niagara.interfaces.property.NiagaraPropertyInterface;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.kltyton.lumenfx.client.resource.EffectJson;
import com.kltyton.lumenfx.effect.curve.FloatCurve;
import com.kltyton.lumenfx.effect.curve.NiagaraCurve;
import com.kltyton.lumenfx.effect.niagara.NiagaraDefinition;
import com.kltyton.lumenfx.effect.niagara.NiagaraBounds;
import com.kltyton.lumenfx.effect.niagara.VectorProgram;
import com.kltyton.lumenfx.effect.niagara.parameter.SourceNames;
import com.kltyton.lumenfx.effect.render.SpriteCoverage;
import com.kltyton.lumenfx.effect.render.material.PrimitiveCustomData;
import com.kltyton.lumenfx.effect.noise.VectorFieldVolume;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class NiagaraJson {
    private NiagaraJson() { }

    public static NiagaraDefinition read(JsonObject value, Function<Identifier, VectorFieldVolume> fields) {
        return read(value, fields, id -> { throw new JsonParseException("Static mesh resolver required for " + id); });
    }

    public static NiagaraDefinition read(JsonObject value, Function<Identifier, VectorFieldVolume> fields,
                                         Function<Identifier, StaticMeshAsset> meshes) {
        return read(value, fields, meshes, id -> { throw new JsonParseException("Skeletal mesh resolver required for " + id); });
    }

    public static NiagaraDefinition read(JsonObject value, Function<Identifier, VectorFieldVolume> fields,
                                         Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal) {
        return read(value, fields, meshes, skeletal, id -> { throw new JsonParseException("Simulation cache resolver required for " + id); });
    }

    public static NiagaraDefinition read(JsonObject value, Function<Identifier, VectorFieldVolume> fields,
                                         Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                         Function<Identifier, NiagaraSimulationCache> simCaches) {
        return read(value, fields, meshes, skeletal, simCaches, id -> { throw new JsonParseException("Sparse volume resolver required for " + id); });
    }

    public static NiagaraDefinition read(JsonObject value, Function<Identifier, VectorFieldVolume> fields,
                                         Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                         Function<Identifier, NiagaraSimulationCache> simCaches,
                                         Function<Identifier, SparseVolumeTextureAsset> sparseVolumes) {
        return read(value, fields, meshes, skeletal, simCaches, sparseVolumes,
                id -> { throw new JsonParseException("Volume Cache resolver required for " + id); });
    }

    public static NiagaraDefinition read(JsonObject value, Function<Identifier, VectorFieldVolume> fields,
                                         Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                         Function<Identifier, NiagaraSimulationCache> simCaches,
                                         Function<Identifier, SparseVolumeTextureAsset> sparseVolumes,
                                         Function<Identifier, NiagaraVolumeCacheAsset> volumeCaches) {
        return read(value, fields, meshes, skeletal, simCaches, sparseVolumes, volumeCaches,
                id -> { throw new JsonParseException("Gaussian Splat resolver required for " + id); });
    }

    public static NiagaraDefinition read(JsonObject value, Function<Identifier, VectorFieldVolume> fields,
                                         Function<Identifier, StaticMeshAsset> meshes, Function<Identifier, SkeletalMeshAsset> skeletal,
                                         Function<Identifier, NiagaraSimulationCache> simCaches,
                                         Function<Identifier, SparseVolumeTextureAsset> sparseVolumes,
                                         Function<Identifier, NiagaraVolumeCacheAsset> volumeCaches,
                                         Function<Identifier, NiagaraGaussianSplatAsset> gaussianSplats) {
        List<NiagaraDefinition.Emitter> emitters = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray("emitters")) {
            JsonObject emitter = item.getAsJsonObject();
            List<NiagaraDefinition.Attribute> attributes = new ArrayList<>();
            for (JsonElement attribute : emitter.getAsJsonArray("attributes")) {
                JsonObject a = attribute.getAsJsonObject();
                attributes.add(new NiagaraDefinition.Attribute(a.get("name").getAsString(), integer(a, "type"),
                        integer(a, "component"), integer(a, "count"), string(a, "source_type")));
            }
            JsonObject coverage = emitter.getAsJsonObject("pixel_coverage");
            List<NiagaraDefinition.Renderer> renderers = new ArrayList<>();
            JsonArray rendererItems = emitter.getAsJsonArray("renderers");
            if (rendererItems == null) renderers.add(renderer(emitter, emitter.get("name").getAsString(), meshes));
            else for (JsonElement renderer : rendererItems) renderers.add(renderer(renderer.getAsJsonObject(), null, meshes));
            JsonArray spawnInfo = emitter.getAsJsonArray("spawn_info");
            if (spawnInfo == null && !emitter.has("stateless") && !emitter.has("disabled"))
                throw new JsonParseException("Missing Niagara SpawnInfo list for " + emitter.get("name"));
            NiagaraDefinition.Simulation simulation;
            JsonObject disabled = com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(emitter, "disabled");
            JsonObject stateless = com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(emitter, "stateless");
            JsonObject gpu = com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(emitter, "gpu_program");
            if (disabled != null) {
                if (gpu != null || emitter.has("spawn") || emitter.has("update") || stateless != null)
                    throw new JsonParseException("Disabled emitter cannot contain simulation contexts");
                simulation = new NiagaraDefinition.DisabledSimulation(disabled.get("stateless").getAsBoolean(),
                        disabled.get("has_emitter").getAsBoolean());
            } else if (stateless != null) {
                if (gpu != null || emitter.has("spawn") || emitter.has("update"))
                    throw new JsonParseException("Stateless emitter cannot contain stateful simulation programs");
                simulation = new NiagaraDefinition.StatelessSimulation(string(stateless, "sim_target"), stateless);
            } else if (gpu != null) {
                JsonObject gpuStore = com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(gpu, "execution_store");
                simulation = new NiagaraDefinition.GpuSimulation(Identifier.parse(string(gpu, "hlsl_source")),
                        string(gpu, "shader_pipeline"), gpu,
                        gpuStore != null ? ScriptParameterStoreJson.read(gpuStore, fields) : null,
                        GpuHostContextJson.read(com.kltyton.lumenfx.client.resource.EffectResources.jsonArray(gpu, "host_contexts"), fields));
            } else {
                simulation = new NiagaraDefinition.CpuSimulation(program(emitter.getAsJsonObject("spawn"), fields),
                        program(emitter.getAsJsonObject("update"), fields),
                        emitter.has("fixed_particle_count") ? integer(emitter, "fixed_particle_count") : null,
                        emitter.has("particle_allocation_estimate") ? integer(emitter, "particle_allocation_estimate") : null,
                        emitter.has("requires_persistent_ids") && emitter.get("requires_persistent_ids").getAsBoolean());
            }
            emitters.add(new NiagaraDefinition.Emitter(emitter.get("name").getAsString(), emitter.get("source_name").getAsString(),
                    emitter.has("handle_id") && !emitter.get("handle_id").isJsonNull() ? emitter.get("handle_id").getAsString() : "",
                    integer(emitter, "random_seed"), emitter.get("deterministic").getAsBoolean(),
                    simulation, attributes,
                    spawnInfo == null ? List.of() : strings(spawnInfo),
                    coverage(coverage),
                    emitter.has("sub_image_columns") ? emitter.get("sub_image_columns").getAsFloat() : 1,
                    emitter.has("sub_image_rows") ? emitter.get("sub_image_rows").getAsFloat() : 1,
                    emitter.has("sub_image_blend") && emitter.get("sub_image_blend").getAsBoolean(),
                    emitter.has("sub_image_binding") && !emitter.get("sub_image_binding").isJsonNull()
                            ? emitter.get("sub_image_binding").getAsString() : null,
                    !emitter.has("sub_image_particle_binding") || emitter.get("sub_image_particle_binding").getAsBoolean(),
                    emitter.has("sub_image_default") ? emitter.get("sub_image_default").getAsFloat() : 0, renderers,
                    emitter.has("local_space") && emitter.get("local_space").getAsBoolean(),
                    emitter.has("max_particles") ? integer(emitter, "max_particles") : 0,
                    eventGenerators(emitter.getAsJsonArray("spawn_events")), eventGenerators(emitter.getAsJsonArray("update_events")),
                    emitter.has("shared_event_ids") ? strings(emitter.getAsJsonArray("shared_event_ids")) : List.of(),
                    eventHandlers(emitter.getAsJsonArray("event_handlers"), fields), dependencies(emitter.getAsJsonArray("emitter_dependencies")),
                    emitter.has("bounds_mode") ? boundsMode(string(emitter, "bounds_mode")) : "Dynamic",
                    !emitter.has("fixed_bounds") ? NiagaraBounds.DEFAULT
                            : emitter.get("fixed_bounds").isJsonNull() ? null : bounds(emitter.getAsJsonObject("fixed_bounds")),
                    NiagaraScalabilityJson.emitter(stateless != null
                            ? com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(stateless, "scalability")
                            : com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(emitter, "scalability")),
                    RendererParameterDefaultsJson.read(emitter.getAsJsonObject("renderer_bindings"))));
        }
        var staticMeshes = new LinkedHashMap<Identifier, StaticMeshAsset>();
        var skeletalMeshes = new LinkedHashMap<Identifier, SkeletalMeshAsset>();
        var channels = new LinkedHashMap<String, Channel>();
        var cacheInputs = new LinkedHashMap<Identifier, NiagaraSimulationCache>();
        var sparseInputs = new LinkedHashMap<Identifier, SparseVolumeTextureAsset>();
        var volumeCacheInputs = new LinkedHashMap<Identifier, NiagaraVolumeCacheAsset>();
        var gaussianSplatInputs = new LinkedHashMap<Identifier, NiagaraGaussianSplatAsset>();
        collectResources(value, staticMeshes, skeletalMeshes, meshes, skeletal, channels);
        collectAssetReferences(value, cacheInputs, simCaches, Set.of("sim_cache_reader", "sim_cache_asset"));
        collectAssetReferences(value, sparseInputs, sparseVolumes, Set.of("sparse_volume_texture", "sparse_volume_asset"));
        collectAssetReferences(value, volumeCacheInputs, volumeCaches, Set.of("volume_cache"));
        collectAssetReferences(value, gaussianSplatInputs, gaussianSplats, Set.of("gaussian_splat_reader"));
                var instanceStores = com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "instance_stores");
                return new NiagaraDefinition(program(value.getAsJsonObject("system_spawn"), fields),
                program(value.getAsJsonObject("system_update"), fields), layout(value.getAsJsonArray("system_layout")),
                layout(value.getAsJsonArray("spawn_input_layout")), layout(value.getAsJsonArray("update_input_layout")),
                bindings(value.getAsJsonArray("spawn_input_bindings")), bindings(value.getAsJsonArray("update_input_bindings")),
                integer(value, "system_random_seed"), value.get("system_deterministic").getAsBoolean(), emitters,
                value.has("system_fixed_bounds_enabled") && !value.get("system_fixed_bounds_enabled").isJsonNull()
                        && value.get("system_fixed_bounds_enabled").getAsBoolean(),
                value.has("system_fixed_bounds") && !value.get("system_fixed_bounds").isJsonNull()
                        ? bounds(value.getAsJsonObject("system_fixed_bounds")) : null,
                timing(com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "timing")),
                renderTargets(com.kltyton.lumenfx.client.resource.EffectResources.jsonArray(value, "render_target_instances")),
                NiagaraScalabilityJson.platform(value.getAsJsonObject("platforms")),
                NiagaraScalabilityJson.refreshSets(com.kltyton.lumenfx.client.resource.EffectResources.jsonArray(value, "platform_refresh_sets")),
                NumericStoreDefinitionJson.read(value.getAsJsonObject("instance_numeric")),
                instanceStores != null ? new NiagaraDefinition.InstanceStores(
                        ScriptParameterStoreJson.read(instanceStores.getAsJsonObject("instance"), fields),
                        ScriptParameterStoreJson.read(instanceStores.getAsJsonObject("override"), fields)) : null,
                mutableInterfaces(com.kltyton.lumenfx.client.resource.EffectResources.jsonArray(value, "mutable_interface_instances")),
                NiagaraGpuInterfaceFunctionsJson.read(com.kltyton.lumenfx.client.resource.EffectResources.jsonArray(value, "gpu_interface_functions")),
                staticMeshes, skeletalMeshes, channels,
                com.kltyton.lumenfx.client.resource.EffectResources.jsonString(value, "source_system"), cacheInputs, sparseInputs,
                debugParameters(com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "debug_parameters")),
                persistentDebugShapes(com.kltyton.lumenfx.client.resource.EffectResources.jsonArray(value, "persistent_debug_draw")), volumeCacheInputs,
                lwc(com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "lwc")),
                component(com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "component"), cacheInputs),
                NiagaraScalabilityJson.system(value.get("system_scalability")),
                value.has("initial_streaming_bounds") && !value.get("initial_streaming_bounds").isJsonNull()
                        ? bounds(value.getAsJsonObject("initial_streaming_bounds")) : null,
                componentPool(com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "component_pool")), gaussianSplatInputs);
    }

    private static NiagaraComponentPoolSettings componentPool(@Nullable JsonObject value) {
        return value == null ? NiagaraComponentPoolSettings.DEFAULT : new NiagaraComponentPoolSettings(
                value.has("maximum") ? integer(value, "maximum") : NiagaraComponentPoolSettings.DEFAULT.maximum(),
                value.has("prime") ? integer(value, "prime") : NiagaraComponentPoolSettings.DEFAULT.prime());
    }

    private static NiagaraComponentDefaults component(@Nullable JsonObject value, Map<Identifier, NiagaraSimulationCache> cacheInputs) {
        if (value == null) return NiagaraComponentDefaults.DEFAULT;
        var warmup = value.get("warmup");
        return new NiagaraComponentDefaults(value.has("source") ? string(value, "source") : null,
                value.has("random_seed_offset") ? integer(value, "random_seed_offset") : null,
                warmup == null || warmup.isJsonNull() ? null : new NiagaraTiming.Warmup(
                        integer(warmup.getAsJsonObject(), "ticks"), warmup.getAsJsonObject().get("delta").getAsFloat()),
                !value.has("rendering_enabled") || value.get("rendering_enabled").getAsBoolean(),
                !value.has("allow_scalability") || value.get("allow_scalability").getAsBoolean(),
                value.has("occlusion_query_mode") ? NiagaraComponentDefaults.OcclusionMode.valueOf(string(value, "occlusion_query_mode"))
                        : NiagaraComponentDefaults.OcclusionMode.Default,
                value.has("tick_behavior") ? NiagaraTickBehavior.valueOf(string(value, "tick_behavior")) : NiagaraTickBehavior.UsePrereqs,
                value.has("component_tick_group") ? ComponentTickGroup.valueOf(string(value, "component_tick_group"))
                        : ComponentTickGroup.TG_PrePhysics,
                !value.has("auto_activate") || value.get("auto_activate").getAsBoolean(),
                value.has("auto_destroy") && value.get("auto_destroy").getAsBoolean(),
                lifeCycleTrack(value.get("life_cycle_track")), movieSceneCacheTrack(value.get("cache_track"), cacheInputs),
                value.has("bounds_scale") ? value.get("bounds_scale").getAsFloat() : 1.0f,
                customPrimitiveData(value.getAsJsonArray("custom_primitive_data")),
                autoAttachment(value.has("auto_attachment") && !value.get("auto_attachment").isJsonNull()
                        ? value.getAsJsonObject("auto_attachment") : null),
                new PrimitiveOwnerVisibility(
                        value.has("only_owner_see") && value.get("only_owner_see").getAsBoolean(),
                        value.has("owner_no_see") && value.get("owner_no_see").getAsBoolean()),
                new NiagaraTranslucencySort(value.has("translucency_sort_priority")
                        ? integer(value, "translucency_sort_priority") : 0,
                        value.has("translucency_sort_distance_offset") ? value.get("translucency_sort_distance_offset").getAsFloat() : 0),
                !value.has("render_in_main_pass") || value.get("render_in_main_pass").getAsBoolean(),
                value.has("cast_hidden_shadow") && value.get("cast_hidden_shadow").getAsBoolean(),
                !value.has("draw_in_game") || value.get("draw_in_game").getAsBoolean());
    }

    private static NiagaraComponentAttachment autoAttachment(@Nullable JsonObject value) {
        if (value == null) return NiagaraComponentAttachment.DISABLED;
        JsonObject local = value.has("relative_transform") && !value.get("relative_transform").isJsonNull()
                ? value.getAsJsonObject("relative_transform") : new JsonObject();
        double[] location = vector(local, "location", 0, 0, 0);
        double[] rotation = vector(local, "rotation", 0, 0, 0);
        double[] scale = vector(local, "scale", 1, 1, 1);
        return new NiagaraComponentAttachment(value.has("enabled") && value.get("enabled").getAsBoolean(),
                new NiagaraAutoAttachmentParameters(value.has("parent_identity") && !value.get("parent_identity").isJsonNull()
                        ? string(value, "parent_identity") : null,
                        value.has("socket_name") ? string(value, "socket_name") : "None",
                        attachmentRule(value, "location_rule"), attachmentRule(value, "rotation_rule"),
                        attachmentRule(value, "scale_rule")),
                value.has("weld_simulated_bodies") && value.get("weld_simulated_bodies").getAsBoolean(),
                new NiagaraComponentAttachment.RelativeTransform(location[0], location[1], location[2],
                        rotation[0], rotation[1], rotation[2], scale[0], scale[1], scale[2],
                        local.has("absolute_location") && local.get("absolute_location").getAsBoolean(),
                        local.has("absolute_rotation") && local.get("absolute_rotation").getAsBoolean(),
                        local.has("absolute_scale") && local.get("absolute_scale").getAsBoolean()));
    }

    private static NiagaraAutoAttachmentParameters.Rule attachmentRule(JsonObject value, String name) {
        return value.has(name) ? NiagaraAutoAttachmentParameters.Rule.valueOf(string(value, name))
                : NiagaraAutoAttachmentParameters.Rule.KeepRelative;
    }

    private static double[] vector(JsonObject value, String name, double x, double y, double z) {
        if (!value.has(name) || value.get(name).isJsonNull()) return new double[] {x, y, z};
        var components = value.getAsJsonArray(name);
        if (components.size() < 3) throw new IllegalArgumentException("Niagara component transform vector needs three values");
        return new double[] {components.get(0).getAsDouble(), components.get(1).getAsDouble(), components.get(2).getAsDouble()};
    }

    private static PrimitiveCustomData customPrimitiveData(@Nullable JsonArray data) {
        if (data == null) return PrimitiveCustomData.EMPTY;
        float[] slots = new float[Math.min(PrimitiveCustomData.FLOATS, data.size())];
        for (int index = 0; index < slots.length; index++) slots[index] = data.get(index).getAsFloat();
        return PrimitiveCustomData.EMPTY.with(0, slots);
    }

    private static @Nullable NiagaraSystemLifeCycleTrack lifeCycleTrack(@Nullable JsonElement raw) {
        if (raw == null || raw.isJsonNull()) return null;
        var track = raw.getAsJsonObject();
        var rate = track.getAsJsonObject("display_rate");
        return new NiagaraSystemLifeCycleTrack(string(track, "source"), integer(track, "start_frame"), integer(track, "end_frame"),
                new FrameRate(integer(rate, "numerator"), integer(rate, "denominator")), track.get("frame_locked").getAsBoolean(),
                EvaluateBehavior.valueOf(string(track, "evaluate_behavior")), EndBehavior.valueOf(string(track, "end_behavior")),
                NiagaraAgeUpdate.Mode.valueOf(string(track, "age_update_mode")), track.get("allow_scalability").getAsBoolean());
    }

    private static @Nullable NiagaraMovieSceneCacheTrack movieSceneCacheTrack(@Nullable JsonElement raw,
            Map<Identifier, NiagaraSimulationCache> cacheInputs) {
        if (raw == null || raw.isJsonNull()) return null;
        var track = raw.getAsJsonObject();
        var sections = new ArrayList<NiagaraMovieSceneCacheTrack.Section>();
        for (var item : track.getAsJsonArray("sections")) {
            var section = item.getAsJsonObject();
            var asset = section.getAsJsonObject("sim_cache_asset").get("asset");
            var cache = asset == null || asset.isJsonNull() ? null
                    : Objects.requireNonNull(cacheInputs.get(Identifier.parse(asset.getAsString())), "MovieScene cache");
            sections.add(new NiagaraMovieSceneCacheTrack.Section(integer(section, "start_frame"), integer(section, "end_frame"),
                    cache, section.get("play_rate").getAsFloat(), integer(section, "start_offset"), integer(section, "end_offset"),
                    integer(section, "first_loop_offset"), section.get("reverse").getAsBoolean(),
                    ReplayMode.valueOf(string(section, "replay_mode")), StretchMode.valueOf(string(section, "stretch_mode"))));
        }
        return new NiagaraMovieSceneCacheTrack(string(track, "source"), sections);
    }

    private static NiagaraLwcSettings lwc(@Nullable JsonObject value) {
        if (value == null) return NiagaraLwcSettings.DEFAULT;
        return new NiagaraLwcSettings(value.get("enabled").getAsBoolean(),
                NiagaraLwcSettings.UpdateMode.valueOf(value.get("update_mode").getAsString()),
                value.get("maximum_tiles_before_reset").getAsLong());
    }

    private static List<NiagaraPersistentDebugShape> persistentDebugShapes(@Nullable JsonArray source) {
        if (source == null) return List.of();
        var shapes = new ArrayList<NiagaraPersistentDebugShape>(source.size());
        for (var element : source) {
            var shape = element.getAsJsonObject();
            var tags = new ArrayList<NiagaraPersistentDebugShape.Tag>();
            for (var entry : shape.getAsJsonArray("tags")) {
                var tag = entry.getAsJsonObject();
                byte[] data = Base64.getDecoder().decode(string(tag, "data"));
                if (data.length % Integer.BYTES != 0) throw new IllegalArgumentException("Unaligned Niagara compiler tag");
                var words = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                var literal = new ArrayList<Integer>(words.remaining());
                while (words.hasRemaining()) literal.add(words.get());
                var binding = new Key(string(tag, "binding"), string(tag, "type"), integer(tag, "underlying"),
                        integer(tag, "flags"), Storage.NUMERIC);
                tags.add(new NiagaraPersistentDebugShape.Tag(string(tag, "name"), binding, integer(tag, "bytes"), literal));
            }
            shapes.add(new NiagaraPersistentDebugShape(string(shape, "interface"), string(shape, "function"),
                    integer(shape, "line_limit"), shape.get("local").getAsBoolean(), tags));
        }
        return List.copyOf(shapes);
    }

    private static @Nullable NiagaraDebugParameters debugParameters(@Nullable JsonObject source) {
        if (source == null) return null;
        var names = new ArrayList<String>();
        for (var name : source.getAsJsonArray("emitter_names")) names.add(name.getAsString());
        var parameters = new ArrayList<NiagaraCacheDebugData.Parameter>();
        for (var element : source.getAsJsonArray("static_values")) {
            var value = element.getAsJsonObject();
            var key = new Key(
                    string(value, "name"), string(value, "type"), integer(value, "underlying"), integer(value, "flags"),
                    Storage.NUMERIC);
            parameters.add(new NiagaraCacheDebugData.Parameter(key, Base64.getDecoder().decode(string(value, "data")), null));
        }
        return new NiagaraDebugParameters(names, parameters);
    }

    private static <T> void collectAssetReferences(JsonElement value, Map<Identifier, T> assets,
                                                     Function<Identifier, T> resolver, Set<String> keys) {
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (keys.contains(entry.getKey()) && entry.getValue().isJsonObject()) {
                    var asset = entry.getValue().getAsJsonObject().get("asset");
                    if (asset != null && !asset.isJsonNull()) assets.computeIfAbsent(Identifier.parse(asset.getAsString()), resolver);
                } else collectAssetReferences(entry.getValue(), assets, resolver, keys);
            }
        } else if (value.isJsonArray()) for (var child : value.getAsJsonArray()) collectAssetReferences(child, assets, resolver, keys);
    }

    private static void collectResources(JsonElement value, Map<Identifier, StaticMeshAsset> staticMeshes,
                                      Map<Identifier, SkeletalMeshAsset> skeletalMeshes, Function<Identifier, StaticMeshAsset> meshes,
                                      Function<Identifier, SkeletalMeshAsset> skeletal, Map<String, Channel> channels) {
        if (value.isJsonObject()) for (var entry : value.getAsJsonObject().entrySet()) {
            switch (entry.getKey()) {
                case "static_mesh_reader", "static_mesh_parameter" -> mesh(entry.getValue(), staticMeshes, meshes);
                case "skeletal_mesh_reader", "skeletal_mesh_parameter" -> mesh(entry.getValue(), skeletalMeshes, skeletal);
                case "data_channel" -> {
                    if (!entry.getValue().isJsonObject() || !entry.getValue().getAsJsonObject().has("writer")) continue;
                    var channel = NiagaraDataChannelDefinition.read(entry.getValue().getAsJsonObject()).channel();
                    if (channel == null) continue;
                    var previous = channels.putIfAbsent(channel.path(), channel);
                    if (previous != null && !previous.equals(channel))
                        throw new JsonParseException("Conflicting Niagara data channel schemas: " + channel.path());
                }
                default -> collectResources(entry.getValue(), staticMeshes, skeletalMeshes, meshes, skeletal, channels);
            }
        } else if (value.isJsonArray()) for (var item : value.getAsJsonArray())
            collectResources(item, staticMeshes, skeletalMeshes, meshes, skeletal, channels);
    }

    private static <T> void mesh(JsonElement value, Map<Identifier, T> output, Function<Identifier, T> loader) {
        if (!value.isJsonObject()) return;
        var mesh = value.getAsJsonObject();
        if (mesh.has("asset_source")) throw new JsonParseException("Unpackaged Niagara mesh source");
        if (mesh.has("asset")) output.computeIfAbsent(Identifier.parse(mesh.get("asset").getAsString()), id ->
                Objects.requireNonNull(loader.apply(id), "Missing Niagara mesh " + id));
    }

    private static List<NiagaraDefinition.MutableInterfaceInstance> mutableInterfaces(com.google.gson.JsonArray values) {
        if (values == null) return List.of();
        var result = new ArrayList<NiagaraDefinition.MutableInterfaceInstance>();
        for (var item : values) {
            var value = item.getAsJsonObject();
            String id = string(value, "interface_id"), type = string(value, "class");
            var data = value.getAsJsonObject("data");
            boolean valid = type.equals("NiagaraDataInterfaceSimpleCounter")
                    ? data.has("initial") && data.has("gpu_sync_mode")
                    : validArrayData(data) && SourceNames.equal(type, string(data, "class"));
            if (id.isEmpty() || !valid) throw new JsonParseException("Invalid mutable data interface initialization " + id);
            result.add(new NiagaraDefinition.MutableInterfaceInstance(id, type,
                    string(value, "parameter_name"), value.get("used_cpu").getAsBoolean(), value.get("used_gpu").getAsBoolean(),
                    data));
        }
        return List.copyOf(result);
    }

    private static List<NiagaraDefinition.RenderTargetInstance> renderTargets(com.google.gson.JsonArray values) {
        if (values == null) return List.of();
        var result = new java.util.ArrayList<NiagaraDefinition.RenderTargetInstance>();
        for (var item : values) {
            var value = item.getAsJsonObject();
            if (!"NiagaraDataInterfaceRenderTarget2D".equals(value.get("class").getAsString()))
                throw new IllegalArgumentException("Invalid render target initialization class");
            result.add(new NiagaraDefinition.RenderTargetInstance(value.get("interface_id").getAsString(),
                    value.get("used_cpu").getAsBoolean(), value.get("used_gpu").getAsBoolean(), value.getAsJsonObject("data")));
        }
        return List.copyOf(result);
    }

    private static NiagaraTiming timing(JsonObject value) {
        if (value == null) return NiagaraTiming.DEFAULT;
        float maximumDelta = NiagaraTiming.DEFAULT_MAXIMUM_DELTA;
        if (value.has("maximum_delta")) {
            var limit = value.get("maximum_delta");
            maximumDelta = limit.isJsonNull() ? Float.POSITIVE_INFINITY : limit.getAsFloat();
        }
        var state = value.get("system_state");
        return new NiagaraTiming(
                value.get("warmup_ticks").getAsInt(), value.get("warmup_delta").getAsFloat(),
                value.get("fixed_tick").getAsBoolean(), value.get("fixed_delta").getAsFloat(),
                !value.has("require_current_frame_data") || value.get("require_current_frame_data").getAsBoolean(), maximumDelta,
                state == null || state.isJsonNull() ? NiagaraSystemState.DEFAULT : systemState(state.getAsJsonObject()),
                !value.has("initial_owner_velocity_from_actor") || value.get("initial_owner_velocity_from_actor").getAsBoolean());
    }

    private static NiagaraSystemState systemState(JsonObject value) {
        return new NiagaraSystemState(value.get("run_spawn").getAsBoolean(), value.get("run_update").getAsBoolean(),
                value.get("ignore_state").getAsBoolean(), value.get("resample_duration").getAsBoolean(),
                value.get("delay_enabled").getAsBoolean(), value.get("first_delay_only").getAsBoolean(),
                value.get("resample_delay").getAsBoolean(),
                NiagaraSystemState.InactiveResponse.valueOf(string(value, "inactive_response").toUpperCase(Locale.ROOT)),
                NiagaraSystemState.LoopBehavior.valueOf(string(value, "loop_behavior").toUpperCase(Locale.ROOT)),
                integer(value, "loop_count"), stateRange(value.getAsJsonArray("duration")), stateRange(value.getAsJsonArray("delay")));
    }

    private static NiagaraSystemState.Range stateRange(JsonArray range) {
        if (range.size() != 2) throw new JsonParseException("Invalid Niagara system state range");
        return new NiagaraSystemState.Range(range.get(0).getAsFloat(), range.get(1).getAsFloat());
    }

    private static NiagaraBounds bounds(JsonObject value) {
        JsonArray min = value.getAsJsonArray("min"), max = value.getAsJsonArray("max");
        if (min == null || max == null || min.size() != 3 || max.size() != 3)
            throw new JsonParseException("Invalid Niagara bounds");
        return new NiagaraBounds(min.get(0).getAsDouble(), min.get(1).getAsDouble(), min.get(2).getAsDouble(),
                max.get(0).getAsDouble(), max.get(1).getAsDouble(), max.get(2).getAsDouble());
    }

    private static List<NiagaraDefinition.EventGenerator> eventGenerators(JsonArray source) {
        if (source == null) return List.of();
        List<NiagaraDefinition.EventGenerator> result = new ArrayList<>();
        for (var item : source) {
            JsonObject value = item.getAsJsonObject();
            result.add(new NiagaraDefinition.EventGenerator(string(value, "id"), integer(value, "max_events_per_frame"),
                    layout(value.getAsJsonArray("layout"))));
        }
        return List.copyOf(result);
    }

    private static List<NiagaraDefinition.EventHandler> eventHandlers(JsonArray source, Function<Identifier, VectorFieldVolume> fields) {
        if (source == null) return List.of();
        List<NiagaraDefinition.EventHandler> result = new ArrayList<>();
        for (var item : source) {
            JsonObject value = item.getAsJsonObject();
            result.add(new NiagaraDefinition.EventHandler(eventExecutionMode(string(value, "execution_mode")), string(value, "source_emitter_id"),
                    string(value, "source_event_name"), value.get("spawn_number").getAsLong(),
                    value.get("min_spawn_number").getAsLong(), value.get("max_events_per_frame").getAsLong(),
                    value.get("random_spawn_number").getAsBoolean(), value.get("update_attribute_initial_values").getAsBoolean(),
                    value.has("program") && !value.get("program").isJsonNull() ? program(value.getAsJsonObject("program"), fields) : null));
        }
        return List.copyOf(result);
    }

    private static String eventExecutionMode(String value) {
        return switch (sourceEnumName(value)) {
            case "0", "EveryParticle" -> "EveryParticle";
            case "1", "SpawnedParticles" -> "SpawnedParticles";
            case "2", "SingleParticle" -> "SingleParticle";
            default -> throw new JsonParseException("Unknown Niagara event execution mode: " + value);
        };
    }

    private static String boundsMode(String value) {
        return switch (sourceEnumName(value)) {
            case "0", "Dynamic" -> "Dynamic";
            case "1", "Fixed" -> "Fixed";
            case "2", "Programmable" -> "Programmable";
            default -> throw new JsonParseException("Unknown Niagara emitter bounds mode: " + value);
        };
    }

    private static String sourceEnumName(String value) {
        int separator = value.lastIndexOf("::");
        return separator < 0 ? value : value.substring(separator + 2);
    }

    private static List<NiagaraDefinition.EmitterDependency> dependencies(JsonArray source) {
        if (source == null) return List.of();
        List<NiagaraDefinition.EmitterDependency> result = new ArrayList<>();
        for (var item : source) {
            JsonObject value = item.getAsJsonObject();
            String mode = value.has("BindingMode") ? value.get("BindingMode").getAsString() : "Other";
            mode = switch (sourceEnumName(mode)) {
                case "0", "Self" -> "Self";
                case "1", "Other" -> "Other";
                default -> throw new JsonParseException("Invalid Niagara emitter dependency binding mode: " + mode);
            };
            String name = value.has("EmitterName") ? value.get("EmitterName").getAsString() : "None";
            result.add(new NiagaraDefinition.EmitterDependency(mode, name));
        }
        return List.copyOf(result);
    }

    private static NiagaraDefinition.Renderer renderer(JsonObject value, @Nullable String fallbackName,
                                                       Function<Identifier, StaticMeshAsset> meshes) {
        JsonObject coverage = value.getAsJsonObject("pixel_coverage");
        return new NiagaraDefinition.Renderer(fallbackName != null ? fallbackName : value.get("name").getAsString(),
                coverage(coverage),
                value.has("sub_image_columns") ? value.get("sub_image_columns").getAsFloat() : 1,
                value.has("sub_image_rows") ? value.get("sub_image_rows").getAsFloat() : 1,
                value.has("sub_image_blend") && value.get("sub_image_blend").getAsBoolean(),
                value.has("sub_image_binding") && !value.get("sub_image_binding").isJsonNull() ? value.get("sub_image_binding").getAsString() : null,
                !value.has("sub_image_particle_binding") || value.get("sub_image_particle_binding").getAsBoolean(),
                value.has("sub_image_default") ? value.get("sub_image_default").getAsFloat() : 0,
                value.has("uv_scale_binding") && !value.get("uv_scale_binding").isJsonNull() ? value.get("uv_scale_binding").getAsString() : null,
                !value.has("uv_scale_particle_binding") || value.get("uv_scale_particle_binding").getAsBoolean(),
                value.has("dynamic_parameter_binding") && !value.get("dynamic_parameter_binding").isJsonNull()
                        ? value.get("dynamic_parameter_binding").getAsString() : null,
                !value.has("dynamic_parameter_particle_binding") || value.get("dynamic_parameter_particle_binding").getAsBoolean(),
                value.has("dynamic_parameter_channel_mask") ? integer(value, "dynamic_parameter_channel_mask") : 15,
                value.has("ribbon_link_binding") && !value.get("ribbon_link_binding").isJsonNull() ? value.get("ribbon_link_binding").getAsString() : "Particles.RibbonLinkOrder",
                value.has("ribbon_id_binding") && !value.get("ribbon_id_binding").isJsonNull() ? value.get("ribbon_id_binding").getAsString() : "Particles.RibbonID",
                !value.has("ribbon_unique_fallback") || value.get("ribbon_unique_fallback").getAsBoolean(),
                !value.has("sort_only_when_translucent") || value.get("sort_only_when_translucent").getAsBoolean(),
                value.has("custom_sort_binding") && !value.get("custom_sort_binding").isJsonNull()
                        ? value.get("custom_sort_binding").getAsString() : "Particles.Age",
                !value.has("custom_sort_particle_binding") || value.get("custom_sort_particle_binding").getAsBoolean(),
                value.has("sort_precision") ? value.get("sort_precision").getAsString() : "Default",
                value.has("distance_culling") && value.get("distance_culling").getAsBoolean(),
                value.has("min_camera_distance") ? value.get("min_camera_distance").getAsFloat() : 0,
                value.has("max_camera_distance") ? value.get("max_camera_distance").getAsFloat() : 1000,
                value.has("renderer_visibility") ? value.get("renderer_visibility").getAsInt() : 0,
                value.has("visibility_binding") && !value.get("visibility_binding").isJsonNull()
                        ? value.get("visibility_binding").getAsString() : "Particles.VisibilityTag",
                !value.has("visibility_particle_binding") || value.get("visibility_particle_binding").getAsBoolean(),
                value.has("enabled_binding") && !value.get("enabled_binding").isJsonNull()
                        ? value.get("enabled_binding").getAsString() : null,
                value.has("source_mode") ? value.get("source_mode").getAsString() : "Particles",
                rendererBindings(com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "attribute_bindings")),
                value.has("alignment") && !value.get("alignment").isJsonNull() ? value.get("alignment").getAsString() : "Unaligned",
                value.has("facing_mode") && !value.get("facing_mode").isJsonNull() ? value.get("facing_mode").getAsString() : "FaceCamera",
                floating(value, "pivot_x", .5f),
                floating(value, "pivot_y", .5f),
                floating(value, "facing_blend_min", 0),
                floating(value, "facing_blend_max", 0),
                com.kltyton.lumenfx.effect.render.ParticleMotionSettings.read(
                        value.has("motion_vector_setting") && !value.get("motion_vector_setting").isJsonNull()
                                ? value.get("motion_vector_setting").getAsString() : "AutoDetect",
                        value.has("motion_vector_default") && !value.get("motion_vector_default").isJsonNull()
                                ? value.get("motion_vector_default").getAsString() : "Precise"),
                com.kltyton.lumenfx.client.resource.niagara.renderer.ComponentRendererJson.read(
                        com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "component"), meshes),
                surfaceMaterialBinding(value.get("surface_material_binding")),
                value.has("gpu_translucent_latency") && !value.get("gpu_translucent_latency").isJsonNull()
                        ? NiagaraDefinition.GpuTranslucentLatency.valueOf(value.get("gpu_translucent_latency").getAsString())
                        : NiagaraDefinition.GpuTranslucentLatency.ProjectDefault,
                !value.has("allow_in_cull_proxies") || value.get("allow_in_cull_proxies").getAsBoolean());
    }

    private static NiagaraDefinition.@Nullable SurfaceMaterialBinding surfaceMaterialBinding(@Nullable JsonElement source) {
        if (source == null || source.isJsonNull()) return null;
        var value = source.getAsJsonObject();
        return new NiagaraDefinition.SurfaceMaterialBinding(string(value, "parameter"), string(value, "variant"));
    }

    private static SpriteCoverage coverage(JsonObject value) {
        int channels = integer(value, "channels");
        return new SpriteCoverage(channels, value.get("blend").getAsFloat(),
                value.has("enabled") ? value.get("enabled").getAsBoolean() : channels != 0,
                value.has("automatic") && value.get("automatic").getAsBoolean());
    }

    private static Map<String, NiagaraDefinition.RendererAttributeBinding> rendererBindings(JsonObject value) {
        if (value == null) return Map.of();
        Map<String, NiagaraDefinition.RendererAttributeBinding> result = new LinkedHashMap<>();
        for (var entry : value.entrySet()) {
            JsonObject binding = entry.getValue().getAsJsonObject();
            result.put(entry.getKey(), new NiagaraDefinition.RendererAttributeBinding(string(binding, "name"),
                    binding.get("particle").getAsBoolean(), binding.has("channel_mask") ? binding.get("channel_mask").getAsInt() : 15,
                    binding.has("source_type") && !binding.get("source_type").isJsonNull() ? binding.get("source_type").getAsString() : null));
        }
        return Map.copyOf(result);
    }

    private static List<NiagaraDefinition.Binding> bindings(JsonArray value) {
        List<NiagaraDefinition.Binding> bindings = new ArrayList<>();
        for (JsonElement item : value) {
            JsonObject binding = item.getAsJsonObject();
            bindings.add(new NiagaraDefinition.Binding(integer(binding, "emitter"), integer(binding, "runtime_offset"),
                    integer(binding, "type"), integer(binding, "component")));
        }
        return bindings;
    }

    private static VectorProgram program(JsonObject value, Function<Identifier, VectorFieldVolume> fields) {
        List<VectorProgram.Parameter> parameters = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray("parameters")) {
            JsonObject p = item.getAsJsonObject();
            parameters.add(new VectorProgram.Parameter(p.get("name").getAsString(), integer(p, "offset"), integer(p, "bytes"),
                    p.has("source_type") ? p.get("source_type").getAsString() : "", NiagaraCollectionJson.read(p),
                    p.has("underlying") ? integer(p, "underlying") : 2, p.has("type_flags") ? integer(p, "type_flags") : 0,
                    p.has("lwc") && !p.get("lwc").isJsonNull() ? p.get("lwc").getAsBoolean() : null));
        }
        List<VectorProgram.Input> inputs = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray("inputs")) {
            JsonObject input = item.getAsJsonObject();
            inputs.add(new VectorProgram.Input(integer(input, "dataset"), integer(input, "type"), integer(input, "component"),
                    input.get("constant").getAsBoolean()));
        }
        List<VectorProgram.Output> outputs = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray("outputs")) {
            JsonObject output = item.getAsJsonObject();
            outputs.add(new VectorProgram.Output(integer(output, "dataset"), integer(output, "type"), integer(output, "component")));
        }
        List<VectorProgram.External> external = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray("external")) {
            JsonObject binding = item.getAsJsonObject();
            JsonArray curveArray = com.kltyton.lumenfx.client.resource.EffectResources.jsonArray(binding, "curves");
            List<NiagaraCurve> curves = curveArray != null ? curveDefaults(curveArray) : List.of();
            String fieldName = com.kltyton.lumenfx.client.resource.EffectResources.jsonString(binding, "field");
            VectorFieldVolume field = fieldName != null ? fields.apply(Identifier.parse(fieldName)) : null;
            JsonArray tile = com.kltyton.lumenfx.client.resource.EffectResources.jsonArray(binding, "tile");
            String name = binding.get("name").getAsString();
            int ins = integer(binding, "inputs"), outs = integer(binding, "outputs");
            String interfaceType = optionalString(binding, "interface_type", "");
            if (interfaceType.isEmpty() && (field == null && (ins != 1 || curves.size() != outs) || field != null && (ins != 3 || outs != 3)))
                throw new JsonParseException("Invalid Niagara data interface binding " + name);
            validateInterface(binding, interfaceType, name, ins, outs);
            JsonObject interfaceKey = com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(binding, "interface_key");
            JsonObject instanceKey = com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(binding, "instance_key");
            external.add(new VectorProgram.External(name, ins, outs, curves, field,
                    tile != null && tile.get(0).getAsBoolean(), tile != null && tile.get(1).getAsBoolean(),
                    tile != null && tile.get(2).getAsBoolean(), interfaceType,
                    optionalString(binding, "interface_id", ""),
                    binding.has("controller_index") && !binding.get("controller_index").isJsonNull()
                            ? binding.get("controller_index").getAsInt() : 0,
                    !binding.has("require_current") || binding.get("require_current").getAsBoolean(),
                    com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(binding, "interface_data"),
                    optionalString(binding, "interface_parameter", ""),
                    interfaceKey != null ? ScriptParameterStoreJson.key(interfaceKey) : null,
                    com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(binding, "binding_info"),
                    instanceKey != null ? ScriptParameterStoreJson.key(instanceKey) : null));
        }
        List<VectorProgram.Instruction> instructions = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray("instructions")) {
            JsonObject instruction = item.getAsJsonObject();
            VectorProgram.Opcode opcode = VectorProgram.Opcode.valueOf(instruction.get("op").getAsString().toUpperCase(Locale.ROOT));
            instructions.add(new VectorProgram.Instruction(opcode, integer(instruction, "inputs"),
                    instruction.has("dataset") ? integer(instruction, "dataset") : -1,
                    instruction.has("function") ? integer(instruction, "function") : -1,
                    integers(instruction.getAsJsonArray("registers")),
                    instruction.has("user_ptr_register") ? integer(instruction, "user_ptr_register") : -1));
        }
        JsonObject executionStore = com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "execution_store");
        return new VectorProgram(integer(value, "temporary_registers"), integer(value, "output_registers"),
                integer(value, "max_output_datasets"), integer(value, "max_external_registers"), integer(value, "dummy_registers"),
                integer(value, "runtime_bytes"), value.get("interpolated").getAsBoolean(), integer(value, "parameter_bytes"),
                parameters, Base64.getDecoder().decode(value.get("constant_data").getAsString()),
                integers(value.getAsJsonArray("constant_slots")), inputs, outputs, external, instructions,
                executionStore != null ? ScriptParameterStoreJson.read(executionStore, fields) : null,
                userPointers(com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(value, "user_pointers")));
    }

    private static VectorProgram.UserPointerLayout userPointers(@Nullable JsonObject value) {
        if (value == null) return VectorProgram.UserPointerLayout.EMPTY;
        var bindings = new ArrayList<VectorProgram.UserPointer>();
        for (var item : value.getAsJsonArray("bindings")) {
            var binding = item.getAsJsonObject();
            bindings.add(new VectorProgram.UserPointer(integer(binding, "index"), ScriptParameterStoreJson.key(binding.getAsJsonObject("key")),
                    binding.has("instance_key") && !binding.get("instance_key").isJsonNull()
                            ? ScriptParameterStoreJson.key(binding.getAsJsonObject("instance_key")) : null));
        }
        return new VectorProgram.UserPointerLayout(integer(value, "count"), bindings);
    }

    private static void validateInterface(JsonObject binding, String type, String name, int inputs, int outputs) {
        boolean valid = switch (type) {
            case "" -> true;
            case "platform_set" -> validPlatformSet(binding, name, inputs, outputs);
            case "object_property_reader" -> inputs == 0 && binding.has("interface_key") && binding.has("interface_data")
                    && NiagaraObjectPropertyReader.Request.read(name, binding.getAsJsonObject("interface_data")).outputs() == outputs;
            case "property_interface" -> inputs == 0 && name.equals("GetInterfaceData") && binding.has("interface_id")
                    && binding.has("interface_data") && NiagaraPropertyInterface.Output.read(
                            binding.getAsJsonObject("interface_data").getAsJsonArray("outputs"))
                            .stream().mapToInt(NiagaraPropertyInterface.Output::width).sum() == outputs;
            case "actor_component" -> binding.has("interface_key") && inputs == 0 && switch (name) {
                case "GetMatrix" -> outputs == 17;
                case "GetTransform" -> outputs == 11;
                case "GetVelocity" -> outputs == 4;
                default -> false;
            };
            case "socket_reader" -> binding.has("interface_key") && binding.has("interface_id")
                    && !binding.get("interface_id").getAsString().isEmpty() && switch (name) {
                        case "IsValid", "GetSocketCount", "GetFilteredSocketCount", "GetUnfilteredSocketCount" ->
                                inputs == 0 && outputs == 1;
                        case "GetComponentToWorld" -> inputs == 0 && outputs == 10;
                        case "GetSocketTransform", "GetFilteredSocketTransform", "GetUnfilteredSocketTransform" ->
                                inputs == 1 && outputs == 13;
                        case "GetSocketTransformInterpolated", "GetFilteredSocketTransformInterpolated",
                                "GetUnfilteredSocketTransformInterpolated" -> inputs == 2 && outputs == 13;
                        default -> false;
                    };
            case "collision_query" -> binding.has("interface_key") && binding.has("interface_id")
                    && !binding.get("interface_id").getAsString().isEmpty() && switch (name) {
                case "PerformCollisionQuerySyncCPU" -> inputs == 9 && outputs == 11;
                case "PerformCollisionQueryAsyncCPU" -> inputs == 10 && outputs == 12;
                default -> false;
            };
            case "console_variable" -> binding.has("interface_key") && binding.has("interface_data")
                    && binding.getAsJsonObject("interface_data").has("console_name") && inputs == 0 && outputs == 2
                    && switch (name) {
                        case "GetConsoleVariableFloat", "GetConsoleVariableInt", "GetConsoleVariableBool" -> true;
                        default -> false;
                    };
            case "ribbon_shape" -> binding.has("interface_key") && inputs >= 1 && outputs == 0 && switch (name) {
                case "SetRibbonShape" -> (inputs - 1) % 2 == 0;
                case "SetRibbonShapeFull" -> (inputs - 1) % 5 == 0;
                default -> false;
            };
            case "data_channel" -> binding.has("interface_key") && binding.has("interface_id") && binding.has("interface_data")
                    && switch (name) {
                        case "Num" -> inputs == 0 && outputs == 1;
                        case "Read" -> inputs == 1 && outputs >= 1;
                        case "Consume" -> inputs == 1 && outputs >= 2;
                        case "GetNDCSpawnData" -> inputs == 2 && outputs == 3;
                        case "SpawnConditional" -> inputs >= 6 && outputs == 0;
                        case "SpawnDirect", "ScaleSpawnCount" -> inputs == 7 && outputs == 0;
                        case "Write" -> inputs >= 2 && outputs == 1;
                        case "Append" -> inputs >= 1 && outputs == 1;
                        default -> false;
                    };
            case "particle_export" -> binding.has("interface_key") && binding.has("interface_id")
                    && inputs == 8 && outputs == 1 && (name.equals("StoreParticleData") || name.equals("ExportParticleData"));
            case "audio_player" -> binding.has("interface_key") && binding.has("interface_id") && switch (name) {
                case "PlayAudioAtLocation" -> (inputs == 10 || inputs == 11) && outputs == 1;
                case "PlayPersistentAudio" -> inputs == 13 && outputs == 1;
                case "UpdateAudioVolume", "UpdateAudioPitch", "SetPaused" -> inputs == 2 && outputs == 0;
                case "UpdateAudioLocation", "UpdateAudioRotation" -> inputs == 4 && outputs == 0;
                case "SetBooleanParameter", "SetIntegerParameter", "SetFloatParameter" -> inputs == 3 && outputs == 0;
                case "SetInitialBooleanParameter", "SetInitialIntegerParameter", "SetInitialFloatParameter" -> inputs == 4 && outputs == 0;
                default -> false;
            };
            case "audio_analysis" -> binding.has("interface_key") && binding.has("interface_id") && outputs == 1
                    && switch (name) {
                        case "AudioSpectrum", "SampleAudioBuffer" -> inputs == 2;
                        case "GetNumChannels", "GetAudioBufferNumChannels" -> inputs == 0;
                        default -> false;
                    };
            case "skeletal_mesh" -> binding.has("interface_key") && binding.has("interface_id") && switch (name) {
                case "GetPreSkinnedLocalBounds" -> inputs == 0 && outputs == 15;
                case "GetBoneCount", "GetFilteredBoneCount", "GetUnfilteredBoneCount", "GetFilteredSocketCount",
                        "GetFilteredSocketOrBoneCount", "GetVertexCount", "GetFilteredVertexCount", "GetTriangleCount",
                        "GetFilteredTriangleCount" -> inputs == 0 && outputs == 1;
                case "IsValidBone", "GetParentBone", "GetFilteredBone", "GetUnfilteredBone", "GetFilteredSocket",
                        "GetFilteredSocketOrBone", "IsValidVertex", "IsValidFilteredVertex", "GetFilteredVertex" -> inputs == 1 && outputs == 1;
                case "RandomBone", "RandomFilteredBone", "RandomUnfilteredBone", "RandomFilteredSocket",
                        "RandomFilteredSocketOrBone", "RandomVertex", "RandomFilteredVertex" -> inputs == 3 && outputs == 1;
                case "GetFilteredSocketTransform" -> inputs == 2 && outputs == 10;
                case "GetSkinnedBoneData", "GetSkinnedBoneDataWS" -> inputs == 1 && outputs == 13;
                case "GetSkinnedBoneDataInterpolated", "GetSkinnedBoneDataWSInterpolated" -> inputs == 2 && outputs == 13;
                case "GetVertexColor" -> inputs == 1 && outputs == 4;
                case "GetVertexUV" -> inputs == 2 && outputs == 2;
                case "GetVertexData" -> inputs == 1 && outputs == 12;
                case "GetSkinnedVertexData", "GetSkinnedVertexDataWS" -> inputs == 1 && outputs == 15;
                case "GetSkinnedVertexDataInterpolated", "GetSkinnedVertexDataInterpolatedWS" -> inputs == 2 && outputs == 15;
                case "RandomTriCoord", "RandomTriangle", "RandomFilteredTriangle" -> inputs == 3 && outputs == 4;
                case "IsValidTriCoord" -> inputs == 4 && outputs == 1;
                case "GetTriangleIndices", "GetTriCoordVertices" -> inputs == 1 && outputs == 3;
                case "GetTriangleData" -> inputs == 4 && outputs == 12;
                case "GetSkinnedTriangleData", "GetSkinnedTriangleDataWS" -> inputs == 4 && outputs == 15;
                case "GetSkinnedTriangleDataInterpolated", "GetSkinnedTriangleDataWSInterpolated" -> inputs == 5 && outputs == 15;
                case "GetSkinnedTriangleVertexData", "GetSkinnedTriangleVertexDataWS" -> inputs == 1 && outputs == 45;
                case "GetSkinnedTriangleVertexDataInterpolated", "GetSkinnedTriangleVertexDataWSInterpolated" -> inputs == 2 && outputs == 45;
                case "GetTriColor", "GetFilteredTriangle" -> inputs == 4 && outputs == 4;
                case "GetTriUV" -> inputs == 5 && outputs == 2;
                case "GetTriangleCoordAtUV" -> inputs == 4 && outputs == 5;
                case "GetTriangleCoordInAabb" -> inputs == 5 && outputs == 5;
                default -> false;
            };
            case "static_mesh" -> binding.has("interface_key") && binding.has("interface_id") && switch (name) {
                case "IsValid", "GetVertexCount", "GetTriangleCount", "GetFilteredTriangleCount", "GetUnfilteredTriangleCount",
                        "GetSectionCount", "GetFilteredSectionCount", "GetUnfilteredSectionCount", "GetSocketCount",
                        "GetFilteredSocketCount", "GetUnfilteredSocketCount", "GetInstanceIndex" -> inputs == 0 && outputs == 1;
                case "IsValidVertex", "IsValidTriangle", "IsValidFilteredTriangle", "IsValidUnfilteredTriangle", "IsValidSection",
                        "GetFilteredTriangleAt", "GetUnfilteredTriangleAt", "GetFilteredSectionAt", "GetUnfilteredSectionAt",
                        "GetSectionTriangleCount", "GetFilteredSocket", "GetUnfilteredSocket" -> inputs == 1 && outputs == 1;
                case "GetVertexColor" -> inputs == 1 && outputs == 4;
                case "GetVertexUV" -> inputs == 2 && outputs == 2;
                case "GetVertex", "GetVertexWS" -> inputs == 1 && outputs == 15;
                case "GetVertexWSInterpolated" -> inputs == 2 && outputs == 15;
                case "GetTriangleIndices" -> inputs == 1 && outputs == 3;
                case "RandomVertex", "RandomSocket", "RandomFilteredSocket", "RandomUnfilteredSocket",
                        "RandomSection", "RandomFilteredSection", "RandomUnfilteredSection" -> inputs == 3 && outputs == 1;
                case "RandomTriangle", "RandomFilteredTriangle", "RandomUnfilteredTriangle" -> inputs == 3 && outputs == 4;
                case "RandomSectionTriangle" -> inputs == 4 && outputs == 4;
                case "GetTriangle", "GetTriangleWS" -> inputs == 4 && outputs == 15;
                case "GetTriangleWSInterpolated" -> inputs == 5 && outputs == 15;
                case "GetTriangleColor" -> inputs == 4 && outputs == 4;
                case "GetTriangleUV" -> inputs == 5 && outputs == 2;
                case "GetSocketTransform", "GetSocketTransformWS", "GetFilteredSocketTransform", "GetFilteredSocketTransformWS",
                        "GetUnfilteredSocketTransform", "GetUnfilteredSocketTransformWS" -> inputs == 1 && outputs == 13;
                case "GetSocketTransformWSInterpolated", "GetFilteredSocketTransformWSInterpolated",
                        "GetUnfilteredSocketTransformWSInterpolated" -> inputs == 2 && outputs == 13;
                case "GetSectionTriangleAt" -> inputs == 2 && outputs == 1;
                case "SetInstanceIndex" -> inputs == 1 && outputs == 0;
                case "BuildUvMapping" -> inputs == 1 && outputs == 0;
                case "GetTriangleCoordAtUV" -> inputs == 4 && outputs == 5;
                case "GetTriangleCoordInAabb" -> inputs == 5 && outputs == 5;
                case "GetWorldVelocity" -> inputs == 0 && outputs == 3;
                case "GetLocalToWorld", "GetLocalToWorldInverseTransposed" -> inputs == 0 && outputs == 16;
                case "GetPreSkinnedLocalBounds" -> inputs == 0 && outputs == 15;
                case "GetMeshBounds", "GetMeshBoundsWS" -> inputs == 0 && outputs == 18;
                case "DeprecatedRandomSection" -> inputs == 0 && outputs == 1;
                case "DeprecatedRandomTriCoord" -> inputs == 0 && outputs == 4;
                case "DeprecatedRandomTriCoordOnSection" -> inputs == 1 && outputs == 4;
                case "DeprecatedGetVertexPosition", "DeprecatedGetVertexPositionWS" -> inputs == 1 && outputs == 3;
                case "DeprecatedGetTriPosition", "DeprecatedGetTriPositionWS", "DeprecatedGetTriNormal", "DeprecatedGetTriNormalWS" ->
                        inputs == 4 && outputs == 3;
                case "DeprecatedGetTriTangents", "DeprecatedGetTriTangentsWS" -> inputs == 4 && outputs == 9;
                case "DeprecatedGetTriPositionAndVelocityWS" -> inputs == 4 && outputs == 6;
                case "DeprecatedGetTriColor" -> inputs == 4 && outputs == 4;
                case "DeprecatedGetTriUV" -> inputs == 5 && outputs == 2;
                default -> false;
            };
            case "fast_path" -> name.equals("FastMatrixToQuaternion") && inputs == 16 && outputs == 4;
            case "curl_noise" -> name.equals("SampleNoiseField") && inputs == 3 && outputs == 3
                    && binding.has("interface_data") && binding.getAsJsonObject("interface_data").has("seed");
            case "camera" -> binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                    && switch (name) {
                        case "GetFieldOfView", "GetSplitscreenInfo" -> inputs == 0 && outputs == 1;
                        case "GetCameraPropertiesCPU/GPU" -> inputs == 0 && (outputs == 12 || outputs == 15);
                        case "CalculateParticleDistancesCPU" -> inputs == 5 && outputs == 0;
                        case "QueryClosestParticlesCPU" -> inputs == 3 && outputs == 1;
                        default -> false;
                    };
            case "array" -> validArrayInterface(binding, name, inputs, outputs);
            case "neighbor_grid" -> binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                    && binding.has("interface_key") && switch (name) {
                        case "GetNumCells", "GetWorldBBoxSize" -> inputs == 0 && outputs == 3;
                        case "MaxNeighborsPerCell" -> inputs == 0 && outputs == 1;
                        case "SetNumCells" -> inputs == 4 && outputs == 1;
                        default -> false;
                    };
            case "grid2d_collection", "grid3d_collection" -> binding.has("interface_id")
                    && !binding.get("interface_id").getAsString().isEmpty() && binding.has("interface_key")
                    && binding.has("interface_data") && switch (name) {
                        case "GetNumCells", "GetWorldBBoxSize", "GetCellSize" -> inputs == 0
                                && outputs == (type.equals("grid3d_collection") ? 3 : 2);
                        case "SetNumCells" -> inputs == 3 && outputs == 1;
                        case "UnitToFloatIndex" -> type.equals("grid3d_collection") && inputs == 3 && outputs == 3;
                        case "GetVector4AttributeIndex", "GetVectorAttributeIndex", "GetVector2DAttributeIndex", "GetFloatAttributeIndex" ->
                                inputs == 0 && outputs == 1 && binding.getAsJsonObject("interface_data").has("attribute");
                        default -> false;
                    };
            case "debug_draw" -> binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                    && binding.has("interface_data") && validDebugDraw(binding.getAsJsonObject("interface_data"), name, inputs, outputs);
            case "rigid_mesh_collision" -> binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                    && binding.has("interface_key") && binding.has("interface_data") && outputs == 1 && switch (name) {
                        case "FindActors" -> inputs == 12;
                        case "GetNumElements" -> inputs == 0;
                        case "IsWorldPositionInsideCombinedBounds" -> inputs == 3;
                        default -> false;
                    };
            case "render_target_2d" -> binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                    && binding.has("interface_data") && binding.getAsJsonObject("interface_data").has("user_parameter")
                    && switch (name) {
                        case "GetRenderTargetSize" -> inputs == 0 && outputs == 2;
                        case "SetRenderTargetSize" -> inputs == 3 && outputs == 1;
                        case "ReleaseResources" -> inputs == 1 && outputs == 0;
                        case "GetNumMipLevels", "SetRenderTargetFormat" -> inputs == (name.equals("GetNumMipLevels") ? 0 : 1) && outputs == 1;
                        default -> false;
                    };
            case "render_target_array", "render_target_cube", "render_target_volume" ->
                    validDimensionalInterface(binding) && switch (name) {
                        case "GetRenderTargetSize" -> inputs == 0 && outputs == (type.equals("render_target_cube") ? 1 : 3);
                        case "SetRenderTargetSize" -> outputs == 1 && inputs == (type.equals("render_target_cube") ? 1 : 3);
                        case "GetNumMipLevels" -> inputs == 0 && outputs == 1;
                        case "SetRenderTargetFormat" -> inputs == 1 && outputs == 1;
                        default -> false;
                    };
            case "texture_array" -> validDimensionalInterface(binding)
                    && name.equals("TextureDimensions") && inputs == 0 && outputs == 3;
            case "texture_cube" -> validDimensionalInterface(binding) && switch (name) {
                case "TextureDimensions" -> inputs == 0 && outputs == 2;
                case "SampleCubeTexture" -> inputs == 4 && outputs == 4;
                default -> false;
            };
            case "texture_volume" -> validDimensionalInterface(binding) && switch (name) {
                case "TextureDimensions3D" -> inputs == 0 && outputs == 3;
                case "SampleVolumeTexture" -> inputs == 4 && outputs == 4;
                default -> false;
            };
            case "vector_field" -> validVectorField(binding, name, inputs, outputs);
            case "texture_2d" -> binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                    && binding.has("interface_data") && binding.getAsJsonObject("interface_data").has("user_parameter")
                    && switch (name) {
                        case "GetTextureDimensions" -> inputs == 1 && outputs == 2;
                        case "GetNumMipLevels" -> inputs == 0 && outputs == 1;
                        case "SampleTexture2D" -> inputs == 3 && outputs == 4;
                        case "SamplePseudoVolumeTexture" -> inputs == 12 && outputs == 4;
                        default -> false;
                    };
            case "particle_read" -> validParticleRead(binding, name, inputs, outputs);
            case "emitter_properties" -> binding.has("interface_data")
                    && binding.getAsJsonObject("interface_data").has("emitter")
                    && binding.getAsJsonObject("interface_data").has("mode")
                    && java.util.Set.of("Self", "Other").contains(binding.getAsJsonObject("interface_data").get("mode").getAsString())
                    && switch (name) {
                        case "GetLocalSpace" -> inputs == 0 && outputs == 1;
                        case "GetBounds", "GetFixedBounds" -> inputs == 0 && outputs == 7;
                        case "SetFixedBounds" -> inputs == 8 && outputs == 0;
                        default -> false;
                    };
            case "sprite_renderer_info" -> validSpriteRendererInfo(binding, name, inputs, outputs);
            case "mesh_renderer_info" -> validMeshRendererInfo(binding, name, inputs, outputs);
            case "simple_counter" -> binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                    && binding.has("interface_data") && binding.getAsJsonObject("interface_data").has("initial")
                    && binding.getAsJsonObject("interface_data").has("gpu_sync_mode") && switch (name) {
                        case "GetNextValue", "Get" -> inputs == 0 && outputs == 1;
                        case "Set" -> inputs == 2 && outputs == 0;
                        case "Exchange" -> inputs == 2 && outputs == 1;
                        case "Add" -> inputs == 2 && outputs == 2;
                        case "Increment", "Decrement" -> inputs == 1 && outputs == 2;
                        default -> false;
                    };
            case "gaussian_splat_reader" -> binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                    && binding.has("interface_data") && binding.getAsJsonObject("interface_data").has("gaussian_splat_reader")
                    && switch (name) {
                        case "GetNumSplats", "GetSHDegree" -> inputs == 0 && outputs == 1;
                        case "GetBounds" -> inputs == 0 && outputs == 6;
                        case "GetBaseData" -> inputs == 1 && outputs == 11;
                        case "GetSHCoefficient" -> inputs == 2 && outputs == 3;
                        case "CalculateColor" -> inputs == 4 && outputs == 3;
                        default -> false;
                    };
            case "gaussian_splat_writer" -> binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                    && binding.has("interface_data") && binding.getAsJsonObject("interface_data").has("gaussian_splat_writer")
                    && switch (name) {
                        case "GetNumSplats", "GetSHDegree" -> inputs == 0 && outputs == 1;
                        case "Clear" -> inputs == 1 && outputs == 0;
                        default -> false;
                    };
            default -> false;
        };
        if (!valid) throw new JsonParseException("Invalid Niagara " + type + " interface signature " + name);
    }

    private static boolean validDebugDraw(JsonObject data, String name, int inputs, int outputs) {
        if (outputs != 0 || !data.has("line_limit") || data.get("line_limit").getAsInt() < 0) return false;
        if (name.endsWith("Persistent") && (!data.has("compiler_tag") || data.get("compiler_tag").getAsString().isBlank())) return false;
        return inputs == switch (name) {
            case "DrawLine" -> 11;
            case "DrawRectangle" -> 19;
            case "DrawCircle", "DrawCone", "DrawGrid2D" -> 16;
            case "DrawBox", "DrawCylinder", "DrawTorus" -> 15;
            case "DrawSphere" -> 10;
            case "DrawCoordinateSystem" -> 9;
            case "DrawGrid3D" -> 18;
            case "DrawLinePersistent" -> 12;
            case "DrawRectanglePersistent" -> 25;
            case "DrawCirclePersistent", "DrawBoxPersistent", "DrawGrid2DPersistent" -> 21;
            case "DrawSpherePersistent" -> 29;
            case "DrawCylinderPersistent" -> 24;
            case "DrawConePersistent", "DrawTorusPersistent", "DrawGrid3DPersistent" -> 23;
            case "DrawCoordinateSystemPersistent" -> 14;
            default -> -1;
        };
    }

    private static boolean validDimensionalInterface(JsonObject binding) {
        return binding.has("interface_id") && !binding.get("interface_id").getAsString().isEmpty()
                && binding.has("interface_data") && binding.getAsJsonObject("interface_data").has("user_parameter");
    }

    private static boolean validPlatformSet(JsonObject binding, String name, int inputs, int outputs) {
        if (!SourceNames.equal(name, "IsActive") || inputs != 0 || outputs != 1
                || !binding.has("interface_id") || binding.get("interface_id").getAsString().isEmpty()
                || !binding.has("interface_data")) return false;
        JsonObject data = binding.getAsJsonObject("interface_data");
        if (!data.has("platforms") || !data.get("platforms").isJsonObject() || !data.has("class_default")
                || !data.has("archetype") || !data.has("initialization")) return false;
        String initialization = data.get("initialization").getAsString();
        if (!initialization.equals("loaded") && !initialization.equals("copy") && !initialization.equals("duplicate")) return false;
        NiagaraScalabilityJson.platform(data.getAsJsonObject("platforms"));
        return true;
    }

    private static boolean validVectorField(JsonObject binding, String name, int inputs, int outputs) {
        if (!validVectorFieldData(binding)) return false;
        JsonObject data = binding.getAsJsonObject("interface_data");
        boolean sample = name.equals("SampleField") || name.equals("LoadField");
        if (sample && data.get("cpu_access").getAsBoolean() && !binding.has("field")) return false;
        return switch (name) {
            case "SampleField", "LoadField" -> inputs == 3 && outputs == 3;
            case "FieldDimensions", "FieldTilingAxes" -> inputs == 0 && outputs == 3;
            case "FieldBounds" -> inputs == 0 && outputs == 6;
            default -> false;
        };
    }

    private static boolean validVectorFieldData(JsonObject binding) {
        if (binding.has("field_source") || !binding.has("interface_data") || !binding.has("tile")) return false;
        JsonObject data = binding.getAsJsonObject("interface_data");
        if (!data.has("cpu_access") || !data.has("dimensions") || !data.has("min_bounds") || !data.has("max_bounds")
                || data.getAsJsonArray("dimensions").size() != 3 || data.getAsJsonArray("min_bounds").size() != 3
                || data.getAsJsonArray("max_bounds").size() != 3 || binding.getAsJsonArray("tile").size() != 3) return false;
        return true;
    }

    public static @Nullable VectorFieldVolume vectorFieldDefault(JsonObject binding,
            Function<Identifier, VectorFieldVolume> fields) {
        String kind = binding.has("field_kind") ? binding.get("field_kind").getAsString() : "";
        if (!validVectorFieldData(binding)
                || !Set.of("none", "static", "animated").contains(kind)
                || kind.equals("static") != binding.has("field")
                || binding.getAsJsonObject("interface_data").get("cpu_access").getAsBoolean() && !binding.has("field"))
            throw new JsonParseException("Invalid vector field object defaults");
        return binding.has("field") ? Objects.requireNonNull(fields.apply(Identifier.parse(binding.get("field").getAsString())),
                "Vector field resolver returned no source volume") : null;
    }

    private static boolean validMeshRendererInfo(JsonObject binding, String name, int inputs, int outputs) {
        if (!binding.has("interface_data")) return false;
        MeshRendererInfoJson.read(binding.getAsJsonObject("interface_data"));
        return switch (name) {
            case "GetNumMeshes" -> inputs == 0 && outputs == 1;
            case "GetMeshLocalBounds" -> inputs == 1 && outputs == 9;
            case "GetSubUVDetails" -> inputs == 0 && outputs == 3;
            default -> false;
        };
    }
    private static boolean validSpriteRendererInfo(JsonObject binding, String name, int inputs, int outputs) {
        if (!binding.has("interface_data") || inputs != 0) return false;
        SpriteRendererInfoJson.read(binding.getAsJsonObject("interface_data"));
        return switch (name) {
            case "IsValid", "GetSourceMode", "GetAlignment", "GetFacingMode" -> outputs == 1;
            case "GetSubUVDetails" -> outputs == 3;
            default -> false;
        };
    }

    private static boolean validParticleRead(JsonObject binding, String name, int inputs, int outputs) {
        if (!binding.has("interface_id") || !binding.has("interface_data")) return false;
        JsonObject data = binding.getAsJsonObject("interface_data");
        if (!data.has("mode") || !data.has("emitter") || !data.has("attribute") || !data.has("value_type")) return false;
        String mode = data.get("mode").getAsString(), type = data.get("value_type").getAsString();
        if (!mode.equals("Self") && !mode.equals("Other")) return false;
        return switch (name) {
            case "GetLocalSpace", "Get Num Spawned Particles", "Get Num Particles" -> inputs == 0 && outputs == 1 && type.isEmpty();
            case "Get ID At Spawn Index" -> inputs == 1 && outputs == 3 && type.isEmpty();
            case "Get Particle Index" -> inputs == 2 && outputs == 1 && type.isEmpty();
            case "Get Particle Index From ID Table" -> inputs == 1 && outputs == 2 && type.isEmpty();
            default -> {
                int width = switch (type) {
                    case "Int", "Bool", "Float" -> 1;
                    case "Vector 2D", "ID" -> 2;
                    case "Vector", "Position" -> 3;
                    case "Vector 4", "Color", "Quaternion" -> 4;
                    default -> 0;
                };
                boolean byId = name.equals("Get " + type + " By ID");
                boolean byIndex = name.equals("Get " + type + " By Index");
                yield width != 0 && !data.get("attribute").getAsString().isBlank()
                        && (byId && inputs == 2 || byIndex && inputs == 1) && outputs == width + 1;
            }
        };
    }

    private static boolean validArrayInterface(JsonObject binding, String name, int inputs, int outputs) {
        if (!binding.has("interface_id") || binding.get("interface_id").getAsString().isEmpty() || !binding.has("interface_data")) return false;
        JsonObject data = binding.getAsJsonObject("interface_data");
        if (!validArrayData(data)) return false;
        int width = data.get("components").getAsInt();
        String arrayClass = data.get("class").getAsString();
        return switch (name) {
            case "Length", "LastIndex" -> inputs == 0 && outputs == 1;
            case "IsValidIndex" -> inputs == 1 && outputs == 1;
            case "Get" -> inputs == 1 && outputs == width;
            case "Clear" -> inputs == 0 && outputs == 0;
            case "Resize" -> inputs == 1 && outputs == 0;
            case "ResizeAndFill", "SetArrayElem" -> inputs == width + 2 && outputs == 0;
            case "Add" -> inputs == width + 1 && outputs == 0;
            case "RemoveLastElem" -> inputs == 1 && outputs == width + 1;
            case "AtomicAdd", "AtomicMin", "AtomicMax" -> arrayClass.equals("NiagaraDataInterfaceArrayInt32") && inputs == 3 && outputs == 2;
            default -> false;
        };
    }

    private static boolean validArrayData(JsonObject data) {
        if (!data.has("components") || !data.has("class") || !data.has("default") || !data.has("words")
                || !data.has("maximum_elements") || !data.has("gpu_sync_mode")) return false;
        int width = data.get("components").getAsInt();
        String arrayClass = data.get("class").getAsString();
        int expectedWidth = switch (arrayClass) {
            case "NiagaraDataInterfaceArrayFloat", "NiagaraDataInterfaceArrayInt32", "NiagaraDataInterfaceArrayUInt8", "NiagaraDataInterfaceArrayBool" -> 1;
            case "NiagaraDataInterfaceArrayFloat2", "NiagaraDataInterfaceArrayNiagaraID" -> 2;
            case "NiagaraDataInterfaceArrayFloat3", "NiagaraDataInterfaceArrayPosition" -> 3;
            case "NiagaraDataInterfaceArrayFloat4", "NiagaraDataInterfaceArrayColor", "NiagaraDataInterfaceArrayQuat" -> 4;
            case "NiagaraDataInterfaceArrayMatrix" -> 16;
            default -> 0;
        };
        return width == expectedWidth && width != 0 && data.getAsJsonArray("default").size() == width
                && data.getAsJsonArray("words").size() % width == 0;
    }

    public static List<NiagaraCurve> curveDefaults(JsonArray values) {
        var result = new ArrayList<NiagaraCurve>();
        for (var value : values) result.add(niagaraCurve(value));
        return List.copyOf(result);
    }

    public static NiagaraCurve niagaraCurve(JsonElement value) {
        if (value.isJsonPrimitive() || value.getAsJsonObject().has("lookup")) {
            FloatCurve curve = EffectJson.curve(value);
            List<NiagaraCurve.Key> keys = curve.keys().stream().map(key -> new NiagaraCurve.Key(
                    (float) key.time(), (float) key.value(), (float) key.arriveTangent(), (float) key.leaveTangent(),
                    0, 0, NiagaraCurve.Weight.NONE, key.interpolation())).toList();
            return new NiagaraCurve(keys, NiagaraCurve.Extrapolation.CONSTANT, NiagaraCurve.Extrapolation.CONSTANT, curve.lookup());
        }
        JsonObject object = value.getAsJsonObject();
        List<NiagaraCurve.Key> keys = new ArrayList<>();
        for (JsonElement element : object.getAsJsonArray("keys")) {
            JsonObject key = element.getAsJsonObject();
            String weightName = key.has("weight_mode") ? key.get("weight_mode").getAsString() : "RCTWM_WeightedNone";
            NiagaraCurve.Weight weight = switch (weightName) {
                case "RCTWM_WeightedNone" -> NiagaraCurve.Weight.NONE;
                case "RCTWM_WeightedArrive" -> NiagaraCurve.Weight.ARRIVE;
                case "RCTWM_WeightedLeave" -> NiagaraCurve.Weight.LEAVE;
                case "RCTWM_WeightedBoth" -> NiagaraCurve.Weight.BOTH;
                default -> throw new JsonParseException("Unknown Niagara tangent weight mode " + weightName);
            };
            keys.add(new NiagaraCurve.Key(floating(key, "time", 0), floating(key, "value", 0),
                    floating(key, "arrive", 0), floating(key, "leave", 0), floating(key, "arrive_weight", 0),
                    floating(key, "leave_weight", 0), weight, FloatCurve.Interpolation.valueOf(
                    (key.has("interpolation") ? key.get("interpolation").getAsString() : "linear").toUpperCase(Locale.ROOT))));
        }
        return new NiagaraCurve(keys, extrapolation(object, "pre_extrapolation"), extrapolation(object, "post_extrapolation"), null);
    }

    private static NiagaraCurve.Extrapolation extrapolation(JsonObject object, String field) {
        String value = optionalString(object, field, "RCCE_Constant");
        return switch (value) {
            case "RCCE_Constant" -> NiagaraCurve.Extrapolation.CONSTANT;
            case "RCCE_Linear" -> NiagaraCurve.Extrapolation.LINEAR;
            case "RCCE_Cycle" -> NiagaraCurve.Extrapolation.CYCLE;
            case "RCCE_CycleWithOffset" -> NiagaraCurve.Extrapolation.CYCLE_WITH_OFFSET;
            case "RCCE_Oscillate" -> NiagaraCurve.Extrapolation.OSCILLATE;
            case "RCCE_None" -> NiagaraCurve.Extrapolation.NONE;
            default -> throw new JsonParseException("Unknown Niagara curve extrapolation " + value);
        };
    }

    private static float floating(JsonObject value, String name, float fallback) {
        JsonElement element = value.get(name);
        return element != null && !element.isJsonNull() ? element.getAsFloat() : fallback;
    }

    private static String optionalString(JsonObject object, String field, String fallback) {
        String value = com.kltyton.lumenfx.client.resource.EffectResources.jsonString(object, field);
        return value != null ? value : fallback;
    }

    private static List<NiagaraDefinition.Variable> layout(JsonArray value) {
        List<NiagaraDefinition.Variable> variables = new ArrayList<>();
        for (JsonElement item : value) {
            JsonObject v = item.getAsJsonObject();
            JsonObject offsets = com.kltyton.lumenfx.client.resource.EffectResources.jsonObject(v, "component_offsets");
            variables.add(new NiagaraDefinition.Variable(v.get("name").getAsString(), integer(v, "float"), integer(v, "float_count"),
                    integer(v, "int"), integer(v, "int_count"), integer(v, "half"), integer(v, "half_count"),
                    string(v, "source_type"), offsets == null ? null : new NiagaraDefinition.ComponentOffsets(
                            Arrays.stream(integers(offsets.getAsJsonArray("float"))).boxed().toList(),
                            Arrays.stream(integers(offsets.getAsJsonArray("int"))).boxed().toList(),
                            Arrays.stream(integers(offsets.getAsJsonArray("half"))).boxed().toList())));
        }
        return List.copyOf(variables);
    }

    private static int integer(JsonObject value, String name) { return value.get(name).getAsInt(); }
    private static String string(JsonObject value, String name) {
        JsonElement element = value.get(name);
        if (element == null || element.isJsonNull()) throw new JsonParseException("Missing Niagara field " + name);
        return element.getAsString();
    }
    private static List<String> strings(JsonArray value) {
        List<String> result = new ArrayList<>();
        for (JsonElement element : value) result.add(element.getAsString());
        return List.copyOf(result);
    }
    private static int[] integers(JsonArray value) {
        int[] result = new int[value.size()];
        for (int i = 0; i < result.length; i++) result[i] = value.get(i).getAsInt();
        return result;
    }
}
