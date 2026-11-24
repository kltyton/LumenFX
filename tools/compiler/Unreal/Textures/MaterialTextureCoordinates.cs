using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr TextureSampleCoordinates(JObject properties, Expr sampler, Expr coordinates)
    {
        if (!sampler.Sampler) throw new ConversionException("Texture sampling requires a texture object");

        int width = TextureCoordinateWidth(TextureShape(sampler.Code));
        Expr cast = Cast(coordinates, width);
        if (!cast.LargeWorld) return cast;
        // The fixed address conversion has no four-component overload.
        if (width == 4) return DemoteLargeWorld(cast);

        TextureAddressMode[] addressModes = TextureSampleAddressModes(properties, sampler, width);
        string[] channels = ["x", "y", "z"];
        string[] addressed = new string[width];
        for (int index = 0; index < width; index++)
        {
            string tile = $"({cast.RawCode}).Tile.{channels[index]}";
            string offset = $"({cast.RawCode}).Offset.{channels[index]}";
            addressed[index] = addressModes[index] switch {
                // MaterialTemplate.ush: LWCApplyAddressModeWrap keeps the tile fraction and
                // offset separate until after the large tile has been reduced.
                TextureAddressMode.Wrap => $"(fract({tile} * LFX_LWC_TILE_SIZE) + {offset})",
                // UE 5.8.4 currently demotes Mirror and Clamp coordinates identically here.
                TextureAddressMode.Clamp or TextureAddressMode.Mirror =>
                    $"lfx_lwc_demote(LfxLWC1({tile}, {offset}))",
                _ => throw new ConversionException($"Unknown texture address mode {addressModes[index]}")
            };
        }

        // HLSLMaterialTranslator adds the address conversion as a zero-derivative code chunk;
        // its explicit LWC gradients are disabled, so sampling derives from the converted UV.
        return new Expr($"vec{width}({string.Join(", ", addressed)})", width);
    }

    private TextureAddressMode[] TextureSampleAddressModes(JObject properties, Expr sampler, int width)
    {
        string sourcePath = TextureSampleSourcePath(sampler);
        SourceObject texture = library.Load(sourcePath).ResolveObject(sourcePath, value => value.ClassName is
            "Texture2D" or "TextureLightProfile" or "Texture2DArray" or "TextureCube" or "VolumeTexture" or
            "TextureRenderTarget2D" or "TextureRenderTarget2DArray" or "TextureRenderTargetCube" or "TextureRenderTargetVolume" or
            "CurveLinearColorAtlas");

        // GetTextureAddressForSamplerSource forces all three axes to wrap for multi-block/UDIM textures.
        if (UsesMultipleTextureAddressBlocks(texture))
            return Enumerable.Repeat(TextureAddressMode.Wrap, width).ToArray();

        string samplerSource = (properties.Value<string>("SamplerSource") ?? "SSM_FromTextureAsset").Split("::").Last();
        TextureAddressMode sharedMode = samplerSource switch {
            "SSM_Wrap_WorldGroupSettings" or "1" => TextureAddressMode.Wrap,
            "SSM_Clamp_WorldGroupSettings" or "SSM_TerrainWeightmapGroupSettings" or "2" or "3" => TextureAddressMode.Clamp,
            "SSM_FromTextureAsset" or "0" => default,
            _ => throw new ConversionException($"Unknown material texture sampler source {samplerSource}")
        };
        if (samplerSource is not ("SSM_FromTextureAsset" or "0"))
            return Enumerable.Repeat(sharedMode, width).ToArray();

        return AssetTextureAddressModes(sourcePath, texture, width);
    }

    private string TextureSampleSourcePath(Expr sampler)
    {
        if (sampler.StaticTextureSource is { } authored) return authored;
        if (sampler.VirtualTexture)
        {
            CompiledVirtualTextureRead? virtualTexture = virtualTextureReads.Values
                .FirstOrDefault(value => string.Equals(value.Name, sampler.Code, StringComparison.Ordinal));
            return virtualTexture?.Source
                ?? throw new ConversionException($"Missing streamed virtual texture source for sampler {sampler.Code}");
        }

        return samplerSources.TryGetValue(sampler.Code, out string? source)
            ? source
            : throw new ConversionException($"Missing texture source for sampler {sampler.Code}");
    }

    private static bool UsesMultipleTextureAddressBlocks(SourceObject texture)
    {
        JObject? source = texture.Properties["Source"] as JObject;
        return source?["Blocks"] is JArray blocks && blocks.Count > 0;
    }

    private TextureAddressMode[] AssetTextureAddressModes(string sourcePath, SourceObject texture, int width)
    {
        // The material translator uses UTexture's virtual address accessors. Cubes
        // and render targets inherit Wrap here even when their actual sampler clamps.
        if (texture.ClassName == "VolumeTexture")
            return Enumerable.Repeat(Textures.VolumeAddress(texture, sourcePath), width).ToArray();
        if (texture.ClassName is not ("Texture2D" or "TextureLightProfile" or "Texture2DArray" or "CurveLinearColorAtlas"))
            return Enumerable.Repeat(TextureAddressMode.Wrap, width).ToArray();

        JObject properties = texture.Properties;
        JObject? tags = texture.Outer != 0
            ? properties
            : (library.Load(sourcePath).Raw["AssetRegistryRecords"] as JArray)?.OfType<JObject>()
                .Select(value => value["TagMap"]).OfType<JObject>().FirstOrDefault();

        TextureSettings? settings = textureSettings.GetValueOrDefault(sourcePath);
        TextureAddressMode ReadAxis(string axis, TextureAddressMode fallback)
        {
            if (settings is not null)
            {
                if (axis == "X") return settings.AddressX;
                if (axis == "Y") return settings.AddressY;
            }

            string key = "Address" + axis;
            string? value = EncodedTextureProperty(properties[key]) ?? EncodedTextureProperty(tags?[key]);
            return value is null ? fallback : Textures.Address(value, sourcePath);
        }

        TextureAddressMode planarFallback = texture.ClassName == "CurveLinearColorAtlas" ? TextureAddressMode.Clamp : TextureAddressMode.Wrap;
        TextureAddressMode x = ReadAxis("X", planarFallback);
        TextureAddressMode y = ReadAxis("Y", planarFallback);
        TextureAddressMode z = texture.ClassName == "Texture2DArray" ? ReadAxis("Z", TextureAddressMode.Wrap) : TextureAddressMode.Wrap;
        return width switch {
            2 => [x, y],
            3 => [x, y, z],
            _ => throw new ConversionException($"Unsupported texture coordinate width {width}")
        };
    }

    private static string? EncodedTextureProperty(JToken? value) => value is null || value.Type == JTokenType.Null
        ? null : value.Type == JTokenType.String ? value.Value<string>() : value.ToString();
}
