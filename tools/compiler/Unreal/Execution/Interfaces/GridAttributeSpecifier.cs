using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class GridAttributeSpecifier
{
    internal static string Read(JToken? specifiers, string functionName)
        => NiagaraFunctionSpecifiers.Find(specifiers, "Attribute")
            ?? throw new ConversionException($"Niagara grid function {functionName} has no Attribute specifier");
}
