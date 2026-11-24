using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Rebuilds editor datasets from enabled module outputs and typed renderer requests. */
internal static class StatelessDatasetCompiler
{
    internal sealed record Result(JArray Attributes, JArray ShaderOutputs, JArray ShaderOffsets);

    internal static Result Compile(SourceAsset asset, JObject source, StatelessTemplateSource template,
        uint features, string emitterNamespace, bool preciseDefault)
    {
        List<StatelessVariables.Variable> shader = new();
        if (template.Path != "None") {
            shader.Add(StatelessVariables.Named("UniqueID"));
            shader.Add(StatelessVariables.Named("MaterialRandom"));
            foreach (string moduleClass in template.Classes)
                if (StatelessVariables.GpuOutputs(moduleClass))
                    foreach (var variable in StatelessVariables.Outputs(moduleClass, new JObject(), true))
                        StatelessVariables.AddUnique(shader, variable);
        }

        JArray attributes;
        if (template.Cooked) attributes = CompiledParticleLayout.FromSaved(asset, source["ParticleDataSetCompiledData"]);
        else {
            List<StatelessVariables.Variable> available = new(), selected = new();
            if (template.Path != "None") {
                available.Add(StatelessVariables.Named("UniqueID"));
                available.Add(StatelessVariables.Named("MaterialRandom"));
                foreach (var module in template.Modules.Where(value => value.Enabled))
                    foreach (var variable in StatelessVariables.Outputs(module.ClassName, module.Properties, false))
                        StatelessVariables.AddUnique(available, variable);
            }
            if ((features & 1u) != 0) available.RemoveAll(variable => !shader.Any(output => output.Matches(variable)));
            if (source.Value<bool?>("bForceOutputAllAttributes") == true) selected.AddRange(available);
            else {
                foreach (int reference in (source["RendererProperties"] as JArray ?? new JArray()).Values<int>()) {
                    if (reference == 0 || available.Count == 0) continue;
                    SourceObject renderer = asset.Object(reference);
                    if (renderer.Properties.Value<bool?>("bIsEnabled") == false) continue;
                    foreach (var requested in StatelessRendererVariables.Read(asset, renderer, emitterNamespace, preciseDefault)) {
                        string name = requested.Name;
                        if (name.StartsWith("Particles.", StringComparison.OrdinalIgnoreCase)) name = name[10..];
                        StatelessVariables.Variable variable = requested with { Name = name };
                        int index = available.FindIndex(value => value.Matches(variable));
                        if (index < 0) continue;
                        available[index] = available[^1];
                        available.RemoveAt(available.Count - 1);
                        selected.Add(variable);
                        if (available.Count == 0) break;
                    }
                }
                if (source.Value<bool?>("bForceOutputUniqueID") == true
                    && available.Any(value => value.Matches(StatelessVariables.Named("UniqueID")))) selected.Add(StatelessVariables.Named("UniqueID"));
            }
            attributes = StatelessVariables.Layout(selected);
        }
        JArray offsets = new();
        foreach (var variable in shader) {
            JObject? attribute = attributes.OfType<JObject>().FirstOrDefault(value => variable.Matches(
                new StatelessVariables.Variable(value.Value<string>("name")!, value.Value<string>("source_type")!)));
            offsets.Add(attribute?.Value<int>("component") ?? -1);
        }
        return new(attributes, StatelessVariables.Layout(shader), offsets);
    }
}
