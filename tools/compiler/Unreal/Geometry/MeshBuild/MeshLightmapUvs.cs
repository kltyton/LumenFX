using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal static class MeshLightmapUvs
{
    internal static async Task<StaticMeshGeometry> GenerateAsync(StaticMeshGeometry source, MeshTopology topology,
        StaticMeshSourceData asset, StaticMeshSourceLod lod, NativeLightmapLayout layout)
    {
        JObject settings = lod.Settings["BuildSettings"] as JObject ?? new JObject();
        if (settings.Value<bool?>("bGenerateLightmapUVs") == false || source.SourceInstanceIds.Length == 0) return source;
        int channels = source.TextureCoordinates.Count;
        if (channels > 8) throw new ConversionException("Static mesh source exceeds MAX_MESH_TEXTURE_COORDS_MD");
        int sourceChannel = settings.Value<int?>("SrcLightmapIndex") ?? 0, destination = settings.Value<int?>("DstLightmapIndex") ?? 1;
        if (sourceChannel < 0 || destination < 0) throw new ConversionException("Negative static mesh lightmap channel index");
        if (sourceChannel >= channels) sourceChannel = 0;
        int expanded = channels;
        if (destination >= channels)
        {
            destination = Math.Min(destination, 7);
            expanded = destination + 1;
            destination = channels;
        }
        float[][] coordinates = new float[expanded][];
        for (int channel = 0; channel < expanded; channel++) coordinates[channel] = channel < channels
            ? source.TextureCoordinates[channel] : new float[checked(source.SourceInstanceIds.Length * 2)];
        var prepared = source with { TextureCoordinates = coordinates };
        int resolution = settings.Value<int?>("MinLightmapResolution") ?? 64;
        int version = asset.Settings.Value<int?>("LightmapUVVersion") ?? 0;
        float threshold = settings.Value<bool?>("bRemoveDegenerates") == false ? 0 : 0.00002f;
        MeshUvLayoutResult result = await layout.PackAsync(prepared, topology, new MeshUvLayoutRequest(sourceChannel, resolution, version, threshold));
        if (!result.Packed) return prepared;
        coordinates[destination] = (float[])coordinates[destination].Clone();
        for (int triangle = 0; triangle < topology.Triangles.Count; triangle++)
        {
            for (int corner = 0; corner < 3; corner++)
            {
                int instance = topology.Triangles[triangle].Instances[corner], wedge = triangle * 3 + corner;
                coordinates[destination][instance * 2] = result.Coordinates[wedge * 2];
                coordinates[destination][instance * 2 + 1] = result.Coordinates[wedge * 2 + 1];
            }
        }
        return prepared;
    }
}
