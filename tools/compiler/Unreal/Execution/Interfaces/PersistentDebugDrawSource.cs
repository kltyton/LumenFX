using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** A persistent draw binds its compiler tag once and reads the supplying script's live values after simulation. */
internal static class PersistentDebugDrawSource
{
    private sealed record Shape(string Interface, string Function, string Prefix, JObject Settings);
    private sealed record TagScript(SourceObject Script, bool Local);

    internal static JArray Compile(SourceAsset asset, SourceObject system, JArray runtimes, AssetLibrary? library)
    {
        List<Shape> shapes = new();
        var bound = new Dictionary<string, HashSet<(string Prefix, string Function)>>(SourceNameOrder.Instance);
        foreach (JArray pair in runtimes.OfType<JArray>()) {
            if (pair.Count != 2 || pair[0] is not JObject key || pair[1] is not JObject runtime) continue;
            SourceObject? script = ScriptDataInterfaceBindings.Script(asset, system, key);
            if (script?.Properties["CachedScriptVM"] is not JObject vm) continue;
            var gpuInterfaces = vm["ShaderScriptParametersMetadata"]?["DataInterfaceParamInfo"] as JArray ?? new();
            foreach (JObject binding in (runtime["ResolvedDataInterfaces"] as JArray ?? new()).OfType<JObject>()) {
                if (binding["ParameterStoreVariable"] is not JObject variable
                    || !SourceNameOrder.Equal(ScriptDataInterfaceBindings.TypeName(asset, variable), DebugDrawInterfaceCompiler.ClassName)) continue;
                int reference = binding.Value<int>("ResolvedDataInterface");
                SourceAsset owner = ScriptDataInterfaceBindings.ObjectAsset(asset, binding, library);
                if (reference != 0 && !SourceNameOrder.Equal(owner.ReferencedClass(reference), DebugDrawInterfaceCompiler.ClassName)) continue;
                string id = binding.Value<string>("InstanceId") ?? ScriptDataInterfaceBindings.Identity(owner, reference);
                string compile = binding.Value<string>("CompileName") ?? "";
                JObject settings = DebugDrawInterfaceCompiler.Definition(owner, reference, library);
                foreach (JObject function in (vm["CalledVMExternalFunctions"] as JArray ?? new()).OfType<JObject>())
                    if (SourceNameOrder.Equal(function.Value<string>("OwnerName"), compile))
                        Add(id, function.Value<string>("Name"), function["FunctionSpecifiers"], settings);
                foreach (JObject parameters in gpuInterfaces.OfType<JObject>())
                    if (parameters.Value<string>("DIClassName") == DebugDrawInterfaceCompiler.ClassName
                        && parameters.Value<string>("DataInterfaceHLSLSymbol") == GpuProgramCompiler.ShaderSymbol(compile))
                        foreach (JObject function in (parameters["GeneratedFunctions"] as JArray ?? new()).OfType<JObject>())
                            Add(id, function.Value<string>("DefinitionName"), function["Specifiers"], settings);
            }
        }
        if (shapes.Count == 0) return new JArray();

        TagScript[] scripts = CandidateScripts(asset, system).ToArray();
        JArray result = new();
        foreach (Shape shape in shapes) foreach (TagScript source in scripts) {
            JArray tags = Tags(asset, source.Script, shape.Prefix);
            if (!tags.HasValues) continue;
            result.Add(new JObject { ["interface"] = shape.Interface, ["function"] = shape.Function,
                ["line_limit"] = shape.Settings.Value<int>("line_limit"), ["local"] = source.Local, ["tags"] = tags });
        }
        return result;

        void Add(string id, string? function, JToken? specifiers, JObject settings) {
            if (function is null || !function.EndsWith("Persistent", StringComparison.Ordinal)) return;
            string? prefix = Prefix(specifiers);
            if (prefix is null) return;
            if (!bound.TryGetValue(id, out var names)) bound.Add(id, names = new());
            string kind = function[..^"Persistent".Length];
            if (names.Add((SourceNameOrder.Canonical(prefix), kind))) shapes.Add(new(id, kind, prefix, settings));
        }
    }

