using System.Globalization;
using System.Runtime.InteropServices;

namespace LumenFx.Compiler.Unreal.Parameters;

/** The fixed Windows source profile splits MPC decimal suffixes with the UTF-16 CRT, independently of FName numbering. */
internal static class MaterialCollectionNames
{
    [DllImport("ucrtbase.dll", EntryPoint = "iswdigit", CallingConvention = CallingConvention.Cdecl, ExactSpelling = true)]
    private static extern int IsDigit(ushort character);

    [DllImport("ucrtbase.dll", EntryPoint = "_wtoi", CallingConvention = CallingConvention.Cdecl, ExactSpelling = true, CharSet = CharSet.Unicode)]
    private static extern int ToInteger(string value);

    internal static IReadOnlyList<MaterialParameterCollectionSource.Parameter> Sanitize(
        IReadOnlyList<MaterialParameterCollectionSource.Parameter> parameters,
        IEnumerable<MaterialParameterCollectionSource.Parameter> inherited, Func<string> newGuid)
    {
        HashSet<string> ids = new(StringComparer.Ordinal);
        Dictionary<string, HashSet<int>> names = new(StringComparer.Ordinal);
        HashSet<int> Numbers(string name) {
            if (!names.TryGetValue(name, out var values)) names.Add(name, values = new());
            return values;
        }
        foreach (var parameter in inherited) {
            ids.Add(parameter.Id);
            var (name, number) = Split(parameter.Name);
            Numbers(name).Add(number);
        }
        List<MaterialParameterCollectionSource.Parameter> result = new(parameters.Count);
        foreach (var parameter in parameters) {
            string id = parameter.Id;
            while (!ids.Add(id)) id = newGuid();
            var (prefix, number) = Split(parameter.Name);
            int available = number;
            HashSet<int> used = Numbers(prefix);
            while (used.Contains(available)) available = unchecked(available + 1);
            used.Add(available);
            string name = available == number ? parameter.Name : prefix + unchecked((uint)available).ToString(CultureInfo.InvariantCulture);
            result.Add(parameter with { Name = name, Id = id });
        }
        return result;
    }

    private static (string Name, int Number) Split(string value)
    {
        int start = value.Length;
        while (start > 0 && IsDigit(value[start - 1]) != 0) start--;
        int number = start == value.Length ? 0 : ToInteger(value[start..]);
        string name = start == 0 ? "Param" : value[..start];
        return (name, number);
    }
}
