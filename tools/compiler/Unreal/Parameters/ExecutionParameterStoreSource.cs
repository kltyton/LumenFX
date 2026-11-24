using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Scene;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Rebuilds the execution store's declared current/PREV slots before applying resolved object references. */
internal static class ExecutionParameterStoreSource
{
    internal static JObject Compile(SourceAsset asset, SourceObject script, JObject runtime, AssetLibrary? library,
        SourceObject? system = null, NiagaraCollectionSource? collectionSource = null)
    {
        JToken? container = script.Properties["CachedScriptVM"]?["Parameters"];
        JArray parameters = container as JArray ?? container?["Parameters"] as JArray ?? new();
        var originals = parameters.Cast<JObject>().ToArray();
        var declarations = new List<(RendererFieldBindings.Variable Variable, JObject Json)>();
        List<byte> bytes = new();
        JArray references = new(), previous = new();
        NiagaraCollectionSource? collections = collectionSource
            ?? (system is null ? null : new NiagaraCollectionSource(asset, system, script.Properties, library));
        if (runtime[ScriptRuntimeSource.ExecutionStore] is JObject cooked)
            return CompileCooked(cooked);
        foreach (var variable in originals) Add(variable, false);
        int currentBytes = bytes.Count;
        string interpolation = (script.Properties["CachedScriptVMId"]?.Value<string>("InterpolatedSpawnMode") ?? "NoInterpolation").Split("::").Last();
        bool interpolated = interpolation is not ("NoInterpolation" or "0");
        if (interpolated) foreach (var variable in originals) {
            JObject current = Add(variable, false);
            JObject old = Add(variable, true);
            if (current.Value<string>("storage") == "numeric")
                previous.Add(new JObject { ["current"] = Key(current), ["previous"] = Key(old) });
        }
        foreach (JObject resolved in (runtime["ResolvedDataInterfaces"] as JArray ?? new()).Cast<JObject>()) {
            JObject variable = resolved["ParameterStoreVariable"] as JObject
                ?? throw new ConversionException("Resolved DI has no execution-store variable");
            JObject declaration = Add(variable, false, "interface");
            SourceAsset owner = ScriptDataInterfaceBindings.ObjectAsset(asset, resolved, library);
            int reference = resolved.Value<int>("ResolvedDataInterface");
            AddReference(declaration, variable, true, Reference(owner, reference,
                resolved.Value<string>("InstanceId"), resolved.Value<string>("InstanceInitialization") ?? "loaded", library,
                resolved["InstanceRuntimeOuter"] as JObject, system, asset));
        }
        foreach (JObject resolved in (runtime["ResolvedUObjects"] as JArray ?? new()).Cast<JObject>()) {
            JObject variable = resolved["ResolvedVariable"] as JObject
                ?? throw new ConversionException("Resolved UObject has no execution-store variable");
            JObject declaration = Add(variable, false, "object");
            AddReference(declaration, variable, false, Reference(asset, resolved.Value<int>("Object"), null, "reference", library,
                system: system, systemAsset: asset));
        }
        JArray interfaceValues = new(), objectValues = new();
        foreach (var declaration in declarations) {
            string storage = declaration.Json.Value<string>("storage")!;
            if (storage == "numeric") continue;
            JArray values = storage == "interface" ? interfaceValues : objectValues;
            declaration.Json["object_index"] = values.Count;
            values.Add(JValue.CreateNull());
        }
        foreach (JObject reference in references) {
            JObject declaration = declarations.Single(value => JToken.DeepEquals(Key(value.Json), reference["key"])).Json;
            JArray values = declaration.Value<string>("storage") == "interface" ? interfaceValues : objectValues;
            values[declaration.Value<int>("object_index")] = reference["value"]!.DeepClone();
        }
        return new JObject { ["variables"] = new JArray(declarations.Select(value => value.Json)),
            ["data"] = Convert.ToBase64String(bytes.ToArray()), ["current_bytes"] = currentBytes,
            ["interpolated"] = interpolated, ["previous"] = previous, ["references"] = references,
            ["interface_values"] = interfaceValues, ["object_values"] = objectValues,
            ["instance_bindings"] = system is null ? new JArray() : ScriptDataInterfaceBindings.UserBindings(asset, system, script, runtime, library),
            ["initial_sources"] = new JArray(SavedParameterStoreSource.RapidIteration(asset, script, false, library, system)) };

        JObject CompileCooked(JObject cooked)
        {
            JObject result = SavedParameterStoreSource.Compile(asset, cooked, library, system, asset,
                resolveObject: (variable, dataInterface, reference) => {
                    if (!dataInterface)
                        return ObjectReference(variable, false, Reference(asset, reference, null, "reference", library,
                            system: system, systemAsset: asset));
                    JObject? resolved = (runtime["ResolvedDataInterfaces"] as JArray ?? new()).OfType<JObject>().LastOrDefault(value =>
                        value["ParameterStoreVariable"] is JObject candidate && ScriptDataInterfaceBindings.Same(asset, candidate, variable));
                    if (resolved is null)
                        return ObjectReference(variable, true, Reference(asset, reference, null, "loaded", library,
                            system: system, systemAsset: asset));
                    return ObjectReference(variable, true, Reference(ScriptDataInterfaceBindings.ObjectAsset(asset, resolved, library),
                        resolved.Value<int>("ResolvedDataInterface"), resolved.Value<string>("InstanceId"),
                        resolved.Value<string>("InstanceInitialization") ?? "loaded", library,
                        resolved["InstanceRuntimeOuter"] as JObject, system, asset));
                });
            JObject[] slots = ((JArray)result["variables"]!).Cast<JObject>().ToArray();
            int size = cooked.Value<int?>("ParameterSize") ?? 0;
            string mode = (script.Properties["CachedScriptVMId"]?.Value<string>("InterpolatedSpawnMode") ?? "NoInterpolation").Split("::").Last();
            bool interpolate = mode is not ("NoInterpolation" or "0");
            int dataSize = NumericParameterStoreSource.Bytes(cooked["ParameterData"]).Length;
            if (size < 0 || size > dataSize || (interpolate && size > dataSize - size))
                throw new ConversionException($"Invalid cooked execution parameter size: {asset.Name}:{script.Name}");
            JArray previous = new();
            if (interpolate) foreach (JObject slot in slots) {
                if (slot.Value<string>("storage") != "numeric" || slot.Value<int>("offset") >= size) continue;
                JObject old = slots.Single(candidate => candidate.Value<string>("storage") == "numeric"
                    && candidate.Value<int>("offset") == size + slot.Value<int>("offset")
                    && SourceNameOrder.Equal(candidate.Value<string>("name"), "PREV_" + slot.Value<string>("name"))
                    && SourceNameOrder.Equal(candidate.Value<string>("source_type"), slot.Value<string>("source_type")));
                previous.Add(new JObject { ["current"] = Key(slot), ["previous"] = Key(old) });
            }
            result["current_bytes"] = size;
            result["interpolated"] = interpolate;
            result["previous"] = previous;
            result["instance_bindings"] = system is null ? new JArray() : ScriptDataInterfaceBindings.UserBindings(asset, system, script, runtime, library);
            JObject rapid = SavedParameterStoreSource.RapidIteration(asset, script, false, library, system);
            rapid["bound_offsets"] = CookedRapidBindings.Read(asset, runtime, result, rapid["definition"]!);
            result["initial_sources"] = new JArray(rapid);
            return result;
        }

        void AddReference(JObject declaration, JObject variable, bool dataInterface, JToken value) {
            JObject reference = ObjectReference(variable, dataInterface, value);
            reference["key"] = Key(declaration);
            references.Add(reference);
        }

        JObject ObjectReference(JObject variable, bool dataInterface, JToken value) {
            JObject reference = new() { ["value"] = value };
            if (collections?.Object(variable, dataInterface) is { } source) {
                reference["value"] = Reference(source.Asset, source.Reference, source.InstanceId, source.Initialization,
                    library, source.RuntimeOuter, system, asset);
                JToken baseDefault = JValue.CreateNull();
                if (source.BaseAsset is { } owner && source.BaseReference is int index) {
                    string? identity = dataInterface ? source.Collection + "@component_di:"
                        + variable.Value<string>("name") + ":" + NiagaraTypeSource.Read(asset, (JObject)variable["type"]!).Path : null;
                    JObject? outer = dataInterface ? new JObject { ["kind"] = "collection_instance",
                        ["collection"] = source.Collection, ["scope"] = "component" } : null;
                    baseDefault = Reference(owner, index, identity, dataInterface ? "duplicate" : "reference", library, outer, system, asset);
                }
                reference["collection"] = new JObject { ["path"] = source.Collection,
                    ["system_override"] = source.Scope == "system", ["base_default"] = baseDefault };
            }
            return reference;
        }

        JObject Add(JObject variable, bool old, string? resolvedStorage = null) {
            var value = ExecutionNumericParameterSource.Read(asset, variable, old);
            var type = value.Type;
            if (type.UnderlyingType != 1 && resolvedStorage is not null)
                throw new ConversionException($"Resolved object has a numeric execution type: {value.Name}");
            if (resolvedStorage is not null) foreach (var declaration in declarations)
                if (declaration.Variable.UnderlyingType == 1 && SourceNameOrder.Equal(declaration.Variable.Type, type.Path))
                    declaration.Json["storage"] = resolvedStorage;
            var identity = new RendererFieldBindings.Variable(value.Name, type.Path, type.UnderlyingType, type.Flags, value.Data.Length, value.Lwc);
            var match = declarations.FindIndex(candidate => candidate.Variable.SameStoreSlot(identity));
            if (match >= 0) return declarations[match].Json;
            string storage = type.UnderlyingType != 1 ? "numeric" : resolvedStorage
                ?? (ScriptDataInterfaceBindings.IsInterface(asset, variable) ? "interface" : "object");
            JObject result = new() { ["name"] = value.Name, ["source_type"] = type.Path,
                ["underlying"] = type.UnderlyingType, ["flags"] = type.Flags, ["lwc"] = value.Lwc,
                ["storage"] = storage, ["bytes"] = value.Data.Length, ["offset"] = bytes.Count };
            declarations.Add((identity, result));
            if (storage == "numeric") bytes.AddRange(value.Data);
            return result;
        }
    }

