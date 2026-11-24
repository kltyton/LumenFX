using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry;

/** Reads the first texture sample in the material's saved opacity mask or opacity chain. */
internal static class SpriteCutoutMaterialTexture
{
    internal static string? Resolve(AssetLibrary library, SourceAsset owner, int materialIndex)
    {
        if (materialIndex == 0) return null;
        string path = owner.ObjectPath(materialIndex);
        var asset = materialIndex > 0 ? owner : library.Load(path);
        var material = materialIndex > 0 ? owner.Object(materialIndex) : asset.ResolveObject(path, IsMaterial);
        var visited = new HashSet<(string, int)>();
        var overrides = new Dictionary<string, string?>(SourceNameOrder.Instance);
        while (material.ClassName is "MaterialInstanceConstant" or "MaterialInstanceDynamic")
        {
            if (!visited.Add((asset.Name, material.Index))) throw new ConversionException($"Recursive cutout material parent: {path}");
            if (material.Properties["TextureParameterValues"] is JArray parameters)
                foreach (var entry in parameters.OfType<JObject>())
                {
                    if (entry["ParameterInfo"] is not JObject info || info.Value<string>("Name") is not { } name) continue;
                    string association = info.Value<string>("Association")?.Split("::").Last() ?? "GlobalParameter";
                    if (association is not ("GlobalParameter" or "2")) continue;
                    int texture = entry.Value<int?>("ParameterValue") ?? 0;
                    if (texture != 0) overrides.TryAdd(name, TexturePath(asset, texture));
                }
            int parent = material.Properties.Value<int?>("Parent") ?? 0;
            if (parent == 0) return null;
            string parentPath = asset.ObjectPath(parent);
            if (parent > 0) material = asset.Object(parent);
            else { asset = library.Load(parentPath); material = asset.ResolveObject(parentPath, IsMaterial); }
        }
        int editorIndex = material.Properties.Value<int?>("EditorOnlyData") ?? 0;
        var properties = (editorIndex > 0 ? asset.Object(editorIndex) : material).Properties;
        var sample = FirstSample(asset, properties["OpacityMask"], new HashSet<int>())
            ?? FirstSample(asset, properties["Opacity"], new HashSet<int>());
        if (sample is null) return null;
        var source = sample.Properties;
        if (sample.ClassName.Contains("Parameter", StringComparison.Ordinal)
                && source.Value<string>("ParameterName") is { } parameter && overrides.TryGetValue(parameter, out string? texturePath))
            return texturePath;
        return TexturePath(asset, source.Value<int?>("Texture") ?? 0);
    }

    private static SourceObject? FirstSample(SourceAsset asset, JToken? input, HashSet<int> visited)
    {
        int expression = input is JObject connection ? connection.Value<int?>("expression") ?? 0 : 0;
        return FirstSample(asset, expression, visited);
    }

    private static SourceObject? FirstSample(SourceAsset asset, int expression, HashSet<int> visited)
    {
        if (expression <= 0 || !visited.Add(expression)) return null;
        var node = asset.Object(expression);
        if (node.ClassName == "MaterialExpressionTextureSample"
                || node.ClassName == "MaterialExpressionParticleSubUV"
                || node.ClassName.StartsWith("MaterialExpressionTextureSampleParameter", StringComparison.Ordinal)) return node;
        var properties = node.Properties;
        if (node.ClassName == "MaterialExpressionNamedRerouteUsage")
            return FirstSample(asset, properties.Value<int?>("Declaration") ?? 0, visited);
        if (node.ClassName == "MaterialExpressionShaderStageSwitch")
            return FirstSample(asset, properties["PixelShader"], visited);
        if (node.ClassName is "MaterialExpressionNaniteReplace" or "MaterialExpressionShadowReplace"
                or "MaterialExpressionReflectionCapturePassSwitch" or "MaterialExpressionGIReplace"
                or "MaterialExpressionMeshPaintTextureReplace" or "MaterialExpressionBindlessSwitch"
                or "MaterialExpressionRuntimeVirtualTextureReplace")
            return FirstSample(asset, properties["Default"], visited);
        if (node.ClassName is "MaterialExpressionLightmassReplace" or "MaterialExpressionMaterialProxyReplace")
            return FirstSample(asset, properties["Realtime"], visited);
        if (node.ClassName is "MaterialExpressionRayTracingQualitySwitch" or "MaterialExpressionPathTracingQualitySwitch")
            return FirstSample(asset, properties["Normal"], visited);
        if (node.ClassName == "MaterialExpressionPathTracingRayTypeSwitch")
            return FirstSample(asset, properties["Main"], visited);
        if (node.ClassName == "MaterialExpressionRequiredSamplersSwitch")
            return FirstSample(asset, properties["InputTrue"], visited);
        return FirstSampleInFields(asset, properties, visited);
    }

    private static SourceObject? FirstSampleInFields(SourceAsset asset, JToken token, HashSet<int> visited)
    {
        if (token is JObject obj && obj["expression"] is not null) return FirstSample(asset, obj, visited);
        if (token is not (JObject or JArray)) return null;
        IEnumerable<JToken> fields = token is JObject data ? data.Properties().Select(field => field.Value) : token.Children();
        foreach (var field in fields)
        {
            var result = FirstSampleInFields(asset, field, visited);
            if (result is not null) return result;
        }
        return null;
    }

    private static string? TexturePath(SourceAsset asset, int index) => index != 0 && asset.ReferencedClass(index) == "Texture2D"
        ? asset.ObjectPath(index) : null;

    private static bool IsMaterial(SourceObject value) => value.ClassName is "Material" or "MaterialInstanceConstant" or "MaterialInstanceDynamic";
}
