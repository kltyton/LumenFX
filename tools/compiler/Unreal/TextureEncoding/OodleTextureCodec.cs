using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Buffers.Binary;

namespace LumenFx.Compiler.Unreal.TextureEncoding;

public enum OodleTextureFormat
{
    Dxt1,
    Dxt5,
    Dxt3,
    Bc4,
    Bc5,
    Bc6h,
    Bc7
}

public sealed class OodleTextureException : Exception
{
    public OodleTextureException(int code, string nativeName)
        : base($"Oodle Texture error {code}: {nativeName}")
    {
        Code = code;
        NativeName = nativeName;
    }

    public int Code { get; }

    public string NativeName { get; }
}

public sealed class OodleTextureCodec : IDisposable
{
    private const int Bc1WithTransparency = 2;
    private const int Bc3 = 4;
    private const int PixelFormat4U8Bgra = 2;
    private const int PixelFormat4U8Bgrx = 17;
    private const int PixelFormat4F32Rgba = 3;
    private const int PixelFormat2U16 = 8;
    private const int ErrorMetricDefault = 0;
    private const int RdoFlagsNone = 0;
    private const int JobsDisable = -1;
    private const int MaximumSurfaceDimension = 2097152;

    private readonly IntPtr library;
    private readonly EncodeBcnRdoExDelegate encode;
    private readonly DecodeBcnLinearSurfacesDelegate decode;
    private readonly BcBytesPerBlockDelegate bytesPerBlock;
    private readonly ErrGetNameDelegate errGetName;
    private bool disposed;

    public OodleTextureCodec(string dllPath)
    {
        if (string.IsNullOrWhiteSpace(dllPath))
            throw new ArgumentException("An absolute Oodle Texture DLL path is required.", nameof(dllPath));
        if (!Path.IsPathFullyQualified(dllPath))
            throw new ArgumentException("Oodle Texture DLL path must be absolute.", nameof(dllPath));
        if (!File.Exists(dllPath))
            throw new FileNotFoundException("Oodle Texture DLL not found.", dllPath);

        IntPtr loaded = NativeLibrary.Load(dllPath);
        try
        {
            encode = LoadDelegate<EncodeBcnRdoExDelegate>(loaded, "OodleTex_EncodeBCN_RDO_Ex");
            decode = LoadDelegate<DecodeBcnLinearSurfacesDelegate>(loaded, "OodleTex_DecodeBCN_LinearSurfaces");
            bytesPerBlock = LoadDelegate<BcBytesPerBlockDelegate>(loaded, "OodleTex_BC_BytesPerBlock");
            errGetName = LoadDelegate<ErrGetNameDelegate>(loaded, "OodleTex_Err_GetName");
            library = loaded;
        }
        catch
        {
            NativeLibrary.Free(loaded);
            throw;
        }
    }

    public byte[] Encode(
        byte[] bgra,
        int width,
        int height,
        OodleTextureFormat format,
        int rdoLambda = 0,
        bool preserveExtremes = false,
        int effort = 20,
        int universalTiling = 0,
        bool? hasAlpha = null)
    {
        if (format == OodleTextureFormat.Bc6h) throw new ArgumentException("BC6H requires linear floating source pixels.", nameof(format));
        int inputFormat = format == OodleTextureFormat.Dxt1 || hasAlpha == false ? PixelFormat4U8Bgrx : PixelFormat4U8Bgra;
        return EncodePixels(bgra, width, height, format, inputFormat, 4, rdoLambda, preserveExtremes, effort, universalTiling);
    }

