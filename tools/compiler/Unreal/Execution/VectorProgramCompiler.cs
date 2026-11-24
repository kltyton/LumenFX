using System.Buffers.Binary;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal.Components;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

public sealed class VectorProgramCompiler
{
    private sealed record Opcode(string Name, string Category, int Inputs, int Outputs);
    private readonly Opcode[] opcodes;
    private readonly bool preciseMotionVectors;
    private readonly NiagaraProjectSettings projectSettings;
    private readonly Lazy<IReadOnlyDictionary<string, string>> shaderKeywords;
    internal string EngineSourceRoot { get; }
    internal bool DefaultPixelCoverage => projectSettings.DefaultPixelCoverage;

    public VectorProgramCompiler(string engineSource, bool preciseMotionVectors = true)
        : this(engineSource, preciseMotionVectors, NiagaraProjectSettings.Default) { }

    internal VectorProgramCompiler(string engineSource, bool preciseMotionVectors, NiagaraProjectSettings projectSettings)
    {
        this.preciseMotionVectors = preciseMotionVectors;
        this.projectSettings = projectSettings;
        EngineSourceRoot = Path.GetFullPath(engineSource);
        shaderKeywords = new(() => NiagaraShaderKeywords.Read(EngineSourceRoot));
        string header = File.ReadAllText(Path.Combine(engineSource, "Engine/Source/Runtime/VectorVM/Public/VectorVM.h"));
        opcodes = Regex.Matches(header, @"VVM_OP_XM\(\s*(\w+)\s*,\s*(\w+)\s*,\s*(\d+)\s*,\s*(\d+)\s*,")
            .Select(match => new Opcode(match.Groups[1].Value, match.Groups[2].Value,
                int.Parse(match.Groups[3].Value), int.Parse(match.Groups[4].Value))).ToArray();
        if (opcodes.Length == 0 || opcodes[0].Name != "done") throw new ConversionException("Missing VectorVM opcode definitions");
    }

    public JObject Compile(SourceAsset asset, SourceObject system) => Compile(asset, system, null);

    public JObject Compile(SourceAsset asset, SourceObject system, AssetLibrary? library)
        => Compile(asset, system, library, null);

