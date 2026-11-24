using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Material-only interface references are resolved from the same script runtime bindings as VM calls. */
internal static class MaterialInterfaceSources
{
    internal readonly record struct TextureExposure(bool Static, string? Source, string? InterfaceId = null, JObject? Definition = null);

    internal static TextureExposure Texture(SourceAsset asset, SourceObject system, JObject expected, string? emitterHandle, AssetLibrary? library = null)
    {
        string variable = expected.Value<string>("name") ?? throw new ConversionException("Material interface binding has no name");
        Dictionary<string, (SourceAsset Asset, int Reference)> references = new(StringComparer.Ordinal);
        JArray runtimes = Execution.ScriptRuntimeSource.Read(asset, system);
        foreach (JArray pair in Execution.ScriptDataInterfaceBindings.Resolve(asset, system, runtimes, library))
        {
            if (pair.Count != 2 || pair[0] is not JObject key || pair[1] is not JObject runtime)
                throw new ConversionException("Invalid script runtime binding map entry");
            if (!Guid.TryParse(key.Value<string>("EmitterHandleId"), out Guid owner))
                throw new ConversionException("Script runtime binding has no emitter identity");
            if (owner != Guid.Empty && (emitterHandle is null || !Guid.TryParse(emitterHandle, out Guid target) || owner != target)) continue;
            if (Execution.ScriptDataInterfaceBindings.Script(asset, system, key) is null) continue;
            foreach (JObject binding in runtime["ResolvedDataInterfaces"] as JArray ?? new JArray())
                if (binding["ResolvedVariable"] is JObject resolved && Execution.ScriptDataInterfaceBindings.Same(asset, expected, resolved)) {
                    int sourceReference = binding.Value<int>("ResolvedDataInterface");
                    SourceAsset objectAsset = Execution.ScriptDataInterfaceBindings.ObjectAsset(asset, binding, library);
                    string id = binding.Value<string>("InstanceId") ?? Execution.ScriptDataInterfaceBindings.Identity(asset, sourceReference);
                    if (references.TryGetValue(id, out var previous) && (previous.Asset != objectAsset || previous.Reference != sourceReference))
                        throw new ConversionException($"Material interface identity has conflicting source objects: {variable}");
                    references[id] = (objectAsset, sourceReference);
                }
        }
        if (references.Count == 0 && system.Properties["ExposedParameters"] is JObject parameters)
        {
            JArray objects = parameters["DataInterfaces"] as JArray ?? new JArray();
            foreach (JObject entry in parameters["SortedParameterOffsets"] as JArray ?? new JArray())
            {
                if (!Execution.ScriptDataInterfaceBindings.Same(asset, expected, entry)) continue;
                int offset = entry.Value<int?>("offset") ?? throw new ConversionException($"Interface parameter has no offset: {variable}");
                if (offset < 0 || offset >= objects.Count) throw new ConversionException($"Interface parameter offset is outside its store: {variable}");
                references[asset.Name + "@user_di:" + offset] = (asset, objects[offset].Value<int>());
            }
        }
        if (references.Count == 0) return new TextureExposure(true, null);
        if (references.Count != 1) throw new ConversionException($"Material interface has multiple resolved objects: {variable}");
        var selected = references.Single();
        int reference = selected.Value.Reference;
        SourceAsset selectedAsset = selected.Value.Asset;
        if (reference == 0) return new TextureExposure(true, null);
        if (reference < 0) {
            string path = selectedAsset.ObjectPath(reference);
            if (selectedAsset.ReferencedClass(reference) == Execution.SceneCapture2DInterfaceCompiler.ClassName
                    || path.EndsWith(".Default__" + Execution.SceneCapture2DInterfaceCompiler.ClassName, StringComparison.Ordinal))
                return new TextureExposure(false, null, selected.Key,
                        Execution.SceneCapture2DInterfaceCompiler.Definition(selectedAsset, reference, library));
            if (path.Contains(".Default__NiagaraDataInterface", StringComparison.Ordinal) && path.EndsWith("Curve", StringComparison.Ordinal))
                return new TextureExposure(true, null);
            JObject? definition = RenderTargetDefinition(selectedAsset, reference, asset, system, library);
            return new TextureExposure(false, null, selected.Key, definition);
        }
        SourceObject data = selectedAsset.Object(reference);
        if (data.ClassName == Execution.SceneCapture2DInterfaceCompiler.ClassName)
            return new TextureExposure(false, null, selected.Key,
                    Execution.SceneCapture2DInterfaceCompiler.Definition(selectedAsset, data.Index, library));
        JObject? targetDefinition = RenderTargetDefinition(selectedAsset, data.Index, asset, system, library);
        if (targetDefinition is not null)
            return new TextureExposure(false, null, selected.Key, targetDefinition);
        if (data.ClassName is not ("NiagaraDataInterfaceCurve" or "NiagaraDataInterfaceColorCurve" or "NiagaraDataInterfaceVector2DCurve"
                or "NiagaraDataInterfaceVectorCurve" or "NiagaraDataInterfaceVector4Curve"))
            return new TextureExposure(false, null, selected.Key);
        JObject properties = data.Properties;
        int texture = properties.Value<int?>("ExposedTexture") ?? 0;
        return new TextureExposure(true, properties.Value<bool?>("bExposeCurve") == true && texture != 0 ? selectedAsset.ObjectPath(texture) : null);
    }

    private static JObject? RenderTargetDefinition(SourceAsset owner, int reference, SourceAsset systemAsset,
        SourceObject system, AssetLibrary? library)
    {
        string type = reference > 0 ? owner.Object(reference).ClassName : owner.ReferencedClass(reference);
        return type switch {
            "NiagaraDataInterfaceRenderTarget2D" => Execution.RenderTarget2DDataInterfaceCompiler.Compile(
                systemAsset, reference > 0 && owner == systemAsset ? owner.Object(reference) : null, system, library,
                reference > 0 && owner == systemAsset ? null : owner.ObjectPath(reference)),
            Execution.RenderTarget2DDataInterfaceCompiler.ArrayClass => Execution.RenderTarget2DDataInterfaceCompiler.CompileArray(
                owner, reference, library, systemAsset, system),
            Execution.RenderTarget2DDataInterfaceCompiler.CubeClass => Execution.RenderTarget2DDataInterfaceCompiler.CompileCube(
                owner, reference, library, systemAsset, system),
            Execution.RenderTarget2DDataInterfaceCompiler.VolumeClass => Execution.RenderTarget2DDataInterfaceCompiler.CompileVolume(
                owner, reference, library, systemAsset, system),
            _ => null
        };
    }
}
