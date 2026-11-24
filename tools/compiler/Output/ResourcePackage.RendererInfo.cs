using System.Security.Cryptography;
using System.Text;
using LumenFx.Compiler.Unreal;
using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Geometry.MeshBuild;
using LumenFx.Compiler.Unreal.Geometry.MeshDescription;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Output;

public static partial class ResourcePackage
{
    private static string MeshIdentifier(string ns, string source) => ns + ":unreal/mesh_"
            + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source)))[..16];

    private static async Task CompleteMeshRendererInformationAsync(ImportedEffect effect, JObject definition,
            AssetLibrary library, MeshBuildTools? tools, string? oodleLibrary, string ns,
            Dictionary<string, MeshSourceBounds> bounds)
    {
        foreach (string source in MeshRendererInfoCompiler.PendingSources(definition)) {
            if (bounds.ContainsKey(source)) continue;
            StaticMeshSourceData mesh = effect.SourceMeshes.TryGetValue(source, out var decoded) ? decoded
                    : StaticMeshSourceCompiler.Compile(library.Load(source), source, oodleLibrary);
            if (mesh.Rendered is { } rendered) {
                bounds.Add(source, MeshRenderMetadata.ExtendedBounds(rendered.Bounds, mesh.Settings));
                continue;
            }
            if (tools is null) throw new ConversionException($"Mesh renderer information requires built extended bounds or explicit mesh build inputs: {source}");
            string id = MeshIdentifier(ns, source);
            var built = await StaticMeshRenderPackage.CollectAsync(mesh, id, tools);
            bounds.Add(source, ExtendedMeshBounds(id, built));
        }
        MeshRendererInfoCompiler.Complete(definition, bounds);
    }

    private static MeshSourceBounds ExtendedMeshBounds(string identifier, IReadOnlyDictionary<string, byte[]> files)
    {
        string path = "lumenfx/meshes/" + identifier.Split(':')[1] + ".json";
        if (!files.TryGetValue(path, out byte[]? bytes)) throw new ConversionException($"Built mesh metadata is missing: {identifier}");
        JObject box = JObject.Parse(Encoding.UTF8.GetString(bytes))["extended_bounds"] as JObject
                ?? throw new ConversionException($"Built mesh extended bounds are missing: {identifier}");
        JArray origin = box["origin"] as JArray ?? throw new ConversionException("Mesh bounds require an origin vector");
        JArray extent = box["extent"] as JArray ?? throw new ConversionException("Mesh bounds require an extent vector");
        if (origin.Count != 3 || extent.Count != 3) throw new ConversionException("Mesh bounds vectors require three components");
        return new MeshSourceBounds(new MeshBoundVector(origin[0].Value<double>(), origin[1].Value<double>(), origin[2].Value<double>()),
                new MeshBoundVector(extent[0].Value<double>(), extent[1].Value<double>(), extent[2].Value<double>()),
                box["radius"]?.Value<double>() ?? throw new ConversionException("Mesh bounds require a radius"));
    }
}
