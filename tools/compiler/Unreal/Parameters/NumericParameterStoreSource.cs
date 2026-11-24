using System.Buffers.Binary;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Reads typed numeric values and retained absolute positions from a serialized parameter store. */
internal static class NumericParameterStoreSource
{
    internal sealed record Value(byte[] Data, double[]? Position)
    {
        internal uint[] Words {
            get {
                if (Data.Length % sizeof(uint) != 0) throw new ConversionException("Numeric value is not DWORD aligned");
                return Enumerable.Range(0, Data.Length / sizeof(uint))
                    .Select(index => BinaryPrimitives.ReadUInt32LittleEndian(Data.AsSpan(index * sizeof(uint)))).ToArray();
            }
        }
    }

    internal static Value? Read(SourceAsset asset, JObject store, string name, string type, int width)
        => ReadBytes(asset, store, name, type, checked(width * sizeof(uint)));

    internal static Value? ReadBytes(SourceAsset asset, JObject store, string name, string type, int byteCount)
    {
        foreach (JObject variable in (store["SortedParameterOffsets"] as JArray ?? new JArray()).OfType<JObject>()) {
            if (!Matches(asset, variable, name, type)) continue;
            byte[] bytes = Bytes(store["ParameterData"]);
            int byteOffset = variable.Value<int>("offset");
            if (byteCount <= 0 || byteOffset < 0 || byteOffset > bytes.Length - byteCount)
                throw new ConversionException($"Numeric parameter is outside its source store: {name}");
            double[]? position = null;
            if (type == "/Script/Niagara.NiagaraPosition") {
                JObject? original = (store["OriginalPositionData"] as JArray ?? new JArray()).OfType<JObject>()
                    .FirstOrDefault(value => SourceNameOrder.Equal(value.Value<string>("Name"), name));
                if (original is not null) position = new[] { "X", "Y", "Z" }.Select(axis => SourceAsset.Number(original["Value"]?[axis])).ToArray();
            }
            return new(bytes.AsSpan(byteOffset, byteCount).ToArray(), position);
        }
        return null;
    }

    internal static bool Matches(SourceAsset asset, JObject variable, string name, string type)
    {
        if (variable["type"] is not JObject definition) return false;
        var loaded = NiagaraTypeSource.Read(asset, definition);
        return loaded.SourceReference != 0 && SourceNameOrder.Equal(variable.Value<string>("name"), name)
            && SourceNameOrder.Equal(loaded.Path, type);
    }

    internal static byte[] Bytes(JToken? value) => value is null || value.Type == JTokenType.Null ? Array.Empty<byte>()
        : value is JArray array ? array.Values<byte>().ToArray() : Convert.FromBase64String(value.Value<string>()!);
}
