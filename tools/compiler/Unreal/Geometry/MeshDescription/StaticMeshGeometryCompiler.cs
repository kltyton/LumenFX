namespace LumenFx.Compiler.Unreal.Geometry.MeshDescription;

internal static class StaticMeshGeometryCompiler
{
    internal static StaticMeshGeometry Compile(MeshDescriptionData source)
    {
        MeshElement vertices = Element(source, "Vertices");
        MeshElement instances = Element(source, "VertexInstances");
        MeshElement triangles = Element(source, "Triangles");
        MeshElement groups = Element(source, "PolygonGroups");
        MeshAttribute position = Attribute(vertices, "Position ", MeshAttributeType.Vector3, 1);
        MeshAttribute vertex = Attribute(instances, "VertexIndex", MeshAttributeType.Int, 1);
        MeshAttribute normal = Attribute(instances, "Normal", MeshAttributeType.Vector3, 1);
        MeshAttribute tangent = Attribute(instances, "Tangent", MeshAttributeType.Vector3, 1);
        MeshAttribute sign = Attribute(instances, "BinormalSign", MeshAttributeType.Float, 1);
        MeshAttribute color = Attribute(instances, "Color", MeshAttributeType.Vector4, 1);
        MeshAttribute uv = Attribute(instances, "TextureCoordinate", MeshAttributeType.Vector2, 1);
        MeshAttribute corners = Attribute(triangles, "VertexInstanceIndex", MeshAttributeType.Int, 3);
        MeshAttribute groupIds = Attribute(triangles, "PolygonGroupIndex", MeshAttributeType.Int, 1);
        MeshAttribute slotNames = Attribute(groups, "ImportedMaterialSlotName", MeshAttributeType.Name, 1);
        int count = instances.ValidIds.Count;
        int[] sourceIds = instances.ValidIds.ToArray();
        int[] remap = new int[instances.Slots];
        Array.Fill(remap, -1);
        var positions = new float[checked(count * 3)];
        var normals = new float[checked(count * 3)];
        var tangents = new float[checked(count * 3)];
        var signs = new float[count];
        var colors = new float[checked(count * 4)];
        float[][] textureCoordinates = Enumerable.Range(0, uv.Channels.Count)
            .Select(_ => new float[checked(count * 2)]).ToArray();
        var validVertices = new HashSet<int>(vertices.ValidIds);
        for (int output = 0; output < count; output++)
        {
            int instance = sourceIds[output];
            int vertexId = Integer(vertex, instance);
            if (!validVertices.Contains(vertexId)) throw new ConversionException($"Mesh instance references missing vertex {vertexId}");
            remap[instance] = output;
            Copy(position, vertexId, positions, output);
            Copy(normal, instance, normals, output);
            Copy(tangent, instance, tangents, output);
            Copy(sign, instance, signs, output);
            Copy(color, instance, colors, output);
            for (int channel = 0; channel < textureCoordinates.Length; channel++)
                Copy(uv, instance, textureCoordinates[channel], output, channel);
        }
        var groupedIndices = new SortedDictionary<int, List<int>>();
        var validGroups = new HashSet<int>(groups.ValidIds);
        foreach (int triangle in triangles.ValidIds)
        {
            int group = Integer(groupIds, triangle);
            if (!validGroups.Contains(group)) throw new ConversionException($"Mesh triangle references missing polygon group {group}");
            if (!groupedIndices.TryGetValue(group, out var indices)) groupedIndices.Add(group, indices = []);
            for (int corner = 0; corner < 3; corner++)
            {
                int instance = Integer(corners, triangle, corner);
                if (instance < 0 || instance >= remap.Length || remap[instance] < 0)
                    throw new ConversionException($"Mesh triangle references missing vertex instance {instance}");
                indices.Add(remap[instance]);
            }
        }
        var allIndices = new List<int>(checked(triangles.ValidIds.Count * 3));
        var sections = new List<StaticMeshSection>();
        foreach (int group in groups.ValidIds)
        {
            List<int> indices = groupedIndices.TryGetValue(group, out var values) ? values : [];
            string[] names = (string[])(slotNames.Channels.Count == 0 ? slotNames.DefaultValue : slotNames.Channels[0]);
            string slot = names[slotNames.Channels.Count == 0 ? 0 : group];
            sections.Add(new StaticMeshSection(group, slot, indices.Count == 0 ? 0 : allIndices.Count, indices.Count));
            allIndices.AddRange(indices);
        }
        return new StaticMeshGeometry(sourceIds, positions, normals, tangents, signs, colors,
            textureCoordinates, allIndices.ToArray(), sections);
    }

    private static MeshElement Element(MeshDescriptionData source, string name)
    {
        if (!source.Elements.TryGetValue(name, out var channels) || channels.Count != 1)
            throw new ConversionException($"Static MeshDescription requires one {name} element channel");
        return channels[0];
    }

    private static MeshAttribute Attribute(MeshElement element, string name, MeshAttributeType type, int extent)
    {
        if (!element.Attributes.TryGetValue(name, out var attribute) || attribute.Type != type || attribute.Extent != extent)
            throw new ConversionException($"Static MeshDescription has missing or incompatible {name} attribute");
        if (name != "TextureCoordinate" && attribute.Channels.Count > 1)
            throw new ConversionException($"Static MeshDescription has multiple channels for {name}");
        return attribute;
    }

    private static int Integer(MeshAttribute attribute, int element, int component = 0)
    {
        int[] values = (int[])(attribute.Channels.Count == 0 ? attribute.DefaultValue : attribute.Channels[0]);
        return values[attribute.Channels.Count == 0 ? 0 : checked(element * attribute.Extent + component)];
    }

    private static void Copy(MeshAttribute attribute, int element, float[] target, int output, int channel = 0)
    {
        float[] values = (float[])(attribute.Channels.Count == 0 ? attribute.DefaultValue : attribute.Channels[channel]);
        int offset = attribute.Channels.Count == 0 ? 0 : checked(element * attribute.Components);
        Array.Copy(values, offset, target, checked(output * attribute.Components), attribute.Components);
    }
}
