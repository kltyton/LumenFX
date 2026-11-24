using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution;

/** Resolves source user-DI defaults before VM functions, GPU bindings and renderer exposure select an object. */
internal static class ScriptDataInterfaceBindings
{
    internal sealed class Parameter(JObject variable, int reference, string? instanceId = null, SourceAsset? objectAsset = null,
        string initialization = "loaded", JObject? runtimeOuter = null, int offset = -1)
    {
        internal JObject Variable { get; } = variable;
        internal int Reference { get; set; } = reference;
        internal string? InstanceId { get; set; } = instanceId;
        internal SourceAsset? ObjectAsset { get; set; } = objectAsset;
        internal string Initialization { get; set; } = initialization;
        internal JObject? RuntimeOuter { get; set; } = runtimeOuter;
        internal int Offset { get; } = offset;
    }

    internal static JArray Resolve(SourceAsset asset, SourceObject system, JArray source, AssetLibrary? library = null)
    {
        JArray result = (JArray)source.DeepClone();
        foreach (JArray pair in result) {
            if (pair.Count != 2 || pair[0] is not JObject key || pair[1] is not JObject runtime)
                throw new ConversionException("Invalid script runtime map entry");
            SourceObject? script = Script(asset, system, key);
            if (script is null) continue;
            bool systemScript = Guid.TryParse(key.Value<string>("EmitterHandleId"), out Guid owner) && owner == Guid.Empty;
            var store = Store(asset, system, script, runtime, systemScript, library);
            JArray resolved = runtime["ResolvedDataInterfaces"] as JArray ?? new JArray();
            foreach (JObject binding in resolved) {
                JObject variable = (JObject)binding["ParameterStoreVariable"]!;
                Parameter parameter = store.Single(value => Same(asset, value.Variable, variable));
                binding["ResolvedDataInterface"] = parameter.Reference;
                binding["InstanceId"] = parameter.InstanceId ?? Identity(parameter.ObjectAsset ?? asset, parameter.Reference);
                binding["ResolvedDataInterfacePackage"] = (parameter.ObjectAsset ?? asset).Name;
                binding["InstanceInitialization"] = parameter.Initialization;
                binding["InstanceRuntimeOuter"] = parameter.RuntimeOuter?.DeepClone();
            }

        }
        return result;
    }

    internal static IReadOnlyList<Parameter> InstanceStore(SourceAsset asset, SourceObject system)
    {
        JObject exposed = system.Properties["ExposedParameters"] as JObject ?? new JObject();
        JArray objects = exposed["DataInterfaces"] as JArray ?? new JArray();
        List<Parameter> store = new();
        foreach (JObject variable in exposed["SortedParameterOffsets"] as JArray ?? new JArray()) {
            if (!IsInterface(asset, variable)) continue;
            int offset = variable.Value<int?>("offset") ?? -1;
            if (offset < 0 || offset >= objects.Count) throw new ConversionException("User interface offset is outside its store");
            store.Add(new Parameter(variable, objects[offset].Value<int>(), asset.Name + "@user_di:" + offset,
                initialization: "copy", runtimeOuter: new JObject { ["kind"] = "component" }, offset: offset));
        }
        return store.OrderBy(value => value.Variable.Value<string>("name")
                    ?? throw new ConversionException("Instance interface has no name"), SourceNameOrder.Instance)
                .ThenBy(value => TypeName(asset, value.Variable), SourceNameOrder.Instance).ToArray();
    }