    internal static IEnumerable<JObject> CurrentNumeric(JObject store)
    {
        int size = store.Value<int>("current_bytes");
        return ((JArray)store["variables"]!).OfType<JObject>()
            .Where(slot => slot.Value<string>("storage") == "numeric" && slot.Value<int>("offset") < size)
            .OrderBy(slot => slot.Value<int>("offset"));
    }

    internal static JObject Key(JObject declaration) => new() {
        ["name"] = declaration["name"]!.DeepClone(), ["source_type"] = declaration["source_type"]!.DeepClone(),
        ["underlying"] = declaration["underlying"]!.DeepClone(), ["flags"] = declaration["flags"]!.DeepClone(),
        ["storage"] = declaration["storage"]!.DeepClone()
    };

    internal static JToken Reference(SourceAsset asset, int reference, string? identity, string initialization, AssetLibrary? library = null,
        JObject? runtimeOuter = null, SourceObject? system = null, SourceAsset? systemAsset = null)
    {
        if (reference == 0) return JValue.CreateNull();
        SourceObject? source = reference > 0 ? asset.Object(reference) : null;
        string? flags = source?.Raw.Value<string>("ObjectFlags");
        string type = asset.ReferencedClass(reference);
        string objectInitialization = initialization == "reference" ? "loaded" : initialization;
        JObject result = new() { ["id"] = identity ?? ScriptDataInterfaceBindings.Identity(asset, reference),
            ["path"] = asset.ObjectPath(reference), ["class"] = type, ["class_path"] = asset.ReferencedClassPath(reference),
            ["properties"] = source?.Raw["Data"] is JArray ? source.Properties.DeepClone() : null,
            ["initialization"] = initialization,
            ["runtime_outer"] = runtimeOuter?.DeepClone(),
            ["class_default"] = flags is null ? null : JToken.FromObject(flags.Split(',').Any(value => value.Trim() == "RF_ClassDefaultObject")) };
        if (initialization == "reference") {
            var fields = ObjectPropertyFieldSource.Compile(asset, reference, library);
            if (fields is not null && fields.Count != 0) result["property_fields"] = fields;
            if (ObjectPropertyFieldSource.CompileActorBinding(asset, reference, library) is { } actor)
                result["actor_component_binding"] = actor;
            if (SplineComponentSource.Compile(asset, reference, library) is { } spline)
                result["saved_spline"] = spline;
        }
        if (ArrayDataInterfaceCompiler.Supports(type) || type == "NiagaraDataInterfaceSimpleCounter") {
            JObject properties = MutableDataInterfaceInstancesCompiler.ReadProperties(asset, reference, type, library ?? asset.Library);
            result["mutable_data"] = type == "NiagaraDataInterfaceSimpleCounter"
                ? SimpleCounterDataInterfaceCompiler.Definition(properties) : ArrayDataInterfaceCompiler.Definition(type, properties, asset.ObjectPath(reference));
        }
        if (type == PlatformSetDataInterfaceCompiler.ClassName)
            result["platform_set"] = PlatformSetDataInterfaceCompiler.Compile(source, library ?? asset.Library,
                source is null ? asset.ObjectPath(reference) : null, objectInitialization);
        if (type is "NiagaraDataInterfaceTexture" or "NiagaraDataInterfaceRenderTarget2D") {
            SourceAsset context = systemAsset ?? asset;
            SourceObject? local = context == asset ? source : null;
            string? path = local is null ? asset.ObjectPath(reference) : null;
            if (type == "NiagaraDataInterfaceTexture")
                result["texture_2d_defaults"] = system is null ? TextureDataInterfaceCompiler.CompileDefaults(asset, reference, library)
                    : TextureDataInterfaceCompiler.Compile(context, local, system, library ?? asset.Library, path);
            else result["render_target_2d_defaults"] = system is null ? RenderTarget2DDataInterfaceCompiler.CompileDefaults(asset, reference, library)
                    : RenderTarget2DDataInterfaceCompiler.Compile(context, local, system, library ?? asset.Library, path);
        }
        if (type == TextureDataInterfaceCompiler.ArrayClass)
            result["texture_array_defaults"] = TextureDataInterfaceCompiler.CompileArray(asset, reference, library, systemAsset ?? asset, system);
        if (type == TextureDataInterfaceCompiler.CubeClass)
            result["texture_cube_defaults"] = TextureDataInterfaceCompiler.CompileCube(asset, reference, library, systemAsset ?? asset, system);
        if (type == TextureDataInterfaceCompiler.VolumeClass)
            result["texture_volume_defaults"] = TextureDataInterfaceCompiler.CompileVolume(asset, reference, library, systemAsset ?? asset, system);
        if (type == RenderTarget2DDataInterfaceCompiler.ArrayClass)
            result["render_target_array_defaults"] = RenderTarget2DDataInterfaceCompiler.CompileArray(asset, reference, library, systemAsset ?? asset, system);
        if (type == RenderTarget2DDataInterfaceCompiler.VolumeClass)
            result["render_target_volume_defaults"] = RenderTarget2DDataInterfaceCompiler.CompileVolume(asset, reference, library, systemAsset ?? asset, system);
        if (type == RenderTarget2DDataInterfaceCompiler.CubeClass)
            result["render_target_cube_defaults"] = RenderTarget2DDataInterfaceCompiler.CompileCube(asset, reference, library, systemAsset ?? asset, system);
        if (CurveDataInterfaceCompiler.Supports(type)) {
            result["curve_defaults"] = CurveDataInterfaceCompiler.CompileDefaults(
                asset, source, type, library ?? asset.Library, source is null ? asset.ObjectPath(reference) : null);
            result["gpu_curve_defaults"] = CurveDataInterfaceCompiler.CompileGpuDefaults(
                asset, source, type, library ?? asset.Library, source is null ? asset.ObjectPath(reference) : null);
        }
        if (type == CurlNoiseDataInterfaceCompiler.ClassName) result["curl"] = CurlNoiseDataInterfaceCompiler.Compile(
            asset, reference, library, objectInitialization);
        if (type == CameraDataInterfaceCompiler.ClassName) result["camera"] = CameraDataInterfaceCompiler.Compile(asset, reference, library);
        if (type == ObjectPropertyReaderCompiler.ClassName)
            result["object_property_reader"] = ObjectPropertyReaderCompiler.Definition(asset, reference, library);
        if (type == PropertyInterfaceCompiler.ClassName)
            result["property_interface"] = PropertyInterfaceCompiler.Definition(asset, reference, library);
        if (type == ActorComponentDataInterfaceCompiler.ClassName)
            result["actor_component"] = ActorComponentDataInterfaceCompiler.Definition(asset, reference, library);
        if (type == AsyncGpuTraceDataInterfaceCompiler.ClassName)
            result["async_gpu_trace"] = AsyncGpuTraceDataInterfaceCompiler.Definition(asset, reference, library);
        if (type == StaticMeshDataInterfaceCompiler.ClassName)
            result["static_mesh_reader"] = StaticMeshDataInterfaceCompiler.Definition(asset, reference, library);
        if (type == MeshArrayInterfaceCompiler.ClassName)
            result[MeshArrayInterfaceCompiler.SourceEntries] = MeshArrayInterfaceCompiler.Describe(asset, reference, library);
        if (type is VelocityGridInterfaceCompiler.VelocityClass or VelocityGridInterfaceCompiler.PressureClass)
            result["velocity_grid"] = VelocityGridInterfaceCompiler.Definition(asset, reference, library, type);
        if (type == DebugDrawInterfaceCompiler.ClassName)
            result["debug_draw"] = DebugDrawInterfaceCompiler.Definition(asset, reference, library);
        if (type == VolumeCacheInterfaceCompiler.ClassName)
            result["volume_cache"] = VolumeCacheInterfaceCompiler.Definition(asset, reference, library);
        if (type == PhysicsAssetInterfaceCompiler.ClassName)
            result["physics_asset"] = PhysicsAssetInterfaceCompiler.Definition(asset, reference, library);
        if (type == GeometryCollectionInterfaceCompiler.ClassName)
            result["geometry_collection"] = GeometryCollectionInterfaceCompiler.Definition(asset, reference, library);
        if (type == ChaosDestructionInterfaceCompiler.ClassName)
            result["chaos_destruction"] = ChaosDestructionInterfaceCompiler.Definition(asset, reference, library);
        if (type == HairStrandsInterfaceCompiler.ClassName)
            result["hair_strands"] = HairStrandsInterfaceCompiler.Definition(asset, reference, library);
        if (type == RigidMeshCollisionInterfaceCompiler.ClassName)
            result["rigid_mesh_collision"] = RigidMeshCollisionInterfaceCompiler.Definition(asset, reference, library);
        if (type == SceneCapture2DInterfaceCompiler.ClassName)
            result["scene_capture_2d"] = SceneCapture2DInterfaceCompiler.Definition(asset, reference, library);
        if (type == LandscapeInterfaceCompiler.ClassName)
            result["landscape"] = LandscapeInterfaceCompiler.Definition(asset, reference, library);
        if (type is VirtualTextureInterfaceCompiler.RuntimeClass or VirtualTextureInterfaceCompiler.StreamingClass)
            result["virtual_texture"] = VirtualTextureInterfaceCompiler.Definition(asset, reference, library, type);
        if (type == "StaticMesh") result["static_mesh_parameter"] = new JObject { ["asset_source"] = asset.ObjectPath(reference) };
        if (type == SkeletalMeshDataInterfaceCompiler.ClassName)
            result["skeletal_mesh_reader"] = SkeletalMeshDataInterfaceCompiler.Definition(asset, reference, library);
        if (type == ParticleExportDataInterfaceCompiler.ClassName)
            result["particle_export"] = ParticleExportDataInterfaceCompiler.Definition(asset, reference, library);
        if (type is DataChannelInterfaceCompiler.Reader or DataChannelInterfaceCompiler.Writer)
            result["data_channel"] = DataChannelInterfaceCompiler.Definition(asset, reference, type, library);
        if (type == AudioPlayerInterfaceCompiler.ClassName)
            result["audio_player"] = AudioPlayerInterfaceCompiler.Definition(asset, reference, library);
        if (type == AudioPlayerInterfaceCompiler.SettingsClass)
            result["audio_settings"] = AudioPlayerInterfaceCompiler.Configuration(asset, reference, library);
        if (type is AudioAnalysisInterfaceCompiler.Spectrum or AudioAnalysisInterfaceCompiler.Oscilloscope)
            result["audio_analysis"] = AudioAnalysisInterfaceCompiler.Definition(asset, reference, type, library);
        if (type == SimCacheReaderInterfaceCompiler.ClassName)
            result["sim_cache_reader"] = SimCacheReaderInterfaceCompiler.Definition(asset, reference, library);
        if (type == MemoryBufferInterfaceCompiler.ClassName)
            result["memory_buffer"] = MemoryBufferInterfaceCompiler.Definition(asset, reference, library);
        if (type == NeighborGridInterfaceCompiler.ClassName)
            result["neighbor_grid"] = NeighborGridInterfaceCompiler.Definition(asset, reference, library);
        if (type == RasterizationGridInterfaceCompiler.ClassName)
            result["rasterization_grid"] = RasterizationGridInterfaceCompiler.Definition(asset, reference, library);
        if (type is Grid2DCollectionInterfaceCompiler.ClassName or Grid2DCollectionInterfaceCompiler.ReaderClass)
            result["grid2d_collection"] = Grid2DCollectionInterfaceCompiler.Definition(asset, reference, library, type);
        if (type is Grid3DCollectionInterfaceCompiler.ClassName or Grid3DCollectionInterfaceCompiler.ReaderClass)
            result["grid3d_collection"] = Grid3DCollectionInterfaceCompiler.Definition(asset, reference, library, type);
        if (type == MaterialCollectionInterfaceCompiler.ClassName)
            result["material_collection_writer"] = MaterialCollectionInterfaceCompiler.Definition(asset, reference, library);
        if (type == MaterialInstanceDynamicInterfaceCompiler.ClassName)
            result["material_instance_dynamic"] = MaterialInstanceDynamicInterfaceCompiler.Definition(asset, reference, library);
        if (type == MaterialCollectionInterfaceCompiler.AssetClass)
            result["material_collection_asset"] = MaterialParameterCollectionSource.Read(asset, reference, library ?? asset.Library).Json();
        if (type == DataTableInterfaceCompiler.ClassName)
            result["data_table"] = DataTableInterfaceCompiler.Definition(asset, reference, library);
        if (type == GaussianSplatInterfaceCompiler.ReaderClass)
            result["gaussian_splat_reader"] = GaussianSplatInterfaceCompiler.Reader(asset, reference, library);
        if (type == GaussianSplatInterfaceCompiler.WriterClass)
            result["gaussian_splat_writer"] = GaussianSplatInterfaceCompiler.Writer(asset, reference, library);
        if (type == "GeometryCollection" && (library ?? asset.Library)?.GeometryCollections is { } geometryCollections)
            result["saved_geometry_collection"] = geometryCollections.Read(asset.ObjectPath(reference));
        if (type == DataTableInterfaceCompiler.AssetClass)
            result["data_table_asset"] = DataTableInterfaceCompiler.Table(asset, reference, library);
        if (type == IntRenderTarget2DInterfaceCompiler.ClassName)
            result["int_render_target_2d"] = IntRenderTarget2DInterfaceCompiler.Definition(asset, reference, library);
        if (type == SocketReaderInterfaceCompiler.ClassName)
            result["socket_reader"] = SocketReaderInterfaceCompiler.Definition(asset, reference, library);
        if (type == SplineInterfaceCompiler.ClassName)
            result["spline"] = SplineInterfaceCompiler.Definition(asset, reference, library);
        if (type == WaterInterfaceCompiler.ClassName)
            result["water"] = WaterInterfaceCompiler.Definition(asset, reference, library);
        if (type == IntDistributionInterfaceCompiler.ClassName)
            result["int_distribution"] = IntDistributionInterfaceCompiler.Definition(asset, reference, library);
        if (type == NeighborQueryInterfaceCompiler.ClassName)
            result["neighbor_query"] = NeighborQueryInterfaceCompiler.Definition(asset, reference, library);
        if (type == DynamicMeshInterfaceCompiler.ClassName)
            result["dynamic_mesh"] = DynamicMeshInterfaceCompiler.Definition(asset, reference, library);
        if (type == SparseVolumeInterfaceCompiler.ClassName)
            result["sparse_volume_texture"] = SparseVolumeInterfaceCompiler.Definition(asset, reference, library);
        if (type is "SparseVolumeTexture" or "StaticSparseVolumeTexture" or "AnimatedSparseVolumeTexture" or "SparseVolumeTextureFrame")
            result["sparse_volume_asset"] = new JObject { ["asset_source"] = asset.ObjectPath(reference) };
        if (type == "NiagaraSimCache")
            result["sim_cache_asset"] = new JObject { ["asset_source"] = asset.ObjectPath(reference) };
        if (type == "SkeletalMesh") result["skeletal_mesh_parameter"] = new JObject { ["asset_source"] = asset.ObjectPath(reference) };
        if (type == "NiagaraDataInterfaceSpriteRendererInfo") result["sprite_info"] = SpriteRendererInfoCompiler.CompileInterface(
            library ?? asset.Library, asset, source, source is null ? asset.ObjectPath(reference) : null);
        if (type == "NiagaraDataInterfaceMeshRendererInfo") result["mesh_info"] = MeshRendererInfoCompiler.CompileInterface(
            library ?? asset.Library, asset, source, source is null ? asset.ObjectPath(reference) : null);
        if (type == "NiagaraDataInterfaceVectorField") result["vector_field_defaults"] = VectorFieldDataInterfaceCompiler.CompileDefaults(
            asset, source, library ?? asset.Library, source is null ? asset.ObjectPath(reference) : null);
        if (type is "NiagaraDataInterfaceParticleRead" or "NiagaraDataInterfaceEmitterProperties")
            result["emitter_binding_source"] = EmitterBindingCompiler.SourceBinding(asset, reference, type, library);
        return result;
    }
}
