using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class ConsoleVariableDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceConsoleVariable";

    internal static JObject CpuFunction(JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        if (inputs != 0 || outputs != 2 || !IsGetter(name))
            throw new ConversionException($"Invalid Niagara console variable signature: {name} ({inputs}, {outputs})");
        return new JObject { ["console_name"] = VariableName(function["FunctionSpecifiers"], name, false) };
    }

    internal static JArray GpuFunctions(JObject shaderInterface)
    {
        var entries = new JArray();
        foreach (var item in shaderInterface["GeneratedFunctions"] as JArray ?? new JArray()) {
            if (item is not JObject function || function.Value<string>("DefinitionName") is not string name || !IsGetter(name))
                throw new ConversionException("Invalid generated Niagara console variable function");
            entries.Add(new JObject { ["name"] = name, ["console_name"] = VariableName(function["Specifiers"], name, true) });
        }
        return entries;
    }

    private static bool IsGetter(string name) => name is "GetConsoleVariableFloat" or "GetConsoleVariableInt" or "GetConsoleVariableBool";

    private static string VariableName(JToken? specifiers, string name, bool allowNone)
    {
        if (NiagaraFunctionSpecifiers.Find(specifiers, "ConsoleVariableName") is string variable) return variable;
        if (allowNone && (specifiers is null || specifiers is JArray { Count: 0 })) return "None";
        throw new ConversionException($"Niagara console variable function has no name specifier: {name}");
    }
}
