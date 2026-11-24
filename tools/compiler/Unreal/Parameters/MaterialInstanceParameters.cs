using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private enum InstanceParameterType { Scalar, Vector, DoubleVector, Texture, RuntimeVirtualTexture, SparseVolumeTexture, Font, ToonProfile }

    private readonly record struct MaterialParameterKey(string Name, string Association, int Index)
    {
        public bool Equals(MaterialParameterKey other) => Index == other.Index
                && SourceNameOrder.Equal(Name, other.Name) && Association == other.Association;
        public override int GetHashCode() => HashCode.Combine(SourceNameOrder.Instance.GetHashCode(Name), Association, Index);
    }

    private readonly Dictionary<(InstanceParameterType Type, MaterialParameterKey Key), JToken> parameters = new();
    private readonly Dictionary<MaterialParameterKey, JToken> parentFontParameters = new();

    private void ReadInstanceParameters(SourceAsset asset, SourceObject instance)
    {
        foreach (var parameter in InstanceParameterValues(asset, instance)) {
            parameters.TryAdd((parameter.Type, parameter.Key), parameter.Value);
            if (sourceDynamicMaterial && materialInstanceSources.Count > 1 && parameter.Type == InstanceParameterType.Font)
                parentFontParameters.TryAdd(parameter.Key, parameter.Value);
        }
    }

    private static IEnumerable<(InstanceParameterType Type, MaterialParameterKey Key, JToken Value)> InstanceParameterValues(
        SourceAsset asset, SourceObject instance)
    {
        foreach ((string field, InstanceParameterType type) in new[] {
                ("ScalarParameterValues", InstanceParameterType.Scalar),
                ("VectorParameterValues", InstanceParameterType.Vector),
                ("DoubleVectorParameterValues", InstanceParameterType.DoubleVector),
                ("TextureParameterValues", InstanceParameterType.Texture),
                ("RuntimeVirtualTextureParameterValues", InstanceParameterType.RuntimeVirtualTexture),
                ("SparseVolumeTextureParameterValues", InstanceParameterType.SparseVolumeTexture),
                ("ToonProfileParameterValues", InstanceParameterType.ToonProfile),
                ("FontParameterValues", InstanceParameterType.Font) }) {
            if (instance.Properties[field] is not { Type: not JTokenType.Null } entries) continue;
            if (entries is not JArray values) throw new ConversionException($"Material instance {field} is not an array: {instance.Name}");
            foreach (JToken entry in values) {
                if (entry is not JObject parameter || parameter["ParameterInfo"] is not JObject info)
                    throw new ConversionException($"Invalid {field} entry: {instance.Name}");
                string name = info.Value<string>("Name") ?? throw new ConversionException($"Material parameter has no name: {instance.Name}");
                var key = new MaterialParameterKey(name, ParameterAssociation(info["Association"]), info.Value<int?>("Index") ?? -1);
                JToken value;
                if (type is InstanceParameterType.Texture or InstanceParameterType.RuntimeVirtualTexture
                    or InstanceParameterType.SparseVolumeTexture or InstanceParameterType.ToonProfile) {
                    int reference = parameter.Value<int?>("ParameterValue") ?? 0;
                    // Texture and Toon profile parameter values must resolve to a live source object.
                    if (reference == 0) continue;
                    value = asset.ObjectPath(reference);
                } else if (type == InstanceParameterType.Font) {
                    int reference = parameter.Value<int?>("FontValue") ?? 0;
                    int page = parameter.Value<int?>("FontPage") ?? 0;
                    if (reference == 0 || page < 0) continue;
                    string font = asset.ObjectPath(reference);
                    SourceAsset? fontAsset = reference > 0 ? asset : asset.Library?.TryLoad(font);
                    if (fontAsset is not null && FontPage(fontAsset, font, page, false) is null) continue;
                    value = new JObject { ["font"] = font, ["page"] = page };
                } else value = parameter["ParameterValue"]?.DeepClone() ?? (type == InstanceParameterType.Scalar
                    ? new JValue(0f) : type == InstanceParameterType.DoubleVector
                        ? new JObject { ["X"] = 0d, ["Y"] = 0d, ["Z"] = 0d, ["W"] = 1d }
                        : new JObject { ["R"] = 0f, ["G"] = 0f, ["B"] = 0f, ["A"] = 0f });
                yield return (type, key, value);
            }
        }
    }

    private JToken? ParameterValue(Context context, InstanceParameterType type, string name)
    {
        if (context.ParameterAssociation == "GlobalParameter" && context.ParameterIndex == -1)
        {
            JToken? renderer = type switch {
                InstanceParameterType.Scalar => rendererConstants.Scalar(name),
                InstanceParameterType.Vector => rendererConstants.Vector(name),
                InstanceParameterType.DoubleVector => null,
                InstanceParameterType.Texture => rendererConstants.Texture(name),
                InstanceParameterType.RuntimeVirtualTexture => null,
                InstanceParameterType.SparseVolumeTexture or InstanceParameterType.Font or InstanceParameterType.ToonProfile => null,
                _ => throw new ArgumentOutOfRangeException(nameof(type))
            };
            if (renderer is not null) return renderer;
        }
        var values = context.LayerParameters?.Values ?? parameters;
        return values.GetValueOrDefault((type, new MaterialParameterKey(name, context.ParameterAssociation, context.ParameterIndex)))
                ?? FunctionParameterValue(context, type, name);
    }

    private static string ParameterAssociation(JToken? token)
    {
        string value = token is null || token.Type == JTokenType.Null ? "GlobalParameter" : token.Value<string>()
                ?? throw new ConversionException("Material parameter association is not a value");
        return value.Split("::").Last() switch {
            "LayerParameter" or "0" => "LayerParameter", "BlendParameter" or "1" => "BlendParameter",
            "GlobalParameter" or "2" => "GlobalParameter",
            var unknown => throw new ConversionException($"Unknown material parameter association {unknown}")
        };
    }
}
