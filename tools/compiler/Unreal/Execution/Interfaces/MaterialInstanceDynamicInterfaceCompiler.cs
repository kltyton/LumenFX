using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class MaterialInstanceDynamicInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceMaterialInstanceDynamic";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var data = source?.Object?.Properties;
        var asset = source?.Asset ?? owner;
        JToken? defaultObject = data?["DefaultMaterialInst"];
        string defaultPath = ObjectPropertyReaderCompiler.ObjectPath(asset, defaultObject);
        int index = defaultObject?.Type == JTokenType.Integer ? defaultObject.Value<int>() : 0;
        if (index != 0 && asset.ReferencedClass(index) != "MaterialInstanceDynamic")
            defaultPath = "";
        return new JObject {
            ["binding"] = data?["InstancedMaterialParamBinding"]?["ResolvedParameter"]?.Value<string>("name") ?? "",
            ["default"] = defaultPath
        };
    }

    internal static JObject CpuFunction(JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        int width = name switch { "SetScalarParameter" => 1, "SetVector4Parameter" => 4, _ => 0 };
        if (width == 0 || inputs != width || outputs != 0)
            throw new ConversionException($"Invalid Niagara MID signature: {name} ({inputs}, {outputs})");
        string? parameter = (function["FunctionSpecifiers"] as JArray)?.OfType<JObject>().FirstOrDefault()?.Value<string>("Value");
        return new JObject { ["parameter"] = parameter ?? throw new ConversionException($"Missing Niagara MID parameter name: {name}") };
    }
}