    internal JObject Compile(SourceAsset asset, SourceObject system, AssetLibrary? library, NiagaraComponentSource? component)
    {
        JObject properties = system.Properties;
        JObject compiled = (JObject)properties["SystemCompiledData"]!;
        JArray defaultBindings = ScriptRuntimeSource.Read(asset, system);
        var bindings = ScriptDataInterfaceBindings.Resolve(asset, system, defaultBindings, library);
        var systemSpawn = asset.Object(properties.Value<int>("SystemSpawnScript"));
        var systemUpdate = asset.Object(properties.Value<int>("SystemUpdateScript"));
        var systemSpawnAttributes = (JArray)((JObject)systemSpawn.Properties["CachedScriptVM"]!)["Attributes"]!;
        var systemUpdateAttributes = (JArray)((JObject)systemUpdate.Properties["CachedScriptVM"]!)["Attributes"]!;
        JArray emitters = new();
        foreach (JObject handle in (JArray)properties["EmitterHandles"]!)
        {
            if (handle.Value<bool?>("bIsEnabled") == false) {
                emitters.Add(DisabledEmitterSource.Compile(asset, handle));
                continue;
            }
            EmitterHandleSource.Resolved resolvedEmitter = EmitterHandleSource.Resolve(asset, handle);
            if (resolvedEmitter.Stateless) {
                emitters.Add(Stateless.StatelessEmitterCompiler.Compile(asset, system, handle, resolvedEmitter, library, preciseMotionVectors));
                continue;
            }
            JObject version = resolvedEmitter.Settings;
            var spawn = asset.Object(version["SpawnScriptProps"]!.Value<int>("Script"));
            var update = asset.Object(version["UpdateScriptProps"]!.Value<int>("Script"));
            string sourceName = resolvedEmitter.Namespace;
            bool gpu = resolvedEmitter.Target == "GPUComputeSim";
            SourceObject particleLayoutScript = gpu ? asset.Object(version.Value<int>("GPUComputeScript")) : update;
            var particleAttributes = particleLayoutScript.Properties["CachedScriptVM"]?["Attributes"] as JArray
                ?? throw new ConversionException($"Niagara emitter {sourceName} has no saved {(gpu ? "GPU" : "CPU")} particle attributes");
            JArray? spawnAttributes = gpu ? null : (JArray)spawn.Properties["CachedScriptVM"]!["Attributes"]!;
            var compiledEmitter = new JObject {
                ["name"] = handle.Value<string>("Name")!.ToLowerInvariant(),
                ["source_name"] = sourceName,
                ["handle_id"] = handle.Value<string>("Id"),
                ["local_space"] = version.Value<bool>("bLocalSpace"), ["max_particles"] = 1000000,
                ["bounds_mode"] = EmitterHandleSource.BoundsMode(version),
                ["fixed_bounds"] = Bounds(version["FixedBounds"]),
                ["random_seed"] = version.Value<int>("RandomSeed"),
                ["deterministic"] = version.Value<bool>("bDeterminism"),
                ["scalability"] = NiagaraScalabilitySource.Emitter(asset, system, version, library),
                ["attributes"] = CompiledParticleLayout.FromScripts(asset, particleAttributes, spawnAttributes),
                ["spawn_info"] = SpawnInfo(asset, sourceName, systemSpawnAttributes, systemUpdateAttributes) };
            compiledEmitter["spawn_events"] = EventGenerators(asset, version["SpawnScriptProps"] as JObject);
            compiledEmitter["update_events"] = EventGenerators(asset, version["UpdateScriptProps"] as JObject);
            compiledEmitter["shared_event_ids"] = version["SharedEventGeneratorIds"]?.DeepClone() ?? new JArray();
            JArray emitterDependencies = (JArray)(version["EmitterDependencies"]?.DeepClone() ?? new JArray());
            foreach (JObject dependency in emitterDependencies)
                dependency["BindingMode"] = EmitterBindingCompiler.BindingMode(dependency);
            if (gpu) AddDataInterfaceDependencies(asset, Resolved(defaultBindings, handle.Value<string>("Id")!, particleLayoutScript), emitterDependencies, library);
            else
            {
                AddDataInterfaceDependencies(asset, Resolved(defaultBindings, handle.Value<string>("Id")!, spawn), emitterDependencies, library);
                AddDataInterfaceDependencies(asset, Resolved(defaultBindings, handle.Value<string>("Id")!, update), emitterDependencies, library);
            }
            JArray handlers = new();
            foreach (JObject handler in (version["EventHandlerScriptProps"] as JArray ?? new JArray()).OfType<JObject>())
            {
                int scriptIndex = handler.Value<int?>("Script") ?? 0;
                JObject compiledHandler = new() {
                    ["execution_mode"] = EventExecutionMode(handler["ExecutionMode"]),
                    ["source_emitter_id"] = handler.Value<string>("SourceEmitterID") ?? Guid.Empty.ToString("B"),
                    ["source_event_name"] = handler.Value<string>("SourceEventName") ?? "None",
                    ["spawn_number"] = handler.Value<long?>("SpawnNumber") ?? 0,
                    ["min_spawn_number"] = handler.Value<long?>("MinSpawnNumber") ?? 0,
                    ["max_events_per_frame"] = handler.Value<long?>("MaxEventsPerFrame") ?? 0,
                    ["random_spawn_number"] = handler.Value<bool?>("bRandomSpawnNumber") ?? false,
                    // The cached event program already contains the compiler's Initial attribute writes.
                    ["update_attribute_initial_values"] = NiagaraImporter.NiagaraVersion(asset) >= 76
                        && (handler.Value<bool?>("UpdateAttributeInitialValues") ?? true)
                };
                if (scriptIndex > 0 && !gpu)
                {
                    SourceObject eventScript = asset.Object(scriptIndex);
                    AddDataInterfaceDependencies(asset, Resolved(defaultBindings, handle.Value<string>("Id")!, eventScript), emitterDependencies, library);
                    compiledHandler["program"] = Program(asset, eventScript, ResolvedRuntime(bindings, handle.Value<string>("Id")!, eventScript), bindings, library, system);
                }
                handlers.Add(compiledHandler);
            }
            compiledEmitter["event_handlers"] = handlers;
            compiledEmitter["emitter_dependencies"] = emitterDependencies;
            compiledEmitter["renderer_bindings"] = RendererParameterStoreSource.Compile(asset, system, version, sourceName, handle.Value<string>("Id"));
            if (!gpu) {
                WriteCpuParticleAllocation(compiledEmitter, version);
                compiledEmitter["requires_persistent_ids"] = version.Value<bool>("bRequiresPersistentIDs");
            }
            if (gpu)
            {
                compiledEmitter["gpu_program"] = GpuProgramCompiler.Compile(asset, particleLayoutScript, version, sourceName, library,
                    ResolvedRuntime(bindings, handle.Value<string>("Id")!, particleLayoutScript), bindings, system,
                    handle.Value<string>("Id")!, shaderKeywords.Value);
            }
            else
            {
                compiledEmitter["spawn"] = Program(asset, spawn, ResolvedRuntime(bindings, handle.Value<string>("Id")!, spawn), bindings, library, system);
                compiledEmitter["update"] = Program(asset, update, ResolvedRuntime(bindings, handle.Value<string>("Id")!, update), bindings, library, system);
            }
            emitters.Add(compiledEmitter);
        }
        var interfaceInstances = DataInterfaceInstanceSources.Collect(asset, system, bindings, library);
        var gpuFunctions = new DataInterfaceGpuFunctions(asset, system, defaultBindings, library);
        JObject result = new() {
            ["instance_numeric"] = InstanceNumericParameters.Compile(asset, system),
            ["instance_stores"] = InstanceNumericParameters.CompileStores(asset, system, library, component),
            ["platforms"] = NiagaraScalabilitySource.Platform(properties["Platforms"]),
            ["system_scalability"] = NiagaraSystemScalabilitySource.Compile(asset, system, library),
            ["platform_refresh_sets"] = PlatformSetCatalogCompiler.Compile(asset, system, defaultBindings, library),
            ["render_target_instances"] = RenderTarget2DInstancesCompiler.Compile(asset, system, interfaceInstances, library),
            ["mutable_interface_instances"] = MutableDataInterfaceInstancesCompiler.Compile(asset, interfaceInstances, library,
                gpuFunctions),
            ["gpu_interface_functions"] = gpuFunctions.Export(),
            ["system_spawn"] = Program(asset, systemSpawn, ResolvedSystemRuntime(bindings, systemSpawn), bindings, library, system),
            ["system_update"] = Program(asset, systemUpdate, ResolvedSystemRuntime(bindings, systemUpdate), bindings, library, system),
            ["debug_parameters"] = NiagaraDebugParametersSource.Compile(asset, system, systemSpawn, systemUpdate),
            ["persistent_debug_draw"] = PersistentDebugDrawSource.Compile(asset, system, defaultBindings, library),
            ["system_random_seed"] = properties["RandomSeed"]?.Value<int>() ?? 0,
            ["system_deterministic"] = SystemDeterministic(asset, properties),
            ["timing"] = new JObject {
                ["warmup_ticks"] = properties.Value<int?>("WarmupTickCount") ?? 0,
                ["warmup_delta"] = SourceAsset.Number(properties["WarmupTickDelta"], 1.0f / 15.0f),
                ["fixed_tick"] = properties.Value<bool?>("bFixedTickDelta") ?? false,
                ["fixed_delta"] = SourceAsset.Number(properties["FixedTickDeltaTime"], .01667f),
                ["maximum_delta"] = projectSettings.MaximumDelta is { } maximumDelta ? new JValue(maximumDelta) : JValue.CreateNull(),
                ["system_state"] = NiagaraSystemStateSource.Read(asset, properties, systemSpawn, systemUpdate, library),
                ["initial_owner_velocity_from_actor"] = InitialOwnerVelocityFromActor(asset, properties),
                ["require_current_frame_data"] = properties.Value<bool?>("bRequireCurrentFrameData") ?? true },
            ["lwc"] = projectSettings.Lwc(properties),
            ["component_pool"] = new JObject {
                ["maximum"] = properties.Value<int?>("MaxPoolSize") ?? 32,
                ["prime"] = properties.Value<int?>("PoolPrimeSize") ?? 0 },
            ["system_fixed_bounds_enabled"] = properties.Value<bool?>("bFixedBounds") ?? false,
            ["system_fixed_bounds"] = properties.Value<bool?>("bFixedBounds") == true ? Bounds(properties["FixedBounds"]) : null,
            ["initial_streaming_bounds"] = properties.Value<bool?>("bUseInitialStreamingBounds") == true
                ? Bounds(properties["InitialStreamingBounds"]) : null,
            ["system_layout"] = Layout(asset, compiled["DataSetCompiledData"]!),
            ["spawn_input_layout"] = Layout(asset, compiled["SpawnInstanceParamsDataSetCompiledData"]!),
            ["update_input_layout"] = Layout(asset, compiled["UpdateInstanceParamsDataSetCompiledData"]!),
            ["spawn_input_bindings"] = Bindings(compiled, "SpawnInstance"),
            ["update_input_bindings"] = Bindings(compiled, "UpdateInstance"),
            ["emitters"] = emitters };
        EmitterBindingCompiler.Complete(asset, system, defaultBindings, result, library);
        GaussianSplatInterfaceCompiler.BindWriters(asset, system, interfaceInstances, result, library);
        if (result["system_scalability"] is JObject scalability)
            scalability["script_reads_significance"] = ReadsSignificance(systemSpawn) || ReadsSignificance(systemUpdate)
                || defaultBindings.OfType<JArray>().Any(entry => entry[0] is JObject key
                    && ReadsSignificance(ScriptDataInterfaceBindings.Script(asset, system, key)));
        return result;

        static bool ReadsSignificance(SourceObject? script)
            => script?.Properties["CachedScriptVM"]?.Value<bool?>("bReadsSignificanceIndex") == true;
    }

    internal static JToken Bounds(JToken? source)
    {
        if (source?.Type == JTokenType.Null) source = null;
        if (source is not null && source["IsValid"] is JToken valid
            && (valid.Type == JTokenType.Boolean ? !valid.Value<bool>() : valid.Value<int>() == 0))
            return JValue.CreateNull();
        JToken? min = source?["Min"], max = source?["Max"];
        if (source is not null && (min is null || max is null))
            throw new ConversionException("Niagara bounds are missing their endpoints");
        return new JObject {
            ["min"] = new JArray(SourceAsset.Number(min?["X"], -100), SourceAsset.Number(min?["Y"], -100), SourceAsset.Number(min?["Z"], -100)),
            ["max"] = new JArray(SourceAsset.Number(max?["X"], 100), SourceAsset.Number(max?["Y"], 100), SourceAsset.Number(max?["Z"], 100))
        };
    }

    private static void AddDataInterfaceDependencies(SourceAsset asset, JArray resolved, JArray dependencies, AssetLibrary? library)
    {
        foreach (JObject binding in resolved.OfType<JObject>())
        {
            int reference = binding.Value<int?>("ResolvedDataInterface") ?? 0;
            if (reference == 0) continue;
            SourceAsset owner = ScriptDataInterfaceBindings.ObjectAsset(asset, binding, library);
            if (owner.ReferencedClass(reference) == "NiagaraDataInterfaceParticleRead")
            {
                JObject emitter = EmitterBindingCompiler.Compile(owner, reference, "NiagaraDataInterfaceParticleRead",
                    binding["InstanceRuntimeOuter"] as JObject, library);
                emitter["BindingMode"] = "Other";
                dependencies.Add(emitter);
            }
        }
    }

    private static string EventExecutionMode(JToken? value) => value?.Value<string>()?.Split("::").Last() switch {
        null or "0" or "EveryParticle" => "EveryParticle",
        "1" or "SpawnedParticles" => "SpawnedParticles",
        "2" or "SingleParticle" => "SingleParticle",
        var mode => throw new ConversionException($"Unknown Niagara event execution mode: {mode}")
    };

