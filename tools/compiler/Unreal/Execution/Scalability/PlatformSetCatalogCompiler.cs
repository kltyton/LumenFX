using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Mirrors the system and effect-type platform-set visitors used by console-variable refresh. */
internal static class PlatformSetCatalogCompiler
{
    internal static JArray Compile(SourceAsset asset, SourceObject system, JArray runtimes, AssetLibrary? library)
    {
        JArray result = new();
        AddSettings(system.Properties["SystemScalabilityOverrides"]?["Overrides"]);
        foreach (int reference in (system.Properties["ExposedParameters"]?["DataInterfaces"] as JArray ?? new()).Values<int>())
            AddInterface(asset, reference);
        foreach (JObject handle in (system.Properties["EmitterHandles"] as JArray ?? new()).OfType<JObject>()) {
            if (!EmitterHandleSource.IsStandard(handle)) continue;
            int reference = handle["VersionedInstance"]?.Value<int?>("Emitter") ?? 0;
            if (reference == 0) continue;
            var emitter = Resolve(asset, reference);
            JObject? version = EmitterVersionSource.Find(emitter.Object, handle["VersionedInstance"]?.Value<string>("Version"));
            if (version is null) continue;
            result.Add(NiagaraScalabilitySource.Platform(version["Platforms"]));
            AddSettings(version["ScalabilityOverrides"]?["Overrides"]);
            foreach (int renderer in (version["RendererProperties"] as JArray ?? new()).Values<int>()) {
                if (renderer == 0) continue;
                var source = Resolve(emitter.Asset, renderer);
                result.Add(NiagaraScalabilitySource.Platform(source.Object.Properties["Platforms"]));
            }
        }
        foreach (JArray pair in runtimes.OfType<JArray>()) {
            if (pair.Count != 2 || pair[0] is not JObject key || pair[1] is not JObject runtime)
                throw new ConversionException("Invalid platform-set script runtime entry");
            if (ScriptDataInterfaceBindings.Script(asset, system, key, includeDisabled: true) is null) continue;
            foreach (JObject binding in (runtime["ResolvedDataInterfaces"] as JArray ?? new()).OfType<JObject>())
                AddInterface(ScriptDataInterfaceBindings.ObjectAsset(asset, binding, library), binding.Value<int>("ResolvedDataInterface"));
        }
        int effectReference = system.Properties.Value<int?>("EffectType") ?? 0;
        if (effectReference != 0) {
            var effect = Resolve(asset, effectReference);
            AddSettings(effect.Object.Properties["SystemScalabilitySettings"]?["Settings"]);
            AddSettings(effect.Object.Properties["EmitterScalabilitySettings"]?["Settings"]);
        }
        return result;

        void AddSettings(JToken? settings)
        {
            foreach (JObject setting in (settings as JArray ?? new()).OfType<JObject>())
                result.Add(NiagaraScalabilitySource.Platform(setting["Platforms"]));
        }
        void AddInterface(SourceAsset owner, int reference)
        {
            if (reference == 0) return;
            string path = owner.ObjectPath(reference);
            if (owner.ReferencedClass(reference) != PlatformSetDataInterfaceCompiler.ClassName
                    && path != PlatformSetDataInterfaceCompiler.DefaultObject) return;
            JObject descriptor = PlatformSetDataInterfaceCompiler.Compile(reference > 0 ? owner.Object(reference) : null,
                    library, reference < 0 ? path : null);
            result.Add(descriptor["platforms"]!.DeepClone());
        }
        (SourceAsset Asset, SourceObject Object) Resolve(SourceAsset owner, int reference)
        {
            if (reference > 0) return (owner, owner.Object(reference));
            string path = owner.ObjectPath(reference);
            SourceAsset external = library?.Load(path)
                ?? throw new ConversionException($"Platform-set source requires its library: {path}");
            return (external, external.ResolveObject(path, _ => true));
        }
    }
}
