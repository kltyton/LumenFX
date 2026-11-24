using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal readonly record struct MeshUvLayoutRequest(int SourceChannel, int Resolution, int Version, float ComparisonThreshold);
internal sealed record MeshUvLayoutResult(bool Packed, float[] Coordinates);

internal static class MeshUvExchange
{
    internal static void WriteInput(string path, StaticMeshGeometry geometry, MeshTopology topology, MeshUvLayoutRequest request)
    {
        using var output = new BinaryWriter(new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None));
        int count = checked(topology.Triangles.Count * 3);
        output.Write(0x5655464c);
        output.Write(1);
        output.Write(count);
        output.Write(request.Resolution);
        output.Write(request.Version);
        output.Write(request.ComparisonThreshold);
        foreach (MeshTriangle triangle in topology.Triangles)
        {
            foreach (int instance in triangle.Instances)
            {
                for (int axis = 0; axis < 3; axis++) output.Write(geometry.Positions[instance * 3 + axis]);
                for (int axis = 0; axis < 3; axis++) output.Write(geometry.Normals[instance * 3 + axis]);
                output.Write(geometry.TextureCoordinates[request.SourceChannel][instance * 2]);
                output.Write(geometry.TextureCoordinates[request.SourceChannel][instance * 2 + 1]);
            }
        }
    }

    internal static MeshUvLayoutResult ReadOutput(string path, int expectedWedges)
    {
        using var input = new BinaryReader(File.OpenRead(path));
        if (input.BaseStream.Length < 16 || input.ReadInt32() != 0x4f55464c || input.ReadInt32() != 1)
            throw new ConversionException("Invalid native lightmap layout result header");
        int count = input.ReadInt32(), status = input.ReadInt32();
        if (count < 0 || status is not (0 or 1) || count != (status == 1 ? expectedWedges : 0)
                || input.BaseStream.Length != checked(16L + count * 8L))
            throw new ConversionException("Native lightmap layout result extent disagrees");
        float[] coordinates = new float[checked(count * 2)];
        for (int i = 0; i < coordinates.Length; i++)
        {
            coordinates[i] = input.ReadSingle();
            if (!float.IsFinite(coordinates[i])) throw new ConversionException("Non-finite packed lightmap coordinate");
        }
        return new MeshUvLayoutResult(status == 1, coordinates);
    }
}
