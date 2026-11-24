using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Matches saved GPU functions by the original object pointer or the first component user variable. */
internal sealed class DataInterfaceGpuFunctions
{
    private sealed record Slot(string Path, JObject? Variable, string[] Functions);
    private readonly SourceAsset asset;
    private readonly IReadOnlyList<ScriptDataInterfaceBindings.Parameter> overrides;
    private readonly List<Slot> slots = new();

    internal DataInterfaceGpuFunctions(SourceAsset asset, SourceObject system, JArray runtimes, AssetLibrary? library)
    {
        this.asset = asset;
        overrides = ScriptDataInterfaceBindings.InstanceStore(asset, system);
        foreach (JArray pair in runtimes.OfType<JArray>()) {
            if (pair.Count != 2 || pair[0] is not JObject key || pair[1] is not JObject runtime)
                throw new ConversionException("Invalid GPU function runtime map entry");
            SourceObject? script = ScriptDataInterfaceBindings.Script(asset, system, key);
            if (script is null) continue;
            JArray resolved = runtime["ResolvedDataInterfaces"] as JArray ?? new();
            JArray parameters = script.Properties["CachedScriptVM"]?["ShaderScriptParametersMetadata"]?["DataInterfaceParamInfo"] as JArray ?? new();
            // Native ForEachGpuFunction pairs these arrays by index, independent of HLSL symbols.
            int count = Math.Min(resolved.Count, parameters.Count);
            for (int index = 0; index < count; index++) {
                JObject binding = resolved[index] as JObject
                    ?? throw new ConversionException("Invalid resolved GPU function interface");
                int reference = binding.Value<int>("ResolvedDataInterface");
                if (reference == 0) continue;
                SourceAsset owner = ScriptDataInterfaceBindings.ObjectAsset(asset, binding, library);
                JArray functions = parameters[index]["GeneratedFunctions"] as JArray ?? new();
                slots.Add(new(owner.ObjectPath(reference), binding["ResolvedVariable"] as JObject,
                    functions.OfType<JObject>().Select(function => function.Value<string>("DefinitionName")
                        ?? throw new ConversionException("GPU function has no definition name")).ToArray()));
            }
        }
    }

    internal bool ArrayWritable(ScriptDataInterfaceBindings.Parameter parameter)
    {
        string? identity = parameter.InstanceId;
        JObject? user = overrides.FirstOrDefault(value => identity is not null
            && SourceNameOrder.Equal(value.InstanceId, identity))?.Variable;
        SourceAsset owner = parameter.ObjectAsset ?? asset;
        string? actualType = owner.ReferencedClassPath(parameter.Reference);
        if (user is not null && !(actualType is null
                ? SourceNameOrder.Equal(ScriptDataInterfaceBindings.TypeName(asset, user), owner.ReferencedClass(parameter.Reference))
                : SourceNameOrder.Equal(NiagaraTypeSource.Read(asset, (JObject)user["type"]!).Path, actualType))) user = null;
        string path = owner.ObjectPath(parameter.Reference);
        foreach (Slot slot in slots) {
            bool sameObject = parameter.Initialization == "loaded" && SourceNameOrder.Equal(slot.Path, path);
            bool sameUser = user is not null && slot.Variable is not null
                && ScriptDataInterfaceBindings.Same(asset, user, slot.Variable);
            if ((sameObject || sameUser) && slot.Functions.Any(ArrayDataInterfaceCompiler.IsGpuWriteFunction)) return true;
        }
        return false;
    }

    internal JArray Export() => new(slots.Select(slot => new JObject {
        ["source_path"] = slot.Path, ["variable"] = Key(slot.Variable), ["functions"] = new JArray(slot.Functions)
    }));

    private JToken Key(JObject? variable)
    {
        if (variable?["type"] is not JObject source) return JValue.CreateNull();
        var type = NiagaraTypeSource.Read(asset, source);
        if (type.UnderlyingType != 1) return JValue.CreateNull();
        return new JObject { ["name"] = variable.Value<string>("name") ?? "None", ["source_type"] = type.Path,
            ["underlying"] = type.UnderlyingType, ["flags"] = type.Flags, ["storage"] = "interface" };
    }
}
