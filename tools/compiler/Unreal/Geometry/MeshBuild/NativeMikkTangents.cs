using System.Runtime.InteropServices;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed class NativeMikkTangents : IDisposable
{
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int GenerateFunction(int vertices, int indices,
        [In] int[] indexData, [In] float[] positions, [In] float[] normals, [In] float[] uv,
        [In, Out] float[] tangents, [In, Out] float[] signs, int ignoreDegenerates);

    private nint library;
    private readonly GenerateFunction generate;

    internal NativeMikkTangents(string libraryPath)
    {
        if (!Path.IsPathFullyQualified(libraryPath))
            throw new ArgumentException("An absolute mesh source library path is required", nameof(libraryPath));
        if (!File.Exists(libraryPath)) throw new FileNotFoundException("Mesh source library is missing", libraryPath);
        nint loaded = NativeLibrary.Load(libraryPath);
        try
        {
            generate = Marshal.GetDelegateForFunctionPointer<GenerateFunction>(
                NativeLibrary.GetExport(loaded, "LumenFxGenerateMeshTangents"));
            library = loaded;
        }
        catch
        {
            NativeLibrary.Free(loaded);
            throw;
        }
    }

    internal StaticMeshGeometry Generate(StaticMeshGeometry source, bool ignoreDegenerates)
    {
        ObjectDisposedException.ThrowIf(library == 0, this);
        if (source.Indices.Length == 0) return source;
        int vertices = source.SourceInstanceIds.Length;
        if (source.Positions.Length != checked(vertices * 3) || source.Normals.Length != source.Positions.Length
                || source.Tangents.Length != source.Positions.Length || source.BinormalSigns.Length != vertices
                || source.TextureCoordinates.Count == 0 || source.TextureCoordinates[0].Length != checked(vertices * 2)
                || source.Indices.Length % 3 != 0)
            throw new ConversionException("MikkTSpace requires matching vertex channels and source UV0");
        float[] tangents = (float[])source.Tangents.Clone();
        float[] signs = (float[])source.BinormalSigns.Clone();
        int result = generate(vertices, source.Indices.Length, source.Indices, source.Positions,
            source.Normals, source.TextureCoordinates[0], tangents, signs, ignoreDegenerates ? 1 : 0);
        if (result != 0) throw new ConversionException($"MikkTSpace mesh tangent generation failed: {result}");
        return source with { Tangents = tangents, BinormalSigns = signs };
    }

    public void Dispose()
    {
        if (library == 0) return;
        NativeLibrary.Free(library);
        library = 0;
    }
}
