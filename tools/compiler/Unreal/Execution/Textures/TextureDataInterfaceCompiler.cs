using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class TextureDataInterfaceCompiler
{
    internal const string ArrayClass = "NiagaraDataInterface2DArrayTexture";
    internal const string CubeClass = "NiagaraDataInterfaceCubeTexture";
    internal const string VolumeClass = "NiagaraDataInterfaceVolumeTexture";

    internal static JObject CompileArray(SourceAsset owner, int reference, AssetLibrary? library,
        SourceAsset? systemAsset = null, SourceObject? system = null)
        => CompileDimensional(owner, reference, library, systemAsset, system, ArrayClass, "array");

    internal static JObject CompileCube(SourceAsset owner, int reference, AssetLibrary? library,
        SourceAsset? systemAsset = null, SourceObject? system = null)
        => CompileDimensional(owner, reference, library, systemAsset, system, CubeClass, "cube");

    internal static JObject CompileVolume(SourceAsset owner, int reference, AssetLibrary? library,
        SourceAsset? systemAsset = null, SourceObject? system = null)
        => CompileDimensional(owner, reference, library, systemAsset, system, VolumeClass, "volume");

    private static JObject CompileDimensional(SourceAsset owner, int reference, AssetLibrary? library,
        SourceAsset? systemAsset, SourceObject? system, string interfaceClass, string dimension)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference,
            interfaceClass, library);
        JObject result = Definition(source?.Asset ?? owner, source?.Object);
        result[dimension] = true;
        string parameter = result.Value<string>("user_parameter")!;
        if (parameter.Length != 0 && systemAsset is not null && system is not null
            && UserObjectReference(systemAsset, system, parameter) is int value) {
            string type = systemAsset.ReferencedClass(value);
            bool compatible = dimension switch {
                "cube" => type is "TextureCube" or "TextureRenderTargetCube",
                "volume" => type is "VolumeTexture" or "TextureRenderTargetVolume",
                _ => type is "Texture2DArray" or "TextureRenderTarget2DArray"
            };
            result["source_user_texture"] = compatible ? systemAsset.ObjectPath(value) : JValue.CreateNull();
        }
        return result;
    }

    internal static JObject Compile(SourceAsset asset, SourceObject? data, SourceObject system,
        AssetLibrary? library = null, string? sourceReference = null)
    {
        SourceAsset owner = asset;
        if (data is null && sourceReference is not null && !sourceReference.EndsWith(".Default__NiagaraDataInterfaceTexture", StringComparison.Ordinal)) {
            if (library is null) throw new ConversionException($"External texture interface requires its source library: {sourceReference}");
            owner = library.Load(sourceReference);
            data = owner.ResolveObject(sourceReference, value => value.ClassName == "NiagaraDataInterfaceTexture");
        }
        JObject result = Definition(owner, data);
        string userParameter = result.Value<string>("user_parameter")!;
        if (userParameter.Length == 0) return result;
        int? reference = UserObjectReference(asset, system, userParameter);
        if (reference is int value && value != 0 && TextureObjectTypes.IsKnownNonTexture(asset.ReferencedClass(value))) reference = 0;
        if (reference.HasValue) result["source_user_texture"] = reference == 0 ? JValue.CreateNull() : asset.ObjectPath(reference.Value);
        return result;
    }

    internal static JObject CompileDefaults(SourceAsset asset, int reference, AssetLibrary? library)
    {
        var source = DataInterfaceInstanceSources.ResolveSource(asset, reference, "NiagaraDataInterfaceTexture", library ?? asset.Library);
        return Definition(source.Asset, source.Object);
    }

    private static JObject Definition(SourceAsset owner, SourceObject? data)
    {
        JObject properties = data?.Properties ?? new JObject();
        JObject? parameter = properties["TextureUserParameter"]?["Parameter"] as JObject;
        string userParameter = parameter?.Value<string>("name") ?? "";
        if (userParameter == "None") userParameter = "";
        int texture = properties.Value<int?>("Texture") ?? 0;
        return new JObject { ["source_texture"] = texture == 0 ? null : owner.ObjectPath(texture), ["user_parameter"] = userParameter };
    }

    internal static int? UserObjectReference(SourceAsset asset, SourceObject system, string name)
    {
        JObject store = system.Properties["ExposedParameters"] as JObject ?? new JObject();
        JArray offsets = store["SortedParameterOffsets"] as JArray ?? new JArray();
        JObject[] variables = offsets.OfType<JObject>().OrderBy(variable => variable.Value<string>("name")
                ?? throw new ConversionException("Direct UObject parameter has no name"), SourceNameOrder.Instance)
            .ThenBy(variable => ScriptDataInterfaceBindings.TypeName(asset, variable), SourceNameOrder.Instance).ToArray();
        JObject? selected = null;
        int start = 0, count = variables.Length;
        // Direct UObject bindings deliberately use FindParameterVariable(..., IgnoreType=true).
        while (count != 0) {
            int remainder = count % 2;
            count /= 2;
            int index = start + count;
            int comparison = SourceNameOrder.Instance.Compare(variables[index].Value<string>("name"), name);
            if (comparison < 0) { start = index + 1; count += remainder - 1; }
            else if (comparison == 0) { selected = variables[index]; break; }
        }
        if (selected is null || selected["type"] is not JObject type) return null;
        var loaded = NiagaraTypeSource.Read(asset, type);
        if (loaded.UnderlyingType != 1) return null;
        string path = loaded.Path;
        if (path.Contains(".NiagaraDataInterface", StringComparison.Ordinal)) return null;
        if (!path.StartsWith("/Script/Engine.", StringComparison.Ordinal) && !path.StartsWith("/Script/CoreUObject.", StringComparison.Ordinal)
                && !path.StartsWith("/Script/Niagara.", StringComparison.Ordinal))
            throw new ConversionException($"Direct UObject binding requires its source class ancestry: {name} ({path})");
        JArray objects = store["UObjects"] as JArray ?? new JArray();
        int offset = selected.Value<int?>("offset") ?? throw new ConversionException($"Texture user parameter has no offset: {name}");
        if (offset < 0 || offset >= objects.Count) throw new ConversionException($"Texture user parameter offset is outside its object store: {name}");
        return objects[offset].Value<int>();
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        bool valid = name switch {
            "GetTextureDimensions" => inputs == 1 && outputs == 2,
            "GetNumMipLevels" => inputs == 0 && outputs == 1,
            "SampleTexture2D" => inputs == 3 && outputs == 4,
            "SamplePseudoVolumeTexture" => inputs == 12 && outputs == 4,
            _ => false };
        if (!valid) throw new ConversionException($"Invalid Niagara texture CPU function {name}: {inputs} inputs, {outputs} outputs");
    }
}
