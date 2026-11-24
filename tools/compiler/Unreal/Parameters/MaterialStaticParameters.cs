using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private readonly Dictionary<MaterialParameterKey, bool> staticSwitchOverrides = new();
    private readonly Dictionary<MaterialParameterKey, bool> inheritedStaticSwitchDefaults = new();
    private readonly Dictionary<MaterialParameterKey, int> staticMaskOverrides = new();

    private void ReadStaticParameters(SourceAsset asset, SourceObject instance, bool parentDefaults)
    {
        foreach (var parameter in StaticParameterValues(asset, instance)) {
            if (parameter.Mask is int bits) staticMaskOverrides.TryAdd(parameter.Key, bits);
            else if (parameter.Switch is bool enabled) {
                staticSwitchOverrides.TryAdd(parameter.Key, enabled);
                if (parentDefaults) inheritedStaticSwitchDefaults.TryAdd(parameter.Key, enabled);
            }
        }
    }

    private static IEnumerable<(MaterialParameterKey Key, bool? Switch, int? Mask)> StaticParameterValues(SourceAsset asset, SourceObject instance)
    {
        StaticParameterSetSource source = InstanceStaticParameters(asset, instance);
        foreach (var value in ReadSet(source.Runtime, "StaticSwitchParameters")) yield return value;
        foreach (var value in ReadSet(source.Editor, "StaticComponentMaskParameters")) yield return value;

        IEnumerable<(MaterialParameterKey Key, bool? Switch, int? Mask)> ReadSet(JObject set, string field)
        {
            if (set[field] is not { } values || values.Type == JTokenType.Null) yield break;
            if (values is not JArray array) throw new ConversionException($"Static material parameters are not an array: {instance.Name}");
            foreach (JToken value in array) {
                if (value is not JObject parameter) throw new ConversionException($"Invalid static material parameter: {instance.Name}");
                if (parameter.Value<bool?>("bOverride") != true) continue;
                JObject info = parameter["ParameterInfo"] as JObject
                        ?? throw new ConversionException($"Static parameter has no identity: {instance.Name}");
                string name = info.Value<string>("Name") ?? throw new ConversionException("Static parameter has no name");
                string association = ParameterAssociation(info["Association"]);
                int index = info.Value<int?>("Index") ?? -1;
                var key = new MaterialParameterKey(name, association, index);
                if (field == "StaticComponentMaskParameters")
                    yield return (key, null, ComponentBits(parameter, ""));
                else yield return (key, parameter.Value<bool?>("Value") ?? false, null);
            }
        }
    }

    private bool StaticBoolValue(Context context, JObject properties, bool functionDefault = true)
    {
        string name = properties.Value<string>("ParameterName") ?? "Param";
        var key = new MaterialParameterKey(name, context.ParameterAssociation, context.ParameterIndex);
        bool expressionDefault = (functionDefault ? FunctionStaticSwitchValue(context, name) : null)
                ?? properties.Value<bool?>("DefaultValue") == true;
        var parentDefaults = context.LayerParameters?.ParentSwitches ?? inheritedStaticSwitchDefaults;
        var switches = context.LayerParameters?.Switches ?? staticSwitchOverrides;
        bool defaultValue = parentDefaults.TryGetValue(key, out bool inherited)
                ? inherited : expressionDefault;
        if (context.ParameterAssociation == "GlobalParameter" && context.ParameterIndex == -1
                && rendererConstants.StaticSwitch(name, defaultValue, out bool rendererValue))
            return rendererValue;
        return switches.TryGetValue(key, out bool value)
                ? value : expressionDefault;
    }

    private Expr StaticSwitchParameter(Context context, JObject properties)
    {
        if (properties.Value<bool?>("DynamicBranch") == true) return DynamicSwitchParameter(context, properties);
        ValidateStaticSwitchInputs(properties);
        return RequiredInput(context, properties, StaticBoolValue(context, properties) ? "A" : "B");
    }

    private Expr StaticComponentMask(Context context, JObject properties)
    {
        Expr input = RequiredInput(context, properties, "Input");
        string name = properties.Value<string>("ParameterName") ?? "Param";
        var key = new MaterialParameterKey(name, context.ParameterAssociation, context.ParameterIndex);
        var masks = context.LayerParameters?.Masks ?? staticMaskOverrides;
        int bits = masks.TryGetValue(key, out int overridden) ? overridden
                : FunctionStaticMaskValue(context, name) ?? ComponentBits(properties, "Default");
        string mask = string.Concat(Enumerable.Range(0, 4).Where(channel => (bits & (1 << channel)) != 0).Select(channel => "rgba"[channel]));
        return Swizzle(input, mask);
    }

    private static int ComponentBits(JObject properties, string prefix)
    {
        int result = 0;
        for (int channel = 0; channel < 4; channel++)
            if (properties.Value<bool?>(prefix + "RGBA"[channel]) == true) result |= 1 << channel;
        return result;
    }

    private static void ValidateStaticSwitchInputs(JObject properties)
    {
        if (!Connected(properties["A"]) || !Connected(properties["B"]))
            throw new ConversionException("Static switch parameter requires both A and B inputs");
    }
}
