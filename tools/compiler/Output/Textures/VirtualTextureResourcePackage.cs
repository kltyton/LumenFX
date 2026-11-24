using System.Security.Cryptography;
using System.Text;
using LumenFx.Compiler.Unreal;
using LumenFx.Compiler.Unreal.TextureSource;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Output;

public static partial class ResourcePackage
{
    internal static ImportedEffect AddRuntimeVirtualTextureScene(ImportedEffect effect, AssetLibrary library,
        IEnumerable<string> components, IEnumerable<string> primitives, string engineSource)
    {
        var definition = (JObject)effect.Definition.DeepClone();
        var values = new JObject();
        foreach (string path in components.Select(value => value.Replace(':', '.')).Distinct(StringComparer.OrdinalIgnoreCase)
                     .Order(StringComparer.Ordinal))
            values.Add(path, RuntimeVirtualTextureComponentSource.Compile(library, path, engineSource));
        definition["niagara"]!["runtime_virtual_texture_components"] = values;
        var targets = new JObject();
        foreach (string path in primitives.Select(value => value.Replace(':', '.')).Distinct(StringComparer.OrdinalIgnoreCase)
                     .Order(StringComparer.Ordinal))
            targets.Add(path, RuntimeVirtualTexturePrimitiveSource.Compile(library, path));
        definition["niagara"]!["runtime_virtual_texture_primitives"] = targets;
        return effect with { Definition = definition };
    }

    internal static ImportedEffect AddRuntimeVirtualTextureWriters(ImportedEffect effect, AssetLibrary library,
        IReadOnlyDictionary<string, string>? aliases, IEnumerable<(string Material, string Texture)> writers, string? engineSource = null,
        IReadOnlyDictionary<string, string>? shaderDirectories = null, MaterialPrecisionSettings? precision = null)
    {
        var materials = new Dictionary<string, CompiledMaterial>(effect.Materials, StringComparer.Ordinal);
        var compiler = new MaterialCompiler(library, aliases, MaterialVertexFactory.Mesh, precision)
            .ConfigureShaderSources(engineSource, shaderDirectories);
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var (material, texture) in writers) {
            paths.Add(texture);
            var compiled = compiler.CompileRuntimeVirtualTextureWriter(material);
            string hash = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(compiled.SourceObject.ToLowerInvariant())))[..16];
            if (!materials.TryAdd("rvt_writer_" + hash, compiled) &&
                !string.Equals(materials["rvt_writer_" + hash].SourceObject, compiled.SourceObject, StringComparison.OrdinalIgnoreCase))
                throw new ConversionException($"Runtime virtual texture writer identifier collision: {compiled.SourceObject}");
        }
        var definition = (JObject)effect.Definition.DeepClone();
        definition["niagara"]!["runtime_virtual_texture_writer_targets"] = new JArray(paths.Order(StringComparer.Ordinal));
        return effect with { Definition = definition, Materials = materials };
    }

    private static void BindVirtualTextureAssets(JObject definition, IEnumerable<CompiledMaterial> materials, AssetLibrary? library, string ns, string effect,
        Dictionary<string, byte[]> files)
    {
        if (definition["niagara"] is not JObject niagara) return;
        var interfaces = niagara.Descendants().OfType<JObject>()
            .Select(value => value["virtual_texture"] as JObject
                ?? (value.Value<string>("interface_type") == "virtual_texture_sample" ? value["interface_data"] as JObject : null))
            .OfType<JObject>()
            .ToArray();
        var paths = new HashSet<string>(StringComparer.Ordinal);
        foreach (var material in materials)
            foreach (var read in material.VirtualTextureReads) {
                paths.Add(read.Source);
                if (read.RendererBase is { } parent && parent != "None") paths.Add(parent);
                if (read.RendererBinding is not { } binding) continue;
                if (binding.ObjectDefault is { } assigned) paths.Add(assigned);
                if (binding.Collection?.BaseDefault is { } baseTexture) paths.Add(baseTexture);
                if (binding.ObjectInputs is not null)
                    foreach (var input in binding.ObjectInputs) if (input.DefaultTexture is { } fallback) paths.Add(fallback);
            }
        if (niagara["runtime_virtual_texture_components"] is JObject components)
            foreach (var placement in components.Properties().Select(item => (JObject)item.Value)) {
                paths.Add(placement.Value<string>("source")!);
                if (placement["streaming"] is JObject streaming) paths.Add(streaming.Value<string>("source")!);
            }
        if (niagara["runtime_virtual_texture_primitives"] is JObject primitives)
            foreach (var primitive in primitives.Properties())
                paths.UnionWith(primitive.Value["targets"]!.Values<string>().OfType<string>());
        foreach (string path in (niagara["runtime_virtual_texture_writer_targets"] as JArray ?? new()).Values<string>().OfType<string>()) {
            if (library is null || library.Load(path).ResolveObject(path, _ => true).ClassName != "RuntimeVirtualTexture")
                throw new ConversionException($"Runtime virtual texture writer target has an invalid asset: {path}");
            paths.Add(path);
        }
        niagara.Remove("runtime_virtual_texture_writer_targets");
        foreach (var binding in interfaces)
            if (binding.Value<string>("texture") is { Length: > 0 } path) paths.Add(path);
        if (interfaces.Any(value => value.Value<string>("parameter") is { Length: > 0 }))
            foreach (var reference in niagara.Descendants().OfType<JObject>())
                if (reference.Value<string>("class") is ("Texture2D" or "RuntimeVirtualTexture")
                    && reference.Value<string>("path") is { Length: > 0 } path)
                    paths.Add(path);

        var resources = new JObject();
        var runtime = new JObject();
        foreach (string path in paths.Order(StringComparer.Ordinal))
        {
            if (library is null) throw new ConversionException($"Virtual texture requires its source library: {path}");
            SourceAsset asset = library.Load(path);
            SourceObject texture = asset.ResolveObject(path, _ => true);
            if (texture.ClassName == "RuntimeVirtualTexture") {
                runtime.Add(path, RuntimeVirtualTextureSource.Compile(texture, path));
                continue;
            }
            bool builtRuntime = texture.ClassName == "VirtualTexture2D";
            if (!builtRuntime && (texture.ClassName != "Texture2D" || texture.Properties.Value<bool?>("VirtualTextureStreaming") != true)) continue;
            string key = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(path)))[..16];
            string stem = $"textures/lumenfx/{effect}/interfaces/virtual/texture_{key}";
            string location = stem + ".lfvt.json";
            var manifest = new JObject {
                ["format_version"] = 1,
                ["asset"] = builtRuntime ? EmbeddedTexturePath(path) : path,
                ["page_data"] = ns + ":" + stem + ".lfvt"
            };
            byte[] bytes = Encoding.UTF8.GetBytes(manifest.ToString(Formatting.Indented) + "\n");
            if (files.TryGetValue(location, out var previous) && !previous.AsSpan().SequenceEqual(bytes))
                throw new ConversionException($"Virtual texture metadata collision: {location}");
            files[location] = bytes;
            resources.Add(path, ns + ":" + location);
        }
        if (resources.HasValues) niagara["virtual_texture_assets"] = resources;
        if (runtime.HasValues) niagara["runtime_virtual_texture_assets"] = runtime;
    }

    private static string EmbeddedTexturePath(string path)
    {
        int separator = path.IndexOf('.', path.IndexOf('.') + 1);
        return separator < 0 ? path : path[..separator] + ":" + path[(separator + 1)..];
    }
}
