namespace LumenFx.Compiler.Unreal.Execution.Stateless;

internal sealed class StatelessDataBuilder
{
    internal enum Mode { Array, Binding, Expression, UniformConstant, NonUniformConstant, UniformRange,
        NonUniformRange, UniformCurve, NonUniformCurve, ColorGradient }
    internal readonly record struct Distribution(uint Header, uint TimeBias, uint TimeScale);
    private const uint Uniform = 1u << 28, Wrap = 1u << 29, Linear = 1u << 30, Static = 1u << 31;
    private readonly List<uint> staticWords = new();
    private uint moduleSeed;

    internal uint BeginModule() => moduleSeed = unchecked(moduleSeed + 1);

    internal uint[] StaticWords()
    {
        uint[] result = new uint[Math.Max(4, staticWords.Count)];
        staticWords.CopyTo(result);
        return result;
    }

    internal int AddStatic(ReadOnlySpan<uint> words)
    {
        if (words.Length == 0) return 0;
        for (int offset = 0; offset + words.Length <= staticWords.Count; offset++) {
            int index = 0;
            while (index < words.Length && staticWords[offset + index] == words[index]) index++;
            if (index == words.Length) return offset;
        }
        int start = staticWords.Count;
        foreach (uint word in words) staticWords.Add(word);
        return start;
    }

    internal Distribution Build(Mode mode, ReadOnlySpan<uint> values, int components, ReadOnlySpan<uint> defaults,
        float timeX, float timeY, byte lookupMode, bool interpolate = true, bool clamp = true, int parameterOffset = -1)
    {
        if (components is < 1 or > 4 || defaults.Length != components || values.Length % components != 0)
            throw new ConversionException("Stateless distribution values do not match their component width");
        int count = values.Length / components;
        if (mode is Mode.Binding or Mode.Expression)
            return Buffer(parameterOffset, 1, defaults, false);
        if (mode == Mode.Array) {
            Distribution array = Buffer(AddStatic(values), count, defaults, true);
            uint header = array.Header;
            header = interpolate ? header | Linear : header & ~Linear;
            header = clamp ? header & ~Wrap : header | Wrap;
            return Time(LookupMode(array with { Header = header | Uniform }, lookupMode), timeX, timeY);
        }
        if (count == 0) { values = defaults; count = 1; }
        if (mode == Mode.UniformConstant) { count = 1; values = values[..components]; }
        else if (mode == Mode.UniformRange) {
            if (count < 2) throw new ConversionException("Uniform stateless range requires two entries");
            count = 2; values = values[..(components * 2)];
        }
        uint flags = Static | Linear;
        if (mode is Mode.UniformConstant or Mode.UniformRange or Mode.UniformCurve or Mode.ColorGradient) flags |= Uniform;
        bool random = mode is Mode.UniformRange or Mode.NonUniformRange;
        Distribution result = LookupMode(Table(flags, AddStatic(values), count), random ? byte.MaxValue : lookupMode);
        float duration = timeY - timeX;
        float scale = count > 1 && duration > 0f ? (float)(count - 1) / duration : 0f;
        return Time(result, scale, timeX);
    }

    internal Distribution Curve(Mode mode, ReadOnlySpan<uint> values, int components, ReadOnlySpan<uint> defaults,
        float minimumTime, float maximumTime, byte lookupMode)
    {
        if (components is < 1 or > 4 || defaults.Length != components || values.Length % components != 0)
            throw new ConversionException("Stateless curve values do not match their component width");
        if ((mode is Mode.UniformCurve or Mode.NonUniformCurve or Mode.ColorGradient) && values.Length / components > 1)
            return Build(mode, values, components, defaults, minimumTime, maximumTime, lookupMode);
        uint[] pair = new uint[components * 2];
        defaults.CopyTo(pair.AsSpan()); defaults.CopyTo(pair.AsSpan(components));
        return Build(Mode.NonUniformCurve, pair, components, defaults, minimumTime, maximumTime, lookupMode);
    }

    private Distribution Buffer(int offset, int count, ReadOnlySpan<uint> defaults, bool isStatic)
    {
        if (offset == -1 || count == 0) { offset = AddStatic(defaults); count = 1; isStatic = true; }
        Distribution result = Table(Uniform | (isStatic ? Static : 0u), offset, count);
        return Time(result, count - 1, 0f);
    }

    private static Distribution Table(uint flags, int offset, int count)
    {
        // SetLookupParameters checks against TableLengthMask itself, even though nine bits encode count minus one.
        if (offset < 0 || offset > 0x1ffff || count < 1 || count > 511)
            throw new ConversionException("Stateless distribution exceeds the source offset or table length limit");
        return new Distribution(flags | (uint)offset | (uint)(count - 1) << 17, 0, 0);
    }

    private static Distribution LookupMode(Distribution value, byte mode)
    {
        if (mode != byte.MaxValue && mode > 2) throw new ConversionException("Stateless distribution has an invalid lookup mode");
        uint packed = mode == byte.MaxValue ? 3u : mode;
        return value with { Header = value.Header & ~(3u << 26) | packed << 26 };
    }

    private static Distribution Time(Distribution value, float scale, float bias)
    {
        scale = .0001f < scale ? scale : .0001f;
        return value with { TimeBias = unchecked((uint)BitConverter.SingleToInt32Bits(bias)),
            TimeScale = unchecked((uint)BitConverter.SingleToInt32Bits(scale)) };
    }
}
