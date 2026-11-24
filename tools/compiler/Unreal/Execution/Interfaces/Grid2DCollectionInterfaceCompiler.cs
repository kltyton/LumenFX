using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class Grid2DCollectionInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceGrid2DCollection";
    internal const string ReaderClass = "NiagaraDataInterfaceGrid2DCollectionReader";
    private const string MainStreamVersion = "{697DD581-E64F-41AB-AA4A-51ECBEB7B628}";
    private const int DefaultUnnamedAttributesZero = 103;

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library, string type)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, type, library);
        var properties = source?.Object?.Properties;
        var bounds = properties?["WorldBBoxSize"];
        int version = source?.Object is not null ? RendererInfoSources.CustomVersion(source.Asset, MainStreamVersion) : int.MaxValue;
        bool reader = type == ReaderClass;
        return new JObject {
            ["cells"] = new JArray(properties?.Value<int?>("NumCellsX") ?? 3, properties?.Value<int?>("NumCellsY") ?? 3),
            ["max_axis"] = properties?.Value<int?>("NumCellsMaxAxis") ?? 3,
            ["size_from_max_axis"] = properties?.Value<bool?>("SetGridFromMaxAxis") ?? false,
            ["world_bounds"] = new JArray(SourceAsset.Number(bounds?["X"], 100), SourceAsset.Number(bounds?["Y"], 100)),
            ["unnamed_attributes"] = properties?.Value<int?>("NumAttributes") ?? (!reader && version < DefaultUnnamedAttributesZero ? 1 : 0),
            ["buffer_format"] = properties?.Value<bool?>("bOverrideFormat") == true
                ? Grid3DCollectionInterfaceCompiler.BufferFormat(properties.Value<string>("OverrideBufferFormat")) : "HalfFloat",
            ["clear_output"] = properties?.Value<bool?>("ClearBeforeNonIterationStage") ?? true,
            ["output_binding"] = properties?["RenderTargetUserParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["reader"] = reader,
            ["reader_emitter"] = properties?.Value<string>("EmitterName") ?? "",
            ["reader_interface"] = properties?.Value<string>("DIName") ?? ""
        };
    }

    internal static JObject CpuFunction(JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        var shape = name switch {
            "GetNumCells" or "GetWorldBBoxSize" or "GetCellSize" => (0, 2),
            "SetNumCells" => (3, 1),
            "GetVector4AttributeIndex" or "GetVectorAttributeIndex" or "GetVector2DAttributeIndex" or "GetFloatAttributeIndex" => (0, 1),
            _ => throw new ConversionException($"Unknown Niagara Grid2D Collection CPU function {name}")
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Grid2D Collection signature: {name} ({inputs}, {outputs})");
        if (inputs != 0 || outputs != 1) return new JObject();
        return new JObject { ["attribute"] = GridAttributeSpecifier.Read(function["FunctionSpecifiers"], name) };
    }

    internal static JArray GpuFunctions(JObject shaderInterface)
    {
        var result = new JArray();
        foreach (var function in (shaderInterface["GeneratedFunctions"] as JArray ?? new()).OfType<JObject>()) {
            string name = function.Value<string>("DefinitionName") ?? "";
            int indexWidth = name switch {
                "GetVector4AttributeIndex" => 4, "GetVectorAttributeIndex" => 3,
                "GetVector2DAttributeIndex" => 2, "GetFloatAttributeIndex" => 1, _ => -1
            };
            int storageWidth = AttributeWidth(name);
            string attribute = Math.Max(indexWidth, storageWidth) > 0
                ? GridAttributeSpecifier.Read(function["Specifiers"], name) : "";
            result.Add(new JObject { ["attribute"] = attribute,
                ["components"] = name.StartsWith("CubicSample", StringComparison.Ordinal) ? -1 : Math.Max(indexWidth, storageWidth),
                ["storage_components"] = storageWidth, ["defines_attribute"] = storageWidth > 0 });
        }
        return result;
    }

    private static int AttributeWidth(string name) => name switch {
        "SetVector4Value" or "GetVector4Value" or "SampleGridVector4Value" or "GetPreviousVector4Value"
            or "SamplePreviousGridVector4Value" or "CubicSamplePreviousGridVector4Value" => 4,
        "SetVector3Value" or "SetVectorValue" or "GetVector3Value" or "SampleGridVector3Value" or "GetPreviousVectorValue"
            or "SamplePreviousGridVector3Value" or "CubicSamplePreviousGridVector3Value" => 3,
        "SetVector2Value" or "SetVector2DValue" or "GetVector2Value" or "SampleGridVector2Value" or "GetPreviousVector2DValue"
            or "SamplePreviousGridVector2DValue" or "CubicSamplePreviousGridVector2DValue" => 2,
        "SetFloatValue" or "GetFloatValue" or "SampleGridFloatValue" or "GetPreviousFloatValue"
            or "SamplePreviousGridFloatValue" or "CubicSamplePreviousGridFloatValue" => 1,
        _ => -1
    };
}
