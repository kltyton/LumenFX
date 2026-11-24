using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed record MeshTriangle(int SourceId, int PolygonGroup, int[] Instances, int[] Vertices, int[] Edges);
internal sealed record MeshEdge(int SourceId, int FirstVertex, int SecondVertex, bool Hard);

internal sealed record MeshTopology(IReadOnlyList<int> Vertices, int[] InstanceVertices,
    IReadOnlyList<MeshTriangle> Triangles, IReadOnlyDictionary<int, MeshEdge> Edges,
    IReadOnlyDictionary<int, int[]> VertexEdges, IReadOnlyDictionary<int, int[]> EdgeTriangles)
{
    internal static MeshTopology Read(MeshDescriptionData source, StaticMeshGeometry geometry)
    {
        MeshElement vertices = Element(source, "Vertices"), instances = Element(source, "VertexInstances"),
            edges = Element(source, "Edges"), triangles = Element(source, "Triangles");
        MeshAttribute instanceVertex = IntegerAttribute(instances, "VertexIndex", 1);
        MeshAttribute edgeVertex = IntegerAttribute(edges, "VertexIndex", 2);
        MeshAttribute triangleInstances = IntegerAttribute(triangles, "VertexInstanceIndex", 3);
        MeshAttribute triangleGroups = IntegerAttribute(triangles, "PolygonGroupIndex", 1);
        if (!edges.Attributes.TryGetValue("IsHard", out var hard) || hard.Type != MeshAttributeType.Bool
                || hard.Extent != 1 || hard.Channels.Count > 1)
            throw new ConversionException("Static mesh edge hardness attribute is missing or incompatible");
        var validVertices = new HashSet<int>(vertices.ValidIds);
        var instanceToOutput = new Dictionary<int, int>();
        int[] instanceVertices = new int[geometry.SourceInstanceIds.Length];
        for (int output = 0; output < instanceVertices.Length; output++)
        {
            int instance = geometry.SourceInstanceIds[output];
            int vertex = Integer(instanceVertex, instance);
            if (!validVertices.Contains(vertex)) throw new ConversionException($"Missing mesh topology vertex {vertex}");
            instanceToOutput.Add(instance, output);
            instanceVertices[output] = vertex;
        }
        var edgeById = new Dictionary<int, MeshEdge>();
        var edgeByPair = new Dictionary<(int, int), int>();
        var vertexEdges = vertices.ValidIds.ToDictionary(id => id, _ => new List<int>());
        var edgeTriangles = new Dictionary<int, List<int>>();
        foreach (int id in edges.ValidIds)
        {
            int first = Integer(edgeVertex, id, 0), second = Integer(edgeVertex, id, 1);
            if (!validVertices.Contains(first) || !validVertices.Contains(second))
                throw new ConversionException($"Mesh edge {id} references a missing vertex");
            byte[] hardness = (byte[])(hard.Channels.Count == 0 ? hard.DefaultValue : hard.Channels[0]);
            edgeById.Add(id, new MeshEdge(id, first, second, hardness[hard.Channels.Count == 0 ? 0 : id] != 0));
            edgeByPair.TryAdd(Pair(first, second), id);
            vertexEdges[first].Add(id);
            if (first != second) vertexEdges[second].Add(id);
            edgeTriangles.Add(id, []);
        }
        var resultTriangles = new List<MeshTriangle>();
        foreach (int id in triangles.ValidIds)
        {
            int[] corners = new int[3], triangleVertices = new int[3], triangleEdges = new int[3];
            for (int corner = 0; corner < 3; corner++)
            {
                int instance = Integer(triangleInstances, id, corner);
                if (!instanceToOutput.TryGetValue(instance, out int output))
                    throw new ConversionException($"Mesh triangle {id} references a missing instance {instance}");
                corners[corner] = output;
                triangleVertices[corner] = instanceVertices[output];
            }
            // Triangle edge/vertex attributes are transient in the source format and must be rebuilt.
            for (int corner = 0; corner < 3; corner++)
            {
                var pair = Pair(triangleVertices[corner], triangleVertices[(corner + 1) % 3]);
                if (!edgeByPair.TryGetValue(pair, out int edge))
                    throw new ConversionException($"Mesh triangle {id} has no edge between vertices {pair.Item1} and {pair.Item2}");
                triangleEdges[corner] = edge;
                if (!edgeTriangles[edge].Contains(resultTriangles.Count)) edgeTriangles[edge].Add(resultTriangles.Count);
            }
            resultTriangles.Add(new MeshTriangle(id, Integer(triangleGroups, id), corners, triangleVertices, triangleEdges));
        }
        return new MeshTopology(vertices.ValidIds.ToArray(), instanceVertices, resultTriangles, edgeById,
            vertexEdges.ToDictionary(pair => pair.Key, pair => pair.Value.ToArray()),
            edgeTriangles.ToDictionary(pair => pair.Key, pair => pair.Value.ToArray()));
    }

    private static (int, int) Pair(int first, int second) => first <= second ? (first, second) : (second, first);

    private static MeshElement Element(MeshDescriptionData source, string name)
    {
        if (!source.Elements.TryGetValue(name, out var channels) || channels.Count != 1)
            throw new ConversionException($"Static mesh topology requires one {name} element channel");
        return channels[0];
    }

    private static MeshAttribute IntegerAttribute(MeshElement element, string name, int extent)
    {
        if (!element.Attributes.TryGetValue(name, out var value) || value.Type != MeshAttributeType.Int
                || value.Extent != extent || value.Channels.Count > 1)
            throw new ConversionException($"Static mesh topology has incompatible {name} attribute");
        return value;
    }

    private static int Integer(MeshAttribute attribute, int element, int component = 0)
    {
        int[] values = (int[])(attribute.Channels.Count == 0 ? attribute.DefaultValue : attribute.Channels[0]);
        return values[attribute.Channels.Count == 0 ? 0 : checked(element * attribute.Extent + component)];
    }
}
