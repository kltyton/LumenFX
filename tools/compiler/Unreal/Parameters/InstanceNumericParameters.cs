using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Components;

namespace LumenFx.Compiler.Unreal.Parameters;

/** InitSystemCompiledData copies exposed numeric values and retained absolute positions into the instance store. */
internal static class InstanceNumericParameters
{
    internal static JObject CompileStores(SourceAsset asset, SourceObject system, AssetLibrary? library = null,
        NiagaraComponentSource? sourceComponent = null)
    {
        JObject exposed = system.Properties["ExposedParameters"] as JObject ?? new();
        JObject original = SavedParameterStoreSource.Compile(asset, exposed, library, system);
        JObject compiled = (JObject)original.DeepClone();
        byte[] source = Convert.FromBase64String(original.Value<string>("data")!);
        List<byte> data = new();
        JArray interfaceValues = new(), objectValues = new();
        JArray declarations = new();
        foreach (JObject variable in ((JArray)compiled["variables"]!).Cast<JObject>()
                .OrderBy(value => value.Value<string>("name"), SourceNameOrder.Instance)
                .ThenBy(value => new RendererFieldBindings.Variable("", value.Value<string>("source_type")!).StoreTypeName, SourceNameOrder.Instance)) {
            if (variable.Value<string>("storage") == "numeric") {
                int offset = variable.Value<int>("offset"), bytes = variable.Value<int>("bytes");
                variable["offset"] = data.Count;
                data.AddRange(source.AsSpan(offset, bytes).ToArray());
                variable.Remove("position");
            } else {
                string field = variable.Value<string>("storage") == "interface" ? "interface_values" : "object_values";
                JArray values = field == "interface_values" ? interfaceValues : objectValues;
                JArray originalValues = (JArray)original[field]!;
                int index = variable.Value<int>("object_index");
                variable["object_index"] = values.Count;
                variable["offset"] = values.Count;
                values.Add(originalValues[index].DeepClone());
            }
            declarations.Add(variable);
        }
        compiled["variables"] = declarations;
        compiled["data"] = Convert.ToBase64String(data.ToArray());
        compiled["current_bytes"] = data.Count;
        compiled["interface_values"] = interfaceValues;
        compiled["object_values"] = objectValues;
        JObject component = (JObject)original.DeepClone();
        JArray sourceInterfaces = exposed["DataInterfaces"] as JArray ?? new();
        JArray componentInterfaces = new();
        for (int slot = 0; slot < sourceInterfaces.Count; slot++) {
            int sourceReference = sourceInterfaces[slot].Value<int>();
            JToken copy = ExecutionParameterStoreSource.Reference(asset, sourceReference, asset.Name + "@user_di:" + slot,
                "copy", library, new JObject { ["kind"] = "component" }, system, asset);
            if (copy is JObject descriptor) descriptor["class_default"] = false;
            componentInterfaces.Add(copy);
        }
        component["interface_values"] = componentInterfaces;
        foreach (JObject reference in (JArray)component["references"]!) {
            if (reference["key"]!.Value<string>("storage") != "interface") continue;
            JObject variable = ((JArray)original["variables"]!).Cast<JObject>().Single(variable =>
                SourceNameOrder.Equal(variable.Value<string>("name"), reference["key"]!.Value<string>("name"))
                && SourceNameOrder.Equal(variable.Value<string>("source_type"), reference["key"]!.Value<string>("source_type")));
            int slot = variable.Value<int>("offset");
            reference["value"] = componentInterfaces[slot].DeepClone();
        }
        if (sourceComponent?.SavedOverrides(asset, system, library ?? throw new ConversionException("NiagaraComponent requires a source library"),
                ((JArray)original["variables"]!).Cast<JObject>().ToArray()) is { } savedOverrides)
            ApplySavedOverrides(component, savedOverrides);
        return new JObject { ["instance"] = compiled, ["override"] = component };
    }

