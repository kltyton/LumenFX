using LumenFx.Compiler.Unreal.Execution;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Rebuilds editor external-object aliases from the emitter's actual script targets and execution-store declarations. */
internal static class RendererExternalObjectBindings
{
    internal static JArray Compile(SourceAsset asset, SourceObject system, JObject emitter, string? handle, JArray declarations)
    {
        JArray result = new();
        var objects = declarations.Cast<JObject>().Where(value => value.Value<string>("storage") == "object").ToArray();
        IEnumerable<JObject> mappings;
        if ((asset.Raw.Value<string>("PackageFlags") ?? "").Contains("PKG_Cooked", StringComparison.Ordinal))
            mappings = (emitter["RendererBindingsExternalObjects"] as JArray ?? new()).Cast<JObject>();
        else mappings = UserWrites(RendererScriptTargets.Read(asset, system, emitter, handle));
        foreach (JObject mapping in mappings) {
            JObject variable = mapping["Variable"] as JObject ?? throw new ConversionException("External UObject mapping has no variable");
            var expected = RendererFieldBindings.Variable.From(asset, variable)
                ?? throw new ConversionException("External UObject mapping has no source type");
            if (!objects.Any(value => new RendererFieldBindings.Variable(value.Value<string>("name")!, value.Value<string>("source_type")!).SameStoreSlot(expected))) continue;
            result.Add(new JObject { ["target"] = new JObject { ["name"] = expected.Name, ["source_type"] = expected.Type,
                    ["underlying"] = expected.UnderlyingType, ["flags"] = expected.Flags, ["storage"] = "object" },
                ["external"] = mapping.Value<string>("ExternalName") ?? throw new ConversionException("External UObject mapping has no source name") });
        }
        return result;
    }

    private static IEnumerable<JObject> UserWrites(IEnumerable<RendererScriptTargets.Target> targets)
    {
        foreach (var target in targets)
            foreach (JObject info in (target.Script.Properties["CachedDefaultUObjects"] as JArray ?? new()).Cast<JObject>()) {
                JObject variable = info["Variable"] as JObject ?? throw new ConversionException("Compiled UObject information has no variable");
                string read = info.Value<string>("RegisteredParameterMapRead") ?? "None";
                int separator = read.IndexOf('.');
                if (separator <= 0 || !SourceNameOrder.Equal(read[..separator], "User")) continue;
                foreach (string write in (info["RegisteredParameterMapWrites"] as JArray ?? new()).Values<string>()) {
                    JObject destination = (JObject)variable.DeepClone(); destination["name"] = write;
                    yield return new JObject { ["Variable"] = destination, ["ExternalName"] = read };
                }
            }
    }
    internal static IEnumerable<JObject> Read(SourceAsset asset, SourceObject system, JObject emitter,
        string? emitterHandle, JObject expected)
    {
        if ((asset.Raw.Value<string>("PackageFlags") ?? "").Contains("PKG_Cooked", StringComparison.Ordinal)) {
            foreach (JObject binding in (emitter["RendererBindingsExternalObjects"] as JArray ?? new()).OfType<JObject>())
                if (binding["Variable"] is JObject variable && NiagaraVariableSource.Same(asset, expected, asset, variable))
                    yield return binding;
            yield break;
        }

        var targets = RendererScriptTargets.Read(asset, system, emitter, emitterHandle).ToArray();
        if (!targets.Any(target => Declares(asset, target, expected))) yield break;
        foreach (var mapping in UserWrites(targets))
            if (NiagaraVariableSource.Same(asset, expected, asset, (JObject)mapping["Variable"]!)) yield return mapping;
    }

    private static bool Declares(SourceAsset asset, RendererScriptTargets.Target target, JObject expected)
        => Declares(target, variable => NiagaraVariableSource.Same(asset, expected, asset, variable));

    internal static bool Declares(RendererScriptTargets.Target target, Func<JObject, bool> matches)
    {
        if (target.Runtime is null) return false;
        if (ScriptDataInterfaceBindings.ParameterVariables(target.Script)
                .Any(matches)) return true;
        return (target.Runtime["ResolvedUObjects"] as JArray ?? new()).OfType<JObject>()
            .Any(value => value["ResolvedVariable"] is JObject variable
                && matches(variable));
    }

}