    internal static IReadOnlyList<Parameter> Store(SourceAsset asset, SourceObject system, SourceObject script, JObject runtime, bool searchInstance, AssetLibrary? library = null)
    {
        JObject properties = script.Properties;
        List<Parameter> store = new();
        if (runtime[ScriptRuntimeSource.ExecutionStore] is JObject cooked) {
            JArray saved = cooked["DataInterfaces"] as JArray ?? new();
            foreach (JObject variable in cooked["SortedParameterOffsets"] as JArray ?? new()) {
                if (!IsInterface(asset, variable)) continue;
                int offset = variable.Value<int?>("offset") ?? -1;
                if (offset < 0 || offset >= saved.Count) throw new ConversionException("Cooked DI offset is outside its parameter store");
                store.Add(new Parameter(variable, saved[offset].Value<int>(), offset: offset));
            }
            JArray rapid = script.Properties["RapidIterationParameters"]?["DataInterfaces"] as JArray ?? new();
            foreach (JObject binding in runtime[ScriptRuntimeSource.RapidBindings] as JArray ?? new()) {
                if (binding["Parameter"] is not JObject variable || !IsInterface(asset, variable)) continue;
                int from = binding.Value<int>("SrcOffset"), to = binding.Value<int>("DestOffset");
                Parameter target = store.Single(value => value.Offset == to && Same(asset, value.Variable, variable));
                if (from < 0 || from >= rapid.Count) throw new ConversionException("Cooked quick iteration DI offset is outside its parameter store");
                target.Reference = rapid[from].Value<int>();
            }
        } else {
            foreach (JObject variable in ParameterVariables(script)) if (IsInterface(asset, variable)) Add(variable, 0);
            foreach (JObject binding in runtime["ResolvedDataInterfaces"] as JArray ?? new())
                Add(binding["ParameterStoreVariable"] as JObject ?? throw new ConversionException("Resolved interface has no store variable"),
                    binding.Value<int>("ResolvedDataInterface"), binding.Value<string>("InstanceId"),
                    binding.Value<string>("InstanceInitialization") ?? "loaded",
                    ObjectAsset(asset, binding, library), binding["InstanceRuntimeOuter"] as JObject);
        }
        if (searchInstance) {
            var instance = InstanceStore(asset, system);
            foreach (Parameter parameter in store) {
                Parameter? replacement = instance.LastOrDefault(value => Same(asset, value.Variable, parameter.Variable));
                if (replacement is not null && replacement.Reference != 0) {
                    parameter.Reference = replacement.Reference; parameter.InstanceId = replacement.InstanceId;
                    parameter.Initialization = replacement.Initialization;
                    parameter.ObjectAsset = replacement.ObjectAsset;
                    parameter.RuntimeOuter = replacement.RuntimeOuter;
                }
            }
        } else {
            var objects = InstanceStore(asset, system);
            foreach (JObject binding in runtime["ResolvedUserDataInterfaceBindings"] as JArray ?? new JArray()) {
                int user = binding.Value<int?>("UserParameterStoreDataInterfaceIndex") ?? -1;
                int target = binding.Value<int?>("ScriptParameterStoreDataInterfaceIndex") ?? -1;
                Parameter? replacement = AtOffset(objects, user), destination = AtOffset(store, target);
                if (replacement is not null && destination is not null) {
                    destination.Reference = replacement.Reference; destination.InstanceId = replacement.InstanceId;
                    destination.Initialization = replacement.Initialization;
                    destination.ObjectAsset = replacement.ObjectAsset;
                    destination.RuntimeOuter = replacement.RuntimeOuter;
                }
            }
        }
        NiagaraCollectionSource collections = new(asset, system, properties, library);
        foreach (Parameter parameter in store) {
            if (collections.Object(parameter.Variable, true) is not { } source) continue;
            parameter.Reference = source.Reference; parameter.InstanceId = source.InstanceId; parameter.ObjectAsset = source.Asset;
            parameter.Initialization = source.Initialization;
            parameter.RuntimeOuter = source.RuntimeOuter;
        }
        return store;

        void Add(JObject variable, int reference, string? instanceId = null, string initialization = "loaded",
            SourceAsset? objectAsset = null, JObject? runtimeOuter = null)
        {
            Parameter? parameter = store.SingleOrDefault(value => Same(asset, value.Variable, variable));
            if (parameter is null) store.Add(new Parameter(variable, reference, instanceId, objectAsset, initialization, runtimeOuter, store.Count));
            else {
                parameter.Reference = reference; parameter.InstanceId = instanceId; parameter.Initialization = initialization;
                parameter.ObjectAsset = objectAsset; parameter.RuntimeOuter = runtimeOuter;
            }
        }
    }

    internal static string Identity(SourceAsset asset, int reference)
        => asset.ObjectPath(reference);

