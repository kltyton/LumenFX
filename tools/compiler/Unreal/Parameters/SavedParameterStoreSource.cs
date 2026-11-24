using LumenFx.Compiler.Unreal.Execution;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Saved source stores retain their physical data array and category-specific object offsets. */
internal static class SavedParameterStoreSource
{
    internal static JObject Compile(SourceAsset asset, JObject store, AssetLibrary? library = null, SourceObject? system = null,
        SourceAsset? systemAsset = null, string? component = null,
        Func<JObject, bool, int, JObject>? resolveObject = null)
    {
        byte[] data = NumericParameterStoreSource.Bytes(store["ParameterData"]);
        JObject[] variables = (store["SortedParameterOffsets"] as JArray ?? new()).Cast<JObject>().ToArray();
        int[] numericOffsets = variables.Where(value => value["type"] is JObject type && NiagaraTypeSource.Read(asset, type).UnderlyingType is 2 or 3)
            .Select(value => value.Value<int>("offset")).ToArray();
        JArray declarations = new(), references = new();
        Dictionary<(bool Interface, int Offset), JToken> resolved = new();
        foreach (JObject variable in variables) {
            JObject type = variable["type"] as JObject ?? throw new ConversionException("Saved parameter has no source type");
            var loaded = NiagaraTypeSource.Read(asset, type);
            string name = variable.Value<string>("name") ?? throw new ConversionException("Saved parameter has no name");
            int offset = variable.Value<int?>("offset") ?? throw new ConversionException($"Saved parameter has no offset: {name}");
            string storage = loaded.UnderlyingType != 1 ? "numeric" : ScriptDataInterfaceBindings.IsInterface(asset, variable) ? "interface" : "object";
            if (loaded.UnderlyingType is not (1 or 2 or 3)) throw new ConversionException($"Saved parameter has an invalid type: {name}");
            int bytes = 0;
            if (storage == "numeric") {
                bytes = loaded.UnderlyingType == 3 ? sizeof(int) : NiagaraTypeSource.Size(asset, type)
                    ?? numericOffsets.Where(value => value > offset).DefaultIfEmpty(data.Length).Min() - offset;
                if (bytes < 0 || offset < 0 || offset > data.Length - bytes)
                    throw new ConversionException($"Saved parameter exceeds its numeric data: {name}");
            }
            JObject declaration = new() { ["name"] = name, ["source_type"] = loaded.Path,
                ["underlying"] = loaded.UnderlyingType, ["flags"] = loaded.Flags, ["lwc"] = NiagaraTypeSource.IsLwc(asset, type),
                ["storage"] = storage, ["offset"] = offset, ["bytes"] = bytes };
            if (storage != "numeric") declaration["object_index"] = offset;
            declarations.Add(declaration);
            if (storage == "numeric") {
                if (loaded.Path == "/Script/Niagara.NiagaraPosition") {
                    JObject? original = (store["OriginalPositionData"] as JArray ?? new()).Cast<JObject>()
                        .FirstOrDefault(value => SourceNameOrder.Equal(value.Value<string>("Name"), name));
                    if (original is not null) declaration["position"] = new JArray(new[] { "X", "Y", "Z" }
                        .Select(axis => SourceAsset.Number(original["Value"]?[axis])));
                }
                continue;
            }
            JArray objects = store[storage == "interface" ? "DataInterfaces" : "UObjects"] as JArray ?? new();
            if (offset < 0 || offset >= objects.Count) throw new ConversionException($"Saved object offset exceeds its source array: {name}");
            bool dataInterface = storage == "interface";
            int reference = objects[offset].Value<int>();
            JObject binding = resolveObject?.Invoke(variable, dataInterface, reference)
                ?? new JObject { ["value"] = Reference(reference, offset, dataInterface, dataInterface ? "loaded" : "reference") };
            binding["key"] = ExecutionParameterStoreSource.Key(declaration);
            references.Add(binding);
            resolved[(dataInterface, offset)] = binding["value"]!;
        }
        return new JObject { ["variables"] = declarations, ["data"] = Convert.ToBase64String(data),
            ["references"] = references, ["previous"] = new JArray(), ["current_bytes"] = data.Length, ["interpolated"] = false,
            ["interface_values"] = Objects("DataInterfaces", "loaded"), ["object_values"] = Objects("UObjects", "reference") };

        JArray Objects(string field, string initialization)
        {
            bool dataInterface = field == "DataInterfaces";
            return new((store[field] as JArray ?? new()).Values<int>()
                .Select((reference, slot) => resolved.TryGetValue((dataInterface, slot), out JToken? value)
                    ? value.DeepClone() : Reference(reference, slot, dataInterface, initialization)));
        }

        JToken Reference(int reference, int slot, bool dataInterface, string initialization)
        {
            bool copy = dataInterface && component is not null;
            JToken result = ExecutionParameterStoreSource.Reference(asset, reference,
                copy ? component + "@user_di:" + slot : null, copy ? "copy" : initialization, library,
                copy ? new JObject { ["kind"] = "component" } : null, system, systemAsset ?? asset);
            if (copy && result is JObject descriptor) descriptor["class_default"] = false;
            return result;
        }
    }

    internal static JObject RapidIteration(SourceAsset asset, SourceObject script, bool tick, AssetLibrary? library = null, SourceObject? system = null)
        => new() { ["id"] = asset.ObjectPath(script.Index) + ":RapidIterationParameters", ["tick"] = tick,
            ["definition"] = Compile(asset, script.Properties["RapidIterationParameters"] as JObject ?? new(), library, system) };
}
