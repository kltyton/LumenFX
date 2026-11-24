using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution.Stateless;

/** Mirrors renderer binding visitation order, including precise-motion and material bindings. */
internal static class StatelessRendererVariables
{
    internal static IEnumerable<StatelessVariables.Variable> Read(SourceAsset asset, SourceObject renderer,
        string emitterNamespace, bool preciseDefault)
    {
        JObject source = RendererAttributeBindings.LoadedProperties(asset, renderer);
        string? sourceMode = source.Value<string>("SourceMode") ?? RendererFieldBindings.DefaultSourceMode(renderer.ClassName);
        int sourceVersion = NiagaraImporter.NiagaraVersion(asset);
        List<StatelessVariables.Variable> result = new();
        string motion = NiagaraRenderer.MotionVectorSetting(source);
        bool precise = (source.Value<bool?>("bMotionBlurEnabled_DEPRECATED") ?? source.Value<bool?>("bMotionBlurEnabled") ?? true)
            && (motion is "Precise" or "1" || motion is "AutoDetect" or "0" && preciseDefault);

        string? Type(JToken? variable) {
            if (variable?["type"] is not JObject type) return null;
            var loaded = NiagaraTypeSource.Read(asset, type);
            return loaded.SourceReference == 0 ? null : loaded.Path;
        }
        void Add(string field, string fallback, bool previous = false) {
            if (previous && !precise) return;
            var variable = RendererFieldBindings.Resolve(asset, source, emitterNamespace, new(field, fallback, previous), sourceMode);
            if (variable is null) return;
            string? saved = previous ? null : RendererAttributeBindings.SavedDatasetBinding(asset, source, field);
            result.Add(new(saved ?? variable.Name, variable.Type));
        }
        foreach (var field in RendererFieldBindings.Read(renderer.ClassName)) Add(field.Name, field.Attribute, field.Previous);

        // Base bindings precede each renderer's additional attribute list.
        if (source["RendererEnabledBinding"] is JObject enabled) AddVariable(new RendererAttributeBindings(source, emitterNamespace, asset, sourceMode).Name("RendererEnabledBinding", "None"), Type(enabled["ParamMapVariable"]));
        if (renderer.ClassName == "NiagaraRibbonRendererProperties" && sourceVersion >= 79 && source.Value<bool?>("bLinkOrderUseUniqueID") != false)
            StatelessVariables.AddUnique(result, StatelessVariables.Named("UniqueID"));
        if (renderer.ClassName == "NiagaraLightRendererProperties") StatelessVariables.AddUnique(result, StatelessVariables.Named("UniqueID"));

        JArray materialBindings = source["MaterialParameters"]?["AttributeBindings"] as JArray ?? new JArray();
        if ((source["MaterialParameterBindings_DEPRECATED"] ?? source["MaterialParameterBindings"]) is JArray { Count: > 0 } legacy) materialBindings = legacy;
        foreach (JObject binding in materialBindings.OfType<JObject>()) {
            JToken? variable = binding["NiagaraVariable"];
            AddVariable(variable?.Value<string>("name"), Type(variable));
        }
        foreach (string field in new[] { "MaterialUserParamBinding", "MeshesBinding", "MaterialParameterBinding" })
            AddParameter(source[field]);
        foreach (JObject mesh in (source["Meshes"] as JArray ?? new JArray()).OfType<JObject>()) AddParameter(mesh["MeshParameterBinding"]);
        if (source.Value<bool?>("bOverrideMaterials") == true)
            foreach (JObject material in (source["OverrideMaterials"] as JArray ?? new JArray()).OfType<JObject>()) AddParameter(material["UserParamBinding"]);
        if (renderer.ClassName == "NiagaraComponentRendererProperties") {
            result.Add(new("Particles.ComponentsEnabled", "/Script/Niagara.NiagaraBool"));
            if (source.Value<bool?>("bAssignComponentsOnParticleID") != false) result.Add(StatelessVariables.Named("UniqueID") with { Name = "Particles.UniqueID" });
            foreach (JObject binding in (source["PropertyBindings"] as JArray ?? new JArray()).OfType<JObject>()) {
                JObject wrapper = new() { ["AttributeBinding"] = binding["AttributeBinding"]?.DeepClone() };
                string name = new RendererAttributeBindings(wrapper, emitterNamespace, asset).Name("AttributeBinding", "None");
                AddVariable(name, Type(binding["AttributeBinding"]?["ParamMapVariable"]));
            }
        }
        return result;

        void AddVariable(string? name, string? type) {
            if (name is not null && name != "None" && type is not null) StatelessVariables.AddUnique(result, new(name, type));
        }
        void AddParameter(JToken? binding) {
            JToken? variable = binding?["AliasedParameter"] ?? binding?["Parameter"];
            AddVariable(variable?.Value<string>("name"), Type(variable));
        }
    }

}
