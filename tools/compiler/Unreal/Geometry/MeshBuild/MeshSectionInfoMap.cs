using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal readonly record struct MeshSectionInfo(int MaterialIndex, bool Collision, bool CastShadow,
    bool VisibleInRayTracing, bool AffectDistanceFieldLighting, bool ForceOpaque)
{
    internal JObject ToJson() => new() {
        ["material_index"] = MaterialIndex, ["collision"] = Collision, ["cast_shadow"] = CastShadow,
        ["visible_in_ray_tracing"] = VisibleInRayTracing, ["affect_distance_field_lighting"] = AffectDistanceFieldLighting,
        ["force_opaque"] = ForceOpaque
    };
}

internal sealed class MeshSectionInfoMap
{
    private readonly Dictionary<uint, MeshSectionInfo> values = new();

    internal MeshSectionInfoMap(JToken? source)
    {
        if (source is null || source.Type == JTokenType.Null) return;
        if (source is not JObject map) throw new ConversionException("Invalid mesh section info structure");
        if (map["Map"] is null) return;
        if (map["Map"] is not JArray entries) throw new ConversionException("Invalid mesh section info map encoding");
        foreach (JArray pair in entries) {
            if (pair.Count != 2 || pair[1] is not JObject value) throw new ConversionException("Invalid mesh section map pair");
            uint key = pair[0].Value<uint>();
            values.Add(key, new MeshSectionInfo(value.Value<int?>("MaterialIndex") ?? 0,
                value.Value<bool?>("bEnableCollision") ?? true, value.Value<bool?>("bCastShadow") ?? true,
                value.Value<bool?>("bVisibleInRayTracing") ?? true, value.Value<bool?>("bAffectDistanceFieldLighting") ?? true,
                value.Value<bool?>("bForceOpaque") ?? false));
        }
    }

    internal bool Contains(int lod, int section) => values.ContainsKey(Key(lod, section));
    internal IEnumerable<KeyValuePair<uint, MeshSectionInfo>> Entries => values;
    internal JObject ToJson() => new() { ["Map"] = new JArray(values.Select(value => new JArray(value.Key, new JObject {
        ["MaterialIndex"] = value.Value.MaterialIndex, ["bEnableCollision"] = value.Value.Collision,
        ["bCastShadow"] = value.Value.CastShadow, ["bVisibleInRayTracing"] = value.Value.VisibleInRayTracing,
        ["bAffectDistanceFieldLighting"] = value.Value.AffectDistanceFieldLighting, ["bForceOpaque"] = value.Value.ForceOpaque
    }))) };
    internal int Count(int lod) => values.Count(value => (value.Key >> 16) == (uint)lod);
    internal void Set(int lod, int section, MeshSectionInfo value) => values[Key(lod, section)] = value;
    internal MeshSectionInfo Get(int lod, int section) => values.TryGetValue(Key(lod, section), out var value)
        || values.TryGetValue(Key(0, section), out value) ? value : new MeshSectionInfo(section, true, true, true, true, false);
    private static uint Key(int lod, int section) => ((uint)lod & 0xffffu) << 16 | ((uint)section & 0xffffu);
}
