using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal static class MeshVertexCache
{
    internal static MeshRenderGeometry Optimize(MeshRenderGeometry source, int sourceInstanceCount, NativeMeshIndexOrder order)
        => Optimize(source, sourceInstanceCount, order.Optimize);

    internal static MeshRenderGeometry Optimize(MeshRenderGeometry source, int sourceInstanceCount, Func<int[], int, int[]> order)
    {
        StaticMeshGeometry geometry = source.Geometry;
        int vertices = geometry.SourceInstanceIds.Length;
        if (sourceInstanceCount >= 300_000 || vertices == 0) return source;
        int[] oldToNew = new int[vertices];
        Array.Fill(oldToNew, -1);
        int[] rows = Enumerable.Range(0, vertices).ToArray();
        int next = 0;
        var indices = new List<int>(geometry.Indices.Length);
        var sections = new List<StaticMeshSection>();
        foreach (StaticMeshSection section in geometry.Sections)
        {
            int[] original = geometry.Indices.AsSpan(section.FirstIndex, section.IndexCount).ToArray();
            int[] optimized = order(original, vertices);
            int firstIndex = optimized.Length == 0 ? 0 : indices.Count;
            foreach (int previous in optimized)
            {
                int output = oldToNew[previous];
                if (output < 0)
                {
                    output = next++;
                    oldToNew[previous] = output;
                    rows[output] = previous;
                }
                indices.Add(output);
            }
            sections.Add(new StaticMeshSection(section.PolygonGroup, section.MaterialSlot, firstIndex, optimized.Length));
        }
        int[] wedgeMap = (int[])source.WedgeMap.Clone();
        for (int wedge = 0; wedge < wedgeMap.Length; wedge++)
            if (wedgeMap[wedge] >= 0) wedgeMap[wedge] = oldToNew[wedgeMap[wedge]];
        return new MeshRenderGeometry(MeshVertexRemap.Apply(geometry, rows, indices.ToArray(), sections), wedgeMap);
    }
}
