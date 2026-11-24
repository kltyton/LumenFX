using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.TextureSource;

internal static class RuntimeVirtualTextureSource
{
    internal static JObject Compile(SourceObject asset, string path)
    {
        JObject properties = asset.Properties;
        string material = (properties.Value<string>("MaterialType") ?? "BaseColor_Normal_Specular").Split("::").Last();
        string priority = material switch {
            "BaseColor" or "BaseColor_Normal_Roughness" or "BaseColor_Normal_Specular"
                or "BaseColor_Normal_Specular_YCoCg" or "BaseColor_Normal_Specular_Mask_YCoCg" => "High",
            "Mask4" => "BelowNormal",
            "WorldHeight" => "Low",
            "Displacement" => "Lower",
            _ => throw new ConversionException($"Unknown runtime virtual texture material {material} in {path}")
        };
        if (properties.Value<bool?>("bUseCustomPriority") == true)
            priority = (properties.Value<string>("CustomPriority") ?? "Lowest").Split("::").Last();
        var data = properties["CustomMaterialData"] as JObject;
        return new JObject {
            ["material"] = material,
            ["tile_count"] = properties.Value<int?>("TileCount") ?? 8,
            ["tile_size"] = properties.Value<int?>("TileSize") ?? 2,
            ["tile_border"] = properties.Value<int?>("TileBorderSize") ?? 2,
            ["remove_low_mips"] = properties.Value<int?>("RemoveLowMips") ?? 0,
            ["lod_group"] = (properties.Value<string>("LODGroup") ?? "TEXTUREGROUP_World").Split("::").Last(),
            ["compress"] = properties.Value<bool?>("bCompressTextures") ?? true,
            ["low_quality_compression"] = properties.Value<bool?>("bUseLowQualityCompression") ?? false,
            ["clear"] = properties.Value<bool?>("bClearTextures") ?? true,
            ["packed_table"] = properties.Value<bool?>("bSinglePhysicalSpace") ?? true,
            ["private_space"] = properties.Value<bool?>("bPrivateSpace") ?? true,
            ["adaptive"] = properties.Value<bool?>("bAdaptive") ?? false,
            ["continuous"] = properties.Value<bool?>("bContinuousUpdate") ?? false,
            ["priority"] = priority,
            ["custom_material_data"] = new JArray(new[] { "X", "Y", "Z", "W" }.Select(key => data?.Value<float?>(key) ?? 0))
        };
    }
}
