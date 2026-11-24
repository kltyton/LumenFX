using System.Text;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Output;

internal static partial class StaticMeshRenderPackage
{
    [GeneratedRegex("^[a-z0-9_]+:[a-z0-9_/.-]+$")]
    private static partial Regex IdentifierPattern();

    internal static async Task WriteAsync(SourceAsset source, string identifier, string resourcesDirectory,
        string? oodleLibrary, MeshBuildTools tools, string? objectPath = null)
    {
        tools.RequireAbsolutePaths();
        if (!IdentifierPattern().IsMatch(identifier) || identifier.Contains("..", StringComparison.Ordinal))
            throw new ConversionException($"Invalid static mesh identifier: {identifier}");
        var files = objectPath is null ? await CollectAsync(source, identifier, oodleLibrary, tools)
            : await CollectAsync(StaticMeshSourceCompiler.Compile(source, objectPath, oodleLibrary), identifier, tools);
        string root = Path.GetFullPath(Path.Combine(resourcesDirectory, "assets", identifier.Split(':')[0]));
        foreach ((string relative, byte[] bytes) in files)
        {
            string path = Path.GetFullPath(Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar)));
            if (!path.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new ConversionException("Static mesh output escapes resource namespace");
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllBytes(path, bytes);
        }
        Console.WriteLine($"Wrote built static mesh: {identifier}");
    }

    internal static async Task<IReadOnlyDictionary<string, byte[]>> CollectAsync(SourceAsset source, string identifier,
        string? oodleLibrary, MeshBuildTools tools)
    {
        tools.RequireAbsolutePaths();
        if (!IdentifierPattern().IsMatch(identifier) || identifier.Contains("..", StringComparison.Ordinal))
            throw new ConversionException($"Invalid static mesh identifier: {identifier}");
        return await CollectAsync(StaticMeshSourceCompiler.Compile(source, oodleLibrary), identifier, tools);
    }

    internal static async Task<IReadOnlyDictionary<string, byte[]>> CollectAsync(StaticMeshSourceData mesh, string identifier, MeshBuildTools tools)
    {
        tools.RequireAbsolutePaths();
        if (!IdentifierPattern().IsMatch(identifier) || identifier.Contains("..", StringComparison.Ordinal))
            throw new ConversionException($"Invalid static mesh identifier: {identifier}");
        string[] parts = identifier.Split(':');
        if (mesh.Rendered is null && mesh.PreparedLods is null
                && (tools.MeshDescriptionExecutable is not null || NativeMeshRender.Required(mesh)))
            mesh = await NativeMeshRender.BuildAsync(mesh, tools).ConfigureAwait(false);
        using var mikk = mesh.Rendered is null && mesh.PreparedLods is null ? new NativeMikkTangents(tools.NativeLibrary
            ?? throw new ConversionException("Legacy MikkTSpace preparation requires its source library")) : null;
        using var ordering = mesh.Rendered is null ? new NativeMeshIndexOrder(tools.NativeLibrary, tools.TriangleOrderOptimization == 0 ? 2 : tools.TriangleOrderOptimization) : null;
        var layout = mesh.Rendered is null && mesh.PreparedLods is null ? new NativeLightmapLayout(tools.UvLayoutExecutable
            ?? throw new ConversionException("Legacy source mesh build requires its lightmap layout executable"), tools.WorkDirectory) : null;
        var colors = mesh.Rendered is null ? new MeshVertexColors(tools.UnrealSourceRoot) : null;
        var files = new Dictionary<string, byte[]>(StringComparer.Ordinal);
        string prefix = "lumenfx/meshes/" + parts[1];
        JArray lods = new();
        var sectionInfo = new MeshSectionInfoMap(mesh.Settings["SectionInfoMap"]);
        MeshSourceBounds renderBounds = default;
        float previousScreenSize = 0;
        var builtLods = new List<(StaticMeshSourceLod Source, MeshSourceBuilder.BuiltLod Built)>();
        foreach (StaticMeshSourceLod lod in mesh.Lods)
        {
            MeshSourceBuilder.BuiltLod built = mesh.Rendered is { } rendered ? rendered.Lods[lod.Index] : mesh.PreparedLods is { } prepared
                ? MeshSourceBuilder.BuildPreparedLod(mesh, prepared[lod.Index], ordering!, colors!, tools.TriangleOrderOptimization != 0)
                : await MeshSourceBuilder.BuildImportedLodAsync(mesh, lod, mikk!, ordering!, layout!, colors!, tools.TriangleOrderOptimization != 0);
            renderBounds = builtLods.Count == 0 ? built.Bounds : renderBounds.Union(built.Bounds);
            builtLods.Add((lod, built));
        }
        if (mesh.Rendered is not null) renderBounds = mesh.Rendered.Bounds;
        else if (tools.TriangleOrderOptimization == 0) await NativeMeshIndexBatch.ApplyAsync(builtLods, tools).ConfigureAwait(false);
        foreach (var (lod, built) in builtLods)
        {
            string path = prefix + "/lod_" + lod.Index + ".lfmh";
            files.Add(path, StaticMeshGeometryWriter.Write(built.Geometry));
            var bounds = built.Bounds;
            JToken screenSize = MeshRenderMetadata.ScreenSize(mesh.Settings, lod.Settings, lod.Index,
                built.MaximumDeviation, (float)renderBounds.Radius, previousScreenSize, built.PixelError);
            previousScreenSize = screenSize is JObject platform ? (float)SourceAsset.Number(platform["default"], 1)
                : (float)SourceAsset.Number(screenSize, 1);
            JObject definitionLod = new() {
                ["geometry"] = parts[0] + ":" + path, ["settings"] = lod.Settings.DeepClone(),
                ["bounds"] = MeshRenderMetadata.Bounds(bounds), ["screen_size"] = screenSize,
                ["sections"] = new JArray(built.Geometry.Sections.Select((section, index) => sectionInfo.Get(lod.Index, index).ToJson())) };
            if (mesh.Rendered is { } cooked && lod.Index >= cooked.FirstAvailableLod && lod.Index < cooked.FirstInlineLod
                    && built.Geometry.SourceInstanceIds.Length > 0)
            {
                definitionLod["streamable_vertex_count"] = built.Geometry.SourceInstanceIds.Length;
                var sections = (JArray)definitionLod["sections"]!;
                for (int index = 0; index < sections.Count; index++)
                {
                    var section = built.Geometry.Sections[index];
                    var metadata = (JObject)sections[index]!;
                    metadata["polygon_group"] = section.PolygonGroup;
                    metadata["material_slot"] = section.MaterialSlot;
                    metadata["first_index"] = section.FirstIndex;
                    metadata["index_count"] = section.IndexCount;
                }
            }
            if (mesh.Rendered is { } rendered && rendered.LodBufferPolicies.Count == builtLods.Count) {
                var policy = rendered.LodBufferPolicies[lod.Index];
                definitionLod["cpu_buffer_access"] = new JObject {
                    ["indices"] = policy.Cpu.Indices, ["positions"] = policy.Cpu.Positions,
                    ["tangents"] = policy.Cpu.Tangents, ["texture_coordinates"] = policy.Cpu.TextureCoordinates,
                    ["colors"] = policy.Cpu.Colors };
                definitionLod["gpu_buffer_policy"] = new JObject(policy.GpuByFormat.Select(entry =>
                    new JProperty(entry.Key, new JObject { ["indices"] = entry.Value.Indices,
                        ["positions"] = entry.Value.Positions, ["tangents"] = entry.Value.Tangents,
                        ["texture_coordinates"] = entry.Value.TextureCoordinates, ["colors"] = entry.Value.Colors })));
            }
            if (built.HasVertexColors is bool hasVertexColors) definitionLod["has_vertex_colors"] = hasVertexColors;
            lods.Add(definitionLod);
            if (built.Repaired != InvalidMeshChannels.None)
                Console.Error.WriteLine($"Repaired source mesh channels in {mesh.Source} LOD {lod.Index}: {built.Repaired}");
        }
        JObject definition = new() {
            ["format_version"] = 1, ["geometry_stage"] = "render", ["source"] = mesh.Source,
            ["settings"] = mesh.Settings.DeepClone(),
            ["sockets"] = mesh.Sockets.DeepClone(),
            ["render_bounds"] = MeshRenderMetadata.Bounds(renderBounds),
            ["extended_bounds"] = MeshRenderMetadata.Bounds(MeshRenderMetadata.ExtendedBounds(renderBounds, mesh.Settings)),
            ["materials"] = new JArray(mesh.Materials.Select(material => new JObject {
                ["slot"] = material.Name, ["imported_slot"] = material.ImportedName, ["material_source"] = material.Material })),
            ["lods"] = lods };
        if (mesh.Rendered?.NaniteFallbackStripped == true) definition["nanite_fallback_stripped"] = true;
        if (mesh.Rendered?.OmitsDefaultMeshBufferViews == true) definition["omits_default_mesh_buffer_views"] = true;
        if (mesh.Rendered is { } nativeMesh) {
            definition["first_inline_lod"] = nativeMesh.FirstInlineLod;
            definition["first_available_lod"] = nativeMesh.FirstAvailableLod;
            definition["first_non_optional_lod"] = nativeMesh.FirstNonOptionalLod;
            if (nativeMesh.CookedFallbackUnavailable) definition["cooked_fallback_unavailable"] = true;
            if (nativeMesh.ShaderAccess.Count != 0)
                definition["shader_access"] = new JObject(nativeMesh.ShaderAccess.Select(entry =>
                    new JProperty(entry.Key, new JObject { ["default_views"] = entry.Value.DefaultViews,
                        ["fallback_available"] = entry.Value.FallbackAvailable })));
        }
        if (mesh.Rendered?.DistanceField is { Length: > 0 } field) {
            string path = prefix + ".lfdf";
            files.Add(path, field);
            definition["distance_field"] = parts[0] + ":" + path;
        }
        if (mesh.Rendered?.Nanite is { } nanite) {
            string path = prefix + "/nanite/index.lfnt";
            definition["nanite"] = parts[0] + ":" + path;
            files.Add(path, nanite.Index);
            for (int page = 0; page < nanite.StreamedPages.Count; page++)
                files.Add(prefix + "/nanite/pages/" + page + ".lfnp", nanite.StreamedPages[page]);
        }
        files.Add(prefix + ".json", Encoding.UTF8.GetBytes(definition.ToString(Formatting.Indented) + "\n"));
        return files;
    }

    internal static bool CanCollectWithoutBuildTools(StaticMeshSourceData mesh) =>
        !NativeMeshRender.Required(mesh)
        && mesh.Lods.Count > 0
        && mesh.Lods.All(lod => lod.Geometry is not null);

    internal static IReadOnlyDictionary<string, byte[]> CollectFromDecoded(StaticMeshSourceData mesh, string identifier)
    {
        if (!IdentifierPattern().IsMatch(identifier) || identifier.Contains("..", StringComparison.Ordinal))
            throw new ConversionException($"Invalid static mesh identifier: {identifier}");
        if (!CanCollectWithoutBuildTools(mesh))
            throw new ConversionException($"Static mesh requires explicit mesh build tools: {mesh.Source}");
        string[] parts = identifier.Split(':');
        var files = new Dictionary<string, byte[]>(StringComparer.Ordinal);
        string prefix = "lumenfx/meshes/" + parts[1];
        JArray lods = new();
        var sectionInfo = new MeshSectionInfoMap(mesh.Settings["SectionInfoMap"]);
        MeshSourceBounds renderBounds = default;
        var lodBoundsList = new List<(StaticMeshSourceLod Source, MeshSourceBounds Bounds)>(mesh.Lods.Count);
        foreach (StaticMeshSourceLod lod in mesh.Lods)
        {
            var bounds = MeshSourceBounds.From(lod.Geometry!);
            renderBounds = lodBoundsList.Count == 0 ? bounds : renderBounds.Union(bounds);
            lodBoundsList.Add((lod, bounds));
        }
        float previousScreenSize = 0;
        foreach (var (lod, bounds) in lodBoundsList)
        {
            var geometry = lod.Geometry!;
            string path = prefix + "/lod_" + lod.Index + ".lfmh";
            files.Add(path, StaticMeshGeometryWriter.Write(geometry));
            JToken screenSize = MeshRenderMetadata.ScreenSize(mesh.Settings, lod.Settings, lod.Index,
                0, (float)renderBounds.Radius, previousScreenSize, 0);
            previousScreenSize = screenSize is JObject platform ? (float)SourceAsset.Number(platform["default"], 1)
                : (float)SourceAsset.Number(screenSize, 1);
            lods.Add(new JObject {
                ["geometry"] = parts[0] + ":" + path,
                ["settings"] = lod.Settings.DeepClone(),
                ["bounds"] = MeshRenderMetadata.Bounds(bounds),
                ["screen_size"] = screenSize,
                ["sections"] = new JArray(geometry.Sections.Select((section, index) => sectionInfo.Get(lod.Index, index).ToJson()))
            });
        }
        JObject definition = new() {
            ["format_version"] = 1,
            ["geometry_stage"] = "render",
            ["source"] = mesh.Source,
            ["settings"] = mesh.Settings.DeepClone(),
            ["sockets"] = mesh.Sockets.DeepClone(),
            ["render_bounds"] = MeshRenderMetadata.Bounds(renderBounds),
            ["extended_bounds"] = MeshRenderMetadata.Bounds(MeshRenderMetadata.ExtendedBounds(renderBounds, mesh.Settings)),
            ["materials"] = new JArray(mesh.Materials.Select(material => new JObject {
                ["slot"] = material.Name,
                ["imported_slot"] = material.ImportedName,
                ["material_source"] = material.Material
            })),
            ["lods"] = lods
        };
        files.Add(prefix + ".json", Encoding.UTF8.GetBytes(definition.ToString(Formatting.Indented) + "\n"));
        return files;
    }
}