    private static void ApplySavedOverrides(JObject component, JObject saved)
    {
        var savedSlots = ((JArray)saved["variables"]!).Cast<JObject>().ToDictionary(Identity);
        var references = ((JArray)component["references"]!).Cast<JObject>().ToDictionary(reference => Identity((JObject)reference["key"]!));
        byte[] data = Convert.FromBase64String(component.Value<string>("data")!);
        byte[] savedData = Convert.FromBase64String(saved.Value<string>("data")!);
        foreach (JObject destination in ((JArray)component["variables"]!).Cast<JObject>()) {
            var identity = Identity(destination);
            if (!savedSlots.TryGetValue(identity, out var source)) continue;
            int from = source.Value<int>("offset"), to = destination.Value<int>("offset");
            if (identity.Storage == "numeric") {
                int width = destination.Value<int>("bytes");
                if (source.Value<int>("bytes") != width)
                    throw new ConversionException($"NiagaraComponent parameter width differs from the exposed type: {identity.Name}");
                savedData.AsSpan(from, width).CopyTo(data.AsSpan(to, width));
                destination.Remove("position");
                if (source["position"] is { } position) destination["position"] = position.DeepClone();
            } else {
                string field = identity.Storage == "interface" ? "interface_values" : "object_values";
                JToken value = saved[field]![from]!;
                component[field]![to] = value.DeepClone();
                references[identity]["value"] = value.DeepClone();
            }
        }
        component["data"] = Convert.ToBase64String(data);
    }

    private static (string Name, string SourceType, int Underlying, int Flags, string Storage) Identity(JObject key)
        => (SourceNameOrder.Canonical(key.Value<string>("name")!), SourceNameOrder.Canonical(key.Value<string>("source_type")!),
            key.Value<int>("underlying"), key.Value<int>("flags"), key.Value<string>("storage")!);

    internal static JObject Compile(SourceAsset asset, SourceObject system)
    {
        JObject store = system.Properties["ExposedParameters"] as JObject ?? new();
        byte[] source = NumericParameterStoreSource.Bytes(store["ParameterData"]);
        var variables = (store["SortedParameterOffsets"] as JArray ?? new()).OfType<JObject>()
            .Where(value => value["type"] is JObject type && NiagaraTypeSource.Read(asset, type).UnderlyingType is 2 or 3).ToArray();
        List<byte> data = new();
        JArray result = new();
        foreach (JObject variable in variables) {
            JObject type = (JObject)variable["type"]!;
            var definition = NiagaraTypeSource.Read(asset, type);
            int offset = variable.Value<int?>("offset") ?? throw new ConversionException("Exposed numeric parameter has no offset");
            int bytes = definition.UnderlyingType == 3 ? sizeof(int) : NiagaraTypeSource.Size(asset, type)
                ?? variables.Select(value => value.Value<int>("offset")).Where(value => value > offset).DefaultIfEmpty(source.Length).Min() - offset;
            if (bytes < 0 || offset < 0 || offset > source.Length - bytes)
                throw new ConversionException("Exposed numeric parameter exceeds its serialized store");
            string name = variable.Value<string>("name") ?? throw new ConversionException("Exposed numeric parameter has no name");
            JObject? original = (store["OriginalPositionData"] as JArray ?? new()).OfType<JObject>()
                .FirstOrDefault(value => SourceNameOrder.Equal(value.Value<string>("Name"), name));
            JArray? position = definition.Path == "/Script/Niagara.NiagaraPosition" && original is not null
                ? new JArray(new[] { "X", "Y", "Z" }.Select(axis => SourceAsset.Number(original["Value"]?[axis]))) : null;
            result.Add(new JObject { ["name"] = name, ["source_type"] = definition.Path, ["underlying"] = definition.UnderlyingType,
                ["flags"] = definition.Flags, ["lwc"] = NiagaraTypeSource.IsLwc(asset, type),
                ["offset"] = data.Count, ["bytes"] = bytes, ["position"] = position });
            data.AddRange(source.AsSpan(offset, bytes).ToArray());
        }
        return new JObject { ["data"] = Convert.ToBase64String(data.ToArray()), ["variables"] = result };
    }
}
