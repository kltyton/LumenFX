using System.Security.Cryptography;
using System.Text;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Geometry.GaussianSplat;

internal sealed class GaussianSplatAssetSource(NativeAssetTools tools)
{
    private readonly Dictionary<string, byte[]> assets = new(SourceNameOrder.Instance);
    internal IEnumerable<(string Key, byte[] Bytes)> ExportedAssets => assets
        .Select(pair => (ResourceKey(pair.Key), pair.Value.ToArray()));

    internal string Read(string source)
    {
        _ = ReadBytes(source);
        return ResourceKey(source);
    }

    internal byte[] ReadBytes(string source)
    {
        if (!assets.TryGetValue(source, out var bytes)) {
            bytes = NativeAssetExchange.ExportAsync(source, tools, "gaussian_splat", ".lfgs",
                value => Validate(value, source), operation: "gaussian-splat").GetAwaiter().GetResult();
            assets.Add(source, bytes);
        }
        return bytes.ToArray();
    }

    private static byte[] Validate(byte[] data, string source)
    {
        const int legacyHeaderBytes = 44;
        const int currentHeaderBytes = 156;
        if (data.Length < legacyHeaderBytes || !data.AsSpan(0, 4).SequenceEqual("LFGS"u8))
            throw new ConversionException($"Invalid native Gaussian Splat archive: {source}");
        using var reader = new BinaryReader(new MemoryStream(data, false), Encoding.UTF8);
        uint magic = reader.ReadUInt32();
        uint version = reader.ReadUInt32();
        int count = reader.ReadInt32(), degree = reader.ReadInt32(), coordinateSpace = reader.ReadInt32();
        if (magic != 0x5347464c || version is not (1 or 2) || count <= 0 || degree is < 0 or > 3 || coordinateSpace is < 0 or > 2
            || version == 2 && data.Length < currentHeaderBytes)
            throw new ConversionException($"Invalid native Gaussian Splat metadata: {source}");
        float minX = reader.ReadSingle(), minY = reader.ReadSingle(), minZ = reader.ReadSingle();
        float maxX = reader.ReadSingle(), maxY = reader.ReadSingle(), maxZ = reader.ReadSingle();
        if (!float.IsFinite(minX) || !float.IsFinite(minY) || !float.IsFinite(minZ)
            || !float.IsFinite(maxX) || !float.IsFinite(maxY) || !float.IsFinite(maxZ)
            || minX > maxX || minY > maxY || minZ > maxZ)
            throw new ConversionException($"Invalid native Gaussian Splat bounds: {source}");
        int totalCount = count;
        if (version == 2) {
            totalCount = reader.ReadInt32();
            int nativeFormat = reader.ReadInt32();
            if (totalCount < count || nativeFormat is < 0 or > 1)
                throw new ConversionException($"Invalid native Gaussian Splat source format metadata: {source}");
            for (int index = 0; index < 26; index++)
                if (!float.IsFinite(reader.ReadSingle()))
                    throw new ConversionException($"Invalid native Gaussian Splat quantization metadata: {source}");
        }
        int headerBytes = version == 1 ? legacyHeaderBytes : currentHeaderBytes;
        long wordsPerSplat = 14L + (((degree + 1L) * (degree + 1L)) - 1L) * 3L;
        long expectedBytes = headerBytes + count * wordsPerSplat * sizeof(float);
        if (expectedBytes != data.Length)
            throw new ConversionException($"Invalid native Gaussian Splat row layout: {source}");
        for (int offset = headerBytes; offset < data.Length; offset += sizeof(float)) {
            float value = BitConverter.ToSingle(data, offset);
            if (!float.IsFinite(value)) throw new ConversionException($"Non-finite Gaussian Splat value in {source}");
        }
        return data;
    }

    private static string ResourceKey(string source)
    {
        string canonical = SourceNameOrder.Canonical(source);
        return "unreal/gaussian_splat_" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(canonical)))[..24];
    }
}
