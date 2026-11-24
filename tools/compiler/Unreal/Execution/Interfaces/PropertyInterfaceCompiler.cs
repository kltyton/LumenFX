using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Types;
using Newtonsoft.Json.Linq;
using System.Text.RegularExpressions;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class PropertyInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfacePropertyInterface";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var properties = reference == 0 ? null
            : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library)?.Object?.Properties;
        return new JObject {
            ["path"] = (properties?.Value<string>("InterfacePath") ?? "None") + "/"
                + (properties?.Value<string>("InterfaceName") ?? "None"),
            ["package"] = properties?.Value<string>("InterfacePackage") ?? "None"
        };
    }

    internal static JArray CpuOutputs(SourceAsset asset, JObject function, int inputs, int outputs)
    {
        var fields = Outputs(asset, function).ToArray();
        if (function.Value<string>("Name") != "GetInterfaceData" || inputs != 0
                || fields.Sum(field => field.Value<int>("width")) != outputs)
            throw new ConversionException($"Invalid Property Interface CPU function ({inputs}, {outputs})");
        return new JArray(fields);
    }

    internal static JArray GpuOutputs(SourceAsset asset, JObject shaderInterface)
    {
        var outputs = new JArray();
        var types = new Dictionary<string, HashSet<string>>(SourceNameOrder.Instance);
        foreach (var function in (shaderInterface["GeneratedFunctions"] as JArray ?? new()).OfType<JObject>()) {
            foreach (var field in Outputs(asset, function)) {
                string name = field.Value<string>("name")!;
                if (!types.TryGetValue(name, out var paths))
                    types.Add(name, paths = new HashSet<string>(SourceNameOrder.Instance));
                if (paths.Add(field.Value<string>("type")!)) outputs.Add(field);
            }
        }
        return outputs;
    }

    private static IEnumerable<JObject> Outputs(SourceAsset asset, JObject function)
    {
        foreach (var item in (function["VariadicOutputs"] as JArray ?? new()).OfType<JObject>()) {
            var field = ExecutionNumericParameterSource.Read(asset, item);
            int size = field.Type.Size ?? (BuiltinStructLayouts.TryGet(field.Type.Path, out var layout) ? layout!.Size : 0);
            if (size < 4 || size % 4 != 0)
                throw new ConversionException($"Property Interface output is not word-aligned: {field.Name}");
            yield return new JObject { ["name"] = field.Name, ["type"] = field.Type.Path, ["width"] = size / 4 };
        }
    }

    // The fixed generator passes an attribute ordinal to a ByteAddressBuffer.Load helper.
    // Resolve that ordinal through the existing shader-storage offset table before the load.
    internal static string ResolveGpuReadOffsets(string hlsl, string symbol, int count)
    {
        string invocation = @"\bReadValue_" + Regex.Escape(symbol) + @"\s*\(\s*(?<index>[0-9]+)\s*,";
        return Regex.Replace(hlsl, invocation, match => {
            int index = int.Parse(match.Groups["index"].Value, System.Globalization.CultureInfo.InvariantCulture);
            if (index >= count)
                throw new ConversionException($"Property Interface shader ordinal {index} has no attribute for {symbol}");
            return $"ReadValue_{symbol}({symbol}_AttributeReadOffset[{index / 4}][{index % 4}],";
        });
    }
}
