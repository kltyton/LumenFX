using System.Buffers.Binary;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Components;

/** Applies the editor's template and instance variants to the exposed parameter identities. */
internal sealed class NiagaraComponentParameterOverrides(SourceAsset systemAsset, SourceObject system,
    AssetLibrary library, string component, JObject[] declarations)
{
    private sealed record Value(byte[]? Bytes, JToken? Reference, JToken? Position);
    private readonly Dictionary<int, Value> values = new();
    private readonly JObject[] exposed = (system.Properties["ExposedParameters"]?["SortedParameterOffsets"] as JArray ?? new())
        .Cast<JObject>().ToArray();

    internal void AddVariants(SourceAsset owner, JArray map)
    {
        var entries = new Dictionary<int, (JObject Variant, bool Qualified)>();
        foreach (JToken entry in map) {
            if (entry is not JArray { Count: 2 } pair || pair[0] is not JObject source || pair[1] is not JObject variant)
                throw new ConversionException($"Invalid NiagaraComponent variant map entry: {component}");
            string name = source.Value<string>("name") ?? source.Value<string>("Name")
                ?? throw new ConversionException($"NiagaraComponent variant has no parameter name: {component}");
            bool qualified = IsUser(name);
            JObject key = new() {
                ["name"] = qualified ? name : "User." + name,
                ["type"] = (source["type"] ?? source["TypeDef_DEPRECATED"] ?? source["TypeDef"])?.DeepClone()
            };
            int index = Array.FindIndex(exposed, variable => NiagaraVariableSource.Same(owner, key, systemAsset, variable));
            if (index < 0 || entries.TryGetValue(index, out var previous) && previous.Qualified && !qualified) continue;
            entries[index] = (variant, qualified);
        }
        foreach (var (index, entry) in entries) {
            JObject slot = declarations[index];
            string mode = entry.Variant["CurrentMode"]?.Type == JTokenType.Integer
                ? (entry.Variant.Value<int>("CurrentMode") switch { 0 => "None", 1 => "Object", 2 => "DataInterface", 3 => "Bytes", _ => "" })
                : (entry.Variant.Value<string>("CurrentMode") ?? "None").Split("::").Last();
            var value = ReadVariant(owner, slot, entry.Variant, mode, index);
            if (value is null) values.Remove(index);
            else values[index] = value;
        }
    }

    internal void AddLegacy(SourceAsset owner, JObject source, JArray map)
    {
        var names = new HashSet<string>(SourceNameOrder.Instance);
        foreach (JToken entry in map) {
            if (entry is not JArray { Count: 2 } pair || pair[0].Type != JTokenType.String)
                throw new ConversionException($"Invalid legacy NiagaraComponent override entry: {component}");
            string name = pair[0].Value<string>()!;
            names.Add(IsUser(name) ? name : "User." + name);
        }
        if (names.Count == 0) return;
        JObject saved = SavedParameterStoreSource.Compile(owner, source, library, system, systemAsset, component);
        byte[] data = Convert.FromBase64String(saved.Value<string>("data")!);
        foreach (JObject slot in (JArray)saved["variables"]!) {
            string name = slot.Value<string>("name")!;
            if (!IsUser(name)) name = "User." + name;
            if (!names.Contains(name)) continue;
            int index = Array.FindIndex(declarations, value => SourceNameOrder.Equal(value.Value<string>("name"), name)
                && SourceNameOrder.Equal(value.Value<string>("source_type"), slot.Value<string>("source_type"))
                && value.Value<int>("underlying") == slot.Value<int>("underlying")
                && value.Value<int>("flags") == slot.Value<int>("flags"));
            if (index < 0) continue;
            int offset = slot.Value<int>("offset");
            string storage = slot.Value<string>("storage")!;
            if (storage == "numeric")
                values[index] = new Value(data.AsSpan(offset, slot.Value<int>("bytes")).ToArray(), null, slot["position"]);
            else {
                JToken reference = saved[storage == "interface" ? "interface_values" : "object_values"]![offset]!;
                if (storage == "interface" && reference.Type == JTokenType.Null) values.Remove(index);
                else values[index] = new Value(null, reference, null);
            }
        }
    }

    private Value? ReadVariant(SourceAsset owner, JObject slot, JObject variant, string mode, int index)
    {
        string storage = slot.Value<string>("storage")!;
        string sourceType = slot.Value<string>("source_type")!;
        string name = slot.Value<string>("name")!;
        if (mode == "None") return null;
        if (storage == "numeric" && mode == "Bytes") {
            byte[] bytes = NumericParameterStoreSource.Bytes(variant["Bytes"]);
            if (sourceType == "/Script/Niagara.NiagaraPosition" && bytes.Length is 12 or 24) {
                bool wide = bytes.Length == 24;
                double[] position = new double[3];
                byte[] numeric = new byte[12];
                for (int axis = 0; axis < 3; axis++) {
                    position[axis] = wide ? BinaryPrimitives.ReadDoubleLittleEndian(bytes.AsSpan(axis * 8))
                        : BinaryPrimitives.ReadSingleLittleEndian(bytes.AsSpan(axis * 4));
                    BinaryPrimitives.WriteSingleLittleEndian(numeric.AsSpan(axis * 4), (float)position[axis]);
                }
                return new Value(numeric, null, new JArray(position));
            }
            if (bytes.Length != slot.Value<int>("bytes"))
                throw new ConversionException($"NiagaraComponent variant size differs from the exposed type: {component}:{name}");
            return new Value(bytes, null, null);
        }
        if (storage == "object" && mode == "Object") {
            int reference = variant.Value<int?>("Object") ?? 0;
            return new Value(null, ExecutionParameterStoreSource.Reference(owner, reference, null,
                "reference", library, system: system, systemAsset: systemAsset), null);
        }
        if (storage == "interface" && mode == "DataInterface") {
            int reference = variant.Value<int?>("DataInterface") ?? 0;
            if (reference == 0 || !SourceNameOrder.Equal(owner.ReferencedClassPath(reference), sourceType))
                return null;
            JToken value = ExecutionParameterStoreSource.Reference(owner, reference, component + "@editor_user_di:" + index,
                "copy", library, new JObject { ["kind"] = "component" }, system, systemAsset);
            if (value is JObject descriptor) descriptor["class_default"] = false;
            return new Value(null, value, null);
        }
        throw new ConversionException($"Invalid NiagaraComponent variant mode {mode} for {component}:{name} ({storage})");
    }

    internal JObject? Compile()
    {
        if (values.Count == 0) return null;
        List<byte> bytes = new();
        JArray variables = new(), interfaces = new(), objects = new();
        foreach (var (index, value) in values.OrderBy(entry => entry.Key)) {
            JObject slot = (JObject)declarations[index].DeepClone();
            slot.Remove("position");
            if (value.Bytes is { } numeric) {
                slot["offset"] = bytes.Count;
                bytes.AddRange(numeric);
                if (value.Position is { } position) slot["position"] = position.DeepClone();
            } else {
                JArray destination = slot.Value<string>("storage") == "interface" ? interfaces : objects;
                slot["offset"] = destination.Count;
                slot["object_index"] = destination.Count;
                destination.Add(value.Reference!.DeepClone());
            }
            variables.Add(slot);
        }
        return new JObject {
            ["variables"] = variables, ["data"] = Convert.ToBase64String(bytes.ToArray()),
            ["interface_values"] = interfaces, ["object_values"] = objects
        };
    }

    private static bool IsUser(string name) => name.StartsWith("User.", StringComparison.OrdinalIgnoreCase);
}
