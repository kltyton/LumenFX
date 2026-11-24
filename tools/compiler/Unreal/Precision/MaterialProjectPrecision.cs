using System.Globalization;

namespace LumenFx.Compiler.Unreal;

internal static class MaterialProjectPrecision
{
    private const string UseNewTranslator = "r.Material.Translator.EnableNew";
    private const string TranslatorProfile = "r.Material.Translator.DefaultProfile";
    private const string LargeWorld = "r.MaterialEditor.LWCEnabled";
    private const string TruncateLargeWorld = "r.MaterialEditor.LWCTruncateMode";

    internal static string? ConfigFromGameMount(IReadOnlyDictionary<string, string> contentMounts)
    {
        foreach (var mount in contentMounts) {
            if (!mount.Key.Trim('/').Equals("Game", StringComparison.OrdinalIgnoreCase)) continue;
            string? project = Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(Path.GetFullPath(mount.Value)));
            if (project is null) return null;
            string config = Path.Combine(project, "Config");
            if (Directory.Exists(config)) return config;
        }
        return null;
    }

    internal static MaterialPrecisionSettings Read(string engineSource, string? projectConfig, string platform)
    {
        string engine = Directory.Exists(Path.Combine(engineSource, "Engine")) ? Path.Combine(engineSource, "Engine") : engineSource;
        string engineConfig = Path.Combine(engine, "Config");
        var game = LoadHierarchy(engineConfig, projectConfig, platform, "Engine");
        var editor = LoadHierarchy(engineConfig, projectConfig, platform, "Editor");
        var startup = new PrecisionIni();
        startup.Read(Path.Combine(engineConfig, "ConsoleVariables.ini"));

        // Preserve the engine's CVar source priority and apply equal-priority sections in startup order.
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        game.Apply(values, "/Script/Engine.RendererSettings");
        game.Apply(values, "/Script/Engine.RendererOverrideSettings");
        game.ApplySystemSection(values, "SystemSettings");
        game.ApplySystemSection(values, "SystemSettingsEditor");
        game.Apply(values, "ConsoleVariables");
        editor.Apply(values, "ConsoleVariables");
        startup.Apply(values, "Startup");
        startup.Apply(values, $"Startup_{platform}");
        return new MaterialPrecisionSettings(
            LwcEnabled: Integer(values, LargeWorld, 1) != 0,
            TruncateMode: Integer(values, TruncateLargeWorld, 2),
            NewTranslatorMode: Integer(values, UseNewTranslator, 0),
            MirStrictProfile: Integer(values, TranslatorProfile, 0) != 0);
    }

    private static PrecisionIni LoadHierarchy(string engine, string? project, string platform, string type)
    {
        var ini = new PrecisionIni();
        ini.Read(Path.Combine(engine, "Base.ini"));
        ini.Read(Path.Combine(engine, $"Base{type}.ini"));
        ini.Read(Path.Combine(engine, platform, $"Base{platform}{type}.ini"));
        if (project is not null) {
            ini.Read(Path.Combine(project, $"Default{type}.ini"));
            ini.Read(Path.Combine(project, $"Generated{type}.ini"));
        }
        ini.Read(Path.Combine(engine, platform, $"{platform}{type}.ini"));
        if (project is not null) {
            ini.Read(Path.Combine(project, platform, $"{platform}{type}.ini"));
            ini.Read(Path.Combine(project, platform, $"Generated{platform}{type}.ini"));
            ini.Read(Path.Combine(project, $"User{type}.ini"));
        }
        return ini;
    }

    private static int Integer(Dictionary<string, string> values, string name, int fallback)
    {
        if (!values.TryGetValue(name, out string? source)) return fallback;
        if (!int.TryParse(source, NumberStyles.Integer, CultureInfo.InvariantCulture, out int result))
            throw new ConversionException($"Invalid Unreal material CVar {name}: {source}");
        return result;
    }

    private sealed class PrecisionIni
    {
        private readonly Dictionary<string, Dictionary<string, List<string>>> sections = new(StringComparer.OrdinalIgnoreCase);

        internal void Read(string path)
        {
            if (!File.Exists(path)) return;
            Dictionary<string, List<string>>? section = null;
            foreach (string line in File.ReadLines(path)) {
                string entry = line.Trim();
                if (entry.StartsWith('[') && entry.EndsWith(']')) {
                    string name = entry[1..^1].Trim();
                    if (!sections.TryGetValue(name, out section)) {
                        section = new(StringComparer.OrdinalIgnoreCase);
                        sections.Add(name, section);
                    }
                    continue;
                }
                int separator = entry.IndexOf('=');
                if (section is null || separator <= 0 || entry[0] is ';' or '#') continue;
                char operation = entry[0];
                string key = entry[..separator].Trim().TrimStart('+', '.', '-', '!').Trim();
                if (!IsPrecisionKey(key) && !key.Equals("BasedOn", StringComparison.OrdinalIgnoreCase)) continue;
                string value = entry[(separator + 1)..].Split(';', 2)[0].Trim().Trim('"');
                if (operation == '!') {
                    section.Remove(key);
                    continue;
                }
                if (!section.TryGetValue(key, out var entries)) {
                    entries = new();
                    section.Add(key, entries);
                }
                switch (operation) {
                    case '-': entries.RemoveAll(candidate => candidate == value); break;
                    case '+': if (!entries.Contains(value)) entries.Add(value); break;
                    case '.': entries.Add(value); break;
                    default: entries.Clear(); entries.Add(value); break;
                }
            }
        }

        internal void Apply(Dictionary<string, string> values, string name)
        {
            if (!sections.TryGetValue(name, out var section)) return;
            foreach (var entry in section)
                if (entry.Value.Count != 0 && IsPrecisionKey(entry.Key)) values[entry.Key] = entry.Value[^1];
        }

        internal void ApplySystemSection(Dictionary<string, string> values, string name)
        {
            var ancestry = new Stack<string>();
            var visited = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            while (sections.TryGetValue(name, out var section)) {
                if (!visited.Add(name)) throw new ConversionException($"Cyclic Unreal SystemSettings BasedOn: {name}");
                ancestry.Push(name);
                if (!section.TryGetValue("BasedOn", out var parent) || parent.Count == 0 || parent[^1].Length == 0) break;
                name = parent[^1];
            }
            while (ancestry.TryPop(out string? layer)) Apply(values, layer);
        }

        private static bool IsPrecisionKey(string key)
            => key.Equals(UseNewTranslator, StringComparison.OrdinalIgnoreCase)
                || key.Equals(TranslatorProfile, StringComparison.OrdinalIgnoreCase)
                || key.Equals(LargeWorld, StringComparison.OrdinalIgnoreCase)
                || key.Equals(TruncateLargeWorld, StringComparison.OrdinalIgnoreCase);
    }
}
