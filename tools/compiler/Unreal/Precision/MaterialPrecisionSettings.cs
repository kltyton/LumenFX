using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed record MaterialPrecisionSettings(bool LwcEnabled = true, int TruncateMode = 2,
        bool SupportsFma = true, bool ForceFloatOperations = false, int NewTranslatorMode = 0, bool MirStrictProfile = false)
{
    internal static MaterialPrecisionSettings Read(JToken? value, MaterialPrecisionSettings source)
    {
        if (value is null || value.Type == JTokenType.Null) return source;
        if (value is not JObject settings) throw new ConversionException("Material precision settings must be an object");
        int? translator = settings.Value<int?>("new_translator_mode");
        if (translator is < 0 or > 2) throw new ConversionException("Material new_translator_mode must be between 0 and 2");
        int? profile = settings.Value<int?>("new_translator_profile");
        if (profile is < 0 or > 1) throw new ConversionException("Material new_translator_profile must be 0 or 1");
        return new(settings.Value<bool?>("lwc_enabled") ?? source.LwcEnabled,
                settings.Value<int?>("truncate_mode") ?? source.TruncateMode,
                settings.Value<bool?>("supports_fma") ?? source.SupportsFma,
                settings.Value<bool?>("force_float_operations") ?? source.ForceFloatOperations,
                translator ?? source.NewTranslatorMode, profile is int selected ? selected != 0 : source.MirStrictProfile);
    }
}
