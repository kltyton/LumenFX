using Newtonsoft.Json.Linq;
using System.Runtime.CompilerServices;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Preserves each MPC layer so CPU name lookup and GPU GUID overrides can keep their distinct semantics. */
internal static class MaterialParameterCollectionSource
{
    private const string ZeroGuid = "00000000000000000000000000000000";
    private static readonly ConditionalWeakTable<SourceAsset, Dictionary<int, Definition>> Cache = new();
    internal sealed record Parameter(string Name, string Id, float[] Default)
    {
        internal JObject Json() => new() { ["name"] = Name, ["id"] = Id, ["default"] = Words(Default) };
    }
    internal sealed record Definition(string Path, string StateId, Definition? Base,
        IReadOnlyList<Parameter> Scalars, IReadOnlyList<Parameter> Vectors,
        IReadOnlyDictionary<string, float[]> ScalarOverrides, IReadOnlyDictionary<string, float[]> VectorOverrides)
    {
        internal int VectorCount => checked((Base?.VectorCount ?? 0) + (Scalars.Count + 3) / 4 + Vectors.Count);
        internal (int Vector, int Component) Location(string id) {
            int scalarBase = VectorCount;
            for (Definition? collection = this; collection is not null; collection = collection.Base) {
                int vectorBase = scalarBase - collection.Vectors.Count;
                scalarBase = vectorBase - (collection.Scalars.Count + 3) / 4;
                for (int index = 0; index < collection.Scalars.Count; index++)
                    if (collection.Scalars[index].Id == id) return (scalarBase + index / 4, index % 4);
                for (int index = 0; index < collection.Vectors.Count; index++)
                    if (collection.Vectors[index].Id == id) return (vectorBase + index, -1);
            }
            return (-1, -1);
        }
        internal JObject Json() => new() { ["path"] = Path, ["state_id"] = StateId, ["base"] = Base?.Json(),
            ["scalars"] = new JArray(Scalars.Select(value => value.Json())), ["vectors"] = new JArray(Vectors.Select(value => value.Json())),
            ["scalar_overrides"] = new JArray(ScalarOverrides.Select(value => new JArray(value.Key, Words(value.Value)))),
            ["vector_overrides"] = new JArray(VectorOverrides.Select(value => new JArray(value.Key, Words(value.Value)))) };
    }

    internal static Definition Read(SourceAsset owner, int reference, AssetLibrary? library)
        => Read(owner, reference, library, new HashSet<string>(SourceNameOrder.Instance));

