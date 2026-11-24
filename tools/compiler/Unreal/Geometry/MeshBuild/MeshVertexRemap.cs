using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal static class MeshVertexRemap
{
    internal static StaticMeshGeometry Apply(StaticMeshGeometry source, IReadOnlyList<int> rows,
        int[] indices, IReadOnlyList<StaticMeshSection> sections) => new(
            rows.Select(row => source.SourceInstanceIds[row]).ToArray(), Select(source.Positions, 3, rows),
            Select(source.Normals, 3, rows), Select(source.Tangents, 3, rows), Select(source.BinormalSigns, 1, rows),
            Select(source.Colors, 4, rows), source.TextureCoordinates.Select(channel => Select(channel, 2, rows)).ToArray(),
            indices, sections);

    private static float[] Select(float[] source, int components, IReadOnlyList<int> rows)
    {
        float[] result = new float[checked(rows.Count * components)];
        for (int output = 0; output < rows.Count; output++)
            Array.Copy(source, checked(rows[output] * components), result, output * components, components);
        return result;
    }
}
