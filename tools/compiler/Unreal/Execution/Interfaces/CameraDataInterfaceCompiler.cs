using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

/** Camera properties select a local controller and tick dependency, not a saved-frame offset. */
internal static class CameraDataInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceCamera";
    internal static void Validate(string function, int inputs, int outputs)
    {
        bool valid = function switch {
            "GetFieldOfView" or "GetSplitscreenInfo" => inputs == 0 && outputs == 1,
            "GetCameraPropertiesCPU/GPU" => inputs == 0 && outputs is 12 or 15,
            "CalculateParticleDistancesCPU" => inputs == 5 && outputs == 0,
            "QueryClosestParticlesCPU" => inputs == 3 && outputs == 1,
            _ => false };
        if (!valid) throw new ConversionException($"Invalid camera function signature: {function} ({inputs}, {outputs})");
    }
    internal static JObject Compile(SourceAsset asset, int reference, AssetLibrary? library)
    {
        JObject properties = DataInterfaceInstanceSources.Resolve(asset, reference, ClassName, library)?.Properties ?? new();
        return new JObject { ["controller_index"] = properties.Value<int?>("PlayerControllerIndex") ?? 0,
            ["require_current"] = properties.Value<bool?>("bRequireCurrentFrameData") ?? true };
    }
}
