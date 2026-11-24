using System.Globalization;
using System.Text.RegularExpressions;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Geometry.MeshBuild;

internal sealed record MeshArchiveVersions(int Ue4, int Ue5, int Licensee, IReadOnlyList<(string Guid, int Version)> Custom)
{
    internal static MeshArchiveVersions Read(SourceAsset asset, string unrealRoot)
    {
        string path = Path.Combine(unrealRoot, "Engine/Source/Runtime/Core/Public/UObject/ObjectVersion.h");
        string header = File.ReadAllText(path);
        int ue4 = Version(asset.Raw["ObjectVersion"], header, "EUnrealEngineObjectUE4Version");
        int ue5 = Version(asset.Raw["ObjectVersionUE5"], header, "EUnrealEngineObjectUE5Version");
        var custom = new List<(string, int)>();
        foreach (JObject value in asset.Raw["CustomVersionContainer"] as JArray ?? new JArray())
            custom.Add((value.Value<string>("Key") ?? throw new ConversionException("Custom version has no GUID"), value.Value<int>("Version")));
        return new MeshArchiveVersions(ue4, ue5, asset.Raw.Value<int>("FileVersionLicenseeUE"), custom);
    }

    internal void Write(BinaryWriter writer)
    {
        writer.Write(Ue4); writer.Write(Ue5); writer.Write(Licensee); writer.Write(Custom.Count);
        foreach ((string guid, int version) in Custom) {
            string digits = guid.Replace("{", "").Replace("}", "").Replace("-", "");
            if (digits.Length != 32) throw new ConversionException("Invalid mesh archive custom version GUID");
            for (int index = 0; index < 4; index++)
                writer.Write(uint.Parse(digits.AsSpan(index * 8, 8), NumberStyles.HexNumber, CultureInfo.InvariantCulture));
            writer.Write(version);
        }
    }

    private static int Version(JToken? token, string header, string enumName)
    {
        if (token is null) throw new ConversionException($"Mesh archive is missing {enumName}");
        if (token.Type == JTokenType.Integer) return token.Value<int>();
        string wanted = token.Value<string>() ?? throw new ConversionException("Empty package version name");
        string clean = Regex.Replace(header, @"/\*.*?\*/|//[^\r\n]*", "", RegexOptions.Singleline);
        string declaration = Regex.Escape(enumName);
        var alias = Regex.Match(clean, @"\busing\s+" + declaration + @"\s*=\s*(?<target>\w+)\s*;");
        if (alias.Success) declaration = Regex.Escape(alias.Groups["target"].Value);
        var block = Regex.Match(clean, @"\benum(?:\s+class)?\s+" + declaration + @"(?:\s*:\s*\w+)?\s*\{(?<body>.*?)\}", RegexOptions.Singleline);
        if (!block.Success) throw new ConversionException($"Cannot find the source package version enum {enumName}");
        Dictionary<string, int> values = new(StringComparer.Ordinal);
        int next = 0;
        foreach (string item in block.Groups["body"].Value.Split(',')) {
            if (string.IsNullOrWhiteSpace(item)) continue;
            var value = Regex.Match(item, @"^\s*(?<name>\w+)(?:\s*=\s*(?<base>\w+)(?:\s*-\s*(?<subtract>\d+))?)?\s*$");
            if (!value.Success) throw new ConversionException($"Unrecognized package version declaration: {item.Trim()}");
            if (value.Groups["base"].Success) {
                string basis = value.Groups["base"].Value;
                next = int.TryParse(basis, NumberStyles.Integer, CultureInfo.InvariantCulture, out int number) ? number
                    : values.TryGetValue(basis, out number) ? number : throw new ConversionException($"Unknown package version alias {basis}");
                if (value.Groups["subtract"].Success) next -= int.Parse(value.Groups["subtract"].Value, CultureInfo.InvariantCulture);
            }
            string name = value.Groups["name"].Value;
            values.Add(name, next++);
        }
        return values.TryGetValue(wanted, out int result) ? result
            : throw new ConversionException($"Package version {wanted} is absent from the selected engine source");
    }
}
