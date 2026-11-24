using LumenFx.Compiler.Unreal.Execution;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr CurveAtlasRowParameter(Context context, JObject properties, int output)
    {
        string atlasPath = ObjectPropertyReaderCompiler.ObjectPath(context.Asset, properties["Atlas"]);
        if (atlasPath.Length == 0)
            throw new ConversionException($"Curve atlas is not set: {context.Asset.Name}");

        SourceAsset atlasAsset = library.Load(atlasPath);
        SourceObject atlas = atlasAsset.ResolveObject(atlasPath, value => value.ClassName == "CurveLinearColorAtlas");
        TextureSettings settings = Textures.ReadCurveAtlas(atlasAsset, atlasPath);
        JObject atlasSource = atlas.Properties["Source"] as JObject
            ?? throw new ConversionException($"Curve atlas has no source image: {atlasPath}");
        JArray curves = atlas.Properties["GradientCurves"] as JArray
            ?? throw new ConversionException($"Curve atlas has no curve rows: {atlasPath}");

        bool useCustomPrimitiveData = properties.Value<bool?>("bUseCustomPrimitiveData") == true;
        int curveIndex = -1;
        if (!useCustomPrimitiveData)
        {
            string curvePath = ObjectPropertyReaderCompiler.ObjectPath(context.Asset, properties["Curve"]);
            if (curvePath.Length == 0)
                throw new ConversionException($"Curve is not set: {context.Asset.Name}:{properties.Value<string>("ParameterName")}");

            string normalizedCurvePath = NormalizeObjectPath(curvePath);
            for (int index = 0; index < curves.Count; index++)
            {
                string rowPath = ObjectPropertyReaderCompiler.ObjectPath(atlasAsset, curves[index]);
                if (rowPath.Length != 0 && string.Equals(NormalizeObjectPath(rowPath), normalizedCurvePath, StringComparison.OrdinalIgnoreCase))
                {
                    curveIndex = index;
                    break;
                }
            }
            if (curveIndex < 0)
                throw new ConversionException($"Curve is not contained in its atlas: {curvePath} ({atlasPath})");
        }

        int width = checked((int)SourceAsset.Number(atlasSource["SizeX"]));
        int height = checked((int)SourceAsset.Number(atlasSource["SizeY"]));
        int expectedHeight = Math.Max(curves.Count, 1);
        if (width != settings.Width || height != settings.Height || height != expectedHeight)
            throw new ConversionException($"Curve atlas source dimensions do not match its curve rows: {atlasPath}");

        Expr sampler = RegisterTextureSampler(context, atlasPath, null, MaterialSamplerType.LinearColor);
        usedTextureSamplers.Add(sampler.Code);

        string parameterName = properties.Value<string>("ParameterName") ?? "Param";
        Expr row;
        if (useCustomPrimitiveData)
            row = PrimitiveCustomMaterialParameter(properties, 1);
        else
        {
            double rowDefault = SourceAsset.Number(ParameterValue(context, InstanceParameterType.Scalar, parameterName), curveIndex);
            row = RuntimeParameter(context, parameterName, "scalar", new[] { rowDefault },
                Constant(rowDefault) with { ParameterLiteral = true });
        }
        Expr time = Input(context, properties["InputTime"], () => Constant(0));
        string dimensions = $"vec2(textureSize({sampler.Code}, 0))";
        string u = $"(({Cast(time, 1).Code} * ({dimensions}.x - 1.0) + 0.5) / {dimensions}.x)";
        string v = $"(({row.Code} + 0.5) / {dimensions}.y)";
        Expr rgba = new($"textureLod({sampler.Code}, vec2({u}, {v}), 0.0)", 4);
        return output switch
        {
            0 => rgba,
            1 => Swizzle(rgba, "r"),
            2 => Swizzle(rgba, "g"),
            3 => Swizzle(rgba, "b"),
            4 => Swizzle(rgba, "a"),
            _ => throw new ConversionException($"Invalid curve atlas output {output}")
        };
    }

    private static string NormalizeObjectPath(string path) => path.Replace(':', '.').TrimEnd('.');
}
