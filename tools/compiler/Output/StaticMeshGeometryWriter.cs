using LumenFx.Geometry;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Output;

internal static class StaticMeshGeometryWriter
{
    // Positions, basis vectors and winding remain in the source Unreal coordinate system.
    internal static byte[] Write(StaticMeshGeometry geometry)
    {
        return StaticMeshPayloadWriter.Write((int)geometry.VertexIdKind, geometry.SourceInstanceIds, geometry.Indices,
            geometry.TextureCoordinates.Count, geometry.Sections.Count,
            writer =>
            {
                foreach (float[] values in new[] { geometry.Positions, geometry.Normals, geometry.Tangents,
                             geometry.BinormalSigns, geometry.Colors }.Concat(geometry.TextureCoordinates))
                    foreach (float value in values) writer.Write(value);
            },
            writer =>
            {
                foreach (StaticMeshSection section in geometry.Sections)
                    StaticMeshPayloadWriter.WriteSection(writer, section.PolygonGroup, section.MaterialSlot,
                        section.FirstIndex, section.IndexCount);
            });
    }
}
