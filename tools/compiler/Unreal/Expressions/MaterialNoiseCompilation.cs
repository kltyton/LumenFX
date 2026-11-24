using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    internal const string NoiseGradientTexture = "lumenfx:engine/material_noise_gradient";
    internal const string NoiseVolumeTexture = "lumenfx:engine/material_noise_volume";
    internal const string NoiseScalarBlueTexture = "lumenfx:textures/lumenfx/blue_noise/scalar.lftex.json";

    [Flags]
    private enum NoiseInputs { None = 0, Kernel = 1, Gradient = 2, Volume = 4, ScalarBlue = 8 }

    private enum ScalarNoise { SimplexTex, GradientTex, GradientTex3D, GradientALU, ValueALU, VoronoiALU }
    private enum VectorNoise { CellnoiseALU, VectorALU, GradientALU, CurlALU, VoronoiALU }

    private NoiseInputs pixelNoiseInputs, vertexNoiseInputs;

    private Expr ScalarBlueNoise()
    {
        if (decalCoordinates || lightFunctionCoordinates || volumeCoordinates || runtimeVirtualTextureWriter)
            throw new ConversionException("Scalar Blue Noise requires a surface material");
        RegisterNoiseInputs(NoiseInputs.ScalarBlue);
        materialViewUsed |= 256;
        return new Expr($"lfx_material_scalar_blue_noise({ScreenPosition(1).Code})", 1);
    }

    private Expr MaterialNoise(Context context, JObject properties, bool vector)
    {
        string origin = (properties.Value<string>("WorldPositionOriginType") ?? "Absolute").Split("::").Last();
        bool relative = origin is "CameraRelative" or "1";
        if (!relative && origin is not ("Absolute" or "0"))
            throw new ConversionException($"Unknown material noise position origin {origin}");
        Expr position = Cast(Input(context, properties["Position"], () => WorldPosition(new JObject {
            ["WorldPositionShaderOffset"] = relative ? "WPT_CameraRelative" : "WPT_Default"
        })), 3);
        if (relative) position = AbsolutePosition(position.Code);
        int quality = properties.Value<int?>("Quality") ?? 1;
        string tiling = properties.Value<bool?>("bTiling") == true ? "true" : "false";

        if (vector) {
            RegisterNoiseInputs(NoiseInputs.Kernel);
            VectorNoise vectorMode = ParseNoiseMode(properties, "VNF_", VectorNoise.CellnoiseALU);
            string tileSize = Number((float)(properties.Value<uint?>("TileSize") ?? 300));
            string result = $"lfx_material_vector_noise({position.Code}, {quality}, {(int)vectorMode}, {tiling}, {tileSize})";
            return vectorMode is VectorNoise.GradientALU or VectorNoise.VoronoiALU
                ? new Expr(result, 4) : new Expr(result + ".xyz", 3);
        }

        ScalarNoise mode = ParseNoiseMode(properties, "NOISEFUNCTION_", ScalarNoise.SimplexTex);
        RegisterNoiseInputs(NoiseInputs.Kernel | (mode switch {
            ScalarNoise.SimplexTex or ScalarNoise.GradientTex => NoiseInputs.Gradient,
            ScalarNoise.GradientTex3D => NoiseInputs.Volume,
            _ => NoiseInputs.None
        }));
        string positionCode = position.Code;
        if (position.LargeWorld && precision.LwcEnabled) {
            // Both fixed material translators select the normalized tile-offset overload.
            Expr absolute = Shared(position);
            string value = absolute.RawCode;
            positionCode = $"((({value}).Tile - floor(({value}).Tile + (({value}).Offset * LFX_LWC_TILE_RCP + 0.5))) " +
                $"* LFX_LWC_TILE_SIZE + ({value}).Offset)";
        }
        Expr filterWidth = Cast(Input(context, properties["FilterWidth"], () => Constant(0)), 1);
        int levels = Math.Clamp(properties.Value<int?>("Levels") ?? 6, 1, 10);
        string turbulence = properties.Value<bool?>("bTurbulence") == false ? "false" : "true";
        string repeat = Number((float)(properties.Value<uint?>("RepeatSize") ?? 512));
        string FloatProperty(string name, float fallback) => Number((float)SourceAsset.Number(properties[name], fallback));
        return new Expr($"lfx_material_noise({positionCode}, {FloatProperty("Scale", 1)}, {quality}, {(int)mode}, " +
            $"{turbulence}, {levels}, {FloatProperty("OutputMin", -1)}, {FloatProperty("OutputMax", 1)}, " +
            $"{FloatProperty("LevelScale", 2)}, {filterWidth.Code}, {tiling}, {repeat})", 1);
    }

    private static T ParseNoiseMode<T>(JObject properties, string prefix, T fallback) where T : struct, Enum
    {
        string? value = properties["NoiseFunction"]?.ToString().Split("::").Last();
        if (value is null) return fallback;
        if (value.StartsWith(prefix, StringComparison.Ordinal)) value = value[prefix.Length..];
        return Enum.TryParse(value, out T mode) && Enum.IsDefined(mode) ? mode
            : throw new ConversionException($"Unknown material noise function {value}");
    }

    private void RegisterNoiseInputs(NoiseInputs inputs)
    {
        if (compilingVertex) vertexNoiseInputs |= inputs;
        else pixelNoiseInputs |= inputs;
        if ((inputs & NoiseInputs.Gradient) != 0) samplerSources.TryAdd("LfxMaterialNoiseGradient", NoiseGradientTexture);
        if ((inputs & NoiseInputs.Volume) != 0) samplerSources.TryAdd("LfxMaterialNoiseVolume", NoiseVolumeTexture);
        if ((inputs & NoiseInputs.ScalarBlue) != 0) samplerSources.TryAdd("LfxMaterialScalarBlueNoise", NoiseScalarBlueTexture);
    }

    private void AppendMaterialNoise(StringBuilder shader, bool vertex = false)
    {
        NoiseInputs inputs = vertex ? vertexNoiseInputs : pixelNoiseInputs;
        if ((inputs & NoiseInputs.Kernel) != 0) {
            if ((inputs & NoiseInputs.Gradient) != 0) shader.AppendLine("#define LFX_NOISE_GRADIENT_TEXTURE 1");
            if ((inputs & NoiseInputs.Volume) != 0) shader.AppendLine("#define LFX_NOISE_VOLUME_TEXTURE 1");
            shader.AppendLine("#moj_import <lumenfx:noise/material_noise.glsl>");
        }
        if ((inputs & NoiseInputs.ScalarBlue) != 0)
            shader.AppendLine("#moj_import <lumenfx:noise/material_blue_noise.glsl>");
    }
}
