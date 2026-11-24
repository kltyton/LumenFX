using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class VirtualTextureInterfaceCompiler
{
    internal const string RuntimeClass = "NiagaraDataInterfaceVirtualTexture";
    internal const string StreamingClass = "NiagaraDataInterfaceVirtualTextureSample";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library, string type)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, type, library);
        var asset = source?.Asset ?? owner;
        var properties = source?.Object?.Properties ?? new JObject();
        return new JObject {
            ["runtime"] = type == RuntimeClass,
            ["texture"] = ObjectPropertyReaderCompiler.ObjectPath(asset, properties["Texture"]),
            ["parameter"] = properties["TextureUserParameter"]?["Parameter"]?.Value<string>("name") ?? ""
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "GetTextureDimensions" => (1, 2),
            "GetNumMipLevels" => (0, 1),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Virtual Texture Sample CPU function {name} ({inputs}, {outputs})");
    }
}