    private static Definition Read(SourceAsset owner, int reference, AssetLibrary? library, HashSet<string> active)
    {
        string path = owner.ObjectPath(reference);
        if (!active.Add(path)) throw new ConversionException($"Cyclic material parameter collection base chain: {path}");
        try {
            SourceAsset asset = reference > 0 ? owner : library?.Load(path)
                ?? throw new ConversionException($"A source library is required for material parameter collection {path}");
            SourceObject collection = reference > 0 ? owner.Object(reference)
                : asset.ResolveObject(path, value => value.ClassName == "MaterialParameterCollection");
            if (collection.ClassName != "MaterialParameterCollection") throw new ConversionException($"Expected material parameter collection at {path}");
            Dictionary<int, Definition> definitions = Cache.GetValue(asset, _ => new());
            if (definitions.TryGetValue(collection.Index, out Definition? cached)) return cached;
            JObject properties = collection.Properties;
            int parent = properties.Value<int?>("Base") ?? 0;
            Definition? baseCollection = parent == 0 ? null : Read(asset, parent, library, active);
            string stateId = properties["StateId"] is { } state ? GuidText(state) : ZeroGuid;
            string baseStateId = properties["BaseStateId"] is { } baseState ? GuidText(baseState) : ZeroGuid;
            IReadOnlyList<Parameter> scalars = Parameters(properties["ScalarParameters"], false), vectors = Parameters(properties["VectorParameters"], true);
            IReadOnlyDictionary<string, float[]> scalarOverrides = Overrides(properties["ScalarParameterBaseOverrides"], false),
                vectorOverrides = Overrides(properties["VectorParameterBaseOverrides"], true);
            if (baseCollection is not null && baseStateId != baseCollection.StateId) {
                List<Parameter> baseScalars = new(), baseVectors = new();
                for (Definition? value = baseCollection; value is not null; value = value.Base) {
                    baseScalars.AddRange(value.Scalars); baseVectors.AddRange(value.Vectors);
                }
                scalars = MaterialCollectionNames.Sanitize(scalars, baseScalars, NewGuid);
                vectors = MaterialCollectionNames.Sanitize(vectors, baseVectors, NewGuid);
                HashSet<string> activeIds = baseScalars.Concat(baseVectors).Select(value => value.Id).ToHashSet(StringComparer.Ordinal);
                scalarOverrides = scalarOverrides.Where(value => activeIds.Contains(value.Key)).ToDictionary(value => value.Key, value => value.Value, StringComparer.Ordinal);
                vectorOverrides = vectorOverrides.Where(value => activeIds.Contains(value.Key)).ToDictionary(value => value.Key, value => value.Value, StringComparer.Ordinal);
                stateId = NewGuid();
            } else if (baseCollection is null && baseStateId != ZeroGuid) {
                scalarOverrides = new Dictionary<string, float[]>(); vectorOverrides = new Dictionary<string, float[]>();
                stateId = NewGuid();
            }
            if (stateId == ZeroGuid) stateId = NewGuid();
            Definition result = new(path, stateId, baseCollection, scalars, vectors, scalarOverrides, vectorOverrides);
            definitions.Add(collection.Index, result);
            return result;
        } finally { active.Remove(path); }
    }

    private static IReadOnlyList<Parameter> Parameters(JToken? source, bool vector)
    {
        List<Parameter> result = new();
        foreach (JObject parameter in (source as JArray ?? new JArray()).OfType<JObject>())
            result.Add(new(parameter.Value<string>("ParameterName") ?? (vector ? "Vector" : "Scalar"),
                parameter["Id"] is { } id ? GuidText(id) : NewGuid(),
                Value(parameter["DefaultValue"], vector)));
        return result;
    }

    private static IReadOnlyDictionary<string, float[]> Overrides(JToken? source, bool vector)
    {
        Dictionary<string, float[]> result = new(StringComparer.Ordinal);
        if (source is null || source.Type == JTokenType.Null) return result;
        if (source is not JArray entries) throw new ConversionException("Material collection overrides must be a native GUID/value map");
        foreach (JToken entry in entries) {
            if (entry is not JArray { Count: 2 } pair) throw new ConversionException("Invalid material collection override entry");
            result[GuidText(pair[0])] = Value(pair[1], vector);
        }
        return result;
    }

    private static float[] Value(JToken? source, bool vector) => vector
        ? new[] { "R", "G", "B", "A" }.Select(channel => (float)SourceAsset.Number(source?[channel])).ToArray()
        : new[] { (float)SourceAsset.Number(source) };

    private static JArray Words(IEnumerable<float> values) => new(values.Select(value => unchecked((uint)BitConverter.SingleToInt32Bits(value))));

    internal static string GuidText(JToken source)
    {
        if (source.Type != JTokenType.String) throw new ConversionException("Material collection GUID must retain its exported text");
        string text = source.Value<string>()!.Replace("-", "", StringComparison.Ordinal);
        if (text.Length != 32 || text.Any(value => value is not (>= '0' and <= '9' or >= 'a' and <= 'f' or >= 'A' and <= 'F')))
            throw new ConversionException("Invalid material collection GUID text");
        return text.ToUpperInvariant();
    }
    private static string NewGuid() => Guid.NewGuid().ToString("N").ToUpperInvariant();
}
