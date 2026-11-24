using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;

namespace LumenFx.Compiler.Unreal.Geometry;

internal static class MeshRendererCompiler
{
    internal const string DefaultSurfaceMaterial = "/Engine/EngineMaterials/WorldGridMaterial.WorldGridMaterial";
    internal static JObject Compile(SourceAsset owner, JObject renderer, Func<string, StaticMeshSourceData> sourceMesh,
        Func<string, string> material, bool nativeNanite = false)
    {
        JArray entries = new();
        JArray meshes = nativeNanite ? renderer["Meshes"] as JArray ?? new JArray() : SourceEntries(renderer);
        var replacements = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (JObject value in renderer["MICOverrideMaterials"] as JArray ?? new JArray()) {
            int original = value.Value<int>("OriginalMaterial"), replacement = value.Value<int>("ReplacementMaterial");
            if (original != 0 && replacement != 0) replacements.TryAdd(owner.ObjectPath(original), owner.ObjectPath(replacement));
        }
        JObject remap = new();
        foreach ((string original, string replacement) in replacements) remap[original] = material(replacement);
        JArray overrides = new();
        if (renderer.Value<bool?>("bOverrideMaterials") == true)
            foreach (JToken value in renderer["OverrideMaterials"] as JArray ?? new JArray()) {
                int reference = value.Type == JTokenType.Integer ? value.Value<int>() : value.Value<int?>("ExplicitMat") ?? 0;
                overrides.Add(new JObject { ["material"] = reference == 0 ? null : material(owner.ObjectPath(reference)),
                    ["user_parameter"] = value is JObject binding ? UserParameter(binding["UserParamBinding"]) : null });
            }
        foreach (JObject mesh in meshes) {
            var entry = SourceEntry(owner, mesh, nativeNanite);
            BindEntryMaterials(entry, sourceMesh, material);
            entries.Add(entry);
        }
        return new JObject {
            ["scene_transforms"] = "gpu_scene_compressed",
            ["entries"] = entries, ["meshes_binding"] = nativeNanite ? null : Parameter(renderer["MeshesBinding"]),
            ["override_materials"] = overrides, ["material_remap"] = remap,
            ["facing_mode"] = NiagaraRenderer.SourceEnum(renderer, "FacingMode", "Default", "Default", "Velocity", "CameraPosition", "CameraPlane") switch {
                "Default" => "default", "Velocity" => "velocity", "CameraPosition" => "camera_position", "CameraPlane" => "camera_plane",
                var value => throw new ConversionException($"Unknown mesh facing mode: {value}") },
            ["locked_axis_enabled"] = renderer.Value<bool?>("bLockedAxisEnable") ?? false,
            ["locked_axis"] = Vector(renderer["LockedAxis"], 0, 0, 1),
            ["locked_axis_space"] = NiagaraRenderer.SourceEnum(renderer, "LockedAxisSpace", "Simulation", "Simulation", "World", "Local").ToLowerInvariant(),
            ["bounds_scale"] = Vector(renderer["MeshBoundsScale"], 1, 1, 1),
            ["frustum_culling"] = renderer.Value<bool?>("bEnableFrustumCulling") ?? false,
            ["cast_shadows"] = renderer.Value<bool?>("bCastShadows") ?? true };
    }

