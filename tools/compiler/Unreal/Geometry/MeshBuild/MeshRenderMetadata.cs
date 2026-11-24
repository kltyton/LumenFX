using System.Numerics;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal static class MeshRenderMetadata
{
    internal static JObject Bounds(MeshSourceBounds bounds) => new() {
        ["origin"] = new JArray(bounds.Origin.X, bounds.Origin.Y, bounds.Origin.Z),
        ["extent"] = new JArray(bounds.Extent.X, bounds.Extent.Y, bounds.Extent.Z),
        ["radius"] = bounds.Radius
    };

    internal static MeshSourceBounds ExtendedBounds(MeshSourceBounds bounds, JObject settings)
    {
        MeshBoundVector negative = Vector(settings["NegativeBoundsExtension"]), positive = Vector(settings["PositiveBoundsExtension"]);
        if (negative == default && positive == default) return bounds;
        MeshBoundVector minimum = bounds.Origin - bounds.Extent - negative;
        MeshBoundVector maximum = bounds.Origin + bounds.Extent + positive;
        MeshBoundVector extent = (maximum - minimum) * .5;
        return new MeshSourceBounds((minimum + maximum) * .5, extent, extent.Length());
    }

    internal static JToken ScreenSize(JObject assetSettings, JObject lodSettings, int lod, float maximumDeviation,
        float meshRadius, float previousScreenSize, float? pixelErrorOverride = null)
    {
        if (!(assetSettings.Value<bool?>("bAutoComputeLODScreenSize") ?? true))
            return lodSettings["ScreenSize"]?.DeepClone() ?? new JValue(1.0f);
        float screenSize;
        if (lod == 0) screenSize = 2;
        else if (maximumDeviation <= 0) screenSize = MathF.Pow(.75f, lod);
        else {
            float pixelError = pixelErrorOverride ?? (float)SourceAsset.Number(lodSettings["ReductionSettings"]?["PixelError"], 8);
            float distance = maximumDeviation * 960 / MathF.Max(pixelError, 1.0e-8f) + meshRadius;
            // The source reference projection has a 90-degree horizontal FOV and 1920x1080 aspect.
            screenSize = (1920.0f / 1080.0f) * meshRadius / MathF.Max(1, distance);
        }
        if (lod > 0 && screenSize > previousScreenSize) screenSize = previousScreenSize * .5f;
        return new JValue(screenSize);
    }

    private static MeshBoundVector Vector(JToken? value) => new(SourceAsset.Number(value?["X"], 0),
        SourceAsset.Number(value?["Y"], 0), SourceAsset.Number(value?["Z"], 0));
}
