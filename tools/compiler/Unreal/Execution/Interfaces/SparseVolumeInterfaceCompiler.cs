using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class SparseVolumeInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceSparseVolumeTexture";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        var parameter = properties?["SparseVolumeTextureUserParameter"]?["Parameter"];
        return new JObject {
            ["asset_source"] = ObjectPropertyReaderCompiler.ObjectPath(source?.Asset ?? owner, properties?["SparseVolumeTexture"]),
            ["user_parameter"] = parameter?.Value<string>("name") ?? parameter?.Value<string>("Name") ?? "",
            ["blocking"] = properties?.Value<bool?>("BlockingStreamingRequests") ?? true
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var expected = name switch {
            "GetSparseVolumeTextureDimensions" => (1, 3),
            "GetSparseVolumeTextureNumMipLevels" or "GetSparseVolumeTextureNumFrames" => (0, 1),
            "RequestSparseVolumeTextureFrame" => (2, 1),
            _ => throw new ConversionException($"Niagara sparse volume function is GPU-only or unknown: {name}")
        };
        if (expected != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara sparse volume signature: {name} ({inputs}, {outputs})");
    }
}
