using System.Text.RegularExpressions;

namespace LumenFx.Compiler.Unreal.Execution;

/** Restores legacy component-wise logical operations in saved Niagara vector comparisons. */
internal static class GpuVectorBooleanOperations
{
    private const string Vector = @"(?:bool|int|uint|half|float)[234]\s*\(\s*[+\-0-9.eEfFuU, \t]+\)";
    private const string Comparison = @"\(\s*[a-zA-Z_][a-zA-Z_0-9.]*\s*(?:<=|>=|==|!=|<|>)\s*(?:" + Vector
        + @"|[a-zA-Z_][a-zA-Z_0-9.]*)\s*\)";
    private static readonly Regex VectorConstructor = new(Vector);
    private static readonly Regex Reduction = new(
        @"\b(?<operation>any|all)\s*\(\s*(?<left>" + Comparison + @")\s*(?<logical>\|\||&&)\s*(?<right>"
        + Comparison + @")\s*\)");

    internal static string Lower(string hlsl)
    {
        return Reduction.Replace(hlsl, match => {
            string left = match.Groups["left"].Value, right = match.Groups["right"].Value;
            if (!VectorConstructor.IsMatch(left) && !VectorConstructor.IsMatch(right)) return match.Value;
            string intrinsic = match.Groups["logical"].Value == "||" ? "or" : "and";
            return $"{match.Groups["operation"].Value}({intrinsic}({left}, {right}))";
        });
    }
}
