using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

/** Selects the same saved version as UNiagaraEmitter::GetEmitterData. */
internal static class EmitterVersionSource
{
    internal static JObject? Find(SourceObject emitter, string? requestedVersion)
    {
        JArray versions = emitter.Properties["VersionData"] as JArray ?? new();
        if (versions.Count == 0) return null;
        if (emitter.Properties.Value<bool?>("bVersioningEnabled") != true)
            return versions[0] as JObject ?? throw new ConversionException($"Invalid emitter version data: {emitter.Name}");
        Guid selected = Parse(requestedVersion, emitter.Name);
        if (selected == Guid.Empty) selected = Parse(emitter.Properties.Value<string>("ExposedVersion"), emitter.Name);
        foreach (JToken value in versions) {
            JObject version = value as JObject ?? throw new ConversionException($"Invalid emitter version data: {emitter.Name}");
            if (Parse(version["Version"]?.Value<string>("VersionGuid"), emitter.Name) == selected) return version;
        }
        return null;
    }

    internal static JObject Require(SourceObject emitter, string? requestedVersion)
        => Find(emitter, requestedVersion)
            ?? throw new ConversionException($"Niagara emitter has no selected version: {emitter.Name} ({requestedVersion ?? "exposed"})");

    private static Guid Parse(string? value, string emitter)
    {
        if (string.IsNullOrEmpty(value)) return Guid.Empty;
        return Guid.TryParse(value, out Guid result) ? result
            : throw new ConversionException($"Invalid emitter version identity: {emitter} ({value})");
    }
}
