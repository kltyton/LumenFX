using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Authored renderer values are applied after attribute bindings and override material-instance values. */
public sealed class RendererMaterialConstants
{
    private readonly Dictionary<string, JToken> scalars = new(SourceNameOrder.Instance);
    private readonly Dictionary<string, JToken> vectors = new(SourceNameOrder.Instance);
    private readonly Dictionary<string, JToken> textures = new(SourceNameOrder.Instance);
    private readonly Dictionary<string, List<bool>> switches = new(SourceNameOrder.Instance);
    public static RendererMaterialConstants Empty { get; } = new();
    private RendererMaterialConstants() { }

    internal JToken? Scalar(string name) => scalars.GetValueOrDefault(name);
    internal JToken? Vector(string name) => vectors.GetValueOrDefault(name);
    internal JToken? Texture(string name) => textures.GetValueOrDefault(name);
    internal bool StaticSwitch(string name, bool defaultValue, out bool value)
    {
        value = defaultValue;
        bool overridden = false;
        if (!switches.TryGetValue(name, out var writes)) return false;
        foreach (bool next in writes)
        {
            if (overridden || next != defaultValue) { value = next; overridden = true; }
        }
        return overridden;
    }
    internal string Signature => new JObject {
        ["scalar"] = Object(scalars), ["vector"] = Object(vectors), ["texture"] = Object(textures), ["static"] = Switches()
    }.ToString(Formatting.None);

    internal static RendererMaterialConstants Read(SourceAsset owner, JObject renderer, RendererStaticVariables staticVariables,
                                                   string uniqueEmitterName)
    {
        RendererMaterialConstants result = new();
        JToken? source = renderer["MaterialParameters"];
        if (source is null || source.Type == JTokenType.Null) return result;
        if (source is not JObject parameters) throw new ConversionException("Renderer material parameters are not an object");
        foreach (JObject item in Entries(parameters, "ScalarParameters"))
            result.scalars[Name(item)] = new JValue((float)SourceAsset.Number(item["Value"]));
        foreach (JObject item in Entries(parameters, "VectorParameters"))
            result.vectors[Name(item)] = new JObject {
                ["R"] = (float)SourceAsset.Number(item["Value"]?["R"]),
                ["G"] = (float)SourceAsset.Number(item["Value"]?["G"]),
                ["B"] = (float)SourceAsset.Number(item["Value"]?["B"]),
                ["A"] = (float)SourceAsset.Number(item["Value"]?["A"], 1)
            };
        foreach (JObject item in Entries(parameters, "TextureParameters"))
        {
            int reference = item.Value<int?>("Texture") ?? 0;
            if (reference != 0) result.textures[Name(item)] = new JValue(owner.ObjectPath(reference));
        }
        foreach (JObject item in Entries(parameters, "StaticBoolParameters"))
        {
            string name = Name(item);
            if (!result.switches.TryGetValue(name, out var writes)) result.switches.Add(name, writes = new List<bool>());
            JToken? literal = item["StaticValue"];
            if (literal is not null && literal.Type != JTokenType.Null)
            {
                if (literal.Type != JTokenType.Boolean) throw new ConversionException($"Invalid optional static bool: {name}");
                writes.Add(literal.Value<bool>());
                continue;
            }
            string variableName = item.Value<string>("StaticVariableName") ?? "None";
            foreach (var variable in staticVariables.Values)
            {
                string resolved = variable.Name;
                int separator = resolved.IndexOf('.');
                if (separator >= 0 && SourceNameOrder.Equal(resolved[..separator], uniqueEmitterName))
                    resolved = "Emitter" + resolved[separator..];
                if (SourceNameOrder.Equal(resolved, variableName)) writes.Add(variable.Value);
            }
        }
        return result;
    }

    private static string Name(JObject value) => value.Value<string>("MaterialParameterName") ?? "None";
    private static IEnumerable<JObject> Entries(JObject parameters, string field)
    {
        JToken? value = parameters[field];
        if (value is null || value.Type == JTokenType.Null) yield break;
        if (value is not JArray array) throw new ConversionException($"Renderer material {field} is not an array");
        foreach (JToken entry in array)
            yield return entry as JObject ?? throw new ConversionException($"Invalid renderer material {field} entry");
    }
    private static JObject Object(Dictionary<string, JToken> values)
    {
        JObject result = new();
        foreach (var pair in values.OrderBy(value => value.Key, SourceNameOrder.Instance))
            result[SourceNameOrder.Canonical(pair.Key)] = pair.Value.DeepClone();
        return result;
    }
    private JObject Switches()
    {
        JObject result = new();
        foreach (var entry in switches.OrderBy(value => value.Key, SourceNameOrder.Instance))
            if (entry.Value.Count != 0) result[SourceNameOrder.Canonical(entry.Key)] = new JArray(entry.Value);
        return result;
    }
}
