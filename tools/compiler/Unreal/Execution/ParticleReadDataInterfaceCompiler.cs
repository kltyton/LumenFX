using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class ParticleReadDataInterfaceCompiler
{
    internal static JObject Compile(SourceAsset asset, int reference, JObject? runtimeOuter, AssetLibrary? library,
        JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? throw new ConversionException("Particle reader function has no name");
        string kind = "";
        int expectedInputs, expectedOutputs;
        switch (name)
        {
            case "GetLocalSpace": case "Get Num Spawned Particles": case "Get Num Particles":
                expectedInputs = 0; expectedOutputs = 1; break;
            case "Get ID At Spawn Index": expectedInputs = 1; expectedOutputs = 3; break;
            case "Get Particle Index": expectedInputs = 2; expectedOutputs = 1; break;
            case "Get Particle Index From ID Table": expectedInputs = 1; expectedOutputs = 2; break;
            default:
                string suffix = name.EndsWith(" By ID", StringComparison.Ordinal) ? " By ID"
                    : name.EndsWith(" By Index", StringComparison.Ordinal) ? " By Index" : "";
                if (!name.StartsWith("Get ", StringComparison.Ordinal) || suffix.Length == 0)
                    throw new ConversionException($"Unsupported particle reader function {name}");
                kind = name[4..^suffix.Length];
                int width = kind switch {
                    "Int" or "Bool" or "Float" => 1, "Vector 2D" or "ID" => 2,
                    "Vector" or "Position" => 3, "Vector 4" or "Color" or "Quaternion" => 4,
                    _ => throw new ConversionException($"Unsupported particle reader value {kind}") };
                expectedInputs = suffix == " By ID" ? 2 : 1;
                expectedOutputs = 1 + width;
                break;
        }
        if (inputs != expectedInputs || outputs != expectedOutputs)
            throw new ConversionException($"Invalid particle reader signature {name}: {inputs} inputs, {outputs} outputs");
        string attribute = "";
        if (kind.Length != 0)
        {
            JArray specs = function["FunctionSpecifiers"] as JArray ?? new JArray();
            JObject[] matches = specs.OfType<JObject>()
                .Where(value => string.Equals(value.Value<string>("Key"), "Attribute", StringComparison.OrdinalIgnoreCase)).ToArray();
            if (matches.Length != 1 || string.IsNullOrWhiteSpace(matches[0].Value<string>("Value")))
                throw new ConversionException($"Particle reader function {name} lacks an Attribute specifier");
            attribute = matches[0].Value<string>("Value")!;
        }
        JObject result = EmitterBindingCompiler.Compile(asset, reference, "NiagaraDataInterfaceParticleRead", runtimeOuter, library);
        result["attribute"] = attribute;
        result["value_type"] = kind;
        return result;
    }
}
