using System.Runtime.InteropServices;

namespace LumenFx.Compiler.Unreal.TextureSource;

internal sealed class OodleDataCodec : IDisposable
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate long DecodeFunction([In] byte[] source, long sourceSize, [Out] byte[] destination,
        long destinationSize, int fuzzSafe, int checkCrc, int verbosity, nint decodeBase, long decodeSize,
        nint callback, nint callbackData, nint decoderMemory, long decoderMemorySize, int threadPhase);

    private nint library;
    private readonly DecodeFunction decode;

    internal OodleDataCodec(string libraryPath)
    {
        if (!Path.IsPathFullyQualified(libraryPath))
            throw new ArgumentException("An absolute Oodle Data library path is required", nameof(libraryPath));
        if (!File.Exists(libraryPath)) throw new FileNotFoundException("Oodle Data library is missing", libraryPath);
        nint loaded = NativeLibrary.Load(libraryPath);
        try
        {
            decode = Marshal.GetDelegateForFunctionPointer<DecodeFunction>(NativeLibrary.GetExport(loaded, "OodleLZ_Decompress"));
            library = loaded;
        }
        catch
        {
            NativeLibrary.Free(loaded);
            throw;
        }
    }

    internal byte[] Decode(byte[] source, int rawSize)
    {
        ObjectDisposedException.ThrowIf(library == 0, this);
        if (rawSize <= 0) throw new ArgumentOutOfRangeException(nameof(rawSize));
        byte[] output = new byte[rawSize];
        long count = decode(source, source.Length, output, rawSize, 1, 0, 0, 0, 0, 0, 0, 0, 0, 3);
        if (count != rawSize) throw new ConversionException($"Oodle source block decoded {count} bytes, expected {rawSize}");
        return output;
    }

    public void Dispose()
    {
        if (library == 0) return;
        NativeLibrary.Free(library);
        library = 0;
    }
}
