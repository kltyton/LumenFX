using LumenFx.Compiler.Unreal.Geometry.GeometryCollection;
using LumenFx.Compiler.Unreal.Geometry.GaussianSplat;
using LumenFx.Compiler.Unreal.Localization;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed class AssetLibrary
{
    private sealed record CatalogEntry(string Package, string File);
    private sealed record Root(string Directory, IReadOnlyDictionary<string, CatalogEntry> Index);
    private readonly Root[] roots;
    private readonly Dictionary<string, SourceAsset> cache = new(SourceNameOrder.Instance);
    internal GeometryCollectionAssetSource? GeometryCollections { get; set; }
    internal GaussianSplatAssetSource? GaussianSplats { get; set; }
    internal Func<string, JObject>? NativeDataTable { get; set; }
    internal NativeLocalization? Localization { get; set; }
    internal AssetLibrary? NiagaraGpuSource { get; init; }
    internal MaterialGraphPermutation MaterialPermutation { get; set; } = new();

    public AssetLibrary(params string[] directories)
    {
        if (directories.Length == 0) throw new ArgumentException("A source export directory is required", nameof(directories));
        roots = directories.Select(directory => {
            string path = Path.TrimEndingDirectorySeparator(Path.GetFullPath(directory));
            string index = Path.Combine(path, "asset-index.json");
            var entries = new Dictionary<string, CatalogEntry>(SourceNameOrder.Instance);
            if (File.Exists(index)) {
                foreach (var entry in JObject.Parse(File.ReadAllText(index)).Properties()) {
                    if (entry.Value.Type != JTokenType.String ||
                        !entries.TryAdd(entry.Name, new CatalogEntry(entry.Name, entry.Value.Value<string>()!)))
                        throw new ConversionException($"Invalid or duplicate source package {entry.Name} in {index}");
                }
            }
            return new Root(path, entries);
        }).ToArray();
    }

    public SourceAsset Load(string assetPath)
        => TryLoad(assetPath) ?? throw new ConversionException($"Missing source export {assetPath} in {string.Join(", ", roots.Select(root => root.Directory))}");

    internal SourceAsset? TryLoad(string assetPath)
    {
        string package = assetPath.Split('.')[0];
        int secondSlash = package.IndexOf('/', 1);
        if (!package.StartsWith("/", StringComparison.Ordinal)
            || secondSlash <= 1 || secondSlash == package.Length - 1
            || package.StartsWith("/Script/", StringComparison.Ordinal)
            || package.Contains("//", StringComparison.Ordinal)
            || package.Contains("..", StringComparison.Ordinal)
            || package.Contains('\\') || package.Contains(':')) throw new ConversionException($"Invalid source package path {assetPath}");
        if (cache.TryGetValue(package, out var existing)) return existing;
        string? path = null;
        string resolvedPackage = package;
        foreach (Root root in roots)
        {
            root.Index.TryGetValue(package, out CatalogEntry? entry);
            string relative = entry?.File
                ?? package.TrimStart('/').Replace('/', Path.DirectorySeparatorChar) + ".json";
            string candidate = Path.GetFullPath(Path.Combine(root.Directory, relative));
            if (!candidate.StartsWith(root.Directory + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                throw new ConversionException($"Source export escapes input directory: {assetPath}");
            if (File.Exists(candidate)) {
                path = candidate;
                resolvedPackage = entry?.Package ?? package;
                break;
            }
        }
        // The original flat export layout predates plugin content mounts.
        if (path is null && package.StartsWith("/Game/", StringComparison.OrdinalIgnoreCase))
        {
            string candidate = Path.Combine(roots[0].Directory, package.Split('/').Last() + ".json");
            if (File.Exists(candidate)) path = candidate;
        }
        if (path is null) return null;
        using var file = File.OpenText(path);
        using var json = new JsonTextReader(file) { DateParseHandling = DateParseHandling.None };
        JObject raw = JObject.Load(json);
        var result = new SourceAsset(resolvedPackage, raw, this);
        cache.Add(package, result);
        return result;
    }
}
