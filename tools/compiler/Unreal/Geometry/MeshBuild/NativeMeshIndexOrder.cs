using System.Runtime.InteropServices;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed class NativeMeshIndexOrder : IDisposable
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int OptimizeFunction(int vertices, int count, [In] int[] indices, [Out] int[] output);
    private nint library;
    private readonly OptimizeFunction optimize;
    private readonly bool unchanged;
    private bool disposed;

    internal NativeMeshIndexOrder(string? libraryPath, int mode = 1)
    {
        if (mode == 2) { unchanged = true; optimize = null!; return; }
        if (mode != 1) throw new ArgumentException("NVTriStrip requires the native mesh batch operation");
        if (libraryPath is null) throw new ConversionException("Forsyth mesh index library is required");
        if (!Path.IsPathFullyQualified(libraryPath))
            throw new ArgumentException("An absolute mesh source library path is required", nameof(libraryPath));
        if (!File.Exists(libraryPath)) throw new FileNotFoundException("Mesh source library is missing", libraryPath);
        nint loaded = NativeLibrary.Load(libraryPath);
        try
        {
            optimize = Marshal.GetDelegateForFunctionPointer<OptimizeFunction>(
                NativeLibrary.GetExport(loaded, "LumenFxOptimizeMeshIndices"));
            library = loaded;
        }
        catch { NativeLibrary.Free(loaded); throw; }
    }

    internal int[] Optimize(int[] indices, int vertices)
    {
        ObjectDisposedException.ThrowIf(disposed, this);
        if (indices.Length == 0) return [];
        if (indices.Length % 3 != 0 || vertices <= 0) throw new ConversionException("Invalid mesh index optimization input");
        if (unchanged) return (int[])indices.Clone();
        int[] result = new int[indices.Length];
        int status = optimize(vertices, indices.Length, indices, result);
        if (status != 0) throw new ConversionException($"Mesh index optimization failed: {status}");
        return result;
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        if (library != 0) NativeLibrary.Free(library);
        library = 0;
    }
}
