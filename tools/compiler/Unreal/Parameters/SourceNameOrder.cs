namespace LumenFx.Compiler.Unreal.Parameters;

/** Editor parameter stores compare FName base strings before their internal numeric suffixes. */
internal sealed class SourceNameOrder : IComparer<string>, IEqualityComparer<string>
{
    internal static SourceNameOrder Instance { get; } = new();
    private SourceNameOrder() { }

    public int Compare(string? left, string? right)
    {
        ArgumentNullException.ThrowIfNull(left);
        ArgumentNullException.ThrowIfNull(right);
        var a = Split(left);
        var b = Split(right);
        int names = CompareText(a.Name, b.Name);
        return names != 0 ? names : a.Number.CompareTo(b.Number);
    }

    internal static int CompareText(string left, string right)
    {
        int length = Math.Min(left.Length, right.Length);
        for (int i = 0; i < length; i++) {
            int a = left[i], b = right[i];
            if (a == b) continue;
            if ((a | b) < 128) {
                if (a is >= 'A' and <= 'Z') a += 'a' - 'A';
                if (b is >= 'A' and <= 'Z') b += 'a' - 'A';
            }
            if (a != b) return a - b;
        }
        return left.Length - right.Length;
    }

    internal static bool Equal(string? left, string? right)
        => left is null || right is null ? left == right : Instance.Compare(left, right) == 0;

    public bool Equals(string? left, string? right) => Equal(left, right);

    public int GetHashCode(string value)
    {
        HashCode hash = new();
        foreach (char character in value)
            hash.Add(character is >= 'A' and <= 'Z' ? character + ('a' - 'A') : character);
        return hash.ToHashCode();
    }

    internal static string Canonical(string value) => string.Create(value.Length, value, static (output, source) => {
        for (int index = 0; index < source.Length; index++) {
            char character = source[index];
            output[index] = character is >= 'A' and <= 'Z' ? (char)(character + ('a' - 'A')) : character;
        }
    });

    private static (string Name, int Number) Split(string value)
    {
        int firstDigit = value.Length;
        while (firstDigit > 0 && value[firstDigit - 1] is >= '0' and <= '9') firstDigit--;
        int digits = value.Length - firstDigit;
        if (digits == 0 || firstDigit == 0 || value[firstDigit - 1] != '_' || digits > 10
                || digits > 1 && value[firstDigit] == '0') return (value, 0);
        long number = 0;
        for (int i = firstDigit; i < value.Length; i++) number = number * 10 + value[i] - '0';
        return number < int.MaxValue ? (value[..(firstDigit - 1)], (int)number + 1) : (value, 0);
    }
}
