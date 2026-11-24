using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class IntRenderTarget2DInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceIntRenderTarget2D";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        var size = properties?["Size"];
        return new JObject {
            ["width"] = size?.Value<int?>("X") ?? 0,
            ["height"] = size?.Value<int?>("Y") ?? 0,
            ["user_parameter"] = properties?["RenderTargetUserParameter"]?["Parameter"]?.Value<string>("name") ?? ""
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        if (name == "GetRenderTargetSize" && inputs == 0 && outputs == 2
                || name == "SetRenderTargetSize" && inputs == 2 && outputs == 1) return;
        throw new ConversionException($"Invalid Niagara Int Render Target 2D function {name} ({inputs}, {outputs})");
    }
}
