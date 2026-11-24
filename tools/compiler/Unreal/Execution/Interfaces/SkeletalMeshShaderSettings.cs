using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal sealed class SkeletalMeshShaderSettings
{
    private readonly JObject defines;

    private SkeletalMeshShaderSettings(JObject defines) => this.defines = defines;

    internal static SkeletalMeshShaderSettings? Read(JToken? value)
    {
        if (value is null) return null;
        if (value is not JObject settings)
            throw new ConversionException("Niagara skeletal mesh GPU settings must be an object");
        return new SkeletalMeshShaderSettings(new JObject {
            ["DISKELMESH_BONE_INFLUENCES"] = EnumIndex(settings, "NDISkelMesh_GpuMaxInfluences", 2, ["AllowMax4", "AllowMax8", "Unlimited"]),
            ["DISKELMESH_ALLOW_16BIT"] = Enabled(settings, "NDISkelMesh_Support16BitIndexWeight"),
            ["DISKELMESH_ALLOW_DEFORMED"] = Enabled(settings, "NDISkelMesh_SupportReadingDeformedGeometry"),
            ["DISKELMESH_PROBALIAS_FORMAT"] = EnumIndex(settings, "NDISkelMesh_GpuUniformSamplingFormat", 0, ["Full", "Limited_24_8", "Limited_23_9"]),
            ["DISKELMESH_ADJ_INDEX_FORMAT"] = EnumIndex(settings, "NDISkelMesh_AdjacencyTriangleIndexFormat", 0, ["Full", "Half"])
        });
    }

    internal void Apply(JObject program)
    {
        var bindings = (JArray)program["interfaces"]!;
        if (!bindings.OfType<JObject>().Any(binding => binding.Value<string>("class") == SkeletalMeshDataInterfaceCompiler.ClassName
                && binding["shader_symbol"] is not null)) return;
        foreach (var stage in ((JArray)program["variants"]!).OfType<JObject>()) {
            var target = (JObject)stage["defines"]!;
            foreach (var define in defines.Properties()) target[define.Name] = define.Value.DeepClone();
        }
    }

    private static int Enabled(JObject settings, string name) => settings[name] switch {
        null => 1,
        { Type: JTokenType.Boolean } value => value.Value<bool>() ? 1 : 0,
        _ => throw new ConversionException($"Invalid Niagara skeletal mesh setting: {name}")
    };

    private static int EnumIndex(JObject settings, string name, int fallback, string[] values)
    {
        if (settings[name] is not JToken value) return fallback;
        int index = value.Type switch {
            JTokenType.String => Array.IndexOf(values, value.Value<string>()?.Split("::").Last()),
            JTokenType.Integer => value.Value<int>(),
            _ => -1
        };
        if (index < 0 || index >= values.Length)
            throw new ConversionException($"Invalid Niagara skeletal mesh setting: {name}={value}");
        return index;
    }
}
