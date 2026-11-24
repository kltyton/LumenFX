using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.TextureSource;

internal static class TextureProcessingSettings
{
    internal static JObject Read(JObject properties, string compression, string group, string sourceFormat)
    {
        JObject result = new();
        foreach ((string name, double value) in new[] { ("AdjustBrightness", 1d), ("AdjustBrightnessCurve", 1d),
                ("AdjustVibrance", 0d), ("AdjustSaturation", 1d), ("AdjustRGBCurve", 1d), ("AdjustHue", 0d),
                ("AdjustMinAlpha", 0d), ("AdjustMaxAlpha", 1d) })
            result[name] = SourceAsset.Number(properties[name], value);
        result["force_no_alpha"] = properties.Value<bool?>("CompressionNoAlpha") ?? false;
        result["force_alpha"] = properties.Value<bool?>("CompressionForceAlpha") ?? false;
        result["chroma_key"] = properties.Value<bool?>("bChromaKeyTexture") ?? false;
        result["chroma_threshold"] = SourceAsset.Number(properties["ChromaKeyThreshold"], 1f / 255f);
        JObject? key = properties["ChromaKeyColor"] as JObject;
        result["chroma_color"] = new JArray(SourceAsset.Number(key?["R"], 255), SourceAsset.Number(key?["G"]),
                SourceAsset.Number(key?["B"], 255), SourceAsset.Number(key?["A"], 255));
        result["hdr_source"] = sourceFormat is "TSF_RGBA16F" or "TSF_RGBA32F" or "TSF_R16F" or "TSF_R32F" or "TSF_BGRE8";
        result["bokeh_alpha"] = group == "TEXTUREGROUP_Bokeh";
        result["flip_green"] = properties.Value<bool?>("bFlipGreenChannel") ?? false;
        result["replicate_red"] = compression is "TC_Grayscale" or "TC_Alpha";
        result["replicate_alpha"] = compression is "TC_Displacementmap" or "TC_DistanceFieldFont";
        string colorSpace = (properties["SourceColorSettings"]?.Value<string>("ColorSpace") ?? "TCS_None").Split("::").Last();
        result["has_color_space"] = colorSpace is not ("TCS_None" or "0");
        return result;
    }
}
