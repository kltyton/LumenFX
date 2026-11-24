using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class RenderTarget2DDataInterfaceCompiler
{
    internal const string ArrayClass = "NiagaraDataInterfaceRenderTarget2DArray";
    internal const string VolumeClass = "NiagaraDataInterfaceRenderTargetVolume";
    internal const string CubeClass = "NiagaraDataInterfaceRenderTargetCube";

    internal static JObject CompileArray(SourceAsset owner, int reference, AssetLibrary? library,
        SourceAsset? systemAsset = null, SourceObject? system = null)
        => CompileDimensional(owner, reference, library, systemAsset, system, ArrayClass);

    internal static JObject CompileVolume(SourceAsset owner, int reference, AssetLibrary? library,
        SourceAsset? systemAsset = null, SourceObject? system = null)
        => CompileDimensional(owner, reference, library, systemAsset, system, VolumeClass);

    internal static JObject CompileCube(SourceAsset owner, int reference, AssetLibrary? library,
        SourceAsset? systemAsset = null, SourceObject? system = null)
        => CompileDimensional(owner, reference, library, systemAsset, system, CubeClass);

    private static JObject CompileDimensional(SourceAsset owner, int reference, AssetLibrary? library,
        SourceAsset? systemAsset, SourceObject? system, string interfaceClass)
    {
        bool volume = interfaceClass == VolumeClass, cube = interfaceClass == CubeClass;
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, interfaceClass, library);
        JObject properties = source?.Object?.Properties ?? new();
        JObject result = Definition(properties, source?.Object?.Name ?? interfaceClass);
        result["layers"] = cube ? 6 : properties["Size"]?.Value<int?>("Z") ?? 0;
        if (volume) result["volume"] = true;
        if (cube) {
            int size = properties.Value<int?>("Size") ?? 0;
            result["width"] = size;
            result["height"] = size;
            result["cube"] = true;
            result["sampler"]!["address_x"] = "clamp";
            result["sampler"]!["address_y"] = "clamp";
        }
        result.Remove("mip_generation");
        result.Remove("mip_generation_type");
        string name = result.Value<string>("user_parameter") ?? "None";
        if (name.Length != 0 && name != "None" && systemAsset is not null && system is not null
            && TextureDataInterfaceCompiler.UserObjectReference(systemAsset, system, name) is int selected) {
            string expected = cube ? "TextureRenderTargetCube" : volume ? "TextureRenderTargetVolume" : "TextureRenderTarget2DArray";
            result["source_user_texture"] = systemAsset.ReferencedClass(selected) == expected
                ? systemAsset.ObjectPath(selected) : JValue.CreateNull();
        }
        return result;
    }

    internal static void ValidateDimensionalCpu(string name, int inputs, int outputs)
    {
        bool valid = name switch {
            "GetRenderTargetSize" => inputs == 0 && outputs == 3,
            "SetRenderTargetSize" => inputs == 3 && outputs == 1,
            "GetNumMipLevels" => inputs == 0 && outputs == 1,
            "SetRenderTargetFormat" => inputs == 1 && outputs == 1,
            _ => false
        };
        if (!valid) throw new ConversionException($"Invalid dimensional render target CPU signature {name}: {inputs} inputs, {outputs} outputs");
    }

    internal static void ValidateCubeCpu(string name, int inputs, int outputs)
    {
        bool valid = name switch {
            "GetRenderTargetSize" or "GetNumMipLevels" => inputs == 0 && outputs == 1,
            "SetRenderTargetSize" or "SetRenderTargetFormat" => inputs == 1 && outputs == 1,
            _ => false
        };
        if (!valid) throw new ConversionException($"Invalid cube render target CPU signature {name}: {inputs} inputs, {outputs} outputs");
    }

    internal static JObject Definition(SourceObject data)
    {
        return Definition(data.Properties, data.Name);
    }

    internal static JObject CompileDefaults(SourceAsset asset, int reference, AssetLibrary? library)
    {
        var source = DataInterfaceInstanceSources.ResolveSource(asset, reference, "NiagaraDataInterfaceRenderTarget2D", library ?? asset.Library);
        return Definition(source.Object?.Properties ?? new JObject(), source.Object?.Name ?? asset.ObjectPath(reference));
    }

    private static JObject Definition(JObject properties, string name)
    {
        JObject sampling = (JObject)properties.DeepClone();
        sampling["Filter"] = properties["OverrideRenderTargetFilter"]?.DeepClone() ?? "TF_Default";
        return new JObject {
            ["width"] = (properties["Size"] as JObject)?.Value<int?>("X") ?? 0,
            ["height"] = (properties["Size"] as JObject)?.Value<int?>("Y") ?? 0,
            ["mip_generation"] = Enum(properties, "MipMapGeneration", "Disabled"),
            ["mip_generation_type"] = Enum(properties, "MipMapGenerationType", "Linear"),
            ["override_format"] = properties.Value<bool?>("bOverrideFormat") ?? false,
            ["format"] = Enum(properties, "OverrideRenderTargetFormat", "RTF_R8"),
            ["filter"] = Enum(properties, "OverrideRenderTargetFilter", "TF_Default"),
            ["inherit_user_settings"] = properties.Value<bool?>("bInheritUserParameterSettings") ?? false,
            ["user_parameter"] = properties["RenderTargetUserParameter"]?["Parameter"]?.Value<string>("name") ?? "None",
            ["sampler"] = Textures.ReadSampler(sampling, "TEXTUREGROUP_RenderTarget", name)
        };
    }

    internal static JObject Compile(SourceAsset asset, SourceObject? data, SourceObject system,
        AssetLibrary? library, string? sourceReference = null)
    {
        SourceAsset owner = asset;
        if (data is null && sourceReference is not null && !sourceReference.EndsWith(".Default__NiagaraDataInterfaceRenderTarget2D", StringComparison.Ordinal)) {
            if (library is null) throw new ConversionException($"External render-target interface requires its source library: {sourceReference}");
            owner = library.Load(sourceReference);
            data = owner.ResolveObject(sourceReference, value => value.ClassName == "NiagaraDataInterfaceRenderTarget2D");
        }
        JObject properties = data?.Properties ?? new JObject();
        JObject result = Definition(properties, sourceReference ?? data?.Name ?? "NiagaraDataInterfaceRenderTarget2D");
        string name = result.Value<string>("user_parameter") ?? "None";
        if (name.Length == 0 || name == "None") return result;
        int? reference = TextureDataInterfaceCompiler.UserObjectReference(asset, system, name);
        if (reference is int value && value != 0 && TextureObjectTypes.IsKnownNonTexture(asset.ReferencedClass(value))) reference = 0;
        if (reference.HasValue && reference.Value != 0 && asset.ReferencedClass(reference.Value) is
                "TextureRenderTargetCube" or "TextureRenderTargetVolume" or "TextureRenderTarget2DArray") reference = 0;
        if (reference.HasValue) result["source_user_texture"] = reference == 0 ? JValue.CreateNull() : asset.ObjectPath(reference.Value);
        return result;
    }

    internal static JObject CompileCpu(SourceObject data, string name, int inputs, int outputs)
    {
        ValidateCpu(name, inputs, outputs);
        return Definition(data);
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        bool valid = name switch {
            "GetRenderTargetSize" => inputs == 0 && outputs == 2,
            "SetRenderTargetSize" => inputs == 3 && outputs == 1,
            "ReleaseResources" => inputs == 1 && outputs == 0,
            "GetNumMipLevels" => inputs == 0 && outputs == 1,
            "SetRenderTargetFormat" => inputs == 1 && outputs == 1,
            _ => false
        };
        if (!valid) throw new ConversionException($"Invalid RenderTarget2D CPU signature {name}: {inputs} inputs, {outputs} outputs");
    }

    private static string Enum(JObject properties, string field, string fallback)
    {
        string value = (properties.Value<string>(field) ?? fallback).Split("::").Last();
        if (!int.TryParse(value, out int ordinal)) return value;
        string[] names = field switch {
            "MipMapGeneration" => new[] { "Disabled", "PostStage", "PostSimulate" },
            "MipMapGenerationType" => new[] { "Unfiltered", "Linear", "Blur1", "Blur2", "Blur3", "Blur4" },
            "OverrideRenderTargetFilter" => new[] { "TF_Nearest", "TF_Bilinear", "TF_Trilinear", "TF_Default" },
            "OverrideRenderTargetFormat" => new[] { "RTF_R8", "RTF_RG8", "RTF_RGBA8", "RTF_RGBA8_SRGB", "RTF_R16f", "RTF_RG16f",
                "RTF_RGBA16f", "RTF_R32f", "RTF_RG32f", "RTF_RGBA32f", "RTF_RGB10A2" },
            _ => throw new ConversionException($"Unmapped RenderTarget2D enum {field}")
        };
        if (ordinal < 0 || ordinal >= names.Length) throw new ConversionException($"Invalid RenderTarget2D {field}: {ordinal}");
        return names[ordinal];
    }
}
