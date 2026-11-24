using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class NiagaraFunctionSpecifiers
{
    internal static string? Find(JToken? specifiers, string expectedKey)
    {
        // VM signatures use Key/Value structs, while GPU generated functions use TTuple<FName, FName>.
        foreach (var specifier in specifiers as JArray ?? new JArray()) {
            var (key, value) = specifier switch {
                JObject pair when pair.ContainsKey("Item1") => (pair.Value<string>("Item1"), pair.Value<string>("Item2")),
                JObject pair => (pair.Value<string>("Key"), pair.Value<string>("Value")),
                JArray { Count: 2 } pair => (pair[0].Value<string>(), pair[1].Value<string>()),
                _ => (null, null)
            };
            if (SourceNameOrder.Equal(key, expectedKey) && !string.IsNullOrWhiteSpace(value)) return value;
        }
        return null;
    }
}
