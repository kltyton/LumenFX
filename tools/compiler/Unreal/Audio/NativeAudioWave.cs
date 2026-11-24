using System.Buffers.Binary;

namespace LumenFx.Compiler.Unreal.Audio;

internal static class NativeAudioWave
{
    internal sealed record Wave(byte[] Bytes, bool Looping, float Volume, float Pitch, double Duration, bool? Mature);

    internal static Task<Wave> CollectAsync(string source, NativeAssetTools tools) =>
        NativeAssetExchange.ExportAsync(source, tools, "wave", ".lfaw", bytes => Read(bytes, source));

    private static Wave Read(byte[] bytes, string source)
    {
        if (bytes.Length < 36 || UInt(0) != 0x5741464c || UInt(4) is not (1 or 2))
            throw new ConversionException($"Invalid native audio header: {source}");
        uint version = UInt(4);
        uint channels = UInt(12), flags = UInt(16), size = UInt(20);
        float volume = BitConverter.Int32BitsToSingle((int)UInt(24)), pitch = BitConverter.Int32BitsToSingle((int)UInt(28));
        if (UInt(8) == 0 || channels is < 1 or > 8 || (flags & ~(version == 1 ? 1u : 3u)) != 0 || size == 0
            || size % (channels * 2) != 0 || 32 + channels * 4L + size != bytes.Length
            || !float.IsFinite(volume) || !float.IsFinite(pitch))
            throw new ConversionException($"Invalid native audio payload: {source}");
        uint mask = 0;
        for (int channel = 0; channel < channels; channel++) {
            uint tag = UInt(32 + channel * 4);
            if (tag > 7 || (mask & 1u << (int)tag) != 0) throw new ConversionException($"Invalid native audio speaker order: {source}");
            mask |= 1u << (int)tag;
        }
        return new Wave(bytes, (flags & 1) != 0, volume, pitch, (double)size / (UInt(8) * channels * 2L),
            version == 2 ? (flags & 2) != 0 : null);
        uint UInt(int offset) => BinaryPrimitives.ReadUInt32LittleEndian(bytes.AsSpan(offset, 4));
    }
}