    internal static string? Prefix(JToken? specifiers) {
        var prefix = (specifiers as JArray)?.OfType<JObject>().FirstOrDefault(entry =>
            SourceNameOrder.Equal(entry.Value<string>("Key"), "CompilerTagKey"))?.Value<string>("Value");
        return string.IsNullOrEmpty(prefix) || SourceNameOrder.Equal(prefix, "None") ? null : prefix;
    }

    private static IEnumerable<TagScript> CandidateScripts(SourceAsset asset, SourceObject system)
    {
        yield return new(asset.Object(system.Properties.Value<int>("SystemSpawnScript")), false);
        yield return new(asset.Object(system.Properties.Value<int>("SystemUpdateScript")), false);
        foreach (JObject handle in (system.Properties["EmitterHandles"] as JArray ?? new()).OfType<JObject>()) {
            if (!EmitterHandleSource.IsStandard(handle)) continue;
            int index = handle["VersionedInstance"]?.Value<int?>("Emitter") ?? 0;
            if (index <= 0) continue;
            JObject? version = EmitterVersionSource.Find(asset.Object(index), handle["VersionedInstance"]?.Value<string>("Version"));
            if (version is null) continue;
            bool local = version.Value<bool>("bLocalSpace");
            foreach (string field in new[] { "SpawnScriptProps", "UpdateScriptProps" }) {
                int script = version[field]?.Value<int?>("Script") ?? 0;
                if (script > 0) yield return new(asset.Object(script), local);
            }
            if (EmitterHandleSource.SimulationTarget(version) != "CPUSim") continue;
            foreach (JObject handler in (version["EventHandlerScriptProps"] as JArray ?? new()).OfType<JObject>()) {
                int script = handler.Value<int?>("Script") ?? 0;
                if (script > 0) yield return new(asset.Object(script), local);
            }
        }
    }

    private static JArray Tags(SourceAsset asset, SourceObject script, string prefix)
    {
        JArray tags = new();
        JObject properties = script.Properties;
        JObject iteration = properties["RapidIterationParameters"] as JObject ?? new();
        string root = prefix + ".";
        foreach (JObject tag in (properties["CachedScriptVM"]?["CompileTags"] as JArray ?? new()).OfType<JObject>()) {
            if (tag["Variable"] is not JObject variable || variable.Value<string>("name") is not string name
                || name.Length <= root.Length || SourceNameOrder.CompareText(name[..root.Length], root) != 0) continue;
            string member = name[root.Length..];
            JObject type = variable["type"] as JObject ?? throw new ConversionException($"Compiler tag has no type: {name}");
            var definition = NiagaraTypeSource.Read(asset, type);
            int bytes = definition.UnderlyingType == 3 ? sizeof(int) : NiagaraTypeSource.Size(asset, type)
                ?? throw new ConversionException($"Compiler tag has no numeric layout: {name}");
            byte[] data = NumericParameterStoreSource.Bytes(variable["data"]);
            string binding = tag.Value<string>("StringValue") ?? "";
            if (data.Length == 0 && binding.Length != 0)
                data = NumericParameterStoreSource.ReadBytes(asset, iteration, binding, definition.Path, bytes)?.Data ?? Array.Empty<byte>();
            if (data.Length != 0 && data.Length != bytes)
                throw new ConversionException($"Compiler tag has invalid stored width: {name}");
            tags.Add(new JObject { ["name"] = member, ["data"] = Convert.ToBase64String(data), ["binding"] = binding,
                ["type"] = definition.Path, ["underlying"] = definition.UnderlyingType, ["flags"] = definition.Flags,
                ["bytes"] = bytes });
        }
        return tags;
    }
}
