using LumenFx.Compiler.Unreal.Execution;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry;

internal static class NaniteRendererSource
{
    internal const string ClassName = "NiagaraNaniteRendererProperties";

    internal static JObject Settings(SourceAsset asset, JObject renderer, string emitterNamespace)
    {
        JArray bindings = new();
        foreach (JObject source in InstanceBindings(renderer)) {
            var variable = Variable(asset, source, emitterNamespace);
            var key = variable is null ? null : ParameterKey(variable, "numeric");
            int component = source.Value<int?>("Component") ?? 0;
            if (component < 0 || variable is { Bytes: int bytes } && (long)component * 4 + 4 > bytes)
                throw new ConversionException($"Invalid Nanite instance float component {component}: {variable?.Name}");
            bindings.Add(new JObject { ["parameter"] = key, ["component"] = component,
                ["default"] = SourceAsset.Number(source["DefaultValue"]) });
        }
        JObject meshBindings = new();
        var meshes = renderer["Meshes"] as JArray ?? new();
        for (int index = 0; index < meshes.Count; index++)
            if (meshes[index]["MeshParameterBinding"] is JObject binding
                    && Variable(asset, binding, emitterNamespace) is { UnderlyingType: 1 } variable)
                meshBindings[index.ToString(System.Globalization.CultureInfo.InvariantCulture)] = ParameterKey(variable, "object");
        return new JObject { ["instance_floats"] = bindings, ["mesh_parameters"] = meshBindings };
    }

    private static JObject ParameterKey(RendererFieldBindings.Variable variable, string storage) => new() {
        ["name"] = variable.Name, ["source_type"] = variable.Type,
        ["underlying"] = variable.UnderlyingType, ["flags"] = variable.Flags, ["storage"] = storage
    };

    internal static IEnumerable<JObject> InstanceBindings(JObject renderer)
        => (renderer["PerInstanceDataBindings"] as JArray ?? new()).OfType<JObject>();

    internal static RendererFieldBindings.Variable? Variable(SourceAsset asset, JObject binding, string emitterNamespace)
        => binding["ResolvedParameter"] is JObject source
            ? RendererFieldBindings.Variable.From(asset, source, emitterNamespace) : null;
}
