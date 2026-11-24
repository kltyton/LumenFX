using System.Numerics;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed class MeshOverlappingCorners
{
    private readonly int[] belongsTo;
    private readonly int[][] groups;
    private readonly record struct Entry(float Projection, int Wedge);

    internal MeshOverlappingCorners(StaticMeshGeometry geometry, MeshTopology topology, float threshold)
    {
        int count = checked(topology.Triangles.Count * 3);
        belongsTo = new int[count];
        Array.Fill(belongsTo, -1);
        var entries = new Entry[count];
        var positions = new Vector3[count];
        for (int triangle = 0; triangle < topology.Triangles.Count; triangle++)
        {
            for (int corner = 0; corner < 3; corner++)
            {
                int wedge = triangle * 3 + corner;
                Vector3 position = MeshTriangleFrames.Vector(geometry.Positions, topology.Triangles[triangle].Instances[corner]);
                positions[wedge] = position;
                entries[wedge] = new Entry(0.30f * position.X + 0.33f * position.Y + 0.37f * position.Z, wedge);
            }
        }
        Array.Sort(entries, (first, second) => first.Projection < second.Projection ? -1
            : second.Projection < first.Projection ? 1 : 0);
        var containers = new List<HashSet<int>>();
        for (int i = 0; i < entries.Length; i++)
        {
            for (int j = i + 1; j < entries.Length; j++)
            {
                if (MathF.Abs(entries[j].Projection - entries[i].Projection) > threshold) break;
                int key = entries[i].Wedge, value = entries[j].Wedge;
                Vector3 difference = Vector3.Abs(positions[key] - positions[value]);
                if (difference.X > threshold || difference.Y > threshold || difference.Z > threshold) continue;
                int container = belongsTo[key];
                if (container < 0)
                {
                    container = containers.Count;
                    containers.Add([key, value]);
                    belongsTo[key] = container;
                }
                else containers[container].Add(value);
                // This assignment follows the source container ownership; it is not a union-find merge.
                belongsTo[value] = container;
            }
        }
        groups = containers.Select(container => container.Order().ToArray()).ToArray();
    }

    internal IReadOnlyList<int> Find(int wedge) => belongsTo[wedge] < 0 ? Array.Empty<int>() : groups[belongsTo[wedge]];
}
