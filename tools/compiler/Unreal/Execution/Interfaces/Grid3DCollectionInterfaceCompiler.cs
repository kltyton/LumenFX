using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class Grid3DCollectionInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceGrid3DCollection";
    internal const string ReaderClass = "NiagaraDataInterfaceGrid3DCollectionReader";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library, string type)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, type, library);
        var properties = source?.Object?.Properties;
        var cells = properties?["NumCells"];
        var bounds = properties?["WorldBBoxSize"];
        return new JObject {
            ["resolution"] = Resolution(properties?.Value<string>("SetResolutionMethod")),
            ["cells"] = new JArray(cells?.Value<int?>("X") ?? 3, cells?.Value<int?>("Y") ?? 3, cells?.Value<int?>("Z") ?? 3),
            ["cell_size"] = SourceAsset.Number(properties?["CellSize"], 1),
            ["max_axis"] = properties?.Value<int?>("NumCellsMaxAxis") ?? 10,
            ["unnamed_attributes"] = properties?.Value<int?>("NumAttributes") ?? 0,
            ["buffer_format"] = properties?.Value<bool?>("bOverrideFormat") == true
                ? BufferFormat(properties.Value<string>("OverrideBufferFormat")) : "HalfFloat",
            ["clear_output"] = properties?.Value<bool?>("ClearBeforeNonIterationStage") ?? true,
            ["output_binding"] = properties?["RenderTargetUserParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["reader"] = type == ReaderClass,
            ["reader_emitter"] = properties?.Value<string>("EmitterName") ?? "",
            ["reader_interface"] = properties?.Value<string>("DIName") ?? "",
            ["world_bounds"] = new JArray(SourceAsset.Number(bounds?["X"], 100), SourceAsset.Number(bounds?["Y"], 100),
                SourceAsset.Number(bounds?["Z"], 100))
        };
    }

    private static string Resolution(string? value) => value?.Split("::").Last() switch {
        null or "0" => "Independent", "1" => "MaxAxis", "2" => "CellSize", string name => name
    };

    internal static string BufferFormat(string? value) => value?.Split("::").Last() switch {
        null or "0" => "Float", "1" => "HalfFloat", "2" => "UnsignedNormalizedByte", string name => name
    };

    internal static JObject CpuFunction(JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        var shape = name switch {
            "GetNumCells" or "GetWorldBBoxSize" or "GetCellSize" => (0, 3),
            "UnitToFloatIndex" => (3, 3),
            "SetNumCells" => (3, 1),
            "GetVector4AttributeIndex" or "GetVectorAttributeIndex" or "GetVector2DAttributeIndex" or "GetFloatAttributeIndex" => (0, 1),
            _ => throw new ConversionException($"Unknown Niagara Grid3D Collection CPU function {name}")
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Grid3D Collection signature: {name} ({inputs}, {outputs})");
        if (outputs != 1 || inputs != 0) return new JObject();
        return new JObject { ["attribute"] = GridAttributeSpecifier.Read(function["FunctionSpecifiers"], name) };
    }

    internal static JArray GpuFunctions(JObject shaderInterface)
    {
        var result = new JArray();
        foreach (var function in (shaderInterface["GeneratedFunctions"] as JArray ?? new()).OfType<JObject>()) {
            string name = function.Value<string>("DefinitionName") ?? "";
            int components = AttributeComponents(name);
            string attribute = components > 0 && !name.EndsWith("AtIndex", StringComparison.Ordinal)
                ? GridAttributeSpecifier.Read(function["Specifiers"], name) : "";
            result.Add(new JObject { ["attribute"] = attribute, ["components"] = components,
                ["defines_attribute"] = components > 0 && !name.EndsWith("AttributeIndex", StringComparison.Ordinal) });
        }
        return result;
    }

    private static int AttributeComponents(string name) => name switch {
        "SetVector4Value" or "GetPreviousVector4Value" or "SamplePreviousGridVector4Value" or "CubicSamplePreviousGridVector4Value"
            or "SetVector4ValueAtIndex" or "GetPreviousVector4ValueAtIndex" or "SamplePreviousGridVector4AtIndex"
            or "CubicSamplePreviousGridVector4AtIndex" or "GetOutputGridVector4Value" or "GetVector4AttributeIndex" => 4,
        "SetVector3Value" or "SetVectorValue" or "GetPreviousVectorValue" or "SamplePreviousGridVector3Value" or "CubicSamplePreviousGridVector3Value"
            or "SetVector3ValueAtIndex" or "GetPreviousVector3ValueAtIndex" or "SamplePreviousGridVector3AtIndex"
            or "CubicSamplePreviousGridVector3AtIndex" or "GetOutputGridVectorValue" or "GetVectorAttributeIndex" => 3,
        "SetVector2Value" or "SetVector2DValue" or "GetPreviousVector2DValue" or "SamplePreviousGridVector2DValue" or "CubicSamplePreviousGridVector2DValue"
            or "SetVector2ValueAtIndex" or "GetPreviousVector2ValueAtIndex" or "SamplePreviousGridVector2AtIndex"
            or "CubicSamplePreviousGridVector2AtIndex" or "GetOutputGridVector2Value" or "GetVector2DAttributeIndex" => 2,
        "SetFloatValue" or "GetPreviousFloatValue" or "SamplePreviousGridFloatValue" or "CubicSamplePreviousGridFloatValue"
            or "SetFloatValueAtIndex" or "GetPreviousFloatValueAtIndex" or "SamplePreviousGridFloatAtIndex"
            or "CubicSamplePreviousGridFloatAtIndex" or "GetOutputGridFloatValue" or "GetFloatAttributeIndex" => 1,
        _ => -1
    };
}
