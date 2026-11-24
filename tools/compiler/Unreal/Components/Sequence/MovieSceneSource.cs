using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Components.Sequence;

internal static class MovieSceneSource
{
    internal static SourceObject OwningScene(SourceAsset asset, SourceObject track)
    {
        var visited = new HashSet<int>();
        for (int index = track.Outer; index > 0;) {
            if (!visited.Add(index)) break;
            var outer = asset.Object(index);
            if (outer.ClassName == "MovieScene") return outer;
            index = outer.Outer;
        }
        throw new ConversionException($"Missing owning MovieScene for {asset.ObjectPath(track.Index)}");
    }

    internal static JObject DisplayRate(JToken? source) => new() {
        ["numerator"] = source?.Value<int?>("Numerator") ?? 60000,
        ["denominator"] = source?.Value<int?>("Denominator") ?? 1
    };

    internal static (int Start, int End) SectionFrames(JToken? source, string identity)
    {
        var range = source?["LowerBound"] is null ? source?["Value"] : source;
        return (Bound(range?["LowerBound"], true, identity), Bound(range?["UpperBound"], false, identity));
    }

    private static int Bound(JToken? source, bool lower, string identity)
    {
        int? frame = FrameNumber(source?["Value"]);
        if (frame is null) throw new ConversionException($"MovieScene section has no saved frame bound: {identity}");
        string type = EnumValue(source?["Type"], "Open", "Exclusive", "Inclusive", "Open");
        if (type == "Open") throw new ConversionException($"Niagara MovieScene section requires a finite range: {identity}");
        return checked(frame.Value + ((type == "Exclusive") == lower ? 1 : 0));
    }

    internal static int? FrameNumber(JToken? value) =>
        value is JObject number ? number.Value<int?>("Value") : value?.Value<int>();

    internal static string NativeObjectPath(string path)
    {
        int asset = path.IndexOf('.');
        int inner = asset < 0 ? -1 : path.IndexOf('.', asset + 1);
        return inner < 0 ? path : path[..inner] + ":" + path[(inner + 1)..];
    }

    internal static string EnumValue(JToken? value, string fallback, params string[] names)
    {
        var text = value?.Value<string>()?.Split("::").Last();
        if (text is null) return fallback;
        if (int.TryParse(text, out int index) && index >= 0 && index < names.Length) return names[index];
        if (names.Contains(text, StringComparer.Ordinal)) return text;
        throw new ConversionException($"Unknown MovieScene enum value: {text}");
    }
}