    internal static JObject SourceEntry(SourceAsset owner, JObject mesh, bool nativeNanite = false)
    {
        int reference = mesh.Value<int?>("Mesh") ?? 0;
        return new JObject {
            ["asset_source"] = reference == 0 ? null : owner.ObjectPath(reference), ["asset"] = null,
            ["asset_binding"] = nativeNanite ? Parameter(mesh["MeshParameterBinding"])
                : UserParameter(mesh["UserParamBinding"]) ?? Parameter(mesh["MeshParameterBinding"]),
            ["scale"] = Vector(mesh["Scale"], 1, 1, 1),
            ["rotation"] = Rotation(mesh["Rotation"]), ["pivot"] = Vector(mesh["PivotOffset"], 0, 0, 0),
            ["pivot_space"] = NiagaraRenderer.SourceEnum(mesh, "PivotOffsetSpace", "Mesh", "Mesh", "Simulation", "World", "Local").ToLowerInvariant(),
            ["lod_mode"] = NiagaraRenderer.SourceEnum(mesh, "LODMode", "LODLevel",
                "LODLevel", "LODBias", "ByComponentBounds", "ComponentOrigin", "PerParticle") switch {
                "LODLevel" => "lod_level", "LODBias" => "lod_bias", "ByComponentBounds" => "by_component_bounds",
                "ComponentOrigin" => "component_origin", "PerParticle" => "per_particle",
                var value => throw new ConversionException($"Unknown mesh LOD mode: {value}") },
            ["lod_level"] = IntegerBinding(mesh, "LODLevel"), ["lod_bias"] = IntegerBinding(mesh, "LODBias"),
            ["lod_distance_factor"] = SourceAsset.Number(mesh["LODDistanceFactor"], 1),
            ["use_lod_range"] = mesh.Value<bool?>("bUseLODRange") ?? false,
            ["lod_range_min"] = mesh["LODRange"]?.Value<int?>("X") ?? 0,
            ["lod_range_max"] = mesh["LODRange"]?.Value<int?>("Y") ?? 8 };
    }

    internal static void BindEntryMaterials(JObject entry, Func<string, StaticMeshSourceData> sourceMesh, Func<string, string> material)
    {
        JArray bindings = new();
        string? fallback = null;
        if (entry.Value<string>("asset_source") is { } source) {
            var asset = sourceMesh(source);
            var used = MeshUsedMaterials.Collect(asset);
            for (int slot = 0; slot < asset.Materials.Count; slot++) {
                string path = asset.Materials[slot].Material;
                if (path == "None") path = DefaultSurfaceMaterial;
                bindings.Add(new JObject { ["material"] = null, ["user_parameter"] = null,
                    ["default_material"] = used.Contains(slot) ? material(path) : null });
            }
            if (used.Any(index => index < 0 || index >= asset.Materials.Count)) fallback = material(DefaultSurfaceMaterial);
        }
        entry["fallback_material"] = fallback;
        entry["materials"] = bindings;
    }

    internal static JArray SourceEntries(JObject renderer)
    {
        JArray meshes = (JArray?)renderer["Meshes"]?.DeepClone() ?? new JArray(new JObject());
        if (meshes.Count == 1 && (meshes[0].Value<int?>("Mesh") is null or 0) && renderer.Value<int?>("ParticleMesh") is { } legacy && legacy != 0) {
            meshes[0]["Mesh"] = legacy;
            meshes[0]["PivotOffset"] = renderer["PivotOffset"]?.DeepClone();
            meshes[0]["PivotOffsetSpace"] = renderer["PivotOffsetSpace"]?.DeepClone();
        }
        return meshes;
    }

    private static JObject IntegerBinding(JObject mesh, string field) => new() {
        ["name"] = Parameter(mesh[field + "Binding"]), ["value"] = mesh.Value<int?>(field) ?? 0 };
    private static string? Parameter(JToken? binding) => Valid(binding?["ResolvedParameter"]?.Value<string>("name"));
    private static string? UserParameter(JToken? binding) => Valid(binding?["Parameter"]?.Value<string>("name"));
    private static string? Valid(string? value) => string.IsNullOrEmpty(value) || value == "None" ? null : value;
    private static JArray Vector(JToken? value, double x, double y, double z) => new(
        SourceAsset.Number(value?["X"], x), SourceAsset.Number(value?["Y"], y), SourceAsset.Number(value?["Z"], z));

    private static JArray Rotation(JToken? value)
    {
        double halfRadians = Math.PI / 360;
        double pitch = SourceAsset.Number(value?["Pitch"]) % 360 * halfRadians;
        double yaw = SourceAsset.Number(value?["Yaw"]) % 360 * halfRadians;
        double roll = SourceAsset.Number(value?["Roll"]) % 360 * halfRadians;
        double sp = Math.Sin(pitch), cp = Math.Cos(pitch), sy = Math.Sin(yaw), cy = Math.Cos(yaw), sr = Math.Sin(roll), cr = Math.Cos(roll);
        return new JArray(cr * sp * sy - sr * cp * cy, -cr * sp * cy - sr * cp * sy,
            cr * cp * sy - sr * sp * cy, cr * cp * cy + sr * sp * sy);
    }
}
