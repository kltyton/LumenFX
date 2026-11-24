using System.Text.RegularExpressions;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Expressions;

/// <summary>Offline snapshot of Unreal's virtual shader directories.</summary>
internal static class ShaderDirectoryMappings
{
    public static IReadOnlyDictionary<string, string>? Read(string? file)
    {
        if (file is null) return null;
        file = Path.GetFullPath(file);
        string baseDirectory = Path.GetDirectoryName(file)!;
        var entries = JObject.Parse(File.ReadAllText(file));
        var mappings = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var entry in entries.Properties()) {
            if (!Regex.IsMatch(entry.Name, @"\A(?:/[A-Za-z0-9_-]+)+\z") || entry.Value.Type != JTokenType.String)
                throw new ConversionException($"Invalid UE shader directory mount {entry.Name} in {file}");
            string directory = Path.GetFullPath(entry.Value.Value<string>()!, baseDirectory);
            if (!Directory.Exists(directory))
                throw new ConversionException($"UE shader directory for {entry.Name} does not exist: {directory}");
            mappings.Add(entry.Name, directory);
        }
        return mappings;
    }
}
