using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class RenderTargetTextureCompiler
{
    private static readonly string[] Formats = { "PF_G8", "PF_R8G8", "PF_B8G8R8A8", "PF_B8G8R8A8", "PF_R16F",
        "PF_G16R16F", "PF_FloatRGBA", "PF_R32_FLOAT", "PF_G32R32F", "PF_A32B32G32R32F", "PF_A2B10G10R10" };
    private static readonly string[] Names = { "RTF_R8", "RTF_RG8", "RTF_RGBA8", "RTF_RGBA8_SRGB", "RTF_R16f",
        "RTF_RG16f", "RTF_RGBA16f", "RTF_R32f", "RTF_RG32f", "RTF_RGBA32f", "RTF_RGB10A2" };

    internal static JObject Compile(SourceObject source, string path)
    {
        JObject data = source.Properties;
        bool array = source.ClassName == "TextureRenderTarget2DArray";
        bool cube = source.ClassName == "TextureRenderTargetCube";
        bool volume = source.ClassName == "TextureRenderTargetVolume";
        bool dimensional = array || cube || volume;
        int width = data.Value<int?>("SizeX") ?? 1, height = data.Value<int?>("SizeY") ?? 1;
        if (cube) height = width;
        if (width < 1 || height < 1) throw new ConversionException($"Invalid render target dimensions: {path}");
        string declared = (data.Value<string>("RenderTargetFormat") ?? "RTF_RGBA16f").Split("::").Last();
        int index = dimensional ? ((data.Value<bool?>("bHDR") ?? true) ? 6 : 2)
            : int.TryParse(declared, out int ordinal) ? ordinal : Array.IndexOf(Names, declared);
        if (index < 0 || index >= Names.Length) throw new ConversionException($"Unknown render target format {declared}: {path}");
        string forced = (data.Value<string>("OverrideFormat") ?? "PF_Unknown").Split("::").Last();
        if (forced == "0") forced = "PF_Unknown";
        string format = forced == "PF_Unknown" ? Formats[index] : forced;
        string gpuFormat = format switch {
            "PF_G8" or "PF_R8" => "R8_UNORM", "PF_R8G8" => "RG8_UNORM",
            "PF_B8G8R8A8" or "PF_R8G8B8A8" => "RGBA8_UNORM", "PF_R16F" => "R16_FLOAT",
            "PF_FloatRGB" => "RG11B10_FLOAT",
            "PF_G16R16F" => "RG16_FLOAT", "PF_FloatRGBA" => "RGBA16_FLOAT", "PF_R32_FLOAT" => "R32_FLOAT",
            "PF_G32R32F" => "RG32_FLOAT", "PF_A32B32G32R32F" => "RGBA32_FLOAT", "PF_A2B10G10R10" => "RGB10A2_UNORM",
            _ => throw new ConversionException($"Texture2D sampling requires a supported floating color render target: {format} ({path})") };
        bool srgb = forced == "PF_Unknown" ? index == 3 : !(data.Value<bool?>("bForceLinearGamma") ?? true);
        JObject sampling = (JObject)data.DeepClone();
        if (dimensional) {
            float gamma = (float)SourceAsset.Number(data["TargetGamma"]);
            bool linearFormat = format == "PF_FloatRGBA" || format == (volume ? "PF_R32_FLOAT" : "PF_FloatRGB");
            srgb = gamma > .001f ? Math.Abs(gamma - 1f) >= .0001f
                : !linearFormat && !(data.Value<bool?>("bForceLinearGamma") ?? true);
            sampling["AddressX"] = cube ? "TA_Clamp" : "TA_Wrap";
            sampling["AddressY"] = cube ? "TA_Clamp" : "TA_Wrap";
        }
        JObject? clear = data["ClearColor"] as JObject;
        JObject defaultSampling = (JObject)sampling.DeepClone();
        defaultSampling["Filter"] = "TF_Default";
        var result = new JObject { ["format_version"] = 1, ["asset"] = path, ["width"] = width, ["height"] = height,
            ["format"] = gpuFormat, ["source_format"] = format, ["srgb"] = srgb,
            ["target_format"] = index, ["override_format"] = forced != "PF_Unknown",
            ["storage_writable"] = data.Value<bool?>(dimensional ? "bSupportsUAV" : "bCanCreateUAV") ?? false,
            ["filter"] = (data.Value<string>("Filter") ?? "TF_Default").Split("::").Last(),
            ["auto_mips"] = !array && !volume && (data.Value<bool?>("bAutoGenerateMips") ?? false),
            ["clear"] = new JArray(SourceAsset.Number(clear?["R"]), SourceAsset.Number(clear?["G"], dimensional ? 1 : 0),
                SourceAsset.Number(clear?["B"]), SourceAsset.Number(clear?["A"], 1)),
            ["sampler"] = Textures.ReadSampler(sampling, "TEXTUREGROUP_RenderTarget", path),
            ["default_sampler"] = Textures.ReadSampler(defaultSampling, "TEXTUREGROUP_RenderTarget", path),
            ["mip_filter"] = (data.Value<string>("MipsSamplerFilter") ?? "TF_Default").Split("::").Last() switch {
                "TF_Nearest" or "0" => "point", "TF_Default" or "TF_Bilinear" or "TF_Trilinear" or "1" or "2" or "3" => "linear",
                var filter => throw new ConversionException($"Unknown render-target mip sampler filter {filter}: {path}") },
            ["mip_address_x"] = Address(data.Value<string>("MipsAddressU") ?? "TA_Clamp"),
            ["mip_address_y"] = Address(data.Value<string>("MipsAddressV") ?? "TA_Clamp") };
        if (dimensional) {
            result["target_gamma"] = SourceAsset.Number(data["TargetGamma"]);
            result["force_linear_gamma"] = data.Value<bool?>("bForceLinearGamma") ?? true;
        }
        if (array) {
            int layers = data.Value<int?>("Slices") ?? 0;
            if (layers < 1) throw new ConversionException($"Invalid render target array slices: {path}");
            result["array_layers"] = layers;
            result["clear_all_slices"] = data.Value<bool?>("bTargetArraySlicesIndependently") ?? false;
        }
        if (volume) {
            int depth = data.Value<int?>("SizeZ") ?? 0;
            if (depth < 1) throw new ConversionException($"Invalid render target volume depth: {path}");
            result["depth"] = depth;
        }
        return result;
    }

    private static string Address(string value) => value.Split("::").Last() switch {
        "TA_Wrap" or "0" => "wrap", "TA_Clamp" or "1" => "clamp", "TA_Mirror" or "2" => "mirror",
        var address => throw new ConversionException($"Unknown render-target mip address {address}") };
}
