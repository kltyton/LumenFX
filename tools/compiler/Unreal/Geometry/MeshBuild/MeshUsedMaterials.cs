using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal static class MeshUsedMaterials
{
    internal static HashSet<int> Collect(StaticMeshSourceData mesh)
    {
        var map = new MeshSectionInfoMap(mesh.Settings["SectionInfoMap"]);
        HashSet<int> result = new();
        foreach (var lod in mesh.Lods) {
            StaticMeshGeometry? geometry = lod.Geometry;
            if (geometry is null) {
                int basis = lod.Settings["ReductionSettings"]?.Value<int?>("BaseLODModel") ?? 0;
                if (basis < 0 || basis >= mesh.Lods.Count) throw new ConversionException("Generated mesh LOD has an invalid base source");
                geometry = mesh.Lods[basis].Geometry;
                if (geometry is null) throw new ConversionException("Generated mesh LOD requires its base geometry");
            }
            for (int section = 0; section < geometry.Sections.Count; section++) result.Add(map.Get(lod.Index, section).MaterialIndex);
        }
        return result;
    }
}
