using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Filtered packages retain native GPU shader code, while the portable compiler consumes the original Niagara translation. */
internal sealed record GpuScriptTranslation(SourceAsset Asset, JObject Executable, string Hlsl, JArray Defines)
{
    internal static GpuScriptTranslation Read(SourceAsset asset, SourceObject script, JObject executable, AssetLibrary? library)
    {
        JObject? id = script.Properties["CachedScriptVMId"] as JObject;
        string hlsl = executable.Value<string>("LastHlslTranslationGPU") ?? "";
        if (!string.IsNullOrWhiteSpace(hlsl)) return new(asset, executable, hlsl, DefinesOf(id));

        string path = asset.ObjectPath(script.Index);
        SourceAsset? source = (library ?? asset.Library)?.NiagaraGpuSource?.TryLoad(asset.Name);
        SourceObject? sourceScript = source?.Objects.SingleOrDefault(candidate => candidate.ClassName == script.ClassName
            && SourceNameOrder.Equal(source.ObjectPath(candidate.Index), path));
        if (source is null || sourceScript is null)
            throw new ConversionException($"GPU package has no saved HLSL. Supply its matching editor export with --niagara-gpu-source: {path}");
        JObject properties = sourceScript.Properties;
        JObject? sourceId = properties["CachedScriptVMId"] as JObject;
        JObject sourceExecutable = properties["CachedScriptVM"] as JObject
            ?? throw new ConversionException($"GPU companion export lacks a saved executable: {path}");
        byte[] hash = HashOf(id);
        if (hash.Length != 20 || !hash.AsSpan().SequenceEqual(HashOf(sourceId))
                || !Guid.TryParse(id?.Value<string>("CompilerVersionID"), out Guid compiler)
                || !Guid.TryParse(sourceId?.Value<string>("CompilerVersionID"), out Guid sourceCompiler)
                || compiler != sourceCompiler
                || !JToken.DeepEquals(executable["SimulationStageMetaData"], sourceExecutable["SimulationStageMetaData"]))
            throw new ConversionException($"GPU companion export has a different compiled script identity or stage layout: {path}");
        ScriptCompilationState.RequireCurrent(sourceExecutable, path);
        hlsl = sourceExecutable.Value<string>("LastHlslTranslationGPU") ?? "";
        if (string.IsNullOrWhiteSpace(hlsl)) throw new ConversionException($"GPU companion export lacks saved HLSL: {path}");
        return new(source, sourceExecutable, hlsl, DefinesOf(sourceId));

        static byte[] HashOf(JObject? value) => NumericParameterStoreSource.Bytes(value?["BaseScriptCompileHash"]?["DataHash"]);
        static JArray DefinesOf(JObject? value) => value?["AdditionalDefines"] as JArray ?? new();
    }
}
