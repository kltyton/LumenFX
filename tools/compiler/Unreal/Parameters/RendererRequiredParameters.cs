using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Geometry;
using Newtonsoft.Json.Linq;
using Variable = LumenFx.Compiler.Unreal.Execution.RendererFieldBindings.Variable;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Rebuilds PopulateRequiredBindings using real host availability, independently of particle draw requirements. */
internal static class RendererRequiredParameters
{
    internal static IReadOnlyList<Variable> Read(SourceAsset asset, SourceObject system, JObject emitter, string emitterNamespace)
    {
        List<Variable> result = new();
        int spawn = system.Properties.Value<int?>("SystemSpawnScript") ?? 0;
        JArray attributes = spawn == 0 ? new() : asset.Object(spawn).Properties["CachedScriptVM"]?["Attributes"] as JArray ?? new();
        JArray exposed = system.Properties["ExposedParameters"]?["SortedParameterOffsets"] as JArray ?? new();
        foreach (int reference in (emitter["RendererProperties"] as JArray ?? new()).Values<int>()) {
            if (reference == 0) continue;
            SourceObject renderer = asset.Object(reference);
            JObject source = RendererAttributeBindings.LoadedProperties(asset, renderer);
            if (source.Value<bool?>("bIsEnabled") == false) continue;
            string? sourceMode = source.Value<string>("SourceMode") ?? RendererFieldBindings.DefaultSourceMode(renderer.ClassName);
            RendererAttributeBindings names = new(source, emitterNamespace, asset, sourceMode);
            Add(RendererFieldBindings.Resolve(asset, source, emitterNamespace, new("RendererEnabledBinding", "RendererEnabled"), sourceMode));
            if (renderer.ClassName == "NiagaraMeshRendererProperties") AddParameter(source["MeshesBinding"]);
            if (renderer.ClassName == "NiagaraRibbonRendererProperties") Add(NiagaraRenderer.RibbonInterface(asset, source, emitterNamespace));

            // Component rendering uses particle property bindings and inherits only the base host-store population.
            if (renderer.ClassName != "NiagaraComponentRendererProperties")
                foreach (var field in RendererFieldBindings.Read(renderer.ClassName)) {
                    string current = field.Previous ? field.Name[4..] : field.Name;
                    if (names.Particle(current)) continue;
                    Variable? variable = RendererFieldBindings.Resolve(asset, source, emitterNamespace, field, sourceMode);
                    if (variable is null) continue;
                    if (names.Source(current) == "ExplicitUser") {
                        if (HasUser(variable)) Add(variable);
                    } else Add(Host(variable));
                }

            if (renderer.ClassName is "NiagaraSpriteRendererProperties" or "NiagaraRibbonRendererProperties"
                    or "NiagaraMeshRendererProperties" or NaniteRendererSource.ClassName
                    or "NiagaraDecalRendererProperties" or "NiagaraVolumeRendererProperties") {
                JArray material = source["MaterialParameters"]?["AttributeBindings"] as JArray ?? new();
                if ((renderer.ClassName is "NiagaraSpriteRendererProperties" or "NiagaraRibbonRendererProperties" or "NiagaraMeshRendererProperties")
                        && (source["MaterialParameterBindings_DEPRECATED"] ?? source["MaterialParameterBindings"]) is JArray { Count: > 0 } legacy)
                    material = legacy;
                foreach (JObject binding in material.OfType<JObject>())
                    if ((binding["ResolvedNiagaraVariable"] ?? binding["NiagaraVariable"]) is JObject variable)
                        Add(Variable.From(asset, variable, emitterNamespace));
            }
            if (renderer.ClassName is "NiagaraMeshRendererProperties" or NaniteRendererSource.ClassName)
                foreach (JObject mesh in (source["Meshes"] as JArray ?? new()).OfType<JObject>()) AddParameter(mesh["MeshParameterBinding"]);
            if (renderer.ClassName == NaniteRendererSource.ClassName)
                foreach (var binding in NaniteRendererSource.InstanceBindings(source))
                    Add(NaniteRendererSource.Variable(asset, binding, emitterNamespace));
        }

        int gpu = emitter.Value<int?>("GPUComputeScript") ?? 0;
        if (gpu != 0) {
            foreach (JObject stage in (asset.Object(gpu).Properties["CachedScriptVM"]?["SimulationStageMetaData"] as JArray ?? new()).OfType<JObject>()) {
                AddNamed(stage, "EnabledBinding", "/Script/Niagara.NiagaraBool");
                if (GpuProgramCompiler.DirectIteration(stage))
                    foreach (string axis in new[] { "X", "Y", "Z" }) AddNamed(stage, "ElementCount" + axis + "Binding", "/Script/Niagara.NiagaraInt32");
                AddNamed(stage, "NumIterationsBinding", "/Script/Niagara.NiagaraInt32");
            }
            if (emitter["SimStageExecutionLoopEditorData"] is JArray editorLoops) {
                foreach (JObject loop in editorLoops.OfType<JObject>())
                    if (loop.Value<bool?>("bEnabled") != false && HasName(loop, "StageNameStart") && HasName(loop, "StageNameEnd"))
                        AddNamed(loop, "NumLoopsBindingName", "/Script/Niagara.NiagaraInt32");
            } else {
                foreach (JObject loop in (emitter["SimStageExecutionLoops"] as JArray ?? new()).OfType<JObject>())
                    AddNamed(loop, "NumLoopsBinding", "/Script/Niagara.NiagaraInt32");
            }
        }
        return result;

        void Add(Variable? variable) {
            if (variable is not null && !result.Any(value => value.SameStoreSlot(variable))) result.Add(variable);
        }
        void AddParameter(JToken? binding) {
            if (binding?["ResolvedParameter"] is JObject variable) Add(Variable.From(asset, variable, emitterNamespace));
        }
        void AddNamed(JObject source, string field, string type) {
            if (HasName(source, field)) Add(new(source.Value<string>(field)!, type, Bytes: 4));
        }
        static bool HasName(JObject source, string field) => source.Value<string>(field) is { Length: > 0 } name
            && !SourceNameOrder.Equal(name, "None");
        Variable? Host(Variable requested) {
            if (attributes.OfType<JObject>().Any(variable => requested.Matches(asset, variable))) return requested;
            if (requested.Type != "/Script/Niagara.NiagaraPosition" || requested.UnderlyingType != 2 || requested.Flags != 0) return null;
            Variable old = requested with { Type = "/Script/CoreUObject.Vector3f" };
            return attributes.OfType<JObject>().Any(variable => old.Matches(asset, variable)) ? old : null;
        }
        bool HasUser(Variable requested) {
            foreach (JObject variable in exposed.OfType<JObject>()) {
                if (requested.Matches(asset, variable)) return true;
                string name = variable.Value<string>("name") ?? "None";
                if (name.Length > 5 && name[4] == '.' && SourceNameOrder.Equal(name[..4], "User")
                        && SourceNameOrder.Equal(requested.Name, name[5..]) && requested.MatchesType(asset, variable)) return true;
            }
            return false;
        }
    }
}