    internal static int UserPointerIndex(JObject vm, JObject binding)
    {
        int index = binding.Value<int?>("UserPtrIdx") ?? (vm["DataInterfaceInfo"] as JArray ?? new()).OfType<JObject>()
            .SingleOrDefault(value => SourceNameOrder.Equal(value.Value<string>("Name"), binding.Value<string>("CompileName")))
            ?.Value<int?>("UserPtrIdx") ?? -1;
        if (index < -1) throw new ConversionException("Invalid script data-interface user pointer index");
        return index;
    }

    internal static JObject UserPointers(SourceAsset asset, JObject vm, JArray resolved)
    {
        int count = vm.Value<int?>("NumUserPtrs") ?? 0;
        if (count < 0) throw new ConversionException("Invalid script user pointer count");
        JArray bindings = new();
        foreach (JObject binding in resolved) {
            int index = UserPointerIndex(vm, binding);
            if (index < 0) continue;
            if (index >= count) throw new ConversionException("Script DI user pointer index is outside its table");
            JObject variable = binding["ParameterStoreVariable"] as JObject
                ?? throw new ConversionException("Script DI user pointer has no parameter-store variable");
            var value = ExecutionNumericParameterSource.Read(asset, variable);
            bindings.Add(new JObject { ["index"] = index, ["key"] = new JObject {
                ["name"] = value.Name, ["source_type"] = value.Type.Path, ["underlying"] = value.Type.UnderlyingType,
                ["flags"] = value.Type.Flags, ["storage"] = "interface" },
                ["instance_key"] = InstanceBindingKey(asset, vm, binding) });
        }
        return new JObject { ["count"] = count, ["bindings"] = bindings };
    }

    internal static JObject? InstanceBindingKey(SourceAsset asset, JObject vm, JObject binding)
    {
        string name = binding.Value<string>("Name") ?? "None";
        bool user = name.Length >= 5 && SourceNameOrder.Equal(name[..5], "User.");
        if (!user && UserPointerIndex(vm, binding) < 0) return null;
        JObject variable = binding["ResolvedVariable"] as JObject
            ?? throw new ConversionException("Per-instance function binding has no resolved variable");
        var value = ExecutionNumericParameterSource.Read(asset, variable);
        return new JObject { ["name"] = value.Name, ["source_type"] = value.Type.Path,
            ["underlying"] = value.Type.UnderlyingType, ["flags"] = value.Type.Flags, ["storage"] = "interface" };
    }

    internal static JArray UserBindings(SourceAsset asset, SourceObject system, SourceObject script, JObject runtime, AssetLibrary? library)
    {
        var source = InstanceStore(asset, system);
        var target = Store(asset, system, script, runtime, false, library);
        JArray result = new();
        foreach (JObject binding in runtime["ResolvedUserDataInterfaceBindings"] as JArray ?? new()) {
            int user = binding.Value<int?>("UserParameterStoreDataInterfaceIndex") ?? -1;
            int destination = binding.Value<int?>("ScriptParameterStoreDataInterfaceIndex") ?? -1;
            Parameter? from = AtOffset(source, user), to = AtOffset(target, destination);
            if (from is not null && to is not null)
                result.Add(new JObject { ["source"] = Key(from.Variable), ["target"] = Key(to.Variable) });
        }
        return result;

        JObject Key(JObject variable) {
            var value = ExecutionNumericParameterSource.Read(asset, variable);
            return new JObject { ["name"] = value.Name, ["source_type"] = value.Type.Path,
                ["underlying"] = value.Type.UnderlyingType, ["flags"] = value.Type.Flags, ["storage"] = "interface" };
        }
    }

    private static Parameter? AtOffset(IReadOnlyList<Parameter> store, int offset)
        => offset < 0 ? null : store.FirstOrDefault(value => value.Offset == offset);

