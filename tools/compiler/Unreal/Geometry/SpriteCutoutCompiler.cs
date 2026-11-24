using LumenFx.Compiler.Unreal.TextureSource;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry;

internal static class SpriteCutoutCompiler
{
    internal static JToken Compile(AssetLibrary library, SourceAsset owner, JObject renderer, string? oodleLibrary)
    {
        string? cutoutMode = renderer.Value<string>("CutoutMode")?.Split("::").Last() switch {
            "0" => "None", "1" => "Custom", "2" => "Material", "3" => "Texture", var name => name
        };
        if (cutoutMode == "None") return JValue.CreateNull();
        int columns = checked((int)SourceAsset.Number(renderer["SubImageSize"]?["X"], 1));
        int rows = checked((int)SourceAsset.Number(renderer["SubImageSize"]?["Y"], 1));
        if (cutoutMode == "Custom") return CompileCustom(renderer, Math.Max(1, checked(columns * rows)));
        if (cutoutMode is not (null or "Texture" or "Material"))
            throw new ConversionException($"Unknown Niagara sprite cutout mode {cutoutMode}");

        int textureIndex = renderer.Value<int?>("CutoutTexture") ?? 0;
        string? texturePath = textureIndex == 0 ? null : owner.ObjectPath(textureIndex);
        bool useMaterial = cutoutMode == "Material" || cutoutMode is null
            && (renderer.Value<bool?>("bUseMaterialCutoutTexture_DEPRECATED") ?? renderer.Value<bool?>("bUseMaterialCutoutTexture") ?? false);
        if (texturePath is null && useMaterial)
            texturePath = SpriteCutoutMaterialTexture.Resolve(library, owner, renderer.Value<int?>("Material") ?? 0);
        if (texturePath is null) return JValue.CreateNull();
        SourceAsset textureAsset = textureIndex > 0 ? owner : library.Load(texturePath);
        SourceObject texture = textureIndex > 0 ? owner.Object(textureIndex)
            : textureAsset.ResolveObject(texturePath, value => value.ClassName == "Texture2D");
        string mode = (renderer.Value<string>("BoundingMode") ?? "BVC_EightVertices").Split("::").Last();
        int vertices = mode switch {
            "BVC_FourVertices" => 4, "BVC_EightVertices" => 8,
            _ => throw new ConversionException($"Unknown sprite cutout bounding mode {mode}") };
        columns = Math.Max(1, columns);
        rows = Math.Max(1, rows);
        bool ignoreSubUv = renderer.Value<bool?>("bCutoutIgnoreSubUV") ?? false;
        string opacity = (renderer.Value<string>("OpacitySourceMode") ?? "OSM_Alpha").Split("::").Last();
        float threshold = (float)SourceAsset.Number(renderer["AlphaThreshold"], .1f);
        using var codec = oodleLibrary is null ? null : new OodleDataCodec(oodleLibrary);
        var image = TextureSourceReader.Read(textureAsset, texture, codec);
        var geometry = SpriteCutoutGeometry.Build(image, ignoreSubUv ? 1 : columns, ignoreSubUv ? 1 : rows,
                vertices, threshold, opacity);
        int frameCount = checked(columns * rows);
        return new JObject {
            ["vertices_per_frame"] = vertices,
            ["frames"] = new JArray(Enumerable.Range(0, frameCount).Select(frame =>
                new JArray(geometry[ignoreSubUv ? 0 : frame].Select(vertex =>
                    new JArray((float)vertex.X, (float)vertex.Y))))) };
    }

    private static JToken CompileCustom(JObject renderer, int frameCount)
    {
        if (renderer["CutoutCustomVertices"] is not JArray points || points.Count is not (4 or 8)) return JValue.CreateNull();
        var frame = new JArray();
        foreach (JToken point in points)
        {
            float x = (float)SourceAsset.Number(point["X"], double.NaN);
            float y = (float)SourceAsset.Number(point["Y"], double.NaN);
            if (!float.IsFinite(x) || !float.IsFinite(y))
                throw new ConversionException("Custom Niagara sprite cutout has a non-finite vertex");
            frame.Add(new JArray(x, y));
        }
        return new JObject {
            ["vertices_per_frame"] = points.Count,
            ["custom"] = true,
            ["frames"] = new JArray(Enumerable.Range(0, frameCount).Select(_ => frame.DeepClone())) };
    }
}
