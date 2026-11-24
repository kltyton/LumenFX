using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using LumenFx.Compiler.Unreal;

namespace LumenFx.Compiler.Output;

/// <summary>Locates full-size original PNGs in source package layout or an unambiguous legacy export.</summary>
internal sealed class OriginalTextureSources
{
    private readonly Dictionary<string, string[]> byName;

    public OriginalTextureSources(string directory)
    {
        byName = Directory.EnumerateFiles(directory, "*.png", SearchOption.AllDirectories)
            .GroupBy(Path.GetFileNameWithoutExtension, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(group => group.Key!, group => group.ToArray(), StringComparer.OrdinalIgnoreCase);
    }

    public (string Path, string ResourceName) Resolve(string source)
    {
        int separator = source.IndexOf('.');
        string package = separator < 0 ? source : source[..separator];
        string objectName = source[(source.LastIndexOf('.') + 1)..].Split('/').Last();
        if (!byName.TryGetValue(objectName, out var candidates))
            throw new ConversionException($"Missing original PNG for {source}");

        string packagePath = package.TrimStart('/');
        string prefix = package.StartsWith("/Game/", StringComparison.OrdinalIgnoreCase) ? "Game/" : "";
        var matching = candidates.Where(path => Matches(path, packagePath, objectName)).ToArray();
        if (matching.Length == 0 && prefix.Length != 0)
            matching = candidates.Where(path => Matches(path, packagePath[prefix.Length..], objectName)).ToArray();
        if (matching.Length == 0) matching = candidates;
        if (matching.Length != 1)
            throw new ConversionException($"Ambiguous original PNG for {source}: {string.Join(", ", matching)}");

        string name = objectName.ToLowerInvariant();
        if (candidates.Length > 1 || !Regex.IsMatch(name, "^[a-z0-9_]+$"))
            name = "texture_" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(source)))[..12];
        return (matching[0], name);
    }

    private static bool Matches(string candidate, string packagePath, string objectName)
    {
        int separator = packagePath.LastIndexOf('/');
        string folder = separator < 0 ? "" : packagePath[..(separator + 1)];
        return candidate.Replace('\\', '/').EndsWith("/" + folder + objectName + ".png", StringComparison.OrdinalIgnoreCase);
    }
}
