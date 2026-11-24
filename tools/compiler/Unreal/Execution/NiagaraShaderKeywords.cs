using System.Text.RegularExpressions;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class NiagaraShaderKeywords
{
    internal static IReadOnlyDictionary<string, string> Read(string engineSourceRoot)
    {
        string config = Path.Combine(engineSourceRoot, "Engine", "Plugins", "FX", "Niagara", "Config", "DefaultNiagara.ini");
        var keywords = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        if (!File.Exists(config)) return keywords;
        foreach (string line in File.ReadLines(config))
        {
            string setting = line.TrimStart();
            if (!setting.StartsWith("HLSLKeywordReplacements=", StringComparison.Ordinal)) continue;
            foreach (Match pair in Regex.Matches(setting, @"\(""([^""]+)"",\s*""([^""]+)""\)"))
                keywords[pair.Groups[1].Value] = pair.Groups[2].Value;
        }
        return keywords;
    }
}
