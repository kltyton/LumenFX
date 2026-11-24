using System.Numerics;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed record MeshRenderGeometry(StaticMeshGeometry Geometry, int[] WedgeMap);

internal static class MeshRenderIndexBuilder
{
    internal static MeshRenderGeometry Build(StaticMeshGeometry unscaled, MeshScaledGeometry pending,
        MeshTopology topology, bool removeDegenerates)
    {
        StaticMeshGeometry scaled = pending.Geometry;
        float threshold = removeDegenerates ? 0.00002f : 0;
        var overlapping = new MeshOverlappingCorners(unscaled, topology, threshold);
        int count = checked(topology.Triangles.Count * 3);
        int[] remap = new int[count], wedgeMap = new int[count];
        Array.Fill(remap, -1);
        var outputRows = new List<int>();
        var sectionIndices = scaled.Sections.ToDictionary(section => section.PolygonGroup, _ => new List<int>());
        bool colorAllocated = false;
        for (int triangleIndex = 0; triangleIndex < topology.Triangles.Count; triangleIndex++)
        {
            MeshTriangle triangle = topology.Triangles[triangleIndex];
            Vector3 first = MeshTriangleFrames.Vector(unscaled.Positions, triangle.Instances[0]),
                second = MeshTriangleFrames.Vector(unscaled.Positions, triangle.Instances[1]),
                third = MeshTriangleFrames.Vector(unscaled.Positions, triangle.Instances[2]);
            if (Equal(first, second, threshold) || Equal(first, third, threshold) || Equal(second, third, threshold)) continue;
            for (int corner = 0; corner < 3; corner++)
            {
                int wedge = triangleIndex * 3 + corner, row = triangle.Instances[corner], output = -1;
                foreach (int candidate in overlapping.Find(wedge))
                {
                    if (candidate >= wedge) break;
                    int previous = remap[candidate];
                    if (previous >= 0 && Same(pending, row, outputRows[previous], threshold, colorAllocated))
                    {
                        output = previous;
                        break;
                    }
                }
                if (output < 0)
                {
                    output = outputRows.Count;
                    outputRows.Add(row);
                    if (!colorAllocated && !White(scaled.Colors, row)) colorAllocated = true;
                }
                remap[wedge] = output;
                wedgeMap[wedge] = output;
                sectionIndices[triangle.PolygonGroup].Add(output);
            }
        }
        var indices = new List<int>();
        var sections = new List<StaticMeshSection>();
        foreach (StaticMeshSection section in scaled.Sections)
        {
            List<int> values = sectionIndices[section.PolygonGroup];
            sections.Add(new StaticMeshSection(section.PolygonGroup, section.MaterialSlot, values.Count == 0 ? 0 : indices.Count, values.Count));
            indices.AddRange(values);
        }
        StaticMeshGeometry result = MeshVertexRemap.Apply(scaled, outputRows, indices.ToArray(), sections);
        return new MeshRenderGeometry(result, wedgeMap);
    }

    private static bool Same(MeshScaledGeometry pending, int first, int second, float positionThreshold, bool colorAllocated)
    {
        StaticMeshGeometry geometry = pending.Geometry;
        if (!Equal(MeshTriangleFrames.Vector(geometry.Positions, first), MeshTriangleFrames.Vector(geometry.Positions, second), positionThreshold)
                || !Equal(MeshTriangleFrames.Vector(geometry.Normals, first), MeshTriangleFrames.Vector(geometry.Normals, second), 0.00002f)
                || !Equal(MeshTriangleFrames.Vector(geometry.Tangents, first), MeshTriangleFrames.Vector(geometry.Tangents, second), 0.00002f)
                || !Equal(MeshTriangleFrames.Vector(pending.Binormals, first), MeshTriangleFrames.Vector(pending.Binormals, second), 0.00002f)) return false;
        if (colorAllocated)
            for (int component = 0; component < 4; component++)
                if (geometry.Colors[first * 4 + component] != geometry.Colors[second * 4 + component]) return false;
        foreach (float[] coordinates in geometry.TextureCoordinates)
        {
            if (!(MathF.Abs(coordinates[first * 2] - coordinates[second * 2]) <= 1.0f / 1024.0f)
                    || !(MathF.Abs(coordinates[first * 2 + 1] - coordinates[second * 2 + 1]) <= 1.0f / 1024.0f)) return false;
        }
        return true;
    }

    private static bool Equal(Vector3 first, Vector3 second, float threshold) => MathF.Abs(first.X - second.X) <= threshold
        && MathF.Abs(first.Y - second.Y) <= threshold && MathF.Abs(first.Z - second.Z) <= threshold;

    private static bool White(float[] colors, int row) => colors[row * 4] == 1 && colors[row * 4 + 1] == 1
        && colors[row * 4 + 2] == 1 && colors[row * 4 + 3] == 1;

}
