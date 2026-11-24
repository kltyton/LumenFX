using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public enum TextureMipMode
{
    SimpleAverage,
    Sharpen,
    NoMipmaps,
    NativeBuild
}

public enum TextureFilterMode
{
    Point,
    Linear,
    Aniso
}

public enum TextureAddressMode
{
    Wrap,
    Clamp,
    Mirror
}

public sealed record TextureSettings(
    string AssetPath,
    int Width,
    int Height,
    bool Srgb,
    TextureMipMode MipMode,
    double Sharpen,
    int KernelSize,
    bool DownsampleWithAverage,
    bool SharpenWithoutColorShift,
    bool UseNewMipFilter,
    string MipGenSetting,
    string LodGroup,
    string Compression,
    int MaxAnisotropy,
    TextureFilterMode MinMagFilter,
    TextureFilterMode MipFilter,
    TextureAddressMode AddressX,
    TextureAddressMode AddressY,
    string CookedFormat,
    bool CookedPlatformMips,
    int SourceMips,
    string LodBias,
    string MaxTextureSize,
    string MipLoadOptions,
    string NeverStream,
    string OodleSdk,
    bool PreserveExtremes,
    bool LegacyGamma)
{
    public string SourcePixelFormat { get; init; } = "";
    public JObject? Processing { get; init; }
    internal bool FontPage { get; init; }
    public JObject ToJson(string pngSha256) => new()
    {
        ["format_version"] = 1,
        ["source"] = "unreal-texture2d",
        ["build_backend"] = "unreal_native",
        ["asset"] = AssetPath,
        ["png_sha256"] = pngSha256,
        ["width"] = Width,
        ["height"] = Height,
        ["srgb"] = Srgb,
        ["mip_gen"] = MipMode switch
        {
            TextureMipMode.SimpleAverage => "simple_average",
            TextureMipMode.Sharpen => "sharpen",
            TextureMipMode.NoMipmaps => "no_mipmaps",
            TextureMipMode.NativeBuild => "native_build",
            _ => throw new ConversionException($"Unsupported mip mode {MipMode}")
        },
        ["mip_gen_setting"] = MipGenSetting,
        ["sharpen"] = Sharpen,
        ["kernel_size"] = KernelSize,
        ["downsample_with_average"] = DownsampleWithAverage,
        ["sharpen_without_color_shift"] = SharpenWithoutColorShift,
        ["use_new_mip_filter"] = UseNewMipFilter,
        ["lod_group"] = LodGroup,
        ["compression"] = Compression,
        ["max_anisotropy"] = MaxAnisotropy,
        ["min_mag_filter"] = FilterName(MinMagFilter),
        ["mip_filter"] = FilterName(MipFilter),
        ["address_x"] = AddressName(AddressX),
        ["address_y"] = AddressName(AddressY),
        ["cooked_format"] = CookedFormat,
        ["cooked_platform_mips"] = CookedPlatformMips,
        ["source_mips"] = SourceMips,
        ["source_pixel_format"] = SourcePixelFormat,
        ["processing"] = Processing?.DeepClone(),
        ["lod_bias"] = LodBias,
        ["max_texture_size"] = MaxTextureSize,
        ["mip_load_options"] = MipLoadOptions,
        ["never_stream"] = NeverStream,
        ["oodle_sdk"] = OodleSdk,
        ["preserve_extremes"] = PreserveExtremes,
        ["legacy_gamma"] = LegacyGamma,
        ["encode_effort"] = 20,
        ["rdo_lambda"] = 0,
        ["universal_tiling"] = 0
    };

    private static string FilterName(TextureFilterMode mode) => mode switch
    {
        TextureFilterMode.Point => "point",
        TextureFilterMode.Linear => "linear",
        TextureFilterMode.Aniso => "aniso",
        _ => throw new ConversionException($"Unsupported texture filter {mode}")
    };

    private static string AddressName(TextureAddressMode mode) => mode switch
    {
        TextureAddressMode.Wrap => "wrap",
        TextureAddressMode.Clamp => "clamp",
        TextureAddressMode.Mirror => "mirror",
        _ => throw new ConversionException($"Unsupported texture address {mode}")
    };
}

public static class Textures
{
    // TW Config and Saved contain no r.MaxAnisotropy or sg.TextureQuality override.
    // UE 5.8 Scalability.cpp defaults sg.TextureQuality to 3 (DefaultQualityLevel);
    // BaseScalability.ini [TextureQuality@3] sets r.MaxAnisotropy=8.
    private const int MaxAnisotropy = 8;

    private sealed record LodGroup(string MipGenSetting, TextureFilterMode MinMag, TextureFilterMode MipFilter);

