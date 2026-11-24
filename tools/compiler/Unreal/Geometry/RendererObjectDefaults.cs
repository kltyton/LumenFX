using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Geometry;

internal static class RendererObjectDefaults
{
    internal static JObject Compile(JObject store, JObject renderer, Func<string, StaticMeshSourceData> sourceMesh,
        Func<string, string> material)
    {
        Dictionary<string, string> bindings = new(SourceNameOrder.Instance);
        foreach (JObject entry in (JArray)renderer["entries"]!)
            if (entry.Value<string>("asset_binding") is { } name) bindings[name] = "mesh";
        foreach (JObject entry in (JArray)renderer["override_materials"]!)
            if (entry.Value<string>("user_parameter") is { } name) bindings[name] = "material";
        return CompileBindings(store, bindings, sourceMesh, material, renderer.Value<string>("material_variant") ?? "mesh");
    }

    internal static JObject CompileMaterials(JObject store, string? binding,
        Func<string, string> material, string vertexFactory)
    {
        Dictionary<string, string> bindings = new(SourceNameOrder.Instance);
        if (binding is not null) bindings[binding] = "material";
        return CompileBindings(store, bindings, null, material, vertexFactory);
    }

    private static JObject CompileBindings(JObject store, Dictionary<string, string> bindings,
        Func<string, StaticMeshSourceData>? sourceMesh, Func<string, string> material, string vertexFactory)
    {
        JObject result = new();
        JArray objects = (JArray)store["object_values"]!;
        foreach (JObject variable in (JArray)store["variables"]!) {
            string name = variable.Value<string>("name") ?? throw new ConversionException("User object parameter has no name");
            if (!bindings.TryGetValue(name, out string? kind)) continue;
            if (variable.Value<string>("storage") != "object") continue;
            string type = variable.Value<string>("source_type")!;
            if (kind == "mesh" && !type.EndsWith(".StaticMesh", StringComparison.Ordinal)) continue;
            if (kind == "material" && !(type.EndsWith(".MaterialInterface", StringComparison.Ordinal)
                    || type.EndsWith(".Material", StringComparison.Ordinal) || type.EndsWith(".MaterialInstanceConstant", StringComparison.Ordinal)
                    || type.EndsWith(".MaterialInstanceDynamic", StringComparison.Ordinal))) continue;
            int offset = variable.Value<int>("object_index");
            string? source = (objects[offset] as JObject)?.Value<string>("path");
            if (kind == "material") {
                result[name] = new JObject { ["type"] = "material", ["material"] = source is null ? null : material(source),
                    ["material_variant"] = vertexFactory };
                continue;
            }
            JArray materials = new();
            string? fallback = null;
            if (source is not null) {
                var mesh = (sourceMesh ?? throw new ConversionException("Mesh object binding has no source resolver"))(source);
                var used = MeshUsedMaterials.Collect(mesh);
                for (int slot = 0; slot < mesh.Materials.Count; slot++) {
                    string path = mesh.Materials[slot].Material;
                    if (path == "None") path = MeshRendererCompiler.DefaultSurfaceMaterial;
                    materials.Add(new JObject { ["material"] = null, ["user_parameter"] = null,
                        ["default_material"] = used.Contains(slot) ? material(path) : null });
                }
                if (used.Any(index => index < 0 || index >= mesh.Materials.Count)) fallback = material(MeshRendererCompiler.DefaultSurfaceMaterial);
            }
            result[name] = new JObject { ["type"] = "mesh", ["asset_source"] = source, ["asset"] = null, ["materials"] = materials,
                ["fallback_material"] = fallback };
        }
        return result;
    }

    internal static void Merge(JObject destination, JObject values)
    {
        foreach (var (name, value) in values)
        {
            if (destination[name] is not JToken existing || JToken.DeepEquals(existing, value))
            {
                destination[name] = value?.DeepClone();
                continue;
            }
            if (existing is not JObject left || value is not JObject right
                    || !IsMaterial(left) || !IsMaterial(right))
                throw new ConversionException($"Renderer object defaults disagree: {name}");
            JObject variants = Variants(left);
            foreach (var (factory, material) in Variants(right))
            {
                if (variants[factory] is JToken previous && !JToken.DeepEquals(previous, material))
                    throw new ConversionException($"Renderer material defaults disagree: {name}, {factory}");
                variants[factory] = material?.DeepClone();
            }
            destination[name] = new JObject { ["type"] = "material_variants", ["materials"] = variants };
        }
    }

    private static bool IsMaterial(JObject value) => value.Value<string>("type") is "material" or "material_variants";
    private static JObject Variants(JObject value) => value.Value<string>("type") == "material_variants"
        ? (JObject)value["materials"]!.DeepClone()
        : new JObject { [value.Value<string>("material_variant") ?? "mesh"] = value["material"]!.DeepClone() };
}
