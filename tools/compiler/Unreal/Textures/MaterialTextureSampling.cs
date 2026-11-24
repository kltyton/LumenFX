using System.Text;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private const string DefaultMaterialTexture2D = "/Engine/EngineResources/DefaultTexture.DefaultTexture";

    // EngineTypes.h EMaterialSamplerType order is serialized in expression properties.
    private enum MaterialSamplerType
    {
        Color, Grayscale, Alpha, Normal, Masks, DistanceFieldFont, LinearColor, LinearGrayscale, Data, External,
        VirtualColor, VirtualGrayscale, VirtualAlpha, VirtualNormal, VirtualMasks, VirtualLinearColor, VirtualLinearGrayscale
    }

    private enum MaterialTextureShape { Texture2D, Array2D, Cube, Volume, CubeArray }
    private enum MaterialTextureGather { None, Red, Green, Blue, Alpha }
    private enum MaterialTextureMipMode { Automatic, Level, Bias, Derivatives }

    private readonly Dictionary<string, MaterialTextureShape> materialTextureShapes = new(SourceNameOrder.Instance);
    private bool materialTextureGatherUsed;

    private MaterialTextureShape TextureShape(string sampler) =>
        samplerSources.TryGetValue(sampler, out string? source) ? materialTextureShapes.GetValueOrDefault(source)
            : MaterialTextureShape.Texture2D;

    private static int TextureCoordinateWidth(MaterialTextureShape shape) => shape switch {
        MaterialTextureShape.Texture2D => 2,
        MaterialTextureShape.CubeArray => 4,
        _ => 3
    };

    private static int TextureGradientWidth(MaterialTextureShape shape) => shape is MaterialTextureShape.Array2D or MaterialTextureShape.Texture2D ? 2 : 3;

    private static MaterialTextureMipMode ReadTextureMipMode(JObject properties)
    {
        string mode = (properties.Value<string>("MipValueMode") ?? "TMVM_None").Split("::").Last();
        return mode switch {
            "TMVM_None" or "0" => MaterialTextureMipMode.Automatic,
            "TMVM_MipLevel" or "1" => MaterialTextureMipMode.Level,
            "TMVM_MipBias" or "2" => MaterialTextureMipMode.Bias,
            "TMVM_Derivative" or "3" => MaterialTextureMipMode.Derivatives,
            _ => throw new ConversionException($"Unknown material texture mip mode {mode}")
        };
    }

    private static string TextureSamplerType(MaterialTextureShape shape) => shape switch {
        MaterialTextureShape.Array2D => "sampler2DArray",
        MaterialTextureShape.Cube => "samplerCube",
        MaterialTextureShape.CubeArray => "samplerCubeArray",
        MaterialTextureShape.Volume => "sampler3D",
        _ => "sampler2D"
    };

    private void AppendTextureSamplers(StringBuilder shader)
    {
        foreach (var sampler in UsedTextureSamplers())
            shader.AppendLine($"uniform {TextureSamplerType(TextureShape(sampler))} {sampler};");
    }

    private static MaterialTextureGather TextureGather(JObject properties)
    {
        string mode = (properties.Value<string>("GatherMode") ?? "None").Split("::").Last();
        if (mode.StartsWith("TGM_", StringComparison.Ordinal)) mode = mode["TGM_".Length..];
        return Enum.TryParse(mode, out MaterialTextureGather gather) && Enum.IsDefined(gather) ? gather
            : throw new ConversionException($"Unknown texture gather mode {mode}");
    }

    private static MaterialSamplerType ReadSamplerType(JObject properties)
    {
        string value = (properties.Value<string>("SamplerType") ?? "SAMPLERTYPE_Color").Split("::").Last();
        if (value.StartsWith("SAMPLERTYPE_", StringComparison.Ordinal)) value = value["SAMPLERTYPE_".Length..];
        if (!Enum.TryParse(value, out MaterialSamplerType type) || !Enum.IsDefined(type))
            throw new ConversionException($"Unknown material sampler type {value}");
        return type;
    }

    private static bool IsVirtualSampler(MaterialSamplerType type) => type >= MaterialSamplerType.VirtualColor;

    private Expr ApplySamplerType(Expr sample, Expr sampler)
    {
        if (!sampler.Sampler) throw new ConversionException("Texture sampling requires a texture object");
        Expr value = Shared(sample);
        return sampler.SamplerType switch {
            MaterialSamplerType.Alpha or MaterialSamplerType.DistanceFieldFont
                or MaterialSamplerType.Grayscale or MaterialSamplerType.LinearGrayscale
                or MaterialSamplerType.VirtualAlpha or MaterialSamplerType.VirtualGrayscale or MaterialSamplerType.VirtualLinearGrayscale
                => Shared(new Expr($"({value.Code}).rrrr", 4)),
            MaterialSamplerType.Normal or MaterialSamplerType.VirtualNormal
                => Shared(new Expr($"lfx_unpack_normal_rg({value.Code})", 4)),
            MaterialSamplerType.Color or MaterialSamplerType.LinearColor or MaterialSamplerType.Masks or MaterialSamplerType.Data
                or MaterialSamplerType.VirtualColor or MaterialSamplerType.VirtualLinearColor or MaterialSamplerType.VirtualMasks
                or MaterialSamplerType.External => value,
            _ => throw new ConversionException($"Unknown material sampler type {sampler.SamplerType}")
        };
    }

    private Expr TextureProperty(Context context, JObject properties)
    {
        Expr texture = RequiredInput(context, properties, "TextureObject");
        if (!texture.Sampler) throw new ConversionException("TextureProperty requires a texture object");
        if (TextureShape(texture.Code) is MaterialTextureShape.Cube or MaterialTextureShape.CubeArray)
            throw new ConversionException("TextureProperty does not expose cubemap dimensions");
        if (!texture.VirtualTexture && texture.TextureDimensions != 0) usedTextureSamplers.Add(texture.Code);
        string property = (properties.Value<string>("Property") ?? "TMTM_TextureSize").Split("::").Last();
        int dimensions = texture.TextureDimensions == 0 ? 3 : texture.TextureDimensions;
        Expr size = texture.TextureDimensions == 0
            ? new($"(uintBitsToFloat({texture.Code}_PagesAndOffset.xyz) * 16.0)", 3)
            : texture.VirtualTexture ? new($"vec2({texture.Code}_SizeAndAddress.xy)", 2)
                : new($"vec{dimensions}(textureSize({texture.Code}, 0))", dimensions);
        return property switch {
            "TMTM_TextureSize" or "0" => size,
            "TMTM_TexelSize" or "1" => new Expr($"(vec{dimensions}(1.0) / {size.Code})", dimensions),
            _ => throw new ConversionException($"Unknown texture property {property}")
        };
    }
}
