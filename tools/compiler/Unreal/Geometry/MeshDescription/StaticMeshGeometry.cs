namespace LumenFx.Compiler.Unreal.Geometry.MeshDescription;

internal sealed record StaticMeshSection(int PolygonGroup, string MaterialSlot, int FirstIndex, int IndexCount);
internal enum MeshVertexIdKind { SourceVertexInstance, RenderVertex }

internal sealed record StaticMeshGeometry(int[] SourceInstanceIds, float[] Positions, float[] Normals,
    float[] Tangents, float[] BinormalSigns, float[] Colors, IReadOnlyList<float[]> TextureCoordinates,
    int[] Indices, IReadOnlyList<StaticMeshSection> Sections)
{
    internal MeshVertexIdKind VertexIdKind { get; init; } = MeshVertexIdKind.SourceVertexInstance;
}
