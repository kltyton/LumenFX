using Newtonsoft.Json.Linq;
using System.Text;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution;

/// <summary>Preserves the saved Niagara GPU translation and its source binding metadata.</summary>
internal static class GpuProgramCompiler
{
    internal static bool DirectIteration(JObject stage) =>
        stage.Value<string>("IterationSourceType")?.Split("::").Last() is "2" or "DirectSet";

    public static JObject Compile(SourceAsset asset, SourceObject script, JObject emitterVersion, string emitterName, AssetLibrary? library,
        JObject runtimeBindings, JArray allRuntimeBindings, SourceObject system, string handle,
        IReadOnlyDictionary<string, string> shaderKeywords)
    {
        JObject executable = script.Properties["CachedScriptVM"] as JObject
            ?? throw new ConversionException($"Missing GPU executable data: {asset.Name}:{script.Name}");
        ScriptCompilationState.RequireCurrent(executable, asset.Name + ":" + script.Name);
        GpuScriptTranslation translation = GpuScriptTranslation.Read(asset, script, executable, library);
        string hlsl = translation.Hlsl;
        JArray stages = executable["SimulationStageMetaData"] as JArray
            ?? throw new ConversionException($"Missing GPU simulation stages: {asset.Name}:{script.Name}");
        JArray compileInterfaces = translation.Executable["DataInterfaceInfo"] as JArray ?? new JArray();
        JArray resolved = runtimeBindings["ResolvedDataInterfaces"] as JArray
            ?? throw new ConversionException($"Missing GPU interface bindings: {asset.Name}:{script.Name}");
        JObject parameters = executable["ShaderScriptParametersMetadata"] as JObject
            ?? (compileInterfaces.Count == 0 && resolved.Count == 0
                ? new JObject()
                : throw new ConversionException($"Missing GPU parameter metadata: {asset.Name}:{script.Name}"));
        JArray additionalDefines = translation.Defines;
        bool compressedAttributes = additionalDefines.Values<string>().Contains("CompressAttributes", StringComparer.Ordinal);
        JArray variants = new();
        for (int index = 0; index < stages.Count; index++)
        {
            JObject stage = stages[index] as JObject
                ?? throw new ConversionException($"Invalid GPU simulation stage {index}: {asset.Name}:{script.Name}");
            JObject threads = stage["GpuDispatchNumThreads"] as JObject
                ?? throw new ConversionException($"GPU stage has no dispatch dimensions: {asset.Name}:{script.Name}:{index}");
            int x = threads.Value<int>("X"), y = threads.Value<int>("Y"), z = threads.Value<int>("Z");
            if (x < 1 || y < 1 || z < 1) throw new ConversionException($"Invalid GPU dispatch dimensions: {asset.Name}:{script.Name}:{index}");
            int dispatch = (stage.Value<string>("GpuDispatchType") ?? "OneD").Split("::").Last() switch {
                "0" or "OneD" => 0, "1" or "TwoD" => 1, "2" or "ThreeD" => 2, "3" or "Custom" => 3,
                var type => throw new ConversionException($"Unknown GPU dispatch type {type}: {asset.Name}:{script.Name}:{index}") };
            variants.Add(new JObject {
                ["stage_index"] = index, ["stage_name"] = stage.Value<string>("SimulationStageName"),
                ["defines"] = new JObject {
                    ["GPU_SIMULATION"] = 1, ["NIAGARA_MAX_GPU_SPAWN_INFOS"] = 8, ["SimulationStageIndex"] = index,
                    ["NIAGARA_PARTICLE_PARTIAL_ENABLED"] = stage.Value<bool>("bWritesParticles") && stage.Value<bool>("bPartialParticleUpdate") ? 1 : 0,
                    ["NIAGARA_COMPRESSED_ATTRIBUTES_ENABLED"] = compressedAttributes ? 1 : 0,
                    ["NIAGARA_DISPATCH_TYPE"] = dispatch, ["NIAGARA_DISPATCH_INDIRECT"] = stage.Value<bool>("bGpuIndirectDispatch") ? 1 : 0,
                    ["THREADGROUP_SIZE"] = checked(x * y * z), ["THREADGROUP_SIZE_X"] = x,
                    ["THREADGROUP_SIZE_Y"] = y, ["THREADGROUP_SIZE_Z"] = z
                }
            });
        }
        JArray interfaces = new();
        JArray shaderInterfaces = parameters["DataInterfaceParamInfo"] as JArray ?? new JArray();
        foreach (JObject binding in resolved.OfType<JObject>())
        {
            string name = binding.Value<string>("CompileName")
                ?? throw new ConversionException($"Missing GPU interface compile name: {asset.Name}:{script.Name}");
            string parameterName = binding["ParameterStoreVariable"]?.Value<string>("name")
                ?? throw new ConversionException($"Missing GPU interface parameter-store name: {asset.Name}:{script.Name}:{name}");
            string resolvedName = binding["ResolvedVariable"]?.Value<string>("name")
                ?? throw new ConversionException($"Missing GPU interface resolved name: {asset.Name}:{script.Name}:{name}");
            int objectIndex = binding.Value<int>("ResolvedDataInterface");
            SourceAsset objectAsset = ScriptDataInterfaceBindings.ObjectAsset(asset, binding, library);
            SourceObject? data = objectIndex > 0 ? objectAsset.Object(objectIndex) : null;
            JObject sourceInfo = compileInterfaces.OfType<JObject>().Single(info => info.Value<string>("Name") == name);
            JObject interfaceType = sourceInfo["Type"] as JObject
                ?? throw new ConversionException($"Missing GPU interface type: {asset.Name}:{script.Name}:{name}");
            string interfaceClass = data?.ClassName ?? NiagaraTypeSource.Read(translation.Asset, interfaceType).Path.Split('.').Last();
            if (!ArrayDataInterfaceCompiler.Supports(interfaceClass) && interfaceClass is not (
                    "NiagaraDataInterfaceCurve" or "NiagaraDataInterfaceColorCurve" or "NiagaraDataInterfaceVector2DCurve"
                    or "NiagaraDataInterfaceVectorCurve" or "NiagaraDataInterfaceVector4Curve" or "NiagaraDataInterfaceVectorField"
                    or "NiagaraDataInterfaceSpriteRendererInfo" or "NiagaraDataInterfaceMeshRendererInfo" or "NiagaraDataInterfaceCamera" or "NiagaraDataInterfaceCurlNoise"
                    or "NiagaraDataInterfaceParticleRead" or "NiagaraDataInterfaceSimpleCounter" or "NiagaraDataInterfaceEmitterProperties"
                    or "NiagaraDataInterfaceTexture" or "NiagaraDataInterfaceRenderTarget2D" or "NiagaraDataInterfacePlatformSet"
                    or TextureDataInterfaceCompiler.ArrayClass or TextureDataInterfaceCompiler.CubeClass or TextureDataInterfaceCompiler.VolumeClass
                    or RenderTarget2DDataInterfaceCompiler.ArrayClass or RenderTarget2DDataInterfaceCompiler.CubeClass or RenderTarget2DDataInterfaceCompiler.VolumeClass
                    or "NiagaraDataInterfaceOcclusion" or "NiagaraDataInterfaceGBuffer" or "NiagaraDataInterfaceCollisionQuery"
                    or ObjectPropertyReaderCompiler.ClassName or PropertyInterfaceCompiler.ClassName or ActorComponentDataInterfaceCompiler.ClassName
                    or ConsoleVariableDataInterfaceCompiler.ClassName or AsyncGpuTraceDataInterfaceCompiler.ClassName
                    or StaticMeshDataInterfaceCompiler.ClassName or SkeletalMeshDataInterfaceCompiler.ClassName
                    or "NiagaraDataInterfaceStaticMeshIndirect"
                    or DebugDrawInterfaceCompiler.ClassName or VolumeCacheInterfaceCompiler.ClassName or PhysicsAssetInterfaceCompiler.ClassName
                    or RigidMeshCollisionInterfaceCompiler.ClassName
                    or GeometryCollectionInterfaceCompiler.ClassName or ChaosDestructionInterfaceCompiler.ClassName
                    or HairStrandsInterfaceCompiler.ClassName
                    or PhysicsFieldInterfaceCompiler.ClassName
                    or SceneCapture2DInterfaceCompiler.ClassName
                    or LandscapeInterfaceCompiler.ClassName
                    or VirtualTextureInterfaceCompiler.RuntimeClass or VirtualTextureInterfaceCompiler.StreamingClass
                    or ParticleExportDataInterfaceCompiler.ClassName or DataChannelInterfaceCompiler.Reader or DataChannelInterfaceCompiler.Writer
                    or AudioAnalysisInterfaceCompiler.Spectrum or AudioAnalysisInterfaceCompiler.Oscilloscope
                    or SimCacheReaderInterfaceCompiler.ClassName or MemoryBufferInterfaceCompiler.ClassName or DynamicMeshInterfaceCompiler.ClassName
                    or NeighborGridInterfaceCompiler.ClassName or RasterizationGridInterfaceCompiler.ClassName
                    or VelocityGridInterfaceCompiler.VelocityClass or VelocityGridInterfaceCompiler.PressureClass
                    or Grid2DCollectionInterfaceCompiler.ClassName or Grid2DCollectionInterfaceCompiler.ReaderClass
                    or DataTableInterfaceCompiler.ClassName or GaussianSplatInterfaceCompiler.ReaderClass or GaussianSplatInterfaceCompiler.WriterClass
                    or IntRenderTarget2DInterfaceCompiler.ClassName
                    or SocketReaderInterfaceCompiler.ClassName or SplineInterfaceCompiler.ClassName
                    or IntDistributionInterfaceCompiler.ClassName
                    or ViewQueryInterfaceCompiler.MousePosition or ViewQueryInterfaceCompiler.MovieRenderQueue
                    or NeighborQueryInterfaceCompiler.ClassName
                    or Grid3DCollectionInterfaceCompiler.ClassName or Grid3DCollectionInterfaceCompiler.ReaderClass
                    or SparseVolumeInterfaceCompiler.ClassName))
                throw new ConversionException($"GPU data interface requires its runtime binding: {interfaceClass} ({asset.Name}:{script.Name}:{name})");
            JObject compiled = new() {
                ["compile_name"] = name,
                ["parameter_name"] = parameterName,
                ["resolved_parameter_name"] = resolvedName,
                ["class"] = interfaceClass,
                ["interface_id"] = binding.Value<string>("InstanceId") ?? ScriptDataInterfaceBindings.Identity(objectAsset, objectIndex),
                ["binding"] = binding.DeepClone(),
                ["properties"] = data?.Properties.DeepClone()
            };
            var storeVariable = ExecutionNumericParameterSource.Read(asset, (JObject)binding["ParameterStoreVariable"]!);
            compiled["interface_key"] = new JObject { ["name"] = storeVariable.Name, ["source_type"] = storeVariable.Type.Path,
                ["underlying"] = storeVariable.Type.UnderlyingType, ["flags"] = storeVariable.Type.Flags, ["storage"] = "interface" };
            if (objectIndex < 0) compiled["source_reference"] = objectAsset.Reference(objectIndex);
            if (interfaceClass == PlatformSetDataInterfaceCompiler.ClassName)
                compiled["platform_set"] = PlatformSetDataInterfaceCompiler.Compile(data, library,
                    objectIndex < 0 ? objectAsset.ObjectPath(objectIndex) : null,
                    binding.Value<string>("InstanceInitialization") ?? "loaded");
            if (CurveDataInterfaceCompiler.Supports(interfaceClass))
                CurveDataInterfaceCompiler.CompileGpu(compiled, objectAsset, data, interfaceClass, library,
                        objectIndex < 0 ? objectAsset.ObjectPath(objectIndex) : null);
            if (interfaceClass == "NiagaraDataInterfaceTexture")
                compiled["texture_2d"] = TextureDataInterfaceCompiler.Compile(asset, objectAsset == asset ? data : null, system, library,
                        objectIndex < 0 || objectAsset != asset ? objectAsset.ObjectPath(objectIndex) : null);
            if (interfaceClass == TextureDataInterfaceCompiler.ArrayClass)
                compiled["texture_array"] = TextureDataInterfaceCompiler.CompileArray(objectAsset, objectIndex, library, asset, system);
            if (interfaceClass == TextureDataInterfaceCompiler.CubeClass)
                compiled["texture_cube"] = TextureDataInterfaceCompiler.CompileCube(objectAsset, objectIndex, library, asset, system);
            if (interfaceClass == TextureDataInterfaceCompiler.VolumeClass)
                compiled["texture_volume"] = TextureDataInterfaceCompiler.CompileVolume(objectAsset, objectIndex, library, asset, system);
            if (interfaceClass == "NiagaraDataInterfaceRenderTarget2D")
                compiled["render_target_2d"] = RenderTarget2DDataInterfaceCompiler.Compile(asset, objectAsset == asset ? data : null, system, library,
                        objectIndex < 0 || objectAsset != asset ? objectAsset.ObjectPath(objectIndex) : null);
            if (interfaceClass == RenderTarget2DDataInterfaceCompiler.ArrayClass)
                compiled["render_target_array"] = RenderTarget2DDataInterfaceCompiler.CompileArray(objectAsset, objectIndex, library, asset, system);
            if (interfaceClass == RenderTarget2DDataInterfaceCompiler.VolumeClass)
                compiled["render_target_volume"] = RenderTarget2DDataInterfaceCompiler.CompileVolume(objectAsset, objectIndex, library, asset, system);
            if (interfaceClass == RenderTarget2DDataInterfaceCompiler.CubeClass)
                compiled["render_target_cube"] = RenderTarget2DDataInterfaceCompiler.CompileCube(objectAsset, objectIndex, library, asset, system);
            if (interfaceClass == AsyncGpuTraceDataInterfaceCompiler.ClassName)
                compiled["async_gpu_trace"] = AsyncGpuTraceDataInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == VolumeCacheInterfaceCompiler.ClassName)
                compiled["volume_cache"] = VolumeCacheInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == PhysicsAssetInterfaceCompiler.ClassName)
                compiled["physics_asset"] = PhysicsAssetInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == GeometryCollectionInterfaceCompiler.ClassName)
                compiled["geometry_collection"] = GeometryCollectionInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == ChaosDestructionInterfaceCompiler.ClassName)
                compiled["chaos_destruction"] = ChaosDestructionInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == HairStrandsInterfaceCompiler.ClassName)
                compiled["hair_strands"] = HairStrandsInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == RigidMeshCollisionInterfaceCompiler.ClassName)
                compiled["rigid_mesh_collision"] = RigidMeshCollisionInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == PhysicsFieldInterfaceCompiler.ClassName)
                compiled["physics_field"] = true;
            if (interfaceClass == SceneCapture2DInterfaceCompiler.ClassName)
                compiled["scene_capture_2d"] = SceneCapture2DInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == LandscapeInterfaceCompiler.ClassName)
                compiled["landscape"] = LandscapeInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass is VirtualTextureInterfaceCompiler.RuntimeClass or VirtualTextureInterfaceCompiler.StreamingClass)
                compiled["virtual_texture"] = VirtualTextureInterfaceCompiler.Definition(objectAsset, objectIndex, library, interfaceClass);
            if (interfaceClass == StaticMeshDataInterfaceCompiler.ClassName)
                compiled["static_mesh_reader"] = StaticMeshDataInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == SkeletalMeshDataInterfaceCompiler.ClassName)
                compiled["skeletal_mesh_reader"] = SkeletalMeshDataInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == ParticleExportDataInterfaceCompiler.ClassName)
                compiled["particle_export"] = ParticleExportDataInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass is DataChannelInterfaceCompiler.Reader or DataChannelInterfaceCompiler.Writer)
                compiled["data_channel"] = DataChannelInterfaceCompiler.Definition(objectAsset, objectIndex, interfaceClass, library);
            if (interfaceClass is AudioAnalysisInterfaceCompiler.Spectrum or AudioAnalysisInterfaceCompiler.Oscilloscope)
                compiled["audio_analysis"] = AudioAnalysisInterfaceCompiler.Definition(objectAsset, objectIndex, interfaceClass, library);
            if (interfaceClass == SimCacheReaderInterfaceCompiler.ClassName)
                compiled["sim_cache_reader"] = SimCacheReaderInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == MemoryBufferInterfaceCompiler.ClassName)
                compiled["memory_buffer"] = MemoryBufferInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == NeighborGridInterfaceCompiler.ClassName)
                compiled["neighbor_grid"] = NeighborGridInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == RasterizationGridInterfaceCompiler.ClassName)
                compiled["rasterization_grid"] = RasterizationGridInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass is VelocityGridInterfaceCompiler.VelocityClass or VelocityGridInterfaceCompiler.PressureClass)
                compiled["velocity_grid"] = VelocityGridInterfaceCompiler.Definition(objectAsset, objectIndex, library, interfaceClass);
            if (interfaceClass is Grid2DCollectionInterfaceCompiler.ClassName or Grid2DCollectionInterfaceCompiler.ReaderClass)
                compiled["grid2d_collection"] = Grid2DCollectionInterfaceCompiler.Definition(objectAsset, objectIndex, library, interfaceClass);
            if (interfaceClass == DataTableInterfaceCompiler.ClassName)
                compiled["data_table"] = DataTableInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == GaussianSplatInterfaceCompiler.ReaderClass)
                compiled["gaussian_splat_reader"] = GaussianSplatInterfaceCompiler.Reader(objectAsset, objectIndex, library);
            if (interfaceClass == GaussianSplatInterfaceCompiler.WriterClass)
                compiled["gaussian_splat_writer"] = GaussianSplatInterfaceCompiler.Writer(objectAsset, objectIndex, library);
            if (interfaceClass == PropertyInterfaceCompiler.ClassName)
                compiled["property_interface"] = PropertyInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == IntRenderTarget2DInterfaceCompiler.ClassName)
                compiled["int_render_target_2d"] = IntRenderTarget2DInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == SocketReaderInterfaceCompiler.ClassName)
                compiled["socket_reader"] = SocketReaderInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == SplineInterfaceCompiler.ClassName)
                compiled["spline"] = SplineInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == IntDistributionInterfaceCompiler.ClassName)
                compiled["int_distribution"] = IntDistributionInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == NeighborQueryInterfaceCompiler.ClassName)
                compiled["neighbor_query"] = NeighborQueryInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass is Grid3DCollectionInterfaceCompiler.ClassName or Grid3DCollectionInterfaceCompiler.ReaderClass)
                compiled["grid3d_collection"] = Grid3DCollectionInterfaceCompiler.Definition(objectAsset, objectIndex, library, interfaceClass);
            if (interfaceClass == DynamicMeshInterfaceCompiler.ClassName)
                compiled["dynamic_mesh"] = DynamicMeshInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            if (interfaceClass == SparseVolumeInterfaceCompiler.ClassName)
                compiled["sparse_volume_texture"] = SparseVolumeInterfaceCompiler.Definition(objectAsset, objectIndex, library);
            string symbol = ShaderSymbol(name);
            JObject[] symbolMatches = shaderInterfaces.OfType<JObject>().Where(value =>
                value.Value<string>("DataInterfaceHLSLSymbol") == symbol
                && value.Value<string>("DIClassName") == interfaceClass).ToArray();
            if (symbolMatches.Length == 1 && resolved.OfType<JObject>().Count(value =>
                    ShaderSymbol(value.Value<string>("CompileName") ?? "") == symbol) == 1)
            {
                compiled["shader_symbol"] = symbol;
                if (interfaceClass == AsyncGpuTraceDataInterfaceCompiler.ClassName)
                    compiled["async_trace_pre_stage"] = (symbolMatches[0].Value<int?>("ShaderParametersOffset") ?? 0) != -1;
                if (interfaceClass == LandscapeInterfaceCompiler.ClassName)
                    compiled["landscape_usage"] = LandscapeInterfaceCompiler.GpuUsage(symbolMatches[0]);
                if (interfaceClass is DataChannelInterfaceCompiler.Reader or DataChannelInterfaceCompiler.Writer)
                    compiled["data_channel_gpu_parameters"] = DataChannelInterfaceCompiler.GpuParameters(asset, symbolMatches[0]);
                if (interfaceClass == ObjectPropertyReaderCompiler.ClassName)
                    compiled["property_reader_functions"] = ObjectPropertyReaderCompiler.GpuFunctions(symbolMatches[0]);
                if (interfaceClass == PropertyInterfaceCompiler.ClassName) {
                    var outputs = PropertyInterfaceCompiler.GpuOutputs(asset, symbolMatches[0]);
                    compiled["property_interface_outputs"] = outputs;
                    hlsl = PropertyInterfaceCompiler.ResolveGpuReadOffsets(hlsl, symbol, outputs.Count);
                }
                if (interfaceClass == ConsoleVariableDataInterfaceCompiler.ClassName)
                    compiled["console_variable_functions"] = ConsoleVariableDataInterfaceCompiler.GpuFunctions(symbolMatches[0]);
                if (interfaceClass == SimCacheReaderInterfaceCompiler.ClassName)
                    compiled["sim_cache_gpu_functions"] = SimCacheReaderInterfaceCompiler.GpuFunctions(symbolMatches[0]);
                if (interfaceClass is Grid2DCollectionInterfaceCompiler.ClassName or Grid2DCollectionInterfaceCompiler.ReaderClass)
                    compiled["grid2d_functions"] = Grid2DCollectionInterfaceCompiler.GpuFunctions(symbolMatches[0]);
                if (interfaceClass == DataTableInterfaceCompiler.ClassName)
                    compiled["data_table_columns"] = DataTableInterfaceCompiler.GpuColumns(asset, symbolMatches[0]);
                if (interfaceClass is Grid3DCollectionInterfaceCompiler.ClassName or Grid3DCollectionInterfaceCompiler.ReaderClass)
                    compiled["grid3d_functions"] = Grid3DCollectionInterfaceCompiler.GpuFunctions(symbolMatches[0]);
                if (interfaceClass is "NiagaraDataInterfaceCurve" or "NiagaraDataInterfaceColorCurve"
                    or "NiagaraDataInterfaceVector2DCurve" or "NiagaraDataInterfaceVectorCurve" or "NiagaraDataInterfaceVector4Curve")
                {
                    string pattern = @"\bfloat\s+SampleCurveLUT_" + Regex.Escape(symbol)
                        + @"\s*\([^)]*\)\s*\{\s*#if\s+([01])\s*==\s*1\b";
                    MatchCollection matches = Regex.Matches(hlsl, pattern);
                    if (matches.Count == 1) compiled["curve_use_unique_buffer"] = matches[0].Groups[1].Value == "1";
                }
            }
            if (interfaceClass == "NiagaraDataInterfaceVectorField")
                VectorFieldDataInterfaceCompiler.CompileGpu(compiled, objectAsset, data, library,
                        objectIndex < 0 ? objectAsset.ObjectPath(objectIndex) : null);
            if (ArrayDataInterfaceCompiler.Supports(interfaceClass))
            {
                JObject properties = MutableDataInterfaceInstancesCompiler.ReadProperties(objectAsset, objectIndex, interfaceClass, library);
                compiled["array_data"] = ArrayDataInterfaceCompiler.Definition(interfaceClass, properties, objectAsset.ObjectPath(objectIndex));
                compiled["properties"] = properties.DeepClone();
                compiled["array_writable"] = false;
                if (compiled.Value<string>("shader_symbol") is string arraySymbol)
                    compiled["array_writable"] = Regex.IsMatch(hlsl, @"\bRWBuffer\s*<[^>]+>\s+" + Regex.Escape(arraySymbol) + @"_ArrayRWBuffer\b");
            }
            if (interfaceClass == CurlNoiseDataInterfaceCompiler.ClassName) {
                JObject curl = CurlNoiseDataInterfaceCompiler.Compile(objectAsset, objectIndex, library,
                    binding.Value<string>("InstanceInitialization") ?? "loaded");
                compiled["curl_seed"] = curl["seed"]!.DeepClone();
                compiled["curl_offset_seed"] = curl["offset_seed"]!.DeepClone();
            }
            if (interfaceClass == CameraDataInterfaceCompiler.ClassName)
                compiled["camera"] = CameraDataInterfaceCompiler.Compile(objectAsset, objectIndex, library);
            if (interfaceClass == "NiagaraDataInterfaceSimpleCounter") {
                JObject properties = MutableDataInterfaceInstancesCompiler.ReadProperties(objectAsset, objectIndex, interfaceClass, library);
                compiled["simple_counter"] = SimpleCounterDataInterfaceCompiler.Definition(properties);
                compiled["properties"] = properties.DeepClone();
            }
            if (interfaceClass == "NiagaraDataInterfaceParticleRead")
            {
                JObject target = EmitterBindingCompiler.Compile(objectAsset, objectIndex, interfaceClass,
                    binding["InstanceRuntimeOuter"] as JObject, library);
                target["functions"] = symbolMatches.Length == 1
                    ? symbolMatches[0]["GeneratedFunctions"]?.DeepClone() ?? new JArray() : new JArray();
                compiled["particle_read"] = target;
            }
            if (interfaceClass == "NiagaraDataInterfaceEmitterProperties")
                compiled["emitter_properties"] = EmitterBindingCompiler.Compile(objectAsset, objectIndex, interfaceClass,
                    binding["InstanceRuntimeOuter"] as JObject, library);
            if (data is not null && data.ClassName == "NiagaraDataInterfaceSpriteRendererInfo")
            {
                int rendererIndex = data.Properties.Value<int?>("SpriteRenderer") ?? 0;
                var renderer = SpriteRendererInfoCompiler.Resolve(library, objectAsset, rendererIndex);
                if (rendererIndex < 0) compiled["sprite_renderer_reference"] = objectAsset.Reference(rendererIndex);
                if (renderer.Renderer is not null) compiled["sprite_renderer"] = renderer.Renderer.DeepClone();
                compiled["sprite_info"] = SpriteRendererInfoCompiler.Compile(renderer.Asset, renderer.Renderer);
            }
            if (data is null && interfaceClass == "NiagaraDataInterfaceSpriteRendererInfo")
                compiled["sprite_info"] = SpriteRendererInfoCompiler.CompileInterface(library, objectAsset, null,
                        objectIndex < 0 ? objectAsset.ObjectPath(objectIndex) : null);
            if (interfaceClass == "NiagaraDataInterfaceMeshRendererInfo")
                compiled["mesh_info"] = MeshRendererInfoCompiler.CompileInterface(library, objectAsset, data,
                        objectIndex < 0 ? objectAsset.ObjectPath(objectIndex) : null);
            interfaces.Add(compiled);
        }
        JObject emitterSettings = new() { ["AllocationMode"] = EmitterHandleSource.AllocationMode(emitterVersion) };
        foreach (string field in new[] { "bGpuAlwaysRunParticleUpdateScript", "bRequiresPersistentIDs",
                     "MaxGPUParticlesSpawnPerFrame", "PreAllocationCount",
                     "CalculateBoundsMode", "FixedBounds", "SimStageExecutionLoops", "SimStageExecutionLoopEditorData",
                     "EventHandlerScriptProps", "SharedEventGeneratorIds", "SpawnScriptProps", "UpdateScriptProps" })
            if (emitterVersion[field] is JToken value) emitterSettings[field] = value.DeepClone();
        JArray stageObjects = new();
        foreach (JToken token in emitterVersion["SimulationStages"] as JArray ?? new JArray())
        {
            int index = token.Value<int>();
            if (index == 0) { stageObjects.Add(JValue.CreateNull()); continue; }
            if (index < 0) { stageObjects.Add(new JObject { ["reference"] = asset.Reference(index) }); continue; }
            SourceObject stage = asset.Object(index);
            stageObjects.Add(new JObject { ["name"] = stage.Name, ["class"] = stage.ClassName,
                ["properties"] = stage.Properties.DeepClone() });
        }
        JArray parameterBindings = new();
        NiagaraCollectionSource collections = new(asset, system, CollectionOwner(asset, emitterVersion), library);
        JObject executionStore = ExecutionParameterStoreSource.Compile(asset, script, runtimeBindings, library, system, collections);
        Match externalBuffer = Regex.Match(hlsl, @"\bcbuffer\s+FNiagaraExternalParameters\s*\{(?<body>[^}]*)\}");
        JToken? parameterContainer = executable["Parameters"];
        JArray parameterValues = parameterContainer as JArray ?? parameterContainer?["Parameters"] as JArray ?? new JArray();
        if (runtimeBindings[ScriptRuntimeSource.ExecutionStore] is not null) {
            byte[] data = NumericParameterStoreSource.Bytes(executionStore["data"]);
            foreach (JObject slot in ExecutionParameterStoreSource.CurrentNumeric(executionStore))
                Bind(slot.Value<string>("name")!, slot.Value<string>("source_type")!, slot.Value<int>("underlying"),
                    slot.Value<int>("flags"), slot["lwc"]?.DeepClone(),
                    data.AsSpan(slot.Value<int>("offset"), slot.Value<int>("bytes")).ToArray());
        } else {
            foreach (JObject parameter in parameterValues.OfType<JObject>()) {
                var execution = ExecutionNumericParameterSource.Read(asset, parameter);
                Bind(execution.Name, execution.Type.Path, execution.Type.UnderlyingType, execution.Type.Flags,
                    execution.Lwc is bool lwc ? new JValue(lwc) : null, execution.Data);
            }
        }

        void Bind(string parameterName, string sourceType, int underlying, int flags, JToken? lwc, byte[] data)
        {
            string qualifiedAlias = ExecutionShaderAlias(parameterName, emitterName);
            string symbol = ParameterShaderSymbol(qualifiedAlias, shaderKeywords);
            JObject parameterBinding = new() {
                ["name"] = parameterName, ["source_type"] = sourceType,
                ["underlying"] = underlying, ["type_flags"] = flags,
                ["lwc"] = lwc, ["bytes"] = data.Length, ["data"] = Convert.ToBase64String(data)
            };
            int byteCount = data.Length;
            if (byteCount > 0) collections.Apply(parameterBinding, parameterName, sourceType, byteCount);
            if (externalBuffer.Success && Regex.IsMatch(externalBuffer.Groups["body"].Value,
                    @"\b" + Regex.Escape(symbol) + @"\s*(?:\[[^\]]+\])?\s*;"))
                parameterBinding["shader_symbol"] = symbol;
            parameterBindings.Add(parameterBinding);
        }
        JArray rapidSources = new();
        if (!(asset.Raw.Value<string>("PackageFlags") ?? "").Contains("PKG_Cooked", StringComparison.Ordinal)) {
            foreach (string field in new[] { "SpawnScriptProps", "UpdateScriptProps" })
                rapidSources.Add(SavedParameterStoreSource.RapidIteration(asset,
                    asset.Object(emitterVersion[field]!.Value<int>("Script")), true, library, system));
            foreach (int stageIndex in (emitterVersion["SimulationStages"] as JArray ?? new()).Values<int>()) {
                var stage = asset.Object(stageIndex);
                rapidSources.Add(SavedParameterStoreSource.RapidIteration(asset, asset.Object(stage.Properties.Value<int>("Script")), false, library, system));
            }
        }
        executionStore["instance_sources"] = rapidSources;
        JObject emittedBindings = (JObject)runtimeBindings.DeepClone();
        emittedBindings.Remove(ScriptRuntimeSource.ExecutionStore);
        emittedBindings.Remove(ScriptRuntimeSource.RapidBindings);
        return new JObject {
            ["hlsl"] = GpuVectorBooleanOperations.Lower(GpuSpawnInfoUniforms.Pack(hlsl)),
            ["entry_point"] = "SimulateMainComputeCS",
            ["stages"] = stages.DeepClone(),
            ["variants"] = variants,
            ["parameter_metadata"] = parameters.DeepClone(),
            ["additional_defines"] = additionalDefines.DeepClone(),
            ["interface_compile_info"] = compileInterfaces.DeepClone(),
            ["interfaces"] = interfaces,
            ["runtime_bindings"] = emittedBindings,
            ["emitter_settings"] = emitterSettings,
            ["simulation_stage_objects"] = stageObjects,
            ["parameters"] = executable["Parameters"]?.DeepClone() ?? new JArray(),
            ["parameter_bindings"] = parameterBindings,
            ["execution_store"] = executionStore,
            ["host_contexts"] = GpuHostContextCompiler.Compile(asset, system, emitterVersion, handle, allRuntimeBindings, library),
            ["attributes_written"] = executable["AttributesWritten"]?.DeepClone() ?? new JArray()
        };
    }

    private static JObject CollectionOwner(SourceAsset asset, JObject emitter)
    {
        JArray references = new();
        foreach (string field in new[] { "SpawnScriptProps", "UpdateScriptProps" })
            Add(asset.Object(emitter[field]!.Value<int>("Script")));
        foreach (int index in (emitter["SimulationStages"] as JArray ?? new()).Values<int>()) {
            var stage = asset.Object(index).Properties;
            if (stage.Value<bool?>("bEnabled") != false) Add(asset.Object(stage.Value<int>("Script")));
        }
        return new JObject { ["CachedParameterCollectionReferences"] = references };

        void Add(SourceObject source) {
            foreach (int reference in (source.Properties["CachedParameterCollectionReferences"] as JArray ?? new()).Values<int>())
                references.Add(reference);
        }
    }

    private static string ExecutionShaderAlias(string name, string emitterName)
    {
        int first = name.IndexOf('.');
        if (first < 0) return name;
        if (SourceNameOrder.Equal(name[..first], emitterName)) return "Emitter" + name[first..];
        if (!SourceNameOrder.Equal(name[..first], "Constants")) return name;
        int second = name.IndexOf('.', first + 1);
        return second >= 0 && SourceNameOrder.Equal(name[(first + 1)..second], emitterName)
            ? "Constants.Emitter" + name[second..] : name;
    }

    internal static string ShaderSymbol(string name)
    {
        StringBuilder result = new();
        AppendAsciiSymbol(result, name.Replace('.', '_').Trim());
        return EscapeDoubleUnderscores(result.ToString());
    }

    private static string ParameterShaderSymbol(string name, IReadOnlyDictionary<string, string> keywords)
    {
        string[] parts = name.Split('.', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);
        StringBuilder result = new();
        for (int index = 0; index < parts.Length; index++)
        {
            if (index != 0) result.Append('_');
            string part = parts[index];
            // The translator applies keyword protection to each namespace part before joining.
            if (keywords.TryGetValue(part, out string? replacement)) result.Append(replacement);
            else AppendAsciiSymbol(result, part);
        }
        return EscapeDoubleUnderscores(result.ToString());
    }

    private static void AppendAsciiSymbol(StringBuilder result, string input)
    {
        if (input.Length > 0 && input[0] >= '0' && input[0] <= '9') result.Append("INTEGER_");
        foreach (char ch in input)
        {
            if (ch is >= 'a' and <= 'z' or >= 'A' and <= 'Z' or >= '0' and <= '9' or '_') result.Append(ch);
            else if (!char.IsWhiteSpace(ch)) result.Append("ASC").Append((int)ch);
        }
    }

    private static string EscapeDoubleUnderscores(string symbol) =>
        symbol.Replace("__", "ASC95ASC95", StringComparison.Ordinal);

}
