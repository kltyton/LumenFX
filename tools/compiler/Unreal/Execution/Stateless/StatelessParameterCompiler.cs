using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Module bindings and expression outputs occupy one native DWORD parameter store in first-add order. */
internal sealed class StatelessParameterCompiler(SourceAsset asset, SourceObject system, JObject emitter, AssetLibrary? library)
{
    private sealed record Slot(string Type, int Offset);
    private readonly Dictionary<string, List<Slot>> slots = new(SourceNameOrder.Instance);
    private readonly JArray bindings = new(), expressions = new();
    private readonly NiagaraCollectionSource collections = new(asset, system, emitter, library);
    private int wordCount;

    internal JObject Definition() => new() { ["words"] = wordCount,
        ["bindings"] = bindings.DeepClone(), ["expressions"] = expressions.DeepClone() };

    internal int Binding(JToken? variable)
    {
        if (variable is not JObject value || value.Value<string>("name") is not { } name
                || name.Length == 0 || SourceNameOrder.Equal(name, "None")) return -1;
        JObject? type = value["type"] as JObject;
        if (type is null) return -1;
        var loaded = NiagaraTypeSource.Read(asset, type);
        return loaded.SourceReference == 0 ? -1 : Binding(name, loaded.Path);
    }

    internal int Binding(string name, string type)
    {
        if (name.Length == 0 || SourceNameOrder.Equal(name, "None")) return -1;
        if (slots.TryGetValue(name, out List<Slot>? existing)) {
            Slot? slot = existing.FirstOrDefault(value => value.Type == type);
            if (slot is not null) return slot.Offset;
        } else slots.Add(name, existing = new());
        int components = Width(type);
        int offset = wordCount;
        wordCount = checked(wordCount + components);
        existing.Add(new Slot(type, offset));
        JObject binding = new() { ["name"] = name, ["source_type"] = type, ["offset"] = offset,
            ["components"] = components, ["integer"] = type is "/Script/Niagara.NiagaraInt32" or "/Script/Niagara.NiagaraBool" };
        NumericParameterStoreSource.Value? instance = NumericParameterStoreSource.Read(asset,
            system.Properties["ExposedParameters"] as JObject ?? new(), name, type, components);
        if (instance is not null) {
            binding["instance_default"] = new JArray(instance.Words);
            if (instance.Position is not null) binding["position_default"] = new JArray(instance.Position);
        }
        collections.Apply(binding, name, type, components * sizeof(uint));
        bindings.Add(binding);
        return offset;
    }

    internal int Expression(JToken? source, string expectedType)
    {
        if (source is null || source.Type == JTokenType.Null) return -1;
        JObject? structure = source["unreal_struct"] as JObject;
        if (structure is null) throw new ConversionException("Stateless expression lost its script struct type");
        if (structure.Value<int?>("type") == 0) return -1;
        string name = "__StatelessInternal__.Expression" + (expressions.Count == 0 ? "" : "_" + (expressions.Count - 1));
        int offset = Binding(name, expectedType);
        JObject expression = Build(source, expectedType);
        expressions.Add(new JObject { ["offset"] = offset, ["expression"] = expression });
        return offset;
    }

    private JObject Build(JToken source, string expectedType)
    {
        JObject structure = source["unreal_struct"] as JObject
            ?? throw new ConversionException("Stateless expression has no typed instanced struct");
        string path = asset.Reference(structure.Value<int>("type"));
        string family = expectedType switch {
            "/Script/Niagara.NiagaraFloat" => "Float", "/Script/CoreUObject.Vector2f" => "Vec2",
            "/Script/CoreUObject.Vector3f" => "Vec3", "/Script/CoreUObject.Vector4f" => "Vec4",
            "/Script/CoreUObject.LinearColor" => "Color",
            _ => throw new ConversionException($"Unknown stateless expression output type {expectedType}")
        };
        string prefix = "/Script/Niagara.NiagaraStatelessExpression" + family;
        if (!path.StartsWith(prefix, StringComparison.Ordinal))
            throw new ConversionException($"Stateless expression {path} does not produce {expectedType}");
        string operation = path[prefix.Length..];
        JObject fields = structure["value"] as JObject
            ?? throw new ConversionException($"Stateless expression {path} has no decoded fields");
        int width = Width(expectedType);
        if (operation == "Constant") {
            float[] defaults = new float[width];
            if (family == "Color") defaults[3] = 1f;
            return Constant(Components(fields["A"], defaults, family == "Color"));
        }
        if (operation == "Binding")
            return new JObject { ["op"] = "binding", ["width"] = width,
                ["offset"] = Binding(fields.Value<string>("A") ?? "None", expectedType) };
        if (operation is not ("Add" or "Subtract" or "Multiply" or "Divide"))
            throw new ConversionException($"Unknown stateless expression operation {path}");
        JObject Child(string name) => fields[name] is { } child ? Build(child, expectedType)
            : Constant(family == "Color" ? new[] { 0f, 0f, 0f, 1f } : new float[width]);
        JObject a = Child("A"), b = Child("B");
        if (a.Value<string>("op") == "constant" && b.Value<string>("op") == "constant") {
            float[] result = new float[width];
            for (int index = 0; index < width; index++) {
                float left = Bits(a["values"]![index]!), right = Bits(b["values"]![index]!);
                result[index] = operation switch {
                    "Add" => left + right, "Subtract" => left - right, "Multiply" => left * right,
                    _ => MathF.Abs(right) > 1e-8f ? left / right : 0f
                };
            }
            return Constant(result);
        }
        return new JObject { ["op"] = operation.ToLowerInvariant(), ["width"] = width, ["a"] = a, ["b"] = b };
    }

    private static JObject Constant(float[] values) => new() { ["op"] = "constant", ["width"] = values.Length,
        ["values"] = new JArray(values.Select(value => unchecked((uint)BitConverter.SingleToInt32Bits(value)))) };
    private static float Bits(JToken value) => BitConverter.Int32BitsToSingle(unchecked((int)value.Value<uint>()));

    internal static int Width(string type) => type switch {
        "/Script/Niagara.NiagaraFloat" or "/Script/Niagara.NiagaraInt32" or "/Script/Niagara.NiagaraBool" => 1,
        "/Script/CoreUObject.Vector2f" => 2,
        "/Script/CoreUObject.Vector3f" or "/Script/Niagara.NiagaraPosition" => 3,
        "/Script/CoreUObject.Vector4f" or "/Script/CoreUObject.LinearColor" or "/Script/CoreUObject.Quat4f" => 4,
        _ => throw new ConversionException($"Unsupported stateless numeric parameter type {type}")
    };

    internal static float[] Components(JToken? value, float[] defaults, bool color = false, bool rotator = false)
    {
        if (value is null || value.Type == JTokenType.Null) return (float[])defaults.Clone();
        if (defaults.Length == 1) return new[] { (float)SourceAsset.Number(value) };
        string[] keys = rotator ? new[] { "Pitch", "Yaw", "Roll" }
            : color ? new[] { "R", "G", "B", "A" } : new[] { "X", "Y", "Z", "W" };
        return Enumerable.Range(0, defaults.Length).Select(index => (float)SourceAsset.Number(value[keys[index]], defaults[index])).ToArray();
    }

    internal static byte[] Bytes(JToken? value) => NumericParameterStoreSource.Bytes(value);
}
