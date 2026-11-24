namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed record MeshNormalCorner(int Triangle, int Instance, IReadOnlyList<int> Edges);

internal static class MeshNormalGroups
{
    internal static IReadOnlyDictionary<int, MeshNormalCorner> Neighborhood(MeshTopology topology, int vertex)
    {
        var corners = new Dictionary<int, MeshNormalCorner>();
        foreach (int edge in topology.VertexEdges[vertex])
        {
            foreach (int triangleIndex in topology.EdgeTriangles[edge])
            {
                if (!corners.TryGetValue(triangleIndex, out var corner))
                {
                    MeshTriangle triangle = topology.Triangles[triangleIndex];
                    int firstCorner = Array.IndexOf(triangle.Vertices, vertex);
                    if (firstCorner < 0) throw new ConversionException("Mesh edge adjacency references an unrelated triangle");
                    corner = new MeshNormalCorner(triangleIndex, triangle.Instances[firstCorner], new List<int>());
                    corners.Add(triangleIndex, corner);
                }
                var connected = (List<int>)corner.Edges;
                if (!connected.Contains(edge)) connected.Add(edge);
            }
        }
        return corners;
    }

    internal static IReadOnlyList<IReadOnlyList<MeshNormalCorner>> Connected(MeshTopology topology,
        IReadOnlyDictionary<int, MeshNormalCorner> neighborhood)
    {
        var groups = new List<IReadOnlyList<MeshNormalCorner>>();
        var consumedTriangles = new HashSet<int>();
        var consumedEdges = new HashSet<int>();
        var pending = new List<int>();
        foreach ((int first, MeshNormalCorner _) in neighborhood)
        {
            if (consumedTriangles.Contains(first)) continue;
            var group = new List<MeshNormalCorner>();
            var groupTriangles = new HashSet<int>();
            pending.Clear();
            pending.Add(first);
            while (pending.Count != 0)
            {
                int current = pending[^1];
                pending.RemoveAt(pending.Count - 1);
                MeshNormalCorner corner = neighborhood[current];
                if (groupTriangles.Add(current)) group.Add(corner);
                consumedTriangles.Add(current);
                foreach (int edge in corner.Edges)
                {
                    if (topology.Edges[edge].Hard || !consumedEdges.Add(edge)) continue;
                    foreach (int other in topology.EdgeTriangles[edge])
                    {
                        if (other != current && !consumedTriangles.Contains(other)) pending.Add(other);
                    }
                }
            }
            groups.Add(group);
        }
        return groups;
    }
}
