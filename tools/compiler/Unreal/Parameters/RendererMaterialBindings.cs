using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

public sealed record CompiledMaterialBinding(string Name, string Variable, string Type, string? DataInterface, string? Child,
                                             string? ObjectDefault = null, bool StaticExposure = false, string? InterfaceId = null,
                                             JObject? InterfaceDefinition = null, CompiledTextureCollection? Collection = null,
                                             IReadOnlyList<CompiledTextureObjectInput>? ObjectInputs = null,
                                             string? SourceType = null, int TypeFlags = 0);
public sealed record CompiledTextureCollection(string Path, string Name, string SourceType, bool SystemOverride,
                                               bool BasePresent, string? BaseDefault);
public sealed record CompiledTextureObjectInput(string Variable, bool DefaultPresent, string? DefaultTexture);
public sealed record CompiledMaterialParameter(CompiledMaterialBinding? Binding, string Kind, float[] Default)
{
    public string Name { get; init; } = Binding?.Name ?? "";
    public string Association { get; init; } = "GlobalParameter";
    public int ParameterIndex { get; init; } = -1;
    public string? DefaultProfile { get; init; }
    public bool RendererConstant { get; init; }
}
public sealed record CompiledMaterialTexture(CompiledMaterialBinding Binding, string Sampler, string BaseDefault);

/** MID bindings are distinct from per-particle Dynamic Material Parameters. */
public sealed class RendererMaterialBindings
{
    private readonly List<CompiledMaterialBinding> bindings = new();
    public static RendererMaterialBindings Empty { get; } = new();
    private RendererMaterialBindings() { }
    internal string Signature => JsonConvert.SerializeObject(bindings);

    internal CompiledMaterialBinding? Find(string name, string kind)
        => bindings.LastOrDefault(binding => SourceNameOrder.Equal(binding.Name, name)
            && (kind switch {
                "scalar" => binding.Type == "float", "double_vector" => binding.Type == "position",
                "texture" => binding.Type == "texture",
                _ => binding.Type is "float2" or "float3" or "float4" or "color" or "position"
            }));

    internal static RendererMaterialBindings Read(SourceAsset asset, JObject renderer, string sourceEmitter, SourceObject? system = null,
                                                  string? emitterHandle = null, AssetLibrary? library = null, JObject? collectionOwner = null,
                                                  JObject? statefulEmitter = null)
    {
        RendererMaterialBindings result = new();
        JToken? source = renderer["MaterialParameters"]?["AttributeBindings"];
        if (source is null || source.Type == JTokenType.Null) return result;
        if (source is not JArray array) throw new ConversionException("Renderer material attribute bindings are not an array");
        foreach (JObject item in array)
        {
            JObject variable = item["ResolvedNiagaraVariable"] as JObject ?? item["NiagaraVariable"] as JObject
                ?? throw new ConversionException("Material attribute binding has no Niagara variable");
            JObject type = variable["type"] as JObject ?? throw new ConversionException("Material attribute binding has no type");
            string variableName = variable.Value<string>("name") ?? "None";
            if (variableName.Length > 7 && variableName[7] == '.' && SourceNameOrder.Equal(variableName[..7], "Emitter"))
                variableName = sourceEmitter + variableName[7..];
            JObject resolvedVariable = (JObject)variable.DeepClone();
            resolvedVariable["name"] = variableName;
            var declaredType = NiagaraTypeSource.Read(asset, type);
            string path = declaredType.Path;
            bool dataInterface = declaredType.UnderlyingType == 1 && path.Contains(".NiagaraDataInterface", StringComparison.Ordinal);
            JObject? child = item["NiagaraChildVariable"] as JObject;
            JObject? valueType = dataInterface ? child?["type"] as JObject : type;
            var valueDefinition = valueType is null ? null : NiagaraTypeSource.Read(asset, valueType);
            string valuePath = valueDefinition?.Path ?? "None";
            bool texture = valueDefinition?.UnderlyingType == 1 && (valuePath is "/Script/Engine.Texture" or "/Script/Engine.Texture2D"
                or "/Script/Engine.TextureRenderTarget2D" or "/Script/Engine.TextureLightProfile"
                || !dataInterface && (valuePath is "/Script/CoreUObject.Object" or "/Script/Engine.TextureRenderTarget"));
            if (texture) {
                MaterialInterfaceSources.TextureExposure exposure = dataInterface && system is not null
                    ? MaterialInterfaceSources.Texture(asset, system, resolvedVariable, emitterHandle, library) : default;
                var objectValue = !dataInterface && system is not null
                    ? ObjectDefault(asset, system, resolvedVariable, collectionOwner, library) : default;
                result.bindings.Add(new CompiledMaterialBinding(item.Value<string>("MaterialParameterName") ?? "None",
                    variableName, "texture", dataInterface ? path : null, dataInterface ? child?.Value<string>("name") ?? "None" : null,
                    dataInterface ? exposure.Source : objectValue.Source, exposure.Static,
                    exposure.InterfaceId, exposure.Definition, objectValue.Collection,
                    !dataInterface && system is not null ? ObjectInputs(asset, system, resolvedVariable, statefulEmitter, emitterHandle) : null,
                    path, declaredType.Flags));
                continue;
            }
            string? numeric = Numeric(asset, dataInterface ? child?["type"] as JObject : type);
            if (numeric is null) continue;
            result.bindings.Add(new CompiledMaterialBinding(item.Value<string>("MaterialParameterName") ?? "None",
                variableName, numeric, dataInterface ? path : null, dataInterface ? child?.Value<string>("name") ?? "None" : null));
        }
        return result;
    }

