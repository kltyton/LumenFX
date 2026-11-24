using System.Text;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Output;

internal static partial class StaticMeshSourcePackage
{
    [GeneratedRegex("^[a-z0-9_]+:[a-z0-9_/.-]+$")]
    private static partial Regex IdentifierPattern();

    internal static void Write(SourceAsset source, string identifier, string resourcesDirectory, string? oodleLibrary, string? objectPath = null)
    {
        if (!IdentifierPattern().IsMatch(identifier) || identifier.Contains("..", StringComparison.Ordinal))
            throw new ConversionException($"Invalid static mesh identifier: {identifier}");
        string[] parts = identifier.Split(':');
        StaticMeshSourceData mesh = objectPath is null ? StaticMeshSourceCompiler.Compile(source, oodleLibrary)
            : StaticMeshSourceCompiler.Compile(source, objectPath, oodleLibrary);
        string root = Path.GetFullPath(Path.Combine(resourcesDirectory, "assets", parts[0]));
        string prefix = "lumenfx/meshes/" + parts[1];
        var files = new Dictionary<string, byte[]>(StringComparer.Ordinal);
        JArray lods = new();
        foreach (StaticMeshSourceLod lod in mesh.Lods)
        {
            string? path = lod.Geometry is null ? null : prefix + "/lod_" + lod.Index + ".lfmh";
            if (path is not null) files.Add(path, StaticMeshGeometryWriter.Write(lod.Geometry!));
            lods.Add(new JObject { ["source_geometry"] = path is null ? null : parts[0] + ":" + path,
                ["settings"] = lod.Settings.DeepClone() });
        }
        JObject definition = new() {
            ["format_version"] = 1, ["geometry_stage"] = "source", ["source"] = mesh.Source, ["settings"] = mesh.Settings.DeepClone(),
            ["sockets"] = mesh.Sockets.DeepClone(),
            ["materials"] = new JArray(mesh.Materials.Select(material => new JObject {
                ["slot"] = material.Name, ["imported_slot"] = material.ImportedName,
                ["material_source"] = material.Material })), ["lods"] = lods };
        files.Add(prefix + ".json", Encoding.UTF8.GetBytes(definition.ToString(Formatting.Indented) + "\n"));
        foreach ((string relative, byte[] bytes) in files)
        {
            string path = Path.GetFullPath(Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar)));
            if (!path.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new ConversionException("Static mesh output escapes resource namespace");
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllBytes(path, bytes);
        }
        Console.WriteLine($"Wrote {mesh.Lods.Count} source LOD records and {files.Count - 1} geometry resources: {identifier}");
    }
}
