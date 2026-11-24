using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class VectorFieldDataInterfaceCompiler
{
    internal static void CompileCpu(JObject binding, SourceAsset asset, SourceObject? data,
        AssetLibrary? library, string? sourceReference = null)
    {
        ValidateCpu(binding.Value<string>("name") ?? "", binding.Value<int>("inputs"), binding.Value<int>("outputs"));
        ConfigureCpu(binding, asset, data, library, sourceReference);
    }

    internal static JObject CompileDefaults(SourceAsset asset, SourceObject? data, AssetLibrary? library, string? sourceReference)
    {
        JObject result = new();
        ConfigureCpu(result, asset, data, library, sourceReference);
        return result;
    }

    private static void ConfigureCpu(JObject binding, SourceAsset asset, SourceObject? data,
        AssetLibrary? library, string? sourceReference)
    {
        (SourceAsset owner, SourceObject? resolved) = Resolve(asset, data, library, sourceReference);
        data = resolved;
        JObject properties = data?.Properties ?? new JObject();
        binding["interface_type"] = "vector_field";
        binding["tile"] = new JArray(properties.Value<bool?>("bTileX") ?? false,
            properties.Value<bool?>("bTileY") ?? false, properties.Value<bool?>("bTileZ") ?? false);
        binding["interface_data"] = new JObject {
            ["cpu_access"] = false, ["dimensions"] = new JArray(1, 1, 1),
            ["min_bounds"] = new JArray(-1d, -1d, -1d), ["max_bounds"] = new JArray(1d, 1d, 1d)
        };
        int field = properties.Value<int?>("Field") ?? 0;
        if (field != 0) binding["field_source"] = owner.ObjectPath(field);
        else binding["field_kind"] = "none";
    }

    internal static void CompileGpu(JObject binding, SourceAsset asset, SourceObject? data,
        AssetLibrary? library, string? sourceReference)
    {
        (SourceAsset owner, SourceObject? resolved) = Resolve(asset, data, library, sourceReference);
        JObject properties = resolved?.Properties ?? new JObject();
        binding["properties"] = properties.DeepClone();
        binding.Remove("source_reference");
        int field = properties.Value<int?>("Field") ?? 0;
        if (field != 0) binding["field_source"] = owner.ObjectPath(field);
    }

    private static (SourceAsset Asset, SourceObject? Data) Resolve(SourceAsset asset, SourceObject? data,
        AssetLibrary? library, string? sourceReference)
    {
        if (data is not null || sourceReference is null
                || sourceReference.EndsWith(".Default__NiagaraDataInterfaceVectorField", StringComparison.Ordinal)) return (asset, data);
        if (library is null) throw new ConversionException($"External vector field interface requires its source library: {sourceReference}");
        SourceAsset owner = library.Load(sourceReference);
        return (owner, owner.ResolveObject(sourceReference, value => value.ClassName == "NiagaraDataInterfaceVectorField"));
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        bool valid = name switch {
            "SampleField" or "LoadField" => inputs == 3 && outputs == 3,
            "FieldTilingAxes" or "FieldDimensions" => inputs == 0 && outputs == 3,
            "FieldBounds" => inputs == 0 && outputs == 6,
            _ => false
        };
        if (!valid) throw new ConversionException($"Invalid vector field CPU signature {name}: {inputs} inputs, {outputs} outputs");
    }
}
