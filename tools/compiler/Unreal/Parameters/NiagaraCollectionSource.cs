using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Resolves the saved collection list and system instance overrides before numeric store binding. */
internal sealed class NiagaraCollectionSource(SourceAsset asset, SourceObject system, JObject owner, AssetLibrary? library)
{
    internal sealed record Binding(string Path, NumericParameterStoreSource.Value? Default,
        NumericParameterStoreSource.Value Value, bool SystemOverride, MaterialParameterCollectionSource.Definition? MaterialSource,
        string? MaterialParameter);
    internal sealed record ObjectBinding(SourceAsset Asset, int Reference, string InstanceId, string Collection, string Scope,
        string Initialization, SourceAsset? BaseAsset, int? BaseReference, JObject? RuntimeOuter);
    private sealed record ObjectValue(SourceAsset Asset, int Reference, int Slot);
    private sealed record ObjectSource(SourceAsset Asset, SourceObject Object)
    {
        internal string Path => Asset.ObjectPath(Object.Index);
        internal JObject Properties => Object.Properties;
    }
    private readonly List<ObjectSource> collections = new();
    private readonly Dictionary<string, MaterialParameterCollectionSource.Definition> materialSources = new(SourceNameOrder.Instance);
    private bool loaded;

    private void LoadCollections()
    {
        if (loaded) return;
        foreach (int reference in (owner["CachedParameterCollectionReferences"] as JArray ?? new JArray()).Values<int>()) {
            if (reference == 0) continue;
            ObjectSource collection = Resolve(asset, reference, "NiagaraParameterCollection");
            if (!collections.Any(value => SourceNameOrder.Equal(value.Path, collection.Path))) collections.Add(collection);
        }
        loaded = true;
    }

    internal ObjectBinding? Object(JObject expected, bool dataInterface)
    {
        string? name = expected.Value<string>("name");
        if (name is null || !name.StartsWith("NPC.", StringComparison.OrdinalIgnoreCase)) return null;
        LoadCollections();
        ObjectBinding? result = null;
        foreach (ObjectSource collection in collections) {
            if (!(collection.Properties["Parameters"] as JArray ?? new JArray()).OfType<JObject>()
                .Any(variable => NiagaraVariableSource.Same(asset, expected, collection.Asset, variable))) continue;
            ObjectSource defaults = Resolve(collection.Asset, collection.Properties.Value<int>("DefaultInstance"), "NiagaraParameterCollectionInstance");
            ObjectValue? baseValue = ReadObject(defaults, expected, dataInterface);
            ObjectValue? value = baseValue;
            ObjectSource? selectedInstance = null;
            bool inherited = false;
            foreach (int reference in (system.Properties["ParameterCollectionOverrides"] as JArray ?? new JArray()).Values<int>()) {
                if (reference == 0) continue;
                ObjectSource instance = Resolve(asset, reference, "NiagaraParameterCollectionInstance");
                if (!SourceNameOrder.Equal(instance.Asset.ObjectPath(instance.Properties.Value<int?>("Collection") ?? 0), collection.Path)) continue;
                selectedInstance = instance;
                bool overrides = SourceNameOrder.Equal(instance.Path, defaults.Path)
                    || (instance.Properties["OverridenParameters"] as JArray ?? new JArray()).OfType<JObject>()
                        .Any(variable => NiagaraVariableSource.Same(asset, expected, instance.Asset, variable));
                ObjectValue? stored = ReadObject(instance, expected, dataInterface);
                if (overrides && stored is not null) value = stored;
                else inherited = true;
                break;
            }
            if (value is null) continue;
            string source = value.Asset.ObjectPath(value.Reference);
            if (dataInterface && value.Reference < 0 && !source.StartsWith("/Script/", StringComparison.Ordinal)) {
                SourceAsset target = library?.Load(source)
                    ?? throw new ConversionException($"External collection data interface requires its source library: {source}");
                SourceObject targetObject = target.ResolveObject(source, _ => true);
                value = new(target, targetObject.Index, value.Slot);
            }
            string scope = selectedInstance is null ? "world" : "system";
            string id = !dataInterface ? source : selectedInstance is null
                ? collection.Path + "@world_di:" + source
                : inherited ? selectedInstance.Path + "@inherited_di:" + name + ":" + NiagaraTypeSource.Read(asset, (JObject)expected["type"]!).Path
                : source;
            bool duplicated = dataInterface && (selectedInstance is null || inherited);
            JObject? runtimeOuter = duplicated ? new JObject {
                ["kind"] = "collection_instance", ["collection"] = collection.Path, ["scope"] = scope,
                ["source_path"] = selectedInstance?.Path
            } : null;
            result = new(value.Asset, value.Reference, id, collection.Path, scope,
                duplicated ? "duplicate" : "loaded", baseValue?.Asset, baseValue?.Reference, runtimeOuter);
        }
        return result;
    }