    private static JArray EventGenerators(SourceAsset asset, JObject? scriptProperties)
    {
        JArray result = new();
        foreach (JObject generator in (scriptProperties?["EventGenerators"] as JArray ?? new JArray()).OfType<JObject>())
        {
            JToken compiled = generator["DataSetCompiledData"]
                ?? throw new ConversionException("Niagara event generator has no compiled dataset layout");
            result.Add(new JObject {
                ["id"] = generator.Value<string>("ID") ?? throw new ConversionException("Niagara event generator has no ID"),
                ["max_events_per_frame"] = generator.Value<int?>("MaxEventsPerFrame") ?? 64,
                ["layout"] = Layout(asset, compiled)
            });
        }
        return result;
    }

    private static bool SystemDeterministic(SourceAsset asset, JObject properties)
    {
        // UNiagaraSystem::Serialize restores the old default before custom version 69.
        return properties["bDeterminism"]?.Value<bool>() ?? NiagaraImporter.NiagaraVersion(asset) < 69;
    }

    private static bool InitialOwnerVelocityFromActor(SourceAsset asset, JObject properties) =>
        // UNiagaraSystem::Serialize clears this flag on assets predating InitialOwnerVelocityFromActor.
        NiagaraImporter.NiagaraVersion(asset) >= 82 && (properties.Value<bool?>("bInitialOwnerVelocityFromActor") ?? true);

    private static void WriteCpuParticleAllocation(JObject output, JObject properties)
    {
        string mode = EmitterHandleSource.AllocationMode(properties);
        int count = Math.Max(0, properties.Value<int?>("PreAllocationCount") ?? 0);
        switch (mode)
        {
            case "AutomaticEstimate": break;
            case "ManualEstimate": output["particle_allocation_estimate"] = count; break;
            case "FixedCount":
                output["fixed_particle_count"] = count;
                output["particle_allocation_estimate"] = count;
                break;
        }
    }

    private static JArray Resolved(JArray bindings, string emitter, SourceObject script)
        => (JArray)ResolvedRuntime(bindings, emitter, script)["ResolvedDataInterfaces"]!;

    private static JObject ResolvedSystemRuntime(JArray bindings, SourceObject script)
    {
        string usage = script.Properties.Value<string>("Usage") ?? throw new ConversionException($"Missing system script usage: {script.Name}");
        string usageId = script.Properties.Value<string>("UsageId") ?? Guid.Empty.ToString("B");
        if (!Guid.TryParse(usageId, out Guid parsedUsageId)) throw new ConversionException($"Invalid system script usage ID: {script.Name}");
        JObject[] matches = bindings.OfType<JArray>().Where(pair => pair.Count == 2 && pair[0] is JObject key
                && key.Value<string>("ScriptUsage") == usage
                && Guid.TryParse(key.Value<string>("EmitterHandleId"), out Guid emitterId) && emitterId == Guid.Empty
                && Guid.TryParse(key.Value<string>("ScriptUsageId"), out Guid candidateUsageId) && candidateUsageId == parsedUsageId)
            .Select(pair => (JObject)pair[1]).ToArray();
        if (matches.Length == 1) return matches[0];
        if (matches.Length == 0 && ((script.Properties["CachedScriptVM"]?["CalledVMExternalFunctions"] as JArray)?.Count ?? 0) == 0)
            return new JObject { ["ResolvedDataInterfaces"] = new JArray(), ["ResolvedUObjects"] = new JArray() };
        throw new ConversionException($"Expected one system script runtime binding: {script.Name}");
    }

    internal static JObject ResolvedRuntime(JArray bindings, string emitter, SourceObject script)
    {
        string usage = script.Properties.Value<string>("Usage")!;
        string usageId = script.Properties.Value<string>("UsageId") ?? Guid.Empty.ToString("B");
        if (!Guid.TryParse(usageId, out Guid sourceUsageId) || !Guid.TryParse(emitter, out Guid emitterId))
            throw new ConversionException($"Invalid Niagara script identity: {script.Name}");
        JObject[] matches = bindings.OfType<JArray>().Where(pair => pair.Count == 2 && pair[0] is JObject key
                && Guid.TryParse(key.Value<string>("EmitterHandleId"), out Guid entryEmitterId) && entryEmitterId == emitterId
                && key.Value<string>("ScriptUsage") == usage
                && Guid.TryParse(key.Value<string>("ScriptUsageId"), out Guid entryUsageId)
                && entryUsageId == sourceUsageId)
            .Select(pair => (JObject)pair[1]).ToArray();
        if (matches.Length == 1) return matches[0];
        JObject? vm = script.Properties["CachedScriptVM"] as JObject;
        if (matches.Length == 0
                && ((vm?["CalledVMExternalFunctions"] as JArray)?.Count ?? 0) == 0
                && ((vm?["DataInterfaceInfo"] as JArray)?.Count ?? 0) == 0)
            return new JObject { ["ResolvedDataInterfaces"] = new JArray(), ["ResolvedUObjects"] = new JArray() };
        throw new ConversionException($"Expected one emitter script runtime binding: {script.Name} ({usage})");
    }