    // Engine/Config/BaseDeviceProfiles.ini:190-216 of the pinned UE 5.8.4 source.
    private static readonly Dictionary<string, LodGroup> LodGroups = new(StringComparer.Ordinal)
    {
        ["TEXTUREGROUP_World"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_WorldNormalMap"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_WorldSpecular"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Character"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_CharacterNormalMap"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_CharacterSpecular"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Weapon"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_WeaponNormalMap"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_WeaponSpecular"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Vehicle"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_VehicleNormalMap"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_VehicleSpecular"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Cinematic"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Effects"] = new("TMGS_SimpleAverage", TextureFilterMode.Linear, TextureFilterMode.Point),
        ["TEXTUREGROUP_EffectsNotFiltered"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Skybox"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_UI"] = new("TMGS_NoMipmaps", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Lightmap"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Shadowmap"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_RenderTarget"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_MobileFlattened"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Terrain_Heightmap"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Terrain_Weightmap"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Bokeh"] = new("TMGS_SimpleAverage", TextureFilterMode.Linear, TextureFilterMode.Linear),
        ["TEXTUREGROUP_Pixels2D"] = new("TMGS_SimpleAverage", TextureFilterMode.Point, TextureFilterMode.Point),
        ["TEXTUREGROUP_8BitData"] = new("TMGS_NoMipmaps", TextureFilterMode.Point, TextureFilterMode.Point),
        ["TEXTUREGROUP_16BitData"] = new("TMGS_NoMipmaps", TextureFilterMode.Point, TextureFilterMode.Point),
        ["TEXTUREGROUP_ProcBuilding_Face"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_ProcBuilding_LightMap"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_ColorLookupTable"] = new("TMGS_NoMipmaps", TextureFilterMode.Linear, TextureFilterMode.Point),
        ["TEXTUREGROUP_IESLightProfile"] = new("TMGS_NoMipmaps", TextureFilterMode.Linear, TextureFilterMode.Point),
        ["TEXTUREGROUP_HierarchicalLOD"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_Impostor"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point),
        ["TEXTUREGROUP_ImpostorNormalDepth"] = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point)
    };

    private static bool TryResolveLodGroup(string groupName, out LodGroup group)
    {
        if (LodGroups.TryGetValue(groupName, out LodGroup? known))
        {
            group = known;
            return true;
        }
        if (groupName.StartsWith("TEXTUREGROUP_Project", StringComparison.Ordinal)
            && int.TryParse(groupName["TEXTUREGROUP_Project".Length..], out int projectIndex)
            && projectIndex is >= 1 and <= 48)
        {
            group = new("TMGS_SimpleAverage", TextureFilterMode.Aniso, TextureFilterMode.Point);
            return true;
        }
        group = null!;
        return false;
    }

    public static JObject ReadSampler(JObject properties, string defaultGroup, string assetPath)
    {
        string groupName = (properties.Value<string>("LODGroup") ?? defaultGroup).Split("::").Last();
        if (!TryResolveLodGroup(groupName, out LodGroup group)) throw new ConversionException($"Unknown texture group {groupName} in {assetPath}");
        (TextureFilterMode filter, TextureFilterMode mip) = FilterSettings((properties.Value<string>("Filter") ?? "TF_Default").Split("::").Last(), group);
        return new JObject { ["min_mag_filter"] = filter.ToString().ToLowerInvariant(), ["mip_filter"] = mip.ToString().ToLowerInvariant(),
            ["max_anisotropy"] = MaxAnisotropy,
            ["address_x"] = Address(properties.Value<string>("AddressX") ?? "TA_Wrap", assetPath).ToString().ToLowerInvariant(),
            ["address_y"] = Address(properties.Value<string>("AddressY") ?? "TA_Wrap", assetPath).ToString().ToLowerInvariant() };
    }

    public static TextureSettings Read(SourceAsset asset, string assetPath)
    {
        SourceObject texture = asset.ResolveObject(assetPath,
            value => value.ClassName is "Texture2D" or "TextureLightProfile" or "Texture2DArray" or "TextureCube" or "TextureCubeArray" or "VolumeTexture" or "CurveLinearColorAtlas");
        JObject properties = texture.Properties;
        // Embedded Texture2D objects and synthetic/minimal exports may omit AssetRegistryRecords; fall back to properties.
        JObject tags = (texture.Outer == 0
            ? (asset.Raw["AssetRegistryRecords"] as JArray)?.OfType<JObject>()
                .Select(value => value["TagMap"]).OfType<JObject>().FirstOrDefault()
            : null) ?? properties;

        (int width, int height) = Dimensions(tags, properties, assetPath);
        string groupName = (properties.Value<string>("LODGroup")
            ?? Tag(tags, "LODGroup") ?? Tag(tags, "TextureGroup")
            ?? "TEXTUREGROUP_World").Split("::").Last();
        if (!TryResolveLodGroup(groupName, out LodGroup group))
            throw new ConversionException($"Unknown texture group {groupName} in {assetPath}");

        string mipGen = (properties.Value<string>("MipGenSettings") ?? Tag(tags, "MipGenSettings") ?? "TMGS_FromTextureGroup").Split("::").Last();
        string resolved = mipGen == "TMGS_FromTextureGroup" ? group.MipGenSetting : mipGen;
        (TextureMipMode mode, double sharpen, int kernelSize, bool downsample) = MipSettings(resolved, assetPath);

        string compression = (properties.Value<string>("CompressionSettings") ?? Tag(tags, "CompressionSettings") ?? "TC_Default").Split("::").Last();
        bool srgb = properties.Value<bool?>("SRGB") ?? (bool.TryParse(Tag(tags, "SRGB"), out bool srgbValue) ? srgbValue : true);
        bool withoutColorShift = !compression.Contains("Normal", StringComparison.OrdinalIgnoreCase)
            && !string.Equals(compression, "TC_Masks", StringComparison.Ordinal);
        // UTexture::bUseNewMipFilter defaults to false in Engine/Classes/Engine/Texture.h.
        bool useNewMipFilter = properties.Value<bool?>("bUseNewMipFilter")
            ?? (bool.TryParse(Tag(tags, "bUseNewMipFilter"), out bool newFilter) && newFilter);

        string filter = (properties.Value<string>("Filter") ?? Tag(tags, "Filter") ?? "TF_Default").Split("::").Last();
        (TextureFilterMode minMag, TextureFilterMode mipFilter) = FilterSettings(filter, group);
        bool cube = texture.ClassName is "TextureCube" or "TextureCubeArray";
        bool volume = texture.ClassName == "VolumeTexture";
        TextureAddressMode addressX = cube ? TextureAddressMode.Clamp : volume ? VolumeAddress(texture, assetPath)
            : Address(properties.Value<string>("AddressX") ?? Tag(tags, "AddressX") ?? "TA_Wrap", assetPath);
        TextureAddressMode addressY = cube ? TextureAddressMode.Clamp : volume ? addressX
            : Address(properties.Value<string>("AddressY") ?? Tag(tags, "AddressY") ?? "TA_Wrap", assetPath);

        JToken? source = properties["Source"];
        int sourceMips = (int)SourceAsset.Number(source?["NumMips"], 1);

        return new TextureSettings(
            assetPath, width, height, srgb, mode, sharpen, kernelSize, downsample, withoutColorShift,
            useNewMipFilter, mipGen, groupName, compression, MaxAnisotropy, minMag, mipFilter, addressX, addressY,
            Tag(tags, "Format") ?? properties.Value<string>("Format") ?? "", false, sourceMips,
            properties["LODBias"]?.ToString() ?? Tag(tags, "LODBias") ?? "0",
            properties["MaxTextureSize"]?.ToString() ?? Tag(tags, "MaxTextureSize") ?? "0",
            properties.Value<string>("MipLoadOptions")?.Split("::").Last() ?? Tag(tags, "MipLoadOptions") ?? "Default",
            properties["NeverStream"]?.ToString() ?? Tag(tags, "NeverStream") ?? "False",
            properties.Value<string>("OodleTextureSdkVersion") is { Length: > 0 } sdk && sdk != "None" ? sdk : "2.9.5",
            properties.Value<bool?>("bOodlePreserveExtremes") ?? false, properties.Value<bool?>("bUseLegacyGamma") ?? false) {
                SourcePixelFormat = source?.Value<string>("Format") ?? "",
                Processing = TextureSource.TextureProcessingSettings.Read(properties, compression, groupName, source?.Value<string>("Format") ?? "") };
    }

    internal static TextureAddressMode VolumeAddress(SourceObject texture, string source) =>
        Address(texture.Properties.Value<string>("AddressMode") ?? "TA_Wrap", source);

    public static TextureSettings ReadCurveAtlas(SourceAsset asset, string assetPath)
    {
        SourceObject atlas = asset.ResolveObject(assetPath, value => value.ClassName == "CurveLinearColorAtlas");
        JObject source = atlas.Properties["Source"] as JObject
            ?? throw new ConversionException($"Curve atlas has no source image: {assetPath}");
        if (source.Value<string>("Format") != "TSF_RGBA16F")
            throw new ConversionException($"Curve atlas source must use TSF_RGBA16F: {assetPath}");
        if (source.Value<int?>("NumMips") != 1 || source.Value<int?>("NumSlices") != 1)
            throw new ConversionException($"Curve atlas source must be a single, non-array mip level: {assetPath}");

        TextureSettings settings = Read(asset, assetPath);
        JObject processing = new()
        {
            ["AdjustBrightness"] = 1,
            ["AdjustBrightnessCurve"] = 1,
            ["AdjustVibrance"] = 0,
            ["AdjustSaturation"] = 1,
            ["AdjustRGBCurve"] = 1,
            ["AdjustHue"] = 0,
            ["AdjustMinAlpha"] = 0,
            ["AdjustMaxAlpha"] = 1,
            ["hdr_source"] = true
        };
        return settings with
        {
            Srgb = false,
            MipMode = TextureMipMode.NoMipmaps,
            Sharpen = 0,
            KernelSize = 0,
            DownsampleWithAverage = false,
            MipGenSetting = "TMGS_NoMipmaps",
            Compression = "TC_HDR",
            MinMagFilter = TextureFilterMode.Linear,
            MipFilter = TextureFilterMode.Point,
            AddressX = TextureAddressMode.Clamp,
            AddressY = TextureAddressMode.Clamp,
            SourcePixelFormat = "TSF_RGBA16F",
            SourceMips = 1,
            Processing = processing
        };
    }

    private static (int Width, int Height) Dimensions(JObject tags, JObject properties, string assetPath)
    {
        string? dimensions = Tag(tags, "Dimensions");
        if (dimensions is not null)
        {
            string[] parts = dimensions.Split('*')[0].Split('x');
            if ((parts.Length == 2 || parts.Length == 3 && int.TryParse(parts[2], out int depth) && depth > 0)
                && int.TryParse(parts[0], out int width) && int.TryParse(parts[1], out int height))
                return (width, height);
        }
        JToken? imported = properties["ImportedSize"];
        int sizeX = imported is JArray importedArray && importedArray.Count >= 2
            ? (int)SourceAsset.Number(importedArray[0], 0)
            : (int)SourceAsset.Number(imported?["X"], 0);
        int sizeY = imported is JArray importedPair && importedPair.Count >= 2
            ? (int)SourceAsset.Number(importedPair[1], 0)
            : (int)SourceAsset.Number(imported?["Y"], 0);
        if (sizeX <= 0 || sizeY <= 0)
        {
            JToken? source = properties["Source"];
            sizeX = (int)SourceAsset.Number(source?["SizeX"], 0);
            sizeY = (int)SourceAsset.Number(source?["SizeY"], 0);
        }
        if (sizeX > 0 && sizeY > 0) return (sizeX, sizeY);
        throw new ConversionException($"Missing texture dimensions in {assetPath}");
    }

    private static (TextureMipMode Mode, double Sharpen, int KernelSize, bool Downsample) MipSettings(
        string setting, string assetPath)
    {
        if (setting == "TMGS_SimpleAverage") return (TextureMipMode.SimpleAverage, 0, 2, true);
        if (setting == "TMGS_NoMipmaps") return (TextureMipMode.NoMipmaps, 0, 2, true);
        if (setting.StartsWith("TMGS_Sharpen", StringComparison.Ordinal)
            && int.TryParse(setting["TMGS_Sharpen".Length..], out int sharpenIndex)
            && sharpenIndex is >= 0 and <= 10)
            return (TextureMipMode.Sharpen, sharpenIndex * 0.2, 8, true);
        if (setting is "TMGS_LeaveExistingMips" or "TMGS_Unfiltered" or "TMGS_Angular"
                or "TMGS_Blur1" or "TMGS_Blur2" or "TMGS_Blur3" or "TMGS_Blur4" or "TMGS_Blur5")
            return (TextureMipMode.NativeBuild, 0, 0, false);
        throw new ConversionException($"Unsupported MipGenSettings {setting} in {assetPath}");
    }

    private static (TextureFilterMode MinMag, TextureFilterMode Mip) FilterSettings(string filter, LodGroup group) =>
        filter.Split("::").Last() switch
        {
            "TF_Default" or "3" => (group.MinMag, group.MipFilter),
            "TF_Nearest" or "0" => (TextureFilterMode.Point, TextureFilterMode.Point),
            "TF_Bilinear" or "1" => (TextureFilterMode.Linear, TextureFilterMode.Point),
            "TF_Trilinear" or "2" => (TextureFilterMode.Linear, TextureFilterMode.Linear),
            _ => throw new ConversionException($"Unsupported texture filter {filter}")
        };

    internal static TextureAddressMode Address(string value, string assetPath) => value.Split("::").Last() switch
    {
        "TA_Wrap" or "0" => TextureAddressMode.Wrap,
        "TA_Clamp" or "1" => TextureAddressMode.Clamp,
        "TA_Mirror" or "2" => TextureAddressMode.Mirror,
        var unknown => throw new ConversionException($"Unsupported texture address {unknown} in {assetPath}")
    };

    private static string? Tag(JObject tags, string key) => tags.TryGetValue(key, out JToken? value)
        ? value.Type == JTokenType.Null ? null : value.Value<string>() : null;
}
