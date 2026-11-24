using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Types;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class DataTableInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceDataTable";
    internal const string AssetClass = "DataTable";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties;
        int table = properties?.Value<int?>("DataTable") ?? 0;
        return new JObject {
            ["binding"] = properties?["ObjectParameterBinding"]?["Parameter"]?.Value<string>("name") ?? "",
            ["filter_rows"] = properties?["FilteredRowNames"]?.DeepClone() ?? new JArray(),
            ["filtered_table"] = properties?.Value<bool?>("bCreateFilteredTable") ?? true,
            ["default"] = table == 0 ? null : Table(source!.Asset, table, library)
        };
    }

    internal static JObject CpuFunction(SourceAsset asset, JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        bool read = name is "GetRow" or "GetFilteredRow";
        var columns = Columns(asset, function);
        int width = 1 + columns.OfType<JObject>().Sum(column => column.Value<int>("width"));
        if (name is not ("IsValid" or "GetNumRows" or "GetNumFilteredRows" or "GetRow" or "GetFilteredRow")
                || inputs != (read ? 1 : 0) || outputs != width || (!read && columns.Count != 0))
            throw new ConversionException($"Invalid Niagara Data Table function {name} ({inputs}, {outputs})");
        return new JObject { ["columns"] = columns };
    }

    internal static JArray GpuColumns(SourceAsset asset, JObject shaderInterface)
    {
        var result = new JArray();
        var types = new Dictionary<string, HashSet<string>>(SourceNameOrder.Instance);
        foreach (var function in (shaderInterface["GeneratedFunctions"] as JArray ?? new()).OfType<JObject>())
            foreach (var column in Columns(asset, function).OfType<JObject>()) {
                string name = column.Value<string>("name")!;
                if (!types.TryGetValue(name, out var paths))
                    types.Add(name, paths = new HashSet<string>(SourceNameOrder.Instance));
                if (paths.Add(column.Value<string>("type")!)) result.Add(column.DeepClone());
            }
        return result;
    }

    private static JArray Columns(SourceAsset asset, JObject function)
    {
        var columns = new JArray();
        foreach (var item in (function["VariadicOutputs"] as JArray ?? new()).OfType<JObject>()) {
            var field = ExecutionNumericParameterSource.Read(asset, item);
            int bytes = field.Type.Size ?? (BuiltinStructLayouts.TryGet(field.Type.Path, out var layout) ? layout!.Size : 0);
            if (bytes < 4 || bytes % 4 != 0)
                throw new ConversionException($"Niagara Data Table output is not word-aligned: {field.Name}");
            columns.Add(new JObject { ["name"] = field.Name, ["type"] = field.Type.Path, ["width"] = bytes / 4 });
        }
        return columns;
    }

    private readonly record struct Shape(string Type, string[] Components, bool Integer = false)
    {
        internal int Width => Components.Length == 0 ? 1 : Components.Length;
    }
    private sealed record Field(string SerializedName, string Name, Shape Shape);
    private static readonly Shape Int = new("/Script/Niagara.NiagaraInt32", [], true);
    private static readonly Shape Float = new("/Script/Niagara.NiagaraFloat", []);

    internal static JObject Table(SourceAsset owner, int reference, AssetLibrary? library)
    {
        string path = owner.ObjectPath(reference);
        if ((library ?? owner.Library)?.NativeDataTable is { } nativeTable)
            return nativeTable(path);
        SourceAsset asset = reference > 0 ? owner : (library ?? owner.Library)?.Load(path)
            ?? throw new ConversionException($"Niagara Data Table requires its source library: {path}");
        SourceObject table = reference > 0 ? owner.Object(reference) : asset.ResolveObject(path, value => value.ClassName == AssetClass);
        int rowType = table.Properties.Value<int?>("RowStruct") ?? 0;
        var rows = table.Raw["Table"]?["Data"] as JArray
            ?? throw new ConversionException($"Niagara Data Table rows were not decoded: {path}");
        var schema = Schema(asset, rowType, library);
        bool userDefined = schema?.Structure.ClassName == "UserDefinedStruct";
        var fields = new Dictionary<string, Field>(SourceNameOrder.Instance);
        var defaults = new Dictionary<string, JToken>(SourceNameOrder.Instance);
        if (schema is { } resolved) {
            foreach (var field in StructFieldSource.Read(resolved.Asset, resolved.Structure) ?? []) {
                string name = field.Value<string>("Name") ?? throw new ConversionException($"Missing Data Table column in {path}");
                string kind = field.Value<string>("SerializedType") ?? "";
                string nested = kind == "StructProperty" ? resolved.Asset.Reference(field.Value<int>("Struct")).Split('.').Last() : "";
                if (ShapeFor(kind, nested) is { } shape)
                    fields.TryAdd(name, new Field(name, AuthoredFieldName(name, userDefined, field.Value<string>("DisplayName")), shape));
            }
            foreach (var property in (resolved.Structure.Raw["StructData"] as JArray ?? new()).OfType<JObject>()) {
                string name = property.Value<string>("Name") ?? "";
                if (!defaults.ContainsKey(name)) defaults.Add(name, SourceAsset.Value(property));
            }
        }
        foreach (var row in rows.OfType<JObject>()) foreach (var property in RowProperties(row)) {
            string name = property.Value<string>("Name") ?? throw new ConversionException($"Missing Data Table column in {path}");
            if (fields.ContainsKey(name)) continue;
            string kind = (property.Value<string>("$type") ?? "").Split(',')[0].Split('.').Last();
            if (kind.EndsWith("Data", StringComparison.Ordinal)) kind = kind[..^4];
            string nested = property.Value<string>("StructType") ?? "";
            if (ShapeFor(kind, nested) is { } shape)
                fields.Add(name, new Field(name, AuthoredFieldName(name, userDefined, null), shape));
        }
        var data = new JArray();
        foreach (var row in rows.OfType<JObject>()) {
            var values = new Dictionary<string, JToken>(defaults, SourceNameOrder.Instance);
            foreach (var property in RowProperties(row)) {
                string name = property.Value<string>("Name") ?? "";
                if (property.Value<int?>("ArrayIndex") is null or 0) values[name] = SourceAsset.Value(property);
            }
            var words = new JArray();
            foreach (var field in fields.Values) {
                values.TryGetValue(field.SerializedName, out var value);
                if (field.Shape.Integer) words.Add(value?.Value<int>() ?? 0);
                else if (field.Shape.Components.Length == 0)
                    words.Add(BitConverter.SingleToInt32Bits((float)SourceAsset.Number(value)));
                else foreach (string member in field.Shape.Components)
                    words.Add(BitConverter.SingleToInt32Bits((float)SourceAsset.Number(value?[member])));
            }
            data.Add(new JObject { ["name"] = row.Value<string>("Name") ?? "", ["words"] = words });
        }
        return new JObject { ["source"] = path,
            ["columns"] = new JArray(fields.Values.Select(field => new JObject {
                ["name"] = field.Name, ["type"] = field.Shape.Type, ["width"] = field.Shape.Width
            })), ["rows"] = data };
    }

    private static string AuthoredFieldName(string name, bool userDefined, string? friendly)
    {
        if (!userDefined) return name;
        if (!string.IsNullOrEmpty(friendly)) return friendly;
        const int guidLength = 32;
        if (name.Length <= guidLength + 3) return name;
        string prefix = name[..^(guidLength + 1)];
        int ordinal = prefix.LastIndexOf('_');
        return ordinal > 0 ? prefix[..ordinal] : name;
    }

    private static IEnumerable<JObject> RowProperties(JObject row) =>
        (row["Value"] as JArray ?? throw new ConversionException("Niagara Data Table row has no tagged payload")).OfType<JObject>();

    private static (SourceAsset Asset, SourceObject Structure)? Schema(SourceAsset owner, int reference, AssetLibrary? library)
    {
        if (reference > 0) return (owner, owner.Object(reference));
        if (reference == 0) return null;
        string path = owner.ObjectPath(reference);
        if (path.StartsWith("/Script/", StringComparison.Ordinal)) return null;
        var external = (library ?? owner.Library)?.Load(path) ?? throw new ConversionException($"Missing Data Table row struct {path}");
        return (external, external.ResolveObject(path, value => value.ClassName is "UserDefinedStruct" or "ScriptStruct"));
    }

    private static Shape? ShapeFor(string property, string structure) => property switch {
        "IntProperty" => Int,
        "FloatProperty" or "DoubleProperty" => Float,
        "StructProperty" => structure switch {
            "Vector2D" => new("/Script/CoreUObject.Vector2f", ["X", "Y"]),
            "Vector" => new("/Script/CoreUObject.Vector3f", ["X", "Y", "Z"]),
            "Vector4" => new("/Script/CoreUObject.Vector4f", ["X", "Y", "Z", "W"]),
            "Quat" => new("/Script/CoreUObject.Quat4f", ["X", "Y", "Z", "W"]),
            "LinearColor" => new("/Script/CoreUObject.LinearColor", ["R", "G", "B", "A"]),
            _ => null
        },
        _ => null
    };
}
