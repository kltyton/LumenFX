using System.Text;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal static class NativeMeshIndexBatch
{
    private sealed record Request(int[] Indices, int Vertices);

    internal static async Task ApplyAsync(List<(StaticMeshSourceLod Source, MeshSourceBuilder.BuiltLod Built)> lods, MeshBuildTools tools)
    {
        tools.RequireAbsolutePaths();
        List<Request> requests = new();
        foreach (var (source, built) in lods) {
            if (source.Geometry!.SourceInstanceIds.Length >= 300_000 || built.Geometry.SourceInstanceIds.Length == 0) continue;
            foreach (var section in built.Geometry.Sections)
                requests.Add(new Request(built.Geometry.Indices.AsSpan(section.FirstIndex, section.IndexCount).ToArray(), built.Geometry.SourceInstanceIds.Length));
        }
        if (requests.Count == 0) return;
        Directory.CreateDirectory(tools.WorkDirectory);
        string prefix = Path.Combine(tools.WorkDirectory, "mesh_indices_" + Guid.NewGuid().ToString("N"));
        string input = prefix + ".input", output = prefix + ".output";
        try {
            using (var writer = new BinaryWriter(File.Create(input), Encoding.UTF8)) {
                writer.Write(0x49494d4c); writer.Write(1); writer.Write(tools.TriangleOrderOptimization); writer.Write(requests.Count);
                foreach (var request in requests) {
                    writer.Write(request.Vertices); writer.Write(request.Indices.Length);
                    foreach (int index in request.Indices) writer.Write(index);
                }
            }
            var run = await NativeMeshProcess.RunAsync(tools, input, output, "indices").ConfigureAwait(false);
            List<int[]> indices = Read(output, requests);
            int next = 0;
            for (int lod = 0; lod < lods.Count; lod++) {
                var (source, built) = lods[lod];
                int sourceInstances = source.Geometry!.SourceInstanceIds.Length;
                if (sourceInstances >= 300_000 || built.Geometry.SourceInstanceIds.Length == 0) continue;
                var optimized = MeshVertexCache.Optimize(new MeshRenderGeometry(built.Geometry, built.WedgeMap), sourceInstances,
                    (original, vertices) => indices[next++]);
                lods[lod] = (source, built with { Geometry = optimized.Geometry, WedgeMap = optimized.WedgeMap });
            }
            run.Complete();
        } finally {
            if (File.Exists(input)) File.Delete(input);
            if (File.Exists(output)) File.Delete(output);
        }
    }

    private static List<int[]> Read(string path, List<Request> requests)
    {
        using var reader = new BinaryReader(File.OpenRead(path), Encoding.UTF8);
        if (reader.ReadInt32() != 0x4f494d4c || reader.ReadInt32() != 1 || reader.ReadInt32() != requests.Count)
            throw new ConversionException("Native mesh index output header disagrees");
        List<int[]> result = new();
        foreach (var request in requests) {
            int count = reader.ReadInt32();
            if (count < 0 || count % 3 != 0 || count > (reader.BaseStream.Length - reader.BaseStream.Position) / 4)
                throw new ConversionException("Native mesh index output size disagrees");
            int[] indices = new int[count];
            for (int index = 0; index < count; index++) {
                int vertex = reader.ReadInt32();
                if (vertex < 0 || vertex >= request.Vertices) throw new ConversionException("Native mesh optimizer returned an invalid vertex");
                indices[index] = vertex;
            }
            result.Add(indices);
        }
        if (reader.BaseStream.Position != reader.BaseStream.Length) throw new ConversionException("Unexpected native mesh index output tail");
        return result;
    }
}
