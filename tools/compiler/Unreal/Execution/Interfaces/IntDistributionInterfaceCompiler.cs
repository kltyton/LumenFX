using System.Buffers.Binary;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class IntDistributionInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceArrayDistributionInt";

    internal static JObject Definition(SourceAsset asset, int reference, AssetLibrary? library)
    {
        JObject source = MutableDataInterfaceInstancesCompiler.ReadProperties(asset, reference, ClassName, library);
        if (source["BuiltTableData"] is JArray { Count: > 0 } data) {
            byte[] bytes = data.Values<byte>().ToArray();
            if (bytes.Length % 16 != 0) throw new ConversionException("Invalid cooked Niagara integer distribution table");
            var words = new JArray();
            for (int offset = 0; offset < bytes.Length; offset += 4)
                words.Add(BinaryPrimitives.ReadInt32LittleEndian(bytes.AsSpan(offset, 4)));
            return new JObject { ["cooked_words"] = words };
        }
        var entries = new JArray();
        foreach (var entry in (source["ArrayData"] as JArray ?? new()).OfType<JObject>())
            entries.Add(new JArray(entry.Value<int?>("Value") ?? 0, SourceAsset.Number(entry["Weight"], 1)));
        return new JObject { ["entries"] = entries };
    }

    internal static void ValidateCpu(string function, int inputs, int outputs)
    {
        var shape = function switch {
            "Length" or "LastIndex" => (0, 1),
            "IsValidIndex" => (1, 1),
            "Get" or "GetProbabilityAlias" => (1, 2),
            "GetRandomValue" => (3, 1),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs)) throw new ConversionException($"Invalid Niagara integer distribution function {function} ({inputs}, {outputs})");
    }
}
