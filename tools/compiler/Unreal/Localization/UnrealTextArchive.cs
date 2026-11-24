using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Serialization;

namespace LumenFx.Compiler.Unreal.Localization;

// Portable FText archive for the native resolver. FNameAsStringProxyArchive
// reads names as FString so the original package name table isn't needed.
internal sealed class UnrealTextArchive(BinaryWriter writer, ISet<string> tables, ISet<string> generators)
{
    private enum Transform : byte { ToLower, ToUpper }
    private enum DateStyle : byte { Default, Short, Medium, Long, Full, Custom }
    private enum Rounding : byte {
        HalfToEven, HalfFromZero, HalfToZero, FromZero, ToZero, ToNegativeInfinity, ToPositiveInfinity
    }

    internal void Write(JToken text, int depth = 0)
    {
        if (depth == 128) throw new ConversionException("Unreal text history nesting exceeds the supported archive depth");
        if (text.Type == JTokenType.String || text.Type == JTokenType.Null) {
            Invariant(text.Value<string>() ?? "", 2);
            return;
        }
        if (text is not JObject fields) throw new ConversionException("Invalid serialized FText");
        if (fields["unreal_text"] is JObject property) fields = property;
        uint flags = fields.Value<uint?>("Flags") ?? 0;
        if (fields.Value<string>("HistoryType") == "RawText") {
            Invariant(Text(fields, "Value"), flags | 2u);
            return;
        }
        var history = EnumValue<UnrealTextHistory>(fields["HistoryType"]);
        if (history == UnrealTextHistory.None) {
            Invariant(Text(fields, "CultureInvariantString"), flags);
            return;
        }
        writer.Write(flags);
        writer.Write((sbyte)history);
        switch (history) {
            case UnrealTextHistory.Base:
                String(Text(fields, "Namespace"));
                String(Text(fields, "Key", "Value"));
                String(Text(fields, "SourceString", "CultureInvariantString"));
                String(Text(fields, "DevNotes"));
                break;
            case UnrealTextHistory.NamedFormat:
            case UnrealTextHistory.OrderedFormat:
            case UnrealTextHistory.ArgumentFormat:
                Format(history, fields, depth);
                break;
            case UnrealTextHistory.AsNumber:
            case UnrealTextHistory.AsPercent:
            case UnrealTextHistory.AsCurrency:
                if (history == UnrealTextHistory.AsCurrency) String(Text(fields, "CurrencyCode"));
                Argument(Object(fields, "SourceValue"), false, depth);
                NumberOptions(fields["FormatOptions"] as JObject ?? fields["Options"] as JObject);
                String(Text(fields, "TargetCulture", "CultureName"));
                break;
            case UnrealTextHistory.AsDate:
            case UnrealTextHistory.AsTime:
            case UnrealTextHistory.AsDateTime:
                Chrono(history, fields);
                break;
            case UnrealTextHistory.Transform:
                Write(Required(fields, "SourceText", "SourceFmt"), depth + 1);
                writer.Write((byte)EnumValue<Transform>(fields["TransformType"]));
                break;
            case UnrealTextHistory.StringTableEntry:
                string table = Text(fields, "TableId");
                if (table.Length == 0) throw new ConversionException("Missing FText string table ID");
                tables.Add(table);
                String(table);
                String(Text(fields, "Key", "Value"));
                break;
            case UnrealTextHistory.TextGenerator:
                string generator = Text(fields, "GeneratorTypeID");
                if (generator.Length == 0) throw new ConversionException("Missing FText generator type ID");
                String(generator);
                if (!string.Equals(generator, "None", StringComparison.OrdinalIgnoreCase)) {
                    generators.Add(generator);
                    byte[] contents = Required(fields, "GeneratorContents") is JArray words
                        ? words.Values<byte>().ToArray() : Convert.FromBase64String(Text(fields, "GeneratorContents"));
                    writer.Write(contents.Length);
                    writer.Write(contents);
                }
                break;
        }
    }

