using System.Text;

namespace LumenFx.Compiler.Unreal.SimulationCache;

internal sealed record NativeSimulationCache(byte[] Bytes, IReadOnlyList<string> Materials, bool IsValid = true)
{
    internal static Task<NativeSimulationCache> ExportAsync(string path, NativeAssetTools tools) =>
        NativeAssetExchange.ExportAsync(path, tools, "cache", ".lfsc", bytes => Read(path, bytes));

    private static NativeSimulationCache Read(string path, byte[] bytes)
    {
        using var stream = new MemoryStream(bytes, writable: false);
        using var reader = new BinaryReader(stream, new UTF8Encoding(false, true));
        if (bytes.Length < 8 || reader.ReadUInt32() != 0x4353464c) throw Invalid(path);
        int version = reader.ReadInt32();
        if (version == 0 && stream.Position == stream.Length) return new NativeSimulationCache([], [], false);
        if (version is < 1 or > 6) throw Invalid(path);
        if (version < 3) return new NativeSimulationCache(bytes, []);
        _ = Text(reader, path);
        if (stream.Length - stream.Position < 6 * sizeof(int)) throw Invalid(path);
        stream.Position += 5 * sizeof(int);
        int count = reader.ReadInt32();
        if (count < 0 || count > (stream.Length - stream.Position) / sizeof(int)) throw Invalid(path);
        var materials = new string[count];
        for (int index = 0; index < materials.Length; index++) materials[index] = Text(reader, path);
        return new NativeSimulationCache(bytes, materials);
    }

    private static string Text(BinaryReader reader, string path)
    {
        var stream = reader.BaseStream;
        if (stream.Length - stream.Position < sizeof(int)) throw Invalid(path);
        int size = reader.ReadInt32();
        if (size < 0 || size > stream.Length - stream.Position) throw Invalid(path);
        return Encoding.UTF8.GetString(reader.ReadBytes(size));
    }

    private static ConversionException Invalid(string source) => new($"Invalid native simulation cache header: {source}");
}
