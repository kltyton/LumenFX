using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Types;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** The particle dataset is allocated from the source's typed attributes, in source script order. */
internal static class CompiledParticleLayout
{
    internal static JArray FromScripts(SourceAsset asset, JArray main, JArray? spawn)
    {
        var result = new JArray();
        var used = new HashSet<(string Name, string Type, int Kind, int Flags)>();
        int[] starts = new int[3];
        var attributes = main.OfType<JObject>();
        if (spawn is not null) attributes = attributes.Concat(spawn.OfType<JObject>());
        foreach (var attribute in attributes) {
            var (name, type) = Variable(asset, attribute);
            if (!used.Add((SourceNameOrder.Canonical(name), SourceNameOrder.Canonical(type.Path),
                    type.UnderlyingType, type.Flags))) continue;
            int[] counts = Counts(asset, type);
            for (int family = 0; family < starts.Length; family++) {
                Add(result, name, type.Path, family, starts[family], counts[family]);
                starts[family] = checked(starts[family] + counts[family]);
            }
        }
        return result;
    }

    internal static JArray FromSaved(SourceAsset asset, JToken? compiled)
    {
        JArray variables = compiled?["Variables"] as JArray ?? new();
        if (compiled?["VariableLayouts"] is not JArray layouts)
            return FromScripts(asset, variables, null);
        if (layouts.Count != variables.Count) throw new ConversionException("Niagara particle dataset has mismatched saved layout rows");

        var result = new JArray();
        for (int i = 0; i < variables.Count; i++) {
            var (name, type) = Variable(asset, (JObject)variables[i]!);
            JToken layout = layouts[i]!;
            JToken info = layout["LayoutInfo"] ?? throw new ConversionException($"Missing Niagara layout for {name}");
            Add(result, name, type.Path, 0, layout.Value<int>("FloatComponentStart"), info.Value<int>("NumFloatComponents"));
            Add(result, name, type.Path, 1, layout.Value<int>("Int32ComponentStart"), info.Value<int>("NumInt32Components"));
            Add(result, name, type.Path, 2, layout.Value<int>("HalfComponentStart"), info.Value<int>("NumHalfComponents"));
        }
        return result;
    }

    private static (string Name, NiagaraTypeSource.Definition Type) Variable(SourceAsset asset, JObject attribute)
    {
        string name = attribute.Value<string>("name") ?? throw new ConversionException("Niagara particle attribute has no name");
        var definition = attribute["type"] as JObject ?? throw new ConversionException($"Missing type for Niagara particle attribute {name}");
        return (name, NiagaraTypeSource.Read(asset, definition));
    }

    private static int[] Counts(SourceAsset asset, NiagaraTypeSource.Definition type)
    {
        if (type.UnderlyingType == 3) return [0, 1, 0];
        var structure = BuiltinStructLayouts.TryGet(type.Path, out var builtin) ? builtin
                : StructLayoutSource.Find(asset, type.SourceReference);
        if (structure is null) throw new ConversionException($"Missing source particle dataset structure {type.Path}");
        if (StructLayoutSource.HasLwc(structure.Fields))
            structure = NiagaraTypeSource.Projection(structure).Simulation;
        int[] counts = new int[3];
        CountFields(structure, counts);
        return counts;
    }

    private static void CountFields(StructLayout structure, int[] counts)
    {
        // GenerateLayoutInfoInternal visits each reflected property, including inherited and nested fields.
        foreach (var field in structure.Fields) {
            switch (field.Kind) {
                case StructFieldKind.Float: counts[0]++; break;
                case StructFieldKind.Int32:
                case StructFieldKind.Bool: counts[1]++; break;
                case StructFieldKind.UInt16: counts[2]++; break;
                case StructFieldKind.Struct when field.Structure is { } nested:
                    if (NiagaraTypeSource.KnownSwc(nested.Path) is { } path && BuiltinStructLayouts.TryGet(path, out var swc))
                        nested = swc!;
                    CountFields(nested, counts);
                    break;
                default: throw new ConversionException($"Unsupported Niagara particle dataset field {structure.Path}.{field.Name}");
            }
        }
    }

    private static void Add(JArray result, string name, string type, int family, int start, int count)
    {
        if (count < 0 || count > ushort.MaxValue || count != 0 && start < 0)
            throw new ConversionException($"Invalid Niagara particle dataset register range for {name}");
        if (count != 0) result.Add(new JObject {
            ["name"] = name, ["type"] = family, ["component"] = start, ["count"] = count, ["source_type"] = type
        });
    }
}