    public byte[] EncodeLinearFloat(byte[] rgba, int width, int height, OodleTextureFormat format,
        int rdoLambda = 0, bool preserveExtremes = false, int effort = 20, int universalTiling = 0)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        ArgumentNullException.ThrowIfNull(rgba);
        if (format is OodleTextureFormat.Bc4 or OodleTextureFormat.Bc5) {
            ValidateDimensions(width, height);
            if (rgba.Length != checked(width * height * 16)) throw new ArgumentException("Linear RGBA source dimensions differ.", nameof(rgba));
            byte[] rg = new byte[checked(width * height * 4)];
            for (int source = 0, output = 0; source < rgba.Length; source += 16, output += 4)
                for (int channel = 0; channel < 2; channel++) {
                    float value = BinaryPrimitives.ReadSingleLittleEndian(rgba.AsSpan(source + channel * 4));
                    float clamped = float.IsNaN(value) ? 0 : Math.Clamp(value, 0, 1);
                    BinaryPrimitives.WriteUInt16LittleEndian(rg.AsSpan(output + channel * 2), (ushort)(.5f + clamped * 65535f));
                }
            return EncodePixels(rg, width, height, format, PixelFormat2U16, 4, rdoLambda, preserveExtremes, effort, universalTiling);
        }
        if (format != OodleTextureFormat.Bc6h) throw new ArgumentException("Linear source pixels require BC4/5/6 output.", nameof(format));
        return EncodePixels(rgba, width, height, format, PixelFormat4F32Rgba, 16, rdoLambda, preserveExtremes, effort, universalTiling);
    }

    private byte[] EncodePixels(byte[] pixels, int width, int height, OodleTextureFormat format,
        int inputFormat, int pixelBytes, int rdoLambda, bool preserveExtremes, int effort, int universalTiling)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        ArgumentNullException.ThrowIfNull(pixels);
        ValidateDimensions(width, height);
        ValidateFormat(format);
        if (pixels.Length != checked(width * height * pixelBytes))
            throw new ArgumentException("Pixel input length does not match its declared precision and dimensions.", nameof(pixels));
        if (rdoLambda is < 0 or > 100)
            throw new ArgumentOutOfRangeException(nameof(rdoLambda), "RDO lambda must be in 0..100.");
        if (effort is not (0 or 10 or 20 or 30))
            throw new ArgumentOutOfRangeException(nameof(effort), "Unsupported Oodle encode effort.");
        if (universalTiling is < 0 or > 2)
            throw new ArgumentOutOfRangeException(nameof(universalTiling), "Unsupported universal tiling mode.");

        int bcn = NativeFormat(format);
        long blockCount = BlockCount(width, height);
        int blockBytes = bytesPerBlock(bcn);
        if (blockBytes <= 0)
            throw new InvalidOperationException("Oodle Texture returned an invalid BCn block size.");
        long byteCountLong = checked(blockCount * blockBytes);
        if (byteCountLong > int.MaxValue)
            throw new ArgumentOutOfRangeException(nameof(width), "Encoded BCn data exceeds the managed array limit.");
        int byteCount = (int)byteCountLong;

        GCHandle inputHandle = default;
        IntPtr surface = IntPtr.Zero;
        IntPtr options = IntPtr.Zero;
        IntPtr output = IntPtr.Zero;
        try
        {
            inputHandle = GCHandle.Alloc(pixels, GCHandleType.Pinned);
            var inputSurface = new OodleTexSurface
            {
                Pixels = inputHandle.AddrOfPinnedObject(),
                RowStrideBytes = checked(width * pixelBytes),
                Width = width,
                Height = height
            };
            surface = Marshal.AllocHGlobal(Marshal.SizeOf<OodleTexSurface>());
            Marshal.StructureToPtr(inputSurface, surface, false);

            var rdoOptions = new OodleTexRdoOptions
            {
                Effort = effort,
                Metric = ErrorMetricDefault,
                BcnFlags = preserveExtremes ? 1 : 0,
                UniversalTiling = universalTiling,
                RdoFlags = RdoFlagsNone
            };
            options = Marshal.AllocHGlobal(Marshal.SizeOf<OodleTexRdoOptions>());
            Marshal.StructureToPtr(rdoOptions, options, false);

            output = Marshal.AllocHGlobal(byteCount);
            int result = encode(
                bcn, output, (nint)blockCount,
                surface, 1, inputFormat,
                IntPtr.Zero, rdoLambda, options,
                JobsDisable, IntPtr.Zero);
            if (result != 0)
                throw CreateError(result);

            var blocks = new byte[byteCount];
            Marshal.Copy(output, blocks, 0, byteCount);
            return blocks;
        }
        finally
        {
            if (output != IntPtr.Zero)
                Marshal.FreeHGlobal(output);
            if (options != IntPtr.Zero)
                Marshal.FreeHGlobal(options);
            if (surface != IntPtr.Zero)
                Marshal.FreeHGlobal(surface);
            if (inputHandle.IsAllocated)
                inputHandle.Free();
        }
    }

    public byte[] Decode(byte[] blocks, int width, int height, OodleTextureFormat format)
        => DecodePixels(blocks, width, height, format, PixelFormat4U8Bgra, 4);

    public byte[] DecodeLinearFloat(byte[] blocks, int width, int height, OodleTextureFormat format)
        => DecodePixels(blocks, width, height, format, PixelFormat4F32Rgba, 16);

    private byte[] DecodePixels(byte[] blocks, int width, int height, OodleTextureFormat format, int outputFormat, int pixelBytes)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        ArgumentNullException.ThrowIfNull(blocks);
        ValidateDimensions(width, height);
        ValidateFormat(format);

        int bcn = NativeFormat(format);
        long blockCount = BlockCount(width, height);
        int blockBytes = bytesPerBlock(bcn);
        if (blockBytes <= 0)
            throw new InvalidOperationException("Oodle Texture returned an invalid BCn block size.");
        long expectedBlockBytes = checked(blockCount * blockBytes);
        if (expectedBlockBytes > int.MaxValue)
            throw new ArgumentOutOfRangeException(nameof(width), "Encoded BCn data exceeds the managed array limit.");
        if (blocks.Length != (int)expectedBlockBytes)
            throw new ArgumentException("BCn block length does not match width, height and format.", nameof(blocks));

        int outputBytes = checked(width * height * pixelBytes);
        GCHandle inputHandle = default;
        IntPtr surface = IntPtr.Zero;
        IntPtr output = IntPtr.Zero;
        try
        {
            inputHandle = GCHandle.Alloc(blocks, GCHandleType.Pinned);
            output = Marshal.AllocHGlobal(outputBytes);

            var outputSurface = new OodleTexSurface
            {
                Pixels = output,
                RowStrideBytes = checked(width * pixelBytes),
                Width = width,
                Height = height
            };
            surface = Marshal.AllocHGlobal(Marshal.SizeOf<OodleTexSurface>());
            Marshal.StructureToPtr(outputSurface, surface, false);

            int result = decode(
                surface, 1, outputFormat,
                bcn, inputHandle.AddrOfPinnedObject(), (nint)blockCount,
                IntPtr.Zero);
            if (result != 0)
                throw CreateError(result);

            var bgra = new byte[outputBytes];
            Marshal.Copy(output, bgra, 0, outputBytes);
            return bgra;
        }
        finally
        {
            if (output != IntPtr.Zero)
                Marshal.FreeHGlobal(output);
            if (surface != IntPtr.Zero)
                Marshal.FreeHGlobal(surface);
            if (inputHandle.IsAllocated)
                inputHandle.Free();
        }
    }

    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        NativeLibrary.Free(library);
    }

    private OodleTextureException CreateError(int code)
    {
        IntPtr namePointer = errGetName(code);
        string name = namePointer == IntPtr.Zero
            ? "Unknown"
            : Marshal.PtrToStringAnsi(namePointer) ?? "Unknown";
        return new OodleTextureException(code, name);
    }

    private static T LoadDelegate<T>(IntPtr libraryHandle, string exportName) where T : Delegate
    {
        return Marshal.GetDelegateForFunctionPointer<T>(NativeLibrary.GetExport(libraryHandle, exportName));
    }

    private static long BlockCount(int width, int height)
    {
        return checked(((long)width + 3) / 4 * (((long)height + 3) / 4));
    }

    private static void ValidateDimensions(int width, int height)
    {
        if (width <= 0 || height <= 0 || width > MaximumSurfaceDimension || height > MaximumSurfaceDimension)
            throw new ArgumentOutOfRangeException(nameof(width), "Texture dimensions are outside the Oodle surface limits.");
    }

    private static void ValidateFormat(OodleTextureFormat format)
    {
        _ = NativeFormat(format);
    }

    private static int NativeFormat(OodleTextureFormat format) => format switch {
        OodleTextureFormat.Dxt1 => Bc1WithTransparency, OodleTextureFormat.Dxt3 => 3, OodleTextureFormat.Dxt5 => Bc3,
        OodleTextureFormat.Bc4 => 5, OodleTextureFormat.Bc5 => 7, OodleTextureFormat.Bc6h => 9, OodleTextureFormat.Bc7 => 11,
        _ => throw new ArgumentOutOfRangeException(nameof(format), "Unknown Oodle texture format.") };

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int EncodeBcnRdoExDelegate(
        int toBcn,
        IntPtr toBcnBlocks,
        nint numBlocks,
        IntPtr fromSurfaces,
        nint numFromSurfaces,
        int fromFormat,
        IntPtr layout,
        int rdoLagrangeLambda,
        IntPtr options,
        int numJobThreads,
        IntPtr jobifyUserPtr);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int DecodeBcnLinearSurfacesDelegate(
        IntPtr toSurfaces,
        nint numToSurfaces,
        int toFormat,
        int fromBcn,
        IntPtr fromBcnBlocks,
        nint numBlocks,
        IntPtr layout);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int BcBytesPerBlockDelegate(int bcn);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate IntPtr ErrGetNameDelegate(int error);

    [StructLayout(LayoutKind.Sequential)]
    private struct OodleTexSurface
    {
        public IntPtr Pixels;
        public nint RowStrideBytes;
        public int Width;
        public int Height;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct OodleTexRdoOptions
    {
        public int Effort;
        public int Metric;
        public int BcnFlags;
        public int UniversalTiling;
        public int RdoFlags;
        public int UseBc3AlphaLambda;
        public int Bc3AlphaLambda;
        public int NotYetUsedZeroMe0;
        public int NotYetUsedZeroMe1;
        public int NotYetUsedZeroMe2;
        public int NotYetUsedZeroMe3;
        public int NotYetUsedZeroMe4;
        public int NotYetUsedZeroMe5;
        public int NotYetUsedZeroMe6;
        public int NotYetUsedZeroMe7;
    }
}
