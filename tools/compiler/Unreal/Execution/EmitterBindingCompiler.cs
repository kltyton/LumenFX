using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class EmitterBindingCompiler
{
    internal static string BindingMode(JObject binding) => binding.Value<string>("BindingMode")?.Split("::").Last() switch {
        "0" or "Self" => "Self",
        null or "1" or "Other" => "Other",
        var mode => throw new ConversionException($"Invalid emitter binding mode {mode}")
    };

    internal static JObject SourceBinding(SourceAsset asset, int reference, string type, AssetLibrary? library)
    {
        var source = DataInterfaceInstanceSources.ResolveSource(asset, reference, type, library);
        SourceObject? data = source.Object;
        JObject binding = data?.Properties["EmitterBinding"] as JObject ?? new();
        string mode = BindingMode(binding);
        string owner = "None";
        string? ownerPath = null;
        SourceObject? ownerEmitter = null, ownerSystem = null;
        int outer = data?.Outer ?? 0;
        while (outer > 0) {
            SourceObject parent = source.Asset.Object(outer);
            if (parent.ClassName == "NiagaraEmitter" && ownerEmitter is null) {
                owner = parent.Properties.Value<string>("UniqueEmitterName") ?? "None";
                ownerPath = source.Asset.ObjectPath(parent.Index);
                ownerEmitter = parent;
            }
            if (parent.ClassName == "NiagaraSystem") { ownerSystem = parent; break; }
            outer = parent.Outer;
        }
        return new JObject { ["mode"] = mode, ["emitter_name"] = binding.Value<string>("EmitterName") ?? "None", ["interface_class"] = type,
            ["source_owner_emitter"] = owner, ["source_package"] = source.Asset.Name,
            ["source_owner_emitter_path"] = ownerPath,
            ["particle_read_emitter_path"] = type == "NiagaraDataInterfaceParticleRead" && data is not null && ownerSystem is not null
                ? ParticleReadEmitterPath(source.Asset, data, ownerEmitter, ownerSystem, mode, binding.Value<string>("EmitterName") ?? "None", library)
                : null,
            ["source_path"] = data is null ? asset.ObjectPath(reference) : source.Asset.ObjectPath(data.Index),
            ["source_index"] = data?.Index ?? reference };
    }

    /** ParticleRead uses ResolveHandle(this), including the object's owning system and particle-script namespace. */
    private static string? ParticleReadEmitterPath(SourceAsset asset, SourceObject data, SourceObject? ownerEmitter,
        SourceObject system, string mode, string emitterName, AssetLibrary? library)
    {
        JObject[] handles = (system.Properties["EmitterHandles"] as JArray ?? new()).OfType<JObject>().ToArray();
        if (mode == "Other") {
            if (SourceNameOrder.Equal(emitterName, "None")) return null;
            SourceObject? named = handles.Select(Emitter).FirstOrDefault(emitter => emitter is not null
                && SourceNameOrder.Equal(emitter.Properties.Value<string>("UniqueEmitterName"), emitterName));
            return named is null ? null : asset.ObjectPath(named.Index);
        }
        if (ownerEmitter is not null) return handles.Select(Emitter).Any(emitter => emitter?.Index == ownerEmitter.Index)
            ? asset.ObjectPath(ownerEmitter.Index) : null;
        JArray runtimes = ScriptRuntimeSource.Read(asset, system);
        foreach (JObject handle in handles) {
            if (handle.Value<bool?>("bIsEnabled") == false || !EmitterHandleSource.IsStandard(handle) || Emitter(handle) is not { } emitter) continue;
            JObject? version = EmitterVersionSource.Find(emitter, handle["VersionedInstance"]?.Value<string>("Version"));
            if (version is null) continue;
            if (!Guid.TryParse(handle.Value<string>("Id"), out Guid id)) throw new ConversionException("Emitter binding has no valid handle ID");
            List<int> scripts = new() { version["SpawnScriptProps"]!.Value<int>("Script"), version["UpdateScriptProps"]!.Value<int>("Script") };
            int gpu = version.Value<int?>("GPUComputeScript") ?? 0;
            if (gpu != 0) scripts.Add(gpu);
            foreach (JObject handler in version["EventHandlerScriptProps"] as JArray ?? new()) scripts.Add(handler.Value<int>("Script"));
            bool found = false, emitterNamespace = false;
            foreach (int reference in scripts) {
                JObject? match = Resolved(Runtime(reference, id)).FirstOrDefault(SameObject);
                if (match is null) continue;
                found = true;
                string name = match.Value<string>("CompileName") ?? "";
                emitterNamespace = name.Length > 8 && name[7] == '.' && SourceNameOrder.Equal(name[..7], "Emitter");
            }
            if (found) return emitterNamespace ? asset.ObjectPath(emitter.Index) : null;
        }
        foreach (string field in new[] { "SystemSpawnScript", "SystemUpdateScript" })
            foreach (JObject match in Resolved(Runtime(system.Properties.Value<int>(field), Guid.Empty)).Where(SameObject)) {
                string name = match.Value<string>("ResolvedSourceEmitterName") ?? "";
                if (name.Length == 0) continue;
                SourceObject? emitter = handles.Select(Emitter).FirstOrDefault(value => value?.Properties.Value<string>("UniqueEmitterName") == name);
                return emitter is null ? null : asset.ObjectPath(emitter.Index);
            }
        return null;

        SourceObject? Emitter(JObject handle) {
            int reference = handle["VersionedInstance"]?.Value<int?>("Emitter") ?? 0;
            return reference != 0 ? asset.Object(reference) : null;
        }
        JObject? Runtime(int reference, Guid owner) {
            if (reference == 0) return null;
            SourceObject script = asset.Object(reference);
            return runtimes.OfType<JArray>().Where(pair => pair.Count == 2 && pair[0] is JObject key
                    && Guid.TryParse(key.Value<string>("EmitterHandleId"), out Guid id) && id == owner
                    && key.Value<string>("ScriptUsage") == script.Properties.Value<string>("Usage")
                    && Guid.TryParse(key.Value<string>("ScriptUsageId"), out Guid usage)
                    && Guid.TryParse(script.Properties.Value<string>("UsageId") ?? Guid.Empty.ToString(), out Guid scriptUsage) && usage == scriptUsage)
                .Select(pair => (JObject)pair[1]).SingleOrDefault();
        }
        bool SameObject(JObject resolved) {
            int reference = resolved.Value<int>("ResolvedDataInterface");
            if (reference == 0) return false;
            SourceAsset owner = ScriptDataInterfaceBindings.ObjectAsset(asset, resolved, library);
            var value = DataInterfaceInstanceSources.ResolveSource(owner, reference, owner.ReferencedClass(reference), library);
            string path = value.Object is null ? owner.ObjectPath(reference) : value.Asset.ObjectPath(value.Object.Index);
            return path == asset.ObjectPath(data.Index);
        }
        static IEnumerable<JObject> Resolved(JObject? runtime) => (runtime?["ResolvedDataInterfaces"] as JArray ?? new()).OfType<JObject>();
    }

    internal static JObject Compile(SourceAsset asset, int reference, string type, JObject? runtimeOuter, AssetLibrary? library)
    {
        return new JObject { ["emitter_binding_source"] = SourceBinding(asset, reference, type, library),
            ["runtime_outer"] = runtimeOuter?.DeepClone() };
    }

    /** Finalizes every object reference and function against the unmodified default DI pointers. */
    internal static void Complete(SourceAsset asset, SourceObject system, JArray defaultRuntimes, JObject result, AssetLibrary? library)
    {
        Dictionary<string, string> emitters = new(StringComparer.Ordinal);
        Dictionary<string, string> handles = new(StringComparer.Ordinal);
        foreach (JObject handle in system.Properties["EmitterHandles"] as JArray ?? new()) {
            int reference = handle["VersionedInstance"]?.Value<int?>("Emitter") ?? 0;
            if (reference == 0) continue;
            SourceObject emitter = asset.Object(reference);
            string path = asset.ObjectPath(reference), name = emitter.Properties.Value<string>("UniqueEmitterName") ?? "None";
            handles.TryAdd(path, name);
            if (EmitterHandleSource.IsStandard(handle)) emitters.TryAdd(path, name);
        }
        Dictionary<string, string> defaults = new(StringComparer.Ordinal);
        foreach (string usage in new[] { "SystemUpdateScript", "SystemSpawnScript" })
            foreach (JArray entry in defaultRuntimes.OfType<JArray>())
            {
                if (entry.Count != 2 || entry[0] is not JObject key || entry[1] is not JObject runtime) continue;
                if (!string.Equals(key.Value<string>("ScriptUsage")?.Split("::").Last(), usage, StringComparison.Ordinal)) continue;
                if (!Guid.TryParse(key.Value<string>("EmitterHandleId"), out Guid emitterId) || emitterId != Guid.Empty) continue;
                foreach (JObject resolved in (runtime["ResolvedDataInterfaces"] as JArray ?? new()).OfType<JObject>()) {
                    int reference = resolved.Value<int>("ResolvedDataInterface");
                    if (reference == 0) continue;
                    SourceAsset owner = ScriptDataInterfaceBindings.ObjectAsset(asset, resolved, library);
                    string type = owner.ReferencedClass(reference);
                    if (type != "NiagaraDataInterfaceEmitterProperties") continue;
                    JObject source = SourceBinding(owner, reference, type, library);
                    defaults.TryAdd(source.Value<string>("source_path")!, resolved.Value<string>("ResolvedSourceEmitterName") ?? "");
                }
            }
        foreach (JObject value in result.Descendants().OfType<JObject>().Prepend(result).ToArray()) {
            if (value["emitter_binding_source"] is not JObject source) continue;
            string mode = source.Value<string>("mode")!;
            string target;
            if (source.Value<string>("interface_class") == "NiagaraDataInterfaceParticleRead") {
                string? readerPath = source.Value<string>("particle_read_emitter_path");
                var targets = value.ContainsKey("BindingMode") ? handles : emitters;
                target = value["runtime_outer"] is JObject || readerPath is null
                    ? "None" : targets.GetValueOrDefault(readerPath) ?? "None";
            } else if (mode == "Other") {
                string name = source.Value<string>("emitter_name")!;
                target = SourceNameOrder.Equal(name, "None") ? "None"
                    : emitters.Values.FirstOrDefault(candidate => SourceNameOrder.Equal(name, candidate)) ?? "None";
            } else if (value["runtime_outer"] is JObject outer) {
                string kind = outer.Value<string>("kind") ?? throw new ConversionException("Runtime DI outer has no kind");
                if (kind is not ("component" or "collection_instance"))
                    throw new ConversionException($"Emitter binding requires its actual runtime outer: {kind}");
                target = "None";
            } else if (source.Value<string>("source_owner_emitter_path") is string path) {
                target = emitters.GetValueOrDefault(path) ?? "None";
            } else {
                string name = defaults.GetValueOrDefault(source.Value<string>("source_path")!) ?? "";
                target = name.Length != 0 && emitters.Values.Contains(name, StringComparer.Ordinal) ? name : "None";
            }
            value["mode"] = mode;
            value["emitter"] = target;
            if (value.ContainsKey("BindingMode")) value["EmitterName"] = target;
        }
    }

    internal static void ValidatePropertiesFunction(string name, int inputs, int outputs)
    {
        bool valid = name switch {
            "GetLocalSpace" => inputs == 0 && outputs == 1,
            "GetBounds" or "GetFixedBounds" => inputs == 0 && outputs == 7,
            "SetFixedBounds" => inputs == 8 && outputs == 0,
            _ => false };
        if (!valid) throw new ConversionException($"Invalid emitter properties signature {name}: {inputs} inputs, {outputs} outputs");
    }
}