    private JObject Program(SourceAsset asset, SourceObject script, JObject runtime, JArray runtimeBindings, AssetLibrary? library, SourceObject system)
    {
        JArray resolved = runtime["ResolvedDataInterfaces"] as JArray ?? new();
        JObject vm = (JObject)script.Properties["CachedScriptVM"]!;
        string usage = script.Properties.Value<string>("Usage")!;
        ScriptCompilationState.RequireCurrent(vm, asset.Name + ":" + script.Name);
        byte[] context = NumericParameterStoreSource.Bytes(vm["ExperimentalContextData"]);
        int codeLength = U32(context, 0), temp = U32(context, 10), constants = U16(context, 14);
        int inputs = U16(context, 16), datasets = U16(context, 20), outputs = U16(context, 22);
        int externalCount = U32(context, 26), outputInstructions = U16(context, 24);
        int constantMap = Align(64 + codeLength), inputMap = Align(constantMap + 2 * constants);
        int datasetMap = Align(inputMap + 2 * inputs), outputDatasets = Align(datasetMap + 16 * datasets);
        int outputTypes = Align(outputDatasets + outputs), outputTargets = Align(outputTypes + 2 * outputs);
        int externalTable = Align(outputTargets + 2 * outputs);
        if (context.Length < Align(externalTable + 16 * externalCount))
            throw new ConversionException($"Truncated optimized program: {asset.Name}:{script.Name}");

        JArray external = new();
        var functions = (JArray)vm["CalledVMExternalFunctions"]!;
        for (int i = 0; i < externalCount; i++)
        {
            JObject function = (JObject)functions[i];
            string owner = function.Value<string>("OwnerName")!;
            string calledFunctionName = function.Value<string>("Name")!;
            int inputsWithUserPointer = U32(context, externalTable + 16 * i + 8);
            int outs = U32(context, externalTable + 16 * i + 12);
            if (SourceNameOrder.Equal(owner, "FastPathLibrary"))
            {
                if (calledFunctionName != "FastMatrixToQuaternion" || inputsWithUserPointer != 16 || outs != 4)
                    throw new ConversionException($"Unsupported Niagara FastPath function {calledFunctionName} ({inputsWithUserPointer}, {outs})");
                external.Add(new JObject { ["inputs"] = inputsWithUserPointer, ["outputs"] = outs, ["name"] = calledFunctionName,
                    ["user_ptr_index"] = -1, ["interface_type"] = "fast_path", ["binding_info"] = function.DeepClone() });
                continue;
            }
            JObject binding = resolved.OfType<JObject>().SingleOrDefault(value => SourceNameOrder.Equal(value.Value<string>("CompileName"), owner))
                ?? throw new ConversionException($"External function {calledFunctionName} in {asset.Name}:{script.Name} "
                    + $"has no resolved data interface for compile owner {owner}. Available owners: "
                    + string.Join(", ", resolved.OfType<JObject>().Select(value => value.Value<string>("CompileName"))));
            SourceAsset objectAsset = ScriptDataInterfaceBindings.ObjectAsset(asset, binding, library);
            int interfaceIndex = binding.Value<int>("ResolvedDataInterface");
            int userPointer = ScriptDataInterfaceBindings.UserPointerIndex(vm, binding);
            int ins = inputsWithUserPointer - (userPointer < 0 ? 0 : 1);
            if (ins < 0) throw new ConversionException("External function has no input register for its DI user pointer");
            JObject result = new() { ["inputs"] = ins, ["outputs"] = outs, ["name"] = function.Value<string>("Name"),
                ["user_ptr_index"] = userPointer };
            result["interface_id"] = binding.Value<string>("InstanceId")
                ?? ScriptDataInterfaceBindings.Identity(objectAsset, interfaceIndex);
            JObject storeVariable = binding["ParameterStoreVariable"] as JObject
                ?? throw new ConversionException($"External function has no execution-store variable: {owner}");
            var storeType = ExecutionNumericParameterSource.Read(asset, storeVariable);
            result["interface_key"] = new JObject { ["name"] = storeType.Name, ["source_type"] = storeType.Type.Path,
                ["underlying"] = storeType.Type.UnderlyingType, ["flags"] = storeType.Type.Flags, ["storage"] = "interface" };
            result["binding_info"] = function.DeepClone();
            result["instance_key"] = ScriptDataInterfaceBindings.InstanceBindingKey(asset, vm, binding);
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == RenderTarget2DDataInterfaceCompiler.CubeClass
                    : storeType.Type.Path.EndsWith("." + RenderTarget2DDataInterfaceCompiler.CubeClass, StringComparison.Ordinal)) {
                RenderTarget2DDataInterfaceCompiler.ValidateCubeCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "render_target_cube";
                result["interface_data"] = RenderTarget2DDataInterfaceCompiler.CompileCube(objectAsset, interfaceIndex, library, asset, system);
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == TextureDataInterfaceCompiler.VolumeClass
                    : storeType.Type.Path.EndsWith("." + TextureDataInterfaceCompiler.VolumeClass, StringComparison.Ordinal)) {
                bool valid = function.Value<string>("Name") switch {
                    "TextureDimensions3D" => ins == 0 && outs == 3,
                    "SampleVolumeTexture" => ins == 4 && outs == 4,
                    _ => false
                };
                if (!valid) throw new ConversionException("Invalid Niagara volume texture CPU signature");
                result["interface_type"] = "texture_volume";
                result["interface_data"] = TextureDataInterfaceCompiler.CompileVolume(objectAsset, interfaceIndex, library, asset, system);
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == TextureDataInterfaceCompiler.CubeClass
                    : storeType.Type.Path.EndsWith("." + TextureDataInterfaceCompiler.CubeClass, StringComparison.Ordinal)) {
                bool valid = function.Value<string>("Name") switch {
                    "TextureDimensions" => ins == 0 && outs == 2,
                    "SampleCubeTexture" => ins == 4 && outs == 4,
                    _ => false
                };
                if (!valid) throw new ConversionException("Invalid Niagara cube texture CPU signature");
                result["interface_type"] = "texture_cube";
                result["interface_data"] = TextureDataInterfaceCompiler.CompileCube(objectAsset, interfaceIndex, library, asset, system);
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == RenderTarget2DDataInterfaceCompiler.VolumeClass
                    : storeType.Type.Path.EndsWith("." + RenderTarget2DDataInterfaceCompiler.VolumeClass, StringComparison.Ordinal)) {
                RenderTarget2DDataInterfaceCompiler.ValidateDimensionalCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "render_target_volume";
                result["interface_data"] = RenderTarget2DDataInterfaceCompiler.CompileVolume(objectAsset, interfaceIndex, library, asset, system);
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == RenderTarget2DDataInterfaceCompiler.ArrayClass
                    : storeType.Type.Path.EndsWith("." + RenderTarget2DDataInterfaceCompiler.ArrayClass, StringComparison.Ordinal)) {
                RenderTarget2DDataInterfaceCompiler.ValidateDimensionalCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "render_target_array";
                result["interface_data"] = RenderTarget2DDataInterfaceCompiler.CompileArray(objectAsset, interfaceIndex, library, asset, system);
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == TextureDataInterfaceCompiler.ArrayClass
                    : storeType.Type.Path.EndsWith("." + TextureDataInterfaceCompiler.ArrayClass, StringComparison.Ordinal)) {
                if (function.Value<string>("Name") != "TextureDimensions" || ins != 0 || outs != 3)
                    throw new ConversionException("Only TextureDimensions is a Niagara 2D array texture CPU function");
                result["interface_type"] = "texture_array";
                result["interface_data"] = TextureDataInterfaceCompiler.CompileArray(objectAsset, interfaceIndex, library, asset, system);
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == ActorComponentDataInterfaceCompiler.ClassName
                    : storeType.Type.Path.EndsWith("." + ActorComponentDataInterfaceCompiler.ClassName, StringComparison.Ordinal)) {
                ActorComponentDataInterfaceCompiler.Validate(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "actor_component";
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == ConsoleVariableDataInterfaceCompiler.ClassName
                    : storeType.Type.Path.EndsWith("." + ConsoleVariableDataInterfaceCompiler.ClassName, StringComparison.Ordinal)) {
                result["interface_type"] = "console_variable";
                result["interface_data"] = ConsoleVariableDataInterfaceCompiler.CpuFunction(function, ins, outs);
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == RibbonDataInterfaceCompiler.ClassName
                    : storeType.Type.Path.EndsWith("." + RibbonDataInterfaceCompiler.ClassName, StringComparison.Ordinal)) {
                RibbonDataInterfaceCompiler.Validate(asset, function, ins, outs);
                result["interface_type"] = "ribbon_shape";
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == StaticMeshDataInterfaceCompiler.ClassName
                    : storeType.Type.Path.EndsWith("." + StaticMeshDataInterfaceCompiler.ClassName, StringComparison.Ordinal)) {
                StaticMeshDataInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "static_mesh";
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == SkeletalMeshDataInterfaceCompiler.ClassName
                    : storeType.Type.Path.EndsWith("." + SkeletalMeshDataInterfaceCompiler.ClassName, StringComparison.Ordinal)) {
                SkeletalMeshDataInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "skeletal_mesh";
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == ParticleExportDataInterfaceCompiler.ClassName
                    : storeType.Type.Path.EndsWith("." + ParticleExportDataInterfaceCompiler.ClassName, StringComparison.Ordinal)) {
                ParticleExportDataInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "particle_export";
                external.Add(result);
                continue;
            }
            string? channelClass = interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) : storeType.Type.Path.Split('.').Last();
            if (channelClass == GaussianSplatInterfaceCompiler.ReaderClass) {
                GaussianSplatInterfaceCompiler.ValidateReaderCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "gaussian_splat_reader";
                result["interface_data"] = new JObject { ["gaussian_splat_reader"] =
                    GaussianSplatInterfaceCompiler.Reader(objectAsset, interfaceIndex, library) };
                external.Add(result);
                continue;
            }
            if (channelClass == GaussianSplatInterfaceCompiler.WriterClass) {
                GaussianSplatInterfaceCompiler.ValidateWriterCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "gaussian_splat_writer";
                result["interface_data"] = new JObject { ["gaussian_splat_writer"] =
                    GaussianSplatInterfaceCompiler.Writer(objectAsset, interfaceIndex, library) };
                external.Add(result);
                continue;
            }
            if (channelClass == PropertyInterfaceCompiler.ClassName) {
                var propertyData = PropertyInterfaceCompiler.Definition(objectAsset, interfaceIndex, library);
                propertyData["outputs"] = PropertyInterfaceCompiler.CpuOutputs(asset, function, ins, outs);
                result["interface_type"] = "property_interface";
                result["interface_data"] = propertyData;
                external.Add(result);
                continue;
            }
            if (channelClass == DebugDrawInterfaceCompiler.ClassName) {
                result["interface_type"] = "debug_draw";
                result["interface_data"] = DebugDrawInterfaceCompiler.CpuFunction(objectAsset, interfaceIndex, library,
                    function, ins, outs);
                external.Add(result);
                continue;
            }
            if (channelClass == VolumeCacheInterfaceCompiler.ClassName) {
                VolumeCacheInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "volume_cache";
                result["interface_data"] = new JObject { ["volume_cache"] = VolumeCacheInterfaceCompiler.Definition(objectAsset, interfaceIndex, library) };
                external.Add(result);
                continue;
            }
            if (channelClass == HairStrandsInterfaceCompiler.ClassName) {
                var hairData = HairStrandsInterfaceCompiler.Definition(objectAsset, interfaceIndex, library);
                hairData["native_writes"] = HairStrandsInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "hair_strands";
                result["interface_data"] = hairData;
                external.Add(result);
                continue;
            }
            if (channelClass == ChaosDestructionInterfaceCompiler.ClassName) {
                ChaosDestructionInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "chaos_destruction";
                result["interface_data"] = ChaosDestructionInterfaceCompiler.Definition(objectAsset, interfaceIndex, library);
                external.Add(result);
                continue;
            }
            if (channelClass == GeometryCollectionInterfaceCompiler.ClassName) {
                GeometryCollectionInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "geometry_collection";
                result["interface_data"] = GeometryCollectionInterfaceCompiler.Definition(objectAsset, interfaceIndex, library);
                external.Add(result);
                continue;
            }
            if (channelClass == RigidMeshCollisionInterfaceCompiler.ClassName) {
                RigidMeshCollisionInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "rigid_mesh_collision";
                result["interface_data"] = RigidMeshCollisionInterfaceCompiler.Definition(objectAsset, interfaceIndex, library);
                external.Add(result);
                continue;
            }
            if (channelClass == SceneCapture2DInterfaceCompiler.ClassName) {
                SceneCapture2DInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "scene_capture_2d";
                result["interface_data"] = SceneCapture2DInterfaceCompiler.Definition(objectAsset, interfaceIndex, library);
                external.Add(result);
                continue;
            }
            if (channelClass == LandscapeInterfaceCompiler.ClassName) {
                LandscapeInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "landscape";
                result["interface_data"] = LandscapeInterfaceCompiler.Definition(objectAsset, interfaceIndex, library);
                external.Add(result);
                continue;
            }
            if (channelClass == VirtualTextureInterfaceCompiler.StreamingClass) {
                VirtualTextureInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "virtual_texture_sample";
                result["interface_data"] = VirtualTextureInterfaceCompiler.Definition(objectAsset, interfaceIndex, library, channelClass);
                external.Add(result);
                continue;
            }
            if (channelClass == SimCacheReaderInterfaceCompiler.ClassName) {
                result["interface_type"] = "sim_cache_reader";
                result["interface_data"] = SimCacheReaderInterfaceCompiler.CpuFunction(function, ins, outs);
                external.Add(result);
                continue;
            }
            if (channelClass == MemoryBufferInterfaceCompiler.ClassName) {
                MemoryBufferInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "memory_buffer";
                external.Add(result);
                continue;
            }
            if (channelClass == NeighborGridInterfaceCompiler.ClassName) {
                NeighborGridInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "neighbor_grid";
                external.Add(result);
                continue;
            }
            if (channelClass == NeighborQueryInterfaceCompiler.ClassName) {
                NeighborQueryInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "neighbor_query";
                external.Add(result);
                continue;
            }
            if (channelClass == RasterizationGridInterfaceCompiler.ClassName) {
                RasterizationGridInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "rasterization_grid";
                external.Add(result);
                continue;
            }
            if (channelClass is Grid2DCollectionInterfaceCompiler.ClassName or Grid2DCollectionInterfaceCompiler.ReaderClass) {
                result["interface_data"] = Grid2DCollectionInterfaceCompiler.CpuFunction(function, ins, outs);
                result["interface_type"] = "grid2d_collection";
                external.Add(result);
                continue;
            }
            if (channelClass is Grid3DCollectionInterfaceCompiler.ClassName or Grid3DCollectionInterfaceCompiler.ReaderClass) {
                result["interface_data"] = Grid3DCollectionInterfaceCompiler.CpuFunction(function, ins, outs);
                result["interface_type"] = "grid3d_collection";
                external.Add(result);
                continue;
            }
            if (channelClass == SocketReaderInterfaceCompiler.ClassName) {
                SocketReaderInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "socket_reader";
                external.Add(result);
                continue;
            }
            if (channelClass == SplineInterfaceCompiler.ClassName) {
                SplineInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "spline";
                external.Add(result);
                continue;
            }
            if (channelClass == WaterInterfaceCompiler.ClassName) {
                WaterInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "water";
                external.Add(result);
                continue;
            }
            if (channelClass == IntDistributionInterfaceCompiler.ClassName) {
                IntDistributionInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "int_distribution";
                external.Add(result);
                continue;
            }
            if (channelClass == PhysicsFieldInterfaceCompiler.ClassName) {
                PhysicsFieldInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "physics_field";
                external.Add(result);
                continue;
            }
            if (channelClass is VelocityGridInterfaceCompiler.VelocityClass or VelocityGridInterfaceCompiler.PressureClass) {
                VelocityGridInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "velocity_grid";
                external.Add(result);
                continue;
            }
            if (channelClass is ViewQueryInterfaceCompiler.MousePosition or ViewQueryInterfaceCompiler.MovieRenderQueue) {
                result["interface_type"] = ViewQueryInterfaceCompiler.CpuFunction(channelClass, function.Value<string>("Name") ?? "", ins, outs);
                external.Add(result);
                continue;
            }
            if (channelClass == IntRenderTarget2DInterfaceCompiler.ClassName) {
                IntRenderTarget2DInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "int_render_target_2d";
                external.Add(result);
                continue;
            }
            if (channelClass == DataTableInterfaceCompiler.ClassName) {
                result["interface_data"] = DataTableInterfaceCompiler.CpuFunction(asset, function, ins, outs);
                result["interface_type"] = "data_table";
                external.Add(result);
                continue;
            }
            if (channelClass == MaterialCollectionInterfaceCompiler.ClassName) {
                result["interface_type"] = "material_collection_writer";
                result["interface_data"] = MaterialCollectionInterfaceCompiler.CpuFunction(function, ins, outs);
                external.Add(result);
                continue;
            }
            if (channelClass == MaterialInstanceDynamicInterfaceCompiler.ClassName) {
                result["interface_type"] = "material_instance_dynamic";
                result["interface_data"] = MaterialInstanceDynamicInterfaceCompiler.CpuFunction(function, ins, outs);
                external.Add(result);
                continue;
            }
            if (channelClass == DynamicMeshInterfaceCompiler.ClassName) {
                DynamicMeshInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "dynamic_mesh";
                external.Add(result);
                continue;
            }
            if (channelClass == SparseVolumeInterfaceCompiler.ClassName) {
                SparseVolumeInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "sparse_volume_texture";
                external.Add(result);
                continue;
            }
            if (channelClass is AudioAnalysisInterfaceCompiler.Spectrum or AudioAnalysisInterfaceCompiler.Oscilloscope) {
                AudioAnalysisInterfaceCompiler.ValidateCpu(channelClass, function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "audio_analysis";
                external.Add(result);
                continue;
            }
            if (channelClass is DataChannelInterfaceCompiler.Reader or DataChannelInterfaceCompiler.Writer) {
                result["interface_type"] = "data_channel";
                result["interface_data"] = DataChannelInterfaceCompiler.CpuFunction(asset, function, channelClass, ins, outs);
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == AudioPlayerInterfaceCompiler.ClassName
                    : storeType.Type.Path.EndsWith("." + AudioPlayerInterfaceCompiler.ClassName, StringComparison.Ordinal)) {
                AudioPlayerInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "audio_player";
                external.Add(result);
                continue;
            }
            const string collisionClass = "NiagaraDataInterfaceCollisionQuery";
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == collisionClass
                    : storeType.Type.Path.EndsWith("." + collisionClass, StringComparison.Ordinal)) {
                string name = function.Value<string>("Name") ?? "";
                bool valid = name switch {
                    "PerformCollisionQuerySyncCPU" => ins == 9 && outs == 11,
                    "PerformCollisionQueryAsyncCPU" => ins == 10 && outs == 12,
                    _ => false
                };
                if (!valid) throw new ConversionException($"Unknown Niagara CPU collision signature: {name} ({ins}, {outs})");
                result["interface_type"] = "collision_query";
                external.Add(result);
                continue;
            }
            if (interfaceIndex != 0 ? objectAsset.ReferencedClass(interfaceIndex) == ObjectPropertyReaderCompiler.ClassName
                    : storeType.Type.Path.EndsWith("." + ObjectPropertyReaderCompiler.ClassName, StringComparison.Ordinal)) {
                result["interface_type"] = "object_property_reader";
                result["interface_data"] = ObjectPropertyReaderCompiler.Function(function, ins, outs);
                external.Add(result);
                continue;
            }
            if (interfaceIndex <= 0) {
                JObject? sourceInfo = (vm["DataInterfaceInfo"] as JArray)?.OfType<JObject>()
                        .SingleOrDefault(info => info.Value<string>("Name") == owner);
                int type = sourceInfo?["Type"] is JObject definition ? NiagaraTypeSource.Read(asset, definition).SourceReference : 0;
                string? reference = interfaceIndex < 0 ? objectAsset.ObjectPath(interfaceIndex) : null;
                bool meshInfo = type != 0 && asset.Reference(type).Split('.').Last() == "NiagaraDataInterfaceMeshRendererInfo"
                        || reference?.EndsWith(".Default__NiagaraDataInterfaceMeshRendererInfo", StringComparison.Ordinal) == true;
                if (meshInfo) {
                    MeshRendererInfoCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                    result["interface_type"] = "mesh_renderer_info";
                    result["interface_data"] = MeshRendererInfoCompiler.CompileInterface(library, asset, null, reference);
                    external.Add(result);
                    continue;
                }
                bool vectorField = type != 0 && asset.Reference(type).Split('.').Last() == "NiagaraDataInterfaceVectorField"
                        || reference?.EndsWith(".Default__NiagaraDataInterfaceVectorField", StringComparison.Ordinal) == true;
                if (vectorField && interfaceIndex < 0) {
                    VectorFieldDataInterfaceCompiler.CompileCpu(result, asset, null, library, reference);
                    external.Add(result);
                    continue;
                }
                string sourceClass = type == 0 ? "" : asset.Reference(type).Split('.').Last();
                if (interfaceIndex < 0) {
                    string actualClass = objectAsset.ReferencedClass(interfaceIndex);
                    if (actualClass is "NiagaraDataInterfaceParticleRead" or "NiagaraDataInterfaceEmitterProperties") {
                        bool particleRead = actualClass == "NiagaraDataInterfaceParticleRead";
                        if (!particleRead) EmitterBindingCompiler.ValidatePropertiesFunction(function.Value<string>("Name") ?? "", ins, outs);
                        result["interface_type"] = particleRead ? "particle_read" : "emitter_properties";
                        result["interface_data"] = particleRead
                            ? ParticleReadDataInterfaceCompiler.Compile(objectAsset, interfaceIndex, binding["InstanceRuntimeOuter"] as JObject,
                                library, function, ins, outs)
                            : EmitterBindingCompiler.Compile(objectAsset, interfaceIndex, actualClass, binding["InstanceRuntimeOuter"] as JObject, library);
                        external.Add(result);
                        continue;
                    }
                    if (actualClass == "NiagaraDataInterfaceSpriteRendererInfo" || sourceClass == "NiagaraDataInterfaceSpriteRendererInfo") {
                        SpriteRendererInfoCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                        result["interface_type"] = "sprite_renderer_info";
                        result["interface_data"] = SpriteRendererInfoCompiler.CompileInterface(library, objectAsset, null,
                            objectAsset.ObjectPath(interfaceIndex));
                        external.Add(result);
                        continue;
                    }
                    if (actualClass == CameraDataInterfaceCompiler.ClassName || sourceClass == CameraDataInterfaceCompiler.ClassName) {
                        CameraDataInterfaceCompiler.Validate(function.Value<string>("Name") ?? "", ins, outs);
                        JObject camera = CameraDataInterfaceCompiler.Compile(objectAsset, interfaceIndex, library);
                        result["interface_type"] = "camera";
                        result["controller_index"] = camera["controller_index"]!.DeepClone();
                        result["require_current"] = camera["require_current"]!.DeepClone();
                        external.Add(result);
                        continue;
                    }
                    if (actualClass == CurlNoiseDataInterfaceCompiler.ClassName || sourceClass == CurlNoiseDataInterfaceCompiler.ClassName) {
                        CurlNoiseDataInterfaceCompiler.Validate(function.Value<string>("Name") ?? "", ins, outs);
                        result["interface_type"] = "curl_noise";
                        result["interface_data"] = CurlNoiseDataInterfaceCompiler.Compile(objectAsset, interfaceIndex, library,
                            binding.Value<string>("InstanceInitialization") ?? "loaded");
                        external.Add(result);
                        continue;
                    }
                    string mutableClass = ArrayDataInterfaceCompiler.Supports(actualClass) || actualClass == "NiagaraDataInterfaceSimpleCounter"
                        ? actualClass : sourceClass;
                    if (ArrayDataInterfaceCompiler.Supports(mutableClass) || mutableClass == "NiagaraDataInterfaceSimpleCounter") {
                        JObject properties = MutableDataInterfaceInstancesCompiler.ReadProperties(objectAsset, interfaceIndex, mutableClass, library);
                        bool array = ArrayDataInterfaceCompiler.Supports(mutableClass);
                        result["interface_type"] = array ? "array" : "simple_counter";
                        result["interface_data"] = array
                            ? ArrayDataInterfaceCompiler.Compile(mutableClass, properties, objectAsset.ObjectPath(interfaceIndex),
                                function.Value<string>("Name") ?? "", ins, outs)
                            : SimpleCounterDataInterfaceCompiler.Compile(properties, function.Value<string>("Name") ?? "", ins, outs);
                        if (array) result["interface_parameter"] = binding["ResolvedVariable"]?.Value<string>("name")
                            ?? throw new ConversionException($"Missing resolved Niagara array parameter: {owner}");
                        external.Add(result);
                        continue;
                    }
                }
                if (interfaceIndex < 0 && (sourceClass == PlatformSetDataInterfaceCompiler.ClassName
                        || reference == PlatformSetDataInterfaceCompiler.DefaultObject)) {
                    PlatformSetDataInterfaceCompiler.Validate(function.Value<string>("Name") ?? "", ins, outs);
                    result["interface_type"] = "platform_set";
                    result["interface_data"] = PlatformSetDataInterfaceCompiler.Compile(null, library, reference,
                        binding.Value<string>("InstanceInitialization") ?? "loaded");
                    external.Add(result);
                    continue;
                }
                if (interfaceIndex < 0 && CurveDataInterfaceCompiler.Supports(sourceClass)) {
                    CurveDataInterfaceCompiler.CompileCpu(result, asset, null, sourceClass, library, reference);
                    external.Add(result);
                    continue;
                }
                if (interfaceIndex < 0 && sourceClass is "NiagaraDataInterfaceRenderTarget2D" or "NiagaraDataInterfaceTexture") {
                    bool target = sourceClass == "NiagaraDataInterfaceRenderTarget2D";
                    string functionName = function.Value<string>("Name") ?? "";
                    if (target) RenderTarget2DDataInterfaceCompiler.ValidateCpu(functionName, ins, outs);
                    else TextureDataInterfaceCompiler.ValidateCpu(functionName, ins, outs);
                    result["interface_type"] = target ? "render_target_2d" : "texture_2d";
                    result["interface_data"] = target
                        ? RenderTarget2DDataInterfaceCompiler.Compile(asset, null, system, library, reference)
                        : TextureDataInterfaceCompiler.Compile(asset, null, system, library, reference);
                    external.Add(result);
                    continue;
                }
            }
            SourceObject data = objectAsset.Object(interfaceIndex);
            if (CurveDataInterfaceCompiler.Supports(data.ClassName))
            {
                CurveDataInterfaceCompiler.CompileCpu(result, objectAsset, data, data.ClassName, library);
            }
            else if (data.ClassName == "NiagaraDataInterfaceVectorField")
            {
                VectorFieldDataInterfaceCompiler.CompileCpu(result, objectAsset, data, library);
            }
            else if (data.ClassName == PlatformSetDataInterfaceCompiler.ClassName)
            {
                PlatformSetDataInterfaceCompiler.Validate(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "platform_set";
                result["interface_data"] = PlatformSetDataInterfaceCompiler.Compile(data, library,
                    initialization: binding.Value<string>("InstanceInitialization") ?? "loaded");
            }
            else if (data.ClassName == "NiagaraDataInterfaceCamera")
            {
                CameraDataInterfaceCompiler.Validate(function.Value<string>("Name") ?? "", ins, outs);
                JObject camera = CameraDataInterfaceCompiler.Compile(objectAsset, interfaceIndex, library);
                result["interface_type"] = "camera";
                result["controller_index"] = camera["controller_index"]!.DeepClone();
                result["require_current"] = camera["require_current"]!.DeepClone();
            }
            else if (data.ClassName == "NiagaraDataInterfaceSpriteRendererInfo")
            {
                SpriteRendererInfoCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "sprite_renderer_info";
                result["interface_data"] = SpriteRendererInfoCompiler.CompileInterface(library, objectAsset, data);
            }
            else if (data.ClassName == "NiagaraDataInterfaceMeshRendererInfo")
            {
                MeshRendererInfoCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "mesh_renderer_info";
                result["interface_data"] = MeshRendererInfoCompiler.CompileInterface(library, objectAsset, data);
            }
            else if (ArrayDataInterfaceCompiler.Supports(data.ClassName))
            {
                result["interface_type"] = "array";
                result["interface_id"] = asset.Name + "#" + binding.Value<int>("ResolvedDataInterface");
                result["interface_parameter"] = binding["ResolvedVariable"]?.Value<string>("name")
                    ?? throw new ConversionException($"Missing resolved Niagara array parameter: {owner}");
                result["interface_data"] = ArrayDataInterfaceCompiler.Compile(data, function.Value<string>("Name") ?? "", ins, outs);
            }
            else if (data.ClassName == "NiagaraDataInterfaceCurlNoise")
            {
                CurlNoiseDataInterfaceCompiler.Validate(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "curl_noise";
                result["interface_data"] = CurlNoiseDataInterfaceCompiler.Compile(objectAsset, interfaceIndex, library,
                    binding.Value<string>("InstanceInitialization") ?? "loaded");
            }
            else if (data.ClassName == "NiagaraDataInterfaceParticleRead")
            {
                result["interface_type"] = "particle_read";
                result["interface_data"] = ParticleReadDataInterfaceCompiler.Compile(objectAsset, interfaceIndex,
                    binding["InstanceRuntimeOuter"] as JObject, library, function, ins, outs);
            }
            else if (data.ClassName == "NiagaraDataInterfaceEmitterProperties")
            {
                EmitterBindingCompiler.ValidatePropertiesFunction(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "emitter_properties";
                result["interface_data"] = EmitterBindingCompiler.Compile(objectAsset, interfaceIndex, data.ClassName,
                    binding["InstanceRuntimeOuter"] as JObject, library);
            }
            else if (data.ClassName == "NiagaraDataInterfaceTexture")
            {
                TextureDataInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_type"] = "texture_2d";
                result["interface_id"] = asset.Name + "#" + binding.Value<int>("ResolvedDataInterface");
                result["interface_data"] = TextureDataInterfaceCompiler.Compile(asset, objectAsset == asset ? data : null, system, library,
                    objectAsset == asset ? null : objectAsset.ObjectPath(data.Index));
            }
            else if (data.ClassName == "NiagaraDataInterfaceRenderTarget2D")
            {
                result["interface_type"] = "render_target_2d";
                result["interface_id"] = asset.Name + "#" + binding.Value<int>("ResolvedDataInterface");
                RenderTarget2DDataInterfaceCompiler.ValidateCpu(function.Value<string>("Name") ?? "", ins, outs);
                result["interface_data"] = RenderTarget2DDataInterfaceCompiler.Compile(asset, objectAsset == asset ? data : null, system, library,
                    objectAsset == asset ? null : objectAsset.ObjectPath(data.Index));
            }
            else if (data.ClassName == "NiagaraDataInterfaceSimpleCounter")
            {
                result["interface_type"] = "simple_counter";
                result["interface_id"] = asset.Name + "#" + binding.Value<int>("ResolvedDataInterface");
                result["interface_data"] = SimpleCounterDataInterfaceCompiler.Compile(data, function.Value<string>("Name") ?? "", ins, outs);
            }
            else throw new ConversionException($"Unmapped VM data interface: {data.ClassName}");
            result["interface_id"] = binding.Value<string>("InstanceId")
                ?? ScriptDataInterfaceBindings.Identity(objectAsset, interfaceIndex);
            external.Add(result);
        }

        JArray mappedInputs = new();
        for (int dataset = 0; dataset < datasets; dataset++)
            for (int group = 0; group < 6; group++)
            {
                int first = U16(context, datasetMap + dataset * 16 + group * 2);
                int last = U16(context, datasetMap + dataset * 16 + (group + 1) * 2);
                for (int j = first; j < last; j++) mappedInputs.Add(new JObject {
                    ["dataset"] = dataset, ["type"] = group % 3, ["component"] = U16(context, inputMap + 2 * j),
                    ["constant"] = group >= 3 });
            }
        if (mappedInputs.Count != inputs) throw new ConversionException("Optimized input map length mismatch");
        JArray mappedOutputs = new();
        for (int i = 0; i < outputs; i++) mappedOutputs.Add(new JObject {
            ["dataset"] = context[outputDatasets + i], ["type"] = U16(context, outputTypes + 2 * i),
            ["component"] = U16(context, outputTargets + 2 * i) });

        bool cooked = runtime[ScriptRuntimeSource.ExecutionStore] is not null;
        JObject executionStore = ExecutionParameterStoreSource.Compile(asset, script, runtime, library, system);
        int currentBytes = executionStore.Value<int>("current_bytes");
        byte[] executionData = Convert.FromBase64String(executionStore.Value<string>("data")!);
        byte[] parameterBytes = executionData.AsSpan(0, currentBytes).ToArray();
        byte[] internalBytes = cooked
            ? NumericParameterStoreSource.Bytes(vm["ScriptLiterals"])
            : Bytes(Parameters(vm["InternalParameters"] ?? new JArray()));
        int runtimeBytes = usage.Contains("System", StringComparison.Ordinal) ? 32 : 640;
        bool interpolate = executionStore.Value<bool>("interpolated");
        int internalStart = (runtimeBytes + parameterBytes.Length) * (interpolate ? 2 : 1);
        byte[] constantBytes = new byte[internalStart + internalBytes.Length];
        parameterBytes.CopyTo(constantBytes, runtimeBytes);
        if (interpolate) executionData.AsSpan(currentBytes, currentBytes).CopyTo(constantBytes.AsSpan(2 * runtimeBytes + currentBytes));
        internalBytes.CopyTo(constantBytes, internalStart);
        JArray parameterBindings = new();
        NiagaraCollectionSource collections = new(asset, system, script.Properties, library);
        JObject[] slots = ((JArray)executionStore["variables"]!).OfType<JObject>().ToArray();
        IEnumerable<JObject> declaredParameters = cooked
            ? ExecutionParameterStoreSource.CurrentNumeric(executionStore)
            : Parameters(vm["Parameters"] ?? new JArray()).OfType<JObject>().Select(variable => {
                var execution = ExecutionNumericParameterSource.Read(asset, variable);
                var identity = new RendererFieldBindings.Variable(execution.Name, execution.Type.Path);
                return slots.First(slot => identity.SameStoreSlot(new RendererFieldBindings.Variable(
                    slot.Value<string>("name")!, slot.Value<string>("source_type")!)));
            });
        foreach (JObject declaration in declaredParameters)
        {
            int width = declaration.Value<int>("bytes");
            string name = declaration.Value<string>("name")!, type = declaration.Value<string>("source_type")!;
            JObject binding = new() { ["name"] = name, ["offset"] = declaration.Value<int>("offset"), ["bytes"] = width,
                ["source_type"] = type, ["underlying"] = declaration.Value<int>("underlying"),
                ["type_flags"] = declaration.Value<int>("flags"), ["lwc"] = declaration["lwc"]?.DeepClone() };
            if (width > 0) collections.Apply(binding, name, type, width);
            parameterBindings.Add(binding);
        }

        return new JObject { ["temporary_registers"] = temp, ["output_registers"] = outputInstructions,
            ["max_output_datasets"] = U32(context, 4), ["max_external_registers"] = U32(context, 30),
            ["dummy_registers"] = U32(context, 34), ["runtime_bytes"] = runtimeBytes,
            ["interpolated"] = interpolate, ["parameter_bytes"] = parameterBytes.Length, ["parameters"] = parameterBindings,
            ["constant_data"] = Convert.ToBase64String(constantBytes),
            ["execution_store"] = executionStore,
            ["user_pointers"] = ScriptDataInterfaceBindings.UserPointers(asset, vm, resolved),
            ["constant_slots"] = new JArray(Enumerable.Range(0, constants).Select(i => U16(context, constantMap + 2 * i))),
            ["inputs"] = mappedInputs, ["outputs"] = mappedOutputs, ["external"] = external,
            ["instructions"] = Instructions(context.AsSpan(64, codeLength).ToArray(), external) };
    }

    private JArray Instructions(byte[] code, JArray external)
    {
        int cursor = 0;
        int Read() { int result = U16(code, cursor); cursor += 2; return result; }
        JArray instructions = new();
        while (cursor < code.Length)
        {
            int index = code[cursor++];
            if (index >= opcodes.Length) throw new ConversionException($"Unknown optimized opcode {index}");
            Opcode op = opcodes[index];
            if (op.Name == "done") return instructions;
            JObject ins = new() { ["op"] = op.Name };
            JArray registers = new();
            if (op.Category == "Output")
            {
                int count = code[cursor++];
                ins["dataset"] = code[cursor++];
                for (int i = 0; i < 2 * count; i++) registers.Add(Read());
                ins["inputs"] = count;
            }
            else if (op.Category == "ExtFnCall")
            {
                int function = Read();
                ins["function"] = function;
                if (external[function].Value<int?>("user_ptr_index") is >= 0) ins["user_ptr_register"] = Read();
                int count = external[function].Value<int>("inputs") + external[function].Value<int>("outputs");
                for (int i = 0; i < count; i++) registers.Add(Read());
                ins["inputs"] = external[function].Value<int>("inputs");
            }
            else if (op.Category == "IndexGen")
            {
                registers.Add(Read());
                ins["dataset"] = code[cursor++];
                ins["inputs"] = 1;
            }
            else if (op.Category == "RWBuffer")
            {
                registers.Add(Read());
                registers.Add(Read());
                ins["dataset"] = code[cursor++];
                ins["inputs"] = op.Name == "update_id" ? 2 : 0;
            }
            else if (op.Category == "Op")
            {
                int count = op.Inputs + op.Outputs;
                // exec_index and exec_indexf encode only the destination.
                if (op.Name is "exec_index" or "exec_indexf") count = 1;
                for (int i = 0; i < count; i++) registers.Add(Read());
                ins["inputs"] = count - op.Outputs;
            }
            else throw new ConversionException($"Unsupported optimized instruction category {op.Category}: {op.Name}");
            ins["registers"] = registers;
            instructions.Add(ins);
        }
        throw new ConversionException("Optimized program has no terminator");
    }

    private static JArray SpawnInfo(SourceAsset asset, string emitter, JArray spawnAttributes, JArray updateAttributes)
    {
        JArray result = new();
        HashSet<string> names = new(StringComparer.Ordinal);
        foreach (JObject attribute in spawnAttributes.Cast<JObject>().Concat(updateAttributes.Cast<JObject>()))
        {
            string name = attribute.Value<string>("name")!;
            if (!name.StartsWith(emitter + ".", StringComparison.Ordinal)) continue;
            string type = NiagaraTypeSource.Read(asset, (JObject)attribute["type"]!).Path;
            if (type == "/Script/Niagara.NiagaraSpawnInfo" && names.Add(name)) result.Add(name);
        }
        return result;
    }

    private static JArray Layout(SourceAsset asset, JToken compiled)
    {
        JArray result = new();
        var variables = (JArray)compiled["Variables"]!;
        var layouts = (JArray)compiled["VariableLayouts"]!;
        for (int i = 0; i < variables.Count; i++)
        {
            JToken layout = layouts[i], info = layout["LayoutInfo"]!;
            JObject type = variables[i]["type"] as JObject
                ?? throw new ConversionException($"Missing Niagara variable type: {variables[i].Value<string>("name")}");
            string sourceType = NiagaraTypeSource.Read(asset, type).Path;
            int floats = info.Value<int>("NumFloatComponents"), integers = info.Value<int>("NumInt32Components"), halves = info.Value<int>("NumHalfComponents");
            uint[] offsets = (info["ComponentsOffsets"] as JArray ?? new()).Values<uint>().ToArray();
            if (offsets.Length != checked(2 * (floats + integers + halves)))
                throw new ConversionException("Niagara dataset component-offset metadata is incomplete");
            result.Add(new JObject { ["name"] = variables[i].Value<string>("name"), ["source_type"] = sourceType,
                ["float"] = layout.Value<int>("FloatComponentStart"), ["float_count"] = floats,
                ["int"] = layout.Value<int>("Int32ComponentStart"), ["int_count"] = integers,
                ["half"] = layout.Value<int>("HalfComponentStart"), ["half_count"] = halves,
                ["component_offsets"] = new JObject {
                    ["float"] = new JArray(offsets.Take(floats)),
                    ["int"] = new JArray(offsets.Skip(2 * floats).Take(integers)),
                    ["half"] = new JArray(offsets.Skip(2 * (floats + integers)).Take(halves)) } });
        }
        return result;
    }

    private static JArray Bindings(JObject compiled, string prefix)
    {
        JArray result = new();
        void Add(JObject collection, int runtimeOffset, int emitter)
        {
            foreach (var (field, type) in new[] { ("FloatOffsets", 0), ("Int32Offsets", 1) })
                foreach (JObject offset in collection[field] as JArray ?? new JArray())
                    result.Add(new JObject { ["emitter"] = emitter, ["runtime_offset"] = runtimeOffset + offset.Value<int>("ParameterOffset"),
                        ["type"] = type, ["component"] = offset.Value<int>("DataSetComponentOffset") });
        }
        foreach (var (field, offset) in new[] { ("GlobalBinding", 0), ("SystemBinding", 32), ("OwnerBinding", 96) })
            if (compiled[prefix + field] is JObject binding) Add(binding, offset, -1);
        if (compiled[prefix + "EmitterBindings"] is JArray emitters)
            for (int i = 0; i < emitters.Count; i++) Add((JObject)emitters[i], 608, i);
        return result;
    }

    private static JArray Parameters(JToken token) => token as JArray ?? (JArray)token["Parameters"]!;
    private static byte[] Bytes(JArray parameters) => parameters.Cast<JObject>()
        .SelectMany(value => Convert.FromBase64String(value.Value<string>("data")!)).ToArray();
    private static int Align(int value) => checked((value + 15) & ~15);
    private static int U16(byte[] bytes, int offset) => BinaryPrimitives.ReadUInt16LittleEndian(bytes.AsSpan(offset, 2));
    private static int U32(byte[] bytes, int offset) => checked((int)BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(offset, 4)));
}