    internal static IEnumerable<JObject> ParameterVariables(SourceObject script)
    {
        JObject properties = script.Properties;
        JToken? container = properties["CachedScriptVM"]?["Parameters"];
        JArray parameters = container as JArray ?? container?["Parameters"] as JArray ?? new();
        foreach (JObject variable in parameters) yield return variable;
        string interpolation = (properties["CachedScriptVMId"]?.Value<string>("InterpolatedSpawnMode") ?? "NoInterpolation").Split("::").Last();
        if (interpolation is "NoInterpolation" or "0") yield break;
        foreach (JObject variable in parameters) {
            JObject previous = (JObject)variable.DeepClone();
            previous["name"] = "PREV_" + (variable.Value<string>("name")
                ?? throw new ConversionException("Interpolated parameter has no name"));
            yield return previous;
        }
    }

    internal static bool Same(SourceAsset asset, JObject a, JObject b)
        => NiagaraVariableSource.Same(asset, a, asset, b);

    internal static SourceAsset ObjectAsset(SourceAsset asset, JObject binding, AssetLibrary? library)
    {
        string package = binding.Value<string>("ResolvedDataInterfacePackage") ?? asset.Name;
        return package == asset.Name ? asset : library?.Load(package)
            ?? throw new ConversionException($"External data interface requires its source library: {package}");
    }

    internal static bool IsInterface(SourceAsset asset, JObject variable)
    {
        if (variable["type"] is not JObject type) return false;
        var loaded = NiagaraTypeSource.Read(asset, type);
        return loaded.UnderlyingType == 1 && loaded.Path.Contains(".NiagaraDataInterface", StringComparison.Ordinal);
    }

    internal static string TypeName(SourceAsset asset, JObject variable)
    {
        string name = NiagaraTypeSource.Read(asset, (JObject)variable["type"]!).Path;
        return name[(name.LastIndexOf('.') + 1)..];
    }

    internal static SourceObject? Script(SourceAsset asset, SourceObject system, JObject key, bool includeDisabled = false)
    {
        if (!Guid.TryParse(key.Value<string>("EmitterHandleId"), out Guid emitter)
                || !Guid.TryParse(key.Value<string>("ScriptUsageId"), out Guid usageId))
            throw new ConversionException("Script runtime key has no valid identity");
        string usage = key.Value<string>("ScriptUsage") ?? throw new ConversionException("Script runtime key has no usage");
        if (emitter == Guid.Empty) {
            foreach (string field in new[] { "SystemSpawnScript", "SystemUpdateScript" }) {
                SourceObject script = asset.Object(system.Properties.Value<int>(field));
                if (Matches(script)) return script;
            }
            return null;
        }
        JObject? handle = (system.Properties["EmitterHandles"] as JArray ?? new JArray()).OfType<JObject>()
            .SingleOrDefault(value => Guid.TryParse(value.Value<string>("Id"), out Guid id) && id == emitter);
        if (handle is null || (!includeDisabled && handle.Value<bool?>("bIsEnabled") == false)
                || !EmitterHandleSource.IsStandard(handle)) return null;
        int emitterReference = handle["VersionedInstance"]?.Value<int?>("Emitter") ?? 0;
        if (emitterReference == 0) return null;
        SourceObject owner = asset.Object(emitterReference);
        JObject? version = EmitterVersionSource.Find(owner, handle["VersionedInstance"]?.Value<string>("Version"));
        if (version is null) return null;
        List<int> references = new() { version["SpawnScriptProps"]!.Value<int>("Script"), version["UpdateScriptProps"]!.Value<int>("Script") };
        int gpu = version.Value<int?>("GPUComputeScript") ?? 0;
        if (gpu > 0) references.Add(gpu);
        foreach (JObject handler in version["EventHandlerScriptProps"] as JArray ?? new JArray()) {
            int reference = handler.Value<int?>("Script") ?? 0;
            if (reference > 0) references.Add(reference);
        }
        SourceObject[] matches = references.Distinct().Select(asset.Object).Where(Matches).ToArray();
        if (matches.Length > 1) throw new ConversionException("Script runtime key selects multiple active scripts");
        return matches.SingleOrDefault();

        bool Matches(SourceObject script) => script.Properties.Value<string>("Usage") == usage
            && Guid.TryParse(script.Properties.Value<string>("UsageId") ?? Guid.Empty.ToString(), out Guid id) && id == usageId;
    }
}
