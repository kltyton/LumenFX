using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal;

internal sealed record MaterialShaderPlatformFlags(bool IsMobile, bool IsPC, bool IsConsole,
    bool SupportsAdaptiveGBuffer = false, bool SupportsDistanceFields = true, int MaxSamplers = 16)
{
    internal static readonly MaterialShaderPlatformFlags Desktop = new(false, true, false);

    internal bool ReadCondition(string name) {
        if (SourceNameOrder.Equal(name, "IsMobile")) return IsMobile;
        if (SourceNameOrder.Equal(name, "IsPC")) return IsPC;
        if (SourceNameOrder.Equal(name, "IsConsole")) return IsConsole;
        throw new ConversionException($"Unknown DataDrivenShaderPlatformInfo property {name}");
    }

    internal static MaterialShaderPlatformFlags Read(string path, string platform)
    {
        string expected = $"[ShaderPlatform {platform}]";
        bool found = false, active = false;
        var flags = new Dictionary<string, bool>(SourceNameOrder.Instance);
        foreach (string line in File.ReadLines(path)) {
            string entry = line.Trim();
            if (entry.StartsWith('[') && entry.EndsWith(']')) {
                active = SourceNameOrder.Equal(entry, expected);
                found |= active;
                continue;
            }
            if (!active) continue;
            int separator = entry.IndexOf('=');
            if (separator < 0) continue;
            string key = entry[..separator].Trim();
            if (!SourceNameOrder.Equal(key, "bIsMobile") && !SourceNameOrder.Equal(key, "bIsPC")
                    && !SourceNameOrder.Equal(key, "bIsConsole") && !SourceNameOrder.Equal(key, "bSupportsAdaptiveGBuffer")) continue;
            string value = entry[(separator + 1)..].Split(';')[0].Trim().Trim('"');
            flags[key] = value.ToLowerInvariant() switch {
                "true" or "yes" or "on" or "1" => true,
                "false" or "no" or "off" or "0" => false,
                _ => throw new ConversionException($"Invalid shader platform boolean {key} in {path} {expected}")
            };
        }
        if (!found) throw new ConversionException($"Missing shader platform {expected} in {path}");
        return new MaterialShaderPlatformFlags(flags.GetValueOrDefault("bIsMobile"),
            flags.GetValueOrDefault("bIsPC"), flags.GetValueOrDefault("bIsConsole"), flags.GetValueOrDefault("bSupportsAdaptiveGBuffer"));
    }
}
