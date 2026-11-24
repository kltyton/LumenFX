using System.Text;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Localization;

internal sealed class NativeLocalization(
    NativeAssetTools tools, string? language, string? locale, IReadOnlyList<string> localizationResources)
{
    private Task<JArray>? dialogueCultureGroups;
    internal sealed record DialogueLine(string? Wave, string Subtitle);
    internal sealed record LocalizedDialogue(DialogueLine Default, DialogueLine Fallback, IReadOnlyDictionary<string, DialogueLine> Languages);

    internal IReadOnlyDictionary<string, int> Classify(string source, IEnumerable<JToken> values)
    {
        var labels = new Dictionary<string, int>(StringComparer.Ordinal);
        var archives = new Dictionary<string, int>(StringComparer.Ordinal);
        var tables = new SortedSet<string>(StringComparer.Ordinal);
        var generators = new SortedSet<string>(StringComparer.Ordinal);
        foreach (JToken value in values) {
            string label = Identity(value);
            if (labels.ContainsKey(label)) continue;
            using var stream = new MemoryStream();
            using var writer = new BinaryWriter(stream);
            new UnrealTextArchive(writer, tables, generators).Write(value);
            string archive = Convert.ToBase64String(stream.ToArray());
            if (!archives.TryGetValue(archive, out int index)) {
                index = archives.Count;
                archives.Add(archive, index);
            }
            labels.Add(label, index);
        }
        JObject request = Request();
        request["archives"] = new JArray(archives.OrderBy(value => value.Value).Select(value => value.Key));
        request["tables"] = new JArray(tables);
        request["generators"] = new JArray(generators);
        int[] classes = NativeAssetExchange.ExportAsync(source, tools, "text_source", ".lfte.json",
            bytes => JArray.Parse(Encoding.UTF8.GetString(bytes)).Values<int>().ToArray(), request).GetAwaiter().GetResult();
        if (classes.Length != archives.Count || classes.Where((group, index) =>
                group < 0 || group > index || classes[group] != group).Any())
            throw new ConversionException($"Invalid native text equivalence response for {source}");
        return labels.ToDictionary(value => value.Key, value => classes[value.Value], StringComparer.Ordinal);
    }

    internal async Task<LocalizedDialogue> DialogueAsync(string path, string speaker, IReadOnlyList<string> targets)
    {
        JObject request = Request();
        request["speaker"] = speaker;
        request["targets"] = new JArray(targets);
        JObject result = await Resolve(request).ConfigureAwait(false);
        DialogueLine defaultLine = ReadLine(result);
        var lines = new Dictionary<string, DialogueLine>(StringComparer.OrdinalIgnoreCase);
        if (result.Value<string>("language") is not { Length: > 0 } defaultLanguage)
            return new(defaultLine, defaultLine, lines);

        var groups = await (dialogueCultureGroups ??= NativeAssetExchange.ExportAsync(path, tools, "dialogue_cultures",
            ".lfac.json", bytes => JArray.Parse(Encoding.UTF8.GetString(bytes)), operation: "dialogue_cultures")).ConfigureAwait(false);
        if (groups.Count == 0) return new(defaultLine, defaultLine, lines);
        DialogueLine fallback = defaultLine;
        foreach (JObject group in groups.Cast<JObject>()) {
            string[] aliases = group["aliases"]!.Values<string>().OfType<string>().ToArray();
            DialogueLine line = defaultLine;
            if (localizationResources.Count != 0 || !aliases.Contains(defaultLanguage, StringComparer.OrdinalIgnoreCase)) {
                var localized = (JObject)request.DeepClone();
                localized["language"] = group.Value<string>("language");
                // Explicit LocRes files apply to the requested default export.
                localized["locres"] = new JArray();
                line = ReadLine(await Resolve(localized).ConfigureAwait(false));
            }
            foreach (string alias in aliases) lines[alias] = line;
            if (group.Value<bool>("native_fallback")) fallback = line;
        }
        lines[defaultLanguage] = defaultLine;
        return new(defaultLine, fallback, lines.Where(entry => entry.Value != fallback)
            .ToDictionary(entry => entry.Key, entry => entry.Value, StringComparer.OrdinalIgnoreCase));

        static DialogueLine ReadLine(JObject value) => new(value.Value<string>("wave"), value.Value<string>("subtitle") ?? "");
        Task<JObject> Resolve(JObject options) => NativeAssetExchange.ExportAsync(path, tools, "dialogue", ".lfad.json", bytes => {
            using var text = new StringReader(Encoding.UTF8.GetString(bytes));
            using var json = new JsonTextReader(text) { DateParseHandling = DateParseHandling.None };
            return JObject.Load(json);
        }, options, "dialogue");
    }

    private JObject Request() => new() {
        ["language"] = language,
        ["locale"] = locale,
        ["locres"] = new JArray(localizationResources)
    };

    internal static string Identity(JToken value) => value.ToString(Formatting.None);
}
