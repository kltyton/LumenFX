using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed class FunctionParameterOverrides
    {
        internal readonly Dictionary<(InstanceParameterType Type, MaterialParameterKey Key), JToken> Values;
        internal readonly Dictionary<MaterialParameterKey, bool> Switches;
        internal readonly Dictionary<MaterialParameterKey, int> Masks;

        internal FunctionParameterOverrides(FunctionParameterOverrides? outer)
        {
            Values = outer is null ? new() : new(outer.Values);
            Switches = outer is null ? new() : new(outer.Switches);
            Masks = outer is null ? new() : new(outer.Masks);
        }

        internal void Read(SourceAsset asset, SourceObject instance)
        {
            foreach (var parameter in InstanceParameterValues(asset, instance))
                if (Global(parameter.Key)) Values.TryAdd((parameter.Type, parameter.Key), parameter.Value);
            ReadStatic("StaticSwitchParameterValues", false);
            ReadStatic("StaticComponentMaskParameterValues", true);

            void ReadStatic(string field, bool mask)
            {
                if (instance.Properties[field] is not { Type: not JTokenType.Null } values) return;
                if (values is not JArray array) throw new ConversionException($"Function instance {field} is not an array: {instance.Name}");
                foreach (JObject parameter in array) {
                    if (parameter.Value<bool?>("bOverride") != true) continue;
                    JObject info = parameter["ParameterInfo"] as JObject ?? throw new ConversionException("Function static parameter has no identity");
                    var key = new MaterialParameterKey(info.Value<string>("Name") ?? throw new ConversionException("Function parameter has no name"),
                            ParameterAssociation(info["Association"]), info.Value<int?>("Index") ?? -1);
                    if (!Global(key)) continue;
                    if (mask) Masks.TryAdd(key, ComponentBits(parameter, ""));
                    else Switches.TryAdd(key, parameter.Value<bool?>("Value") ?? false);
                }
            }
        }
        private static bool Global(MaterialParameterKey key) => key.Association == "GlobalParameter" && key.Index == -1;
    }

    private static JToken? FunctionParameterValue(Context context, InstanceParameterType type, string name)
        => context.FunctionOverrides?.Values.GetValueOrDefault((type, new MaterialParameterKey(name, "GlobalParameter", -1)));

    private static bool? FunctionStaticSwitchValue(Context context, string name)
        => context.FunctionOverrides is { } overrides && overrides.Switches.TryGetValue(new MaterialParameterKey(name, "GlobalParameter", -1), out bool value)
            ? value : null;

    private static int? FunctionStaticMaskValue(Context context, string name)
        => context.FunctionOverrides is { } overrides && overrides.Masks.TryGetValue(new MaterialParameterKey(name, "GlobalParameter", -1), out int value)
            ? value : null;
}