    private static (string? Source, CompiledTextureCollection? Collection) ObjectDefault(SourceAsset asset, SourceObject system,
        JObject expected, JObject? collectionOwner, AssetLibrary? library)
    {
        string name = expected.Value<string>("name") ?? throw new ConversionException("Material object binding has no name");
        JObject store = system.Properties["ExposedParameters"] as JObject ?? new();
        SourceAsset objectAsset = asset;
        int? reference = ObjectReference(asset, store, expected);
        CompiledTextureCollection? collectionBinding = null;
        if (collectionOwner is not null && new NiagaraCollectionSource(asset, system, collectionOwner, library).Object(expected, false) is { } collection) {
            objectAsset = collection.Asset; reference = collection.Reference;
            collectionBinding = new CompiledTextureCollection(collection.Collection, name,
                NiagaraTypeSource.Read(asset, (JObject)expected["type"]!).Path, collection.Scope == "system",
                collection.BaseReference.HasValue, collection.BaseReference is int baseReference
                    ? TextureObject(collection.BaseAsset!, baseReference) : null);
        }
        return (reference.HasValue ? TextureObject(objectAsset, reference.Value) : null, collectionBinding);
    }

    private static IReadOnlyList<CompiledTextureObjectInput> ObjectInputs(SourceAsset asset, SourceObject system,
        JObject expected, JObject? emitter, string? emitterHandle)
    {
        List<CompiledTextureObjectInput> result = new();
        if (emitter is null) return result;
        foreach (JObject binding in RendererExternalObjectBindings.Read(asset, system, emitter, emitterHandle, expected)) {
            JObject variable = (JObject)binding["Variable"]!;
            string name = binding.Value<string>("ExternalName")
                ?? throw new ConversionException("Renderer object mapping has no external variable name");
            JObject source = (JObject)variable.DeepClone();
            source["name"] = name;
            int? reference = ObjectReference(asset, system.Properties["ExposedParameters"] as JObject ?? new(), source);
            result.Add(new CompiledTextureObjectInput(name, reference.HasValue,
                reference.HasValue ? TextureObject(asset, reference.Value) : null));
        }
        return result;
    }

    private static int? ObjectReference(SourceAsset asset, JObject store, JObject expected)
    {
        string name = expected.Value<string>("name") ?? throw new ConversionException("Object parameter has no name");
        JArray objects = store["UObjects"] as JArray ?? new();
        foreach (JObject variable in (store["SortedParameterOffsets"] as JArray ?? new()).OfType<JObject>()) {
            if (!NiagaraVariableSource.Same(asset, expected, asset, variable)) continue;
            int offset = variable.Value<int?>("offset") ?? throw new ConversionException($"Object parameter has no offset: {name}");
            if (offset < 0 || offset >= objects.Count) throw new ConversionException($"Object parameter offset is outside its store: {name}");
            return objects[offset].Value<int>();
        }
        return null;
    }

    private static string? TextureObject(SourceAsset asset, int reference)
    {
        if (reference == 0) return null;
        string type = asset.ReferencedClass(reference);
        if (type is "Texture2D" or "TextureLightProfile" or "TextureRenderTarget2D") return asset.ObjectPath(reference);
        if (TextureObjectTypes.IsKnownNonTexture(type)) return null;
        throw new ConversionException($"Material object needs its matching texture or source ancestry metadata: {asset.ObjectPath(reference)} ({type})");
    }

    private static string? Numeric(SourceAsset asset, JObject? type)
    {
        if (type is null) return null;
        var loaded = NiagaraTypeSource.Read(asset, type);
        if (loaded.UnderlyingType != 2 || loaded.Flags != 0) return null;
        return loaded.Path switch {
            "/Script/Niagara.NiagaraFloat" => "float", "/Script/CoreUObject.Vector2f" => "float2",
            "/Script/CoreUObject.Vector3f" => "float3", "/Script/CoreUObject.Vector4f" => "float4",
            "/Script/CoreUObject.LinearColor" => "color", "/Script/Niagara.NiagaraPosition" => "position",
            _ => null
        };
    }
}