    private ObjectValue? ReadObject(ObjectSource instance, JObject expected, bool dataInterface)
    {
        JObject store = instance.Properties["ParameterStorage"] as JObject ?? new();
        JObject? variable = (store["SortedParameterOffsets"] as JArray ?? new JArray()).OfType<JObject>()
            .FirstOrDefault(value => NiagaraVariableSource.Same(asset, expected, instance.Asset, value));
        if (variable is null) return null;
        int slot = variable.Value<int?>("offset") ?? -1;
        JArray objects = store[dataInterface ? "DataInterfaces" : "UObjects"] as JArray ?? new JArray();
        if (slot < 0 || slot >= objects.Count) throw new ConversionException($"Collection object offset is outside its source store: {expected.Value<string>("name")}");
        return new(instance.Asset, objects[slot].Value<int>(), slot);
    }

    private Binding? Find(string name, string type, int byteCount)
    {
        if (!name.StartsWith("NPC.", StringComparison.OrdinalIgnoreCase)) return null;
        LoadCollections();
        Binding? result = null;
        foreach (ObjectSource collection in collections) {
            JObject properties = collection.Properties;
            if (!(properties["Parameters"] as JArray ?? new JArray()).OfType<JObject>()
                .Any(parameter => NumericParameterStoreSource.Matches(collection.Asset, parameter, name, type))) continue;
            ObjectSource defaults = Resolve(collection.Asset, properties.Value<int>("DefaultInstance"), "NiagaraParameterCollectionInstance");
            NumericParameterStoreSource.Value? value = NumericParameterStoreSource.ReadBytes(defaults.Asset,
                defaults.Properties["ParameterStorage"] as JObject ?? new(), name, type, byteCount);
            NumericParameterStoreSource.Value? defaultValue = value;
            MaterialParameterCollectionSource.Definition? material = null;
            string? materialParameter = null;
            int materialReference = properties.Value<int?>("SourceMaterialCollection") ?? 0;
            if (materialReference != 0) {
                if (!materialSources.TryGetValue(collection.Path, out material)) {
                    material = MaterialParameterCollectionSource.Read(collection.Asset, materialReference, library);
                    materialSources.Add(collection.Path, material);
                }
                string prefix = "NPC." + (properties.Value<string>("Namespace") ?? collection.Object.Name) + ".";
                IEnumerable<MaterialParameterCollectionSource.Parameter> driven = type switch {
                    "/Script/Niagara.NiagaraFloat" => material.Scalars,
                    "/Script/CoreUObject.LinearColor" => material.Vectors,
                    _ => Array.Empty<MaterialParameterCollectionSource.Parameter>()
                };
                materialParameter = driven.FirstOrDefault(parameter => SourceNameOrder.Equal(prefix + parameter.Name, name))?.Name;
            }
            bool systemOverride = false;
            foreach (int reference in (system.Properties["ParameterCollectionOverrides"] as JArray ?? new JArray()).Values<int>()) {
                if (reference == 0) continue;
                ObjectSource instance = Resolve(asset, reference, "NiagaraParameterCollectionInstance");
                string parent = instance.Asset.ObjectPath(instance.Properties.Value<int?>("Collection") ?? 0);
                if (!SourceNameOrder.Equal(parent, collection.Path)) continue;
                systemOverride = true;
                bool overrides = SourceNameOrder.Equal(instance.Path, defaults.Path)
                    || (instance.Properties["OverridenParameters"] as JArray ?? new JArray()).OfType<JObject>()
                        .Any(parameter => NumericParameterStoreSource.Matches(instance.Asset, parameter, name, type));
                if (overrides && NumericParameterStoreSource.ReadBytes(instance.Asset,
                    instance.Properties["ParameterStorage"] as JObject ?? new(), name, type, byteCount) is { } replacement) value = replacement;
                break;
            }
            if (value is not null) result = new(collection.Path, defaultValue, value, systemOverride, material, materialParameter);
        }
        return result;
    }

    internal void Apply(JObject target, string name, string type, int byteCount)
    {
        if (Find(name, type, byteCount) is not { } binding) return;
        target["collection"] = binding.Path;
        target["collection_default"] = Convert.ToBase64String(binding.Value.Data);
        target["collection_system_override"] = binding.SystemOverride;
        if (binding.MaterialSource is not null) {
            target["collection_material_source"] = binding.MaterialSource.Json();
            target["collection_material_parameter"] = binding.MaterialParameter;
        }
        if (binding.Value.Position is not null) target["collection_position"] = new JArray(binding.Value.Position);
        if (binding.Default is not null) {
            target["collection_base_default"] = Convert.ToBase64String(binding.Default.Data);
            if (binding.Default.Position is not null) target["collection_base_position"] = new JArray(binding.Default.Position);
        }
    }

    private ObjectSource Resolve(SourceAsset owner, int reference, string type)
    {
        string path = owner.ObjectPath(reference);
        SourceAsset target = reference > 0 ? owner : library?.Load(path)
            ?? throw new ConversionException($"A source library is required for parameter collection object {path}");
        SourceObject value = reference > 0 ? owner.Object(reference) : target.ResolveObject(path, item => item.ClassName == type);
        if (value.ClassName != type) throw new ConversionException($"Expected {type} for parameter collection object {path}");
        return new(target, value);
    }
}