    private void Format(UnrealTextHistory history, JObject fields, int depth)
    {
        Write(Required(fields, "FormatText", "SourceFmt"), depth + 1);
        bool argumentData = history == UnrealTextHistory.ArgumentFormat;
        JToken arguments = argumentData ? Required(fields, "ArgumentsData", "Arguments") : Required(fields, "Arguments");
        if (history == UnrealTextHistory.NamedFormat && arguments is JObject named) {
            writer.Write(named.Count);
            foreach (var entry in named.Properties()) {
                String(entry.Name);
                Argument((JObject)entry.Value, false, depth);
            }
            return;
        }
        if (arguments is not JArray list) throw new ConversionException("Invalid FText arguments");
        writer.Write(list.Count);
        foreach (JObject argument in list) {
            if (history == UnrealTextHistory.OrderedFormat) Argument(argument, false, depth);
            else {
                String(Text(argument, "ArgumentName", "Key"));
                Argument(Object(argument, "ArgumentValue", "Value"), argumentData, depth);
            }
        }
    }

    private void Argument(JObject argument, bool argumentData, int depth)
    {
        var type = EnumValue<UnrealTextArgument>(argument["Type"]);
        writer.Write((byte)type);
        if (type == UnrealTextArgument.UInt && argumentData) return;
        JToken value = Required(argument, "Value");
        switch (type) {
            case UnrealTextArgument.Int: writer.Write(value.Value<long>()); break;
            case UnrealTextArgument.UInt: writer.Write(value.Value<ulong>()); break;
            case UnrealTextArgument.Float: writer.Write(value.Value<float>()); break;
            case UnrealTextArgument.Double: writer.Write(value.Value<double>()); break;
            case UnrealTextArgument.Text: Write(value, depth + 1); break;
            case UnrealTextArgument.Gender:
                if (argumentData) writer.Write(value.Value<byte>());
                else writer.Write(value.Value<ulong>());
                break;
        }
    }

    private void NumberOptions(JObject? options)
    {
        writer.Write(options is null ? 0 : 1);
        if (options is null) return;
        writer.Write(options.Value<bool>("AlwaysSign") ? 1 : 0);
        writer.Write(options.Value<bool>("UseGrouping") ? 1 : 0);
        writer.Write((byte)EnumValue<Rounding>(options["RoundingMode"]));
        writer.Write(Required(options, "MinimumIntegralDigits").Value<int>());
        writer.Write(Required(options, "MaximumIntegralDigits").Value<int>());
        writer.Write(Required(options, "MinimumFractionalDigits").Value<int>());
        writer.Write(Required(options, "MaximumFractionalDigits").Value<int>());
    }

    private void Chrono(UnrealTextHistory history, JObject fields)
    {
        JToken ticks = Required(fields, "SourceDateTime", "Ticks");
        writer.Write((ticks is JObject date ? Required(date, "Ticks") : ticks).Value<long>());
        if (history == UnrealTextHistory.AsTime) writer.Write((byte)EnumValue<DateStyle>(fields["TimeStyle"]));
        else {
            var style = EnumValue<DateStyle>(fields["DateStyle"]);
            writer.Write((byte)style);
            if (history == UnrealTextHistory.AsDateTime) {
                writer.Write((byte)EnumValue<DateStyle>(fields["TimeStyle"]));
                if (style == DateStyle.Custom) String(Text(fields, "CustomPattern"));
            }
        }
        String(Text(fields, "TimeZone"));
        String(Text(fields, "TargetCulture", "CultureName"));
    }

    private void Invariant(string text, uint flags)
    {
        writer.Write(flags);
        writer.Write((sbyte)UnrealTextHistory.None);
        writer.Write(text.Length == 0 ? 0 : 1);
        if (text.Length != 0) String(text);
    }

    private void String(string value)
    {
        if (value.Length == 0) { writer.Write(0); return; }
        writer.Write(checked(-value.Length - 1));
        foreach (char unit in value) writer.Write((ushort)unit);
        writer.Write((ushort)0);
    }

    private static string Text(JObject value, string name, string? alternate = null) =>
        Field(value, name, alternate)?.Value<string>() ?? "";

    private static JObject Object(JObject value, string name, string? alternate = null) =>
        Required(value, name, alternate) as JObject ?? throw new ConversionException($"Invalid FText {name}");

    private static JToken Required(JObject value, string name, string? alternate = null) =>
        Field(value, name, alternate) ?? throw new ConversionException($"Missing FText {name}");

    private static JToken? Field(JObject value, string name, string? alternate) =>
        value[name] is { Type: not JTokenType.Null } field ? field : alternate is null ? null : value[alternate];

    private static T EnumValue<T>(JToken? value) where T : struct, Enum
    {
        string? text = value?.Value<string>()?.Split("::").Last();
        if (text is not null && Enum.TryParse(text, out T result) && Enum.IsDefined(result)) return result;
        throw new ConversionException($"Invalid FText {typeof(T).Name} value {text}");
    }
}
