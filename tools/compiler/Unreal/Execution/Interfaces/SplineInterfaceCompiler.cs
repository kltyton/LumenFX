using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Scene;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class SplineInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceSpline";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var data = source?.Object?.Properties ?? new JObject();
        string[] modes = ["Default", "AttachParentOnly", "ParameterBindingOnly"];
        string mode = (data.Value<string>("SourceMode") ?? "Default").Split("::").Last();
        if (int.TryParse(mode, out int index) && index >= 0 && index < modes.Length) mode = modes[index];
        if (!modes.Contains(mode, StringComparer.Ordinal))
            throw new ConversionException($"Unknown Niagara Spline source mode {mode}");
        int steps = data.Value<int?>("NumLUTSteps") ?? 256;
        if (steps < 0) throw new ConversionException($"Invalid Niagara Spline LUT sample count {steps}");
        string actor = ObjectPropertyReaderCompiler.ObjectPath(source?.Asset ?? owner, data["SoftSourceActor"]);
        if (actor.Length == 0)
            actor = ObjectPropertyReaderCompiler.ObjectPath(source?.Asset ?? owner, data["Source"]);
        return new JObject {
            ["mode"] = mode,
            ["parameter"] = data["SplineUserParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["actor"] = actor,
            ["saved_actor"] = actor.Length == 0 ? null : SplineComponentSource.Compile(source?.Asset ?? owner, actor, library),
            ["use_lut"] = data.Value<bool?>("bUseLUT") ?? false,
            ["lut_steps"] = steps
        };
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "SampleSplinePositionByUnitDistance" or "SampleSplinePositionByUnitDistanceWS"
                or "SampleSplineUpVectorByUnitDistance" or "SampleSplineUpVectorByUnitDistanceWS"
                or "SampleSplineRightVectorByUnitDistance" or "SampleSplineRightVectorByUnitDistanceWS"
                or "SampleSplineDirectionByUnitDistance" or "SampleSplineDirectionByUnitDistanceWS"
                or "SampleSplineTangentByUnitDistance" or "SampleSplineTangentByUnitDistanceWS" => (1, 3),
            "SampleSplineRotationByUnitDistance" or "SampleSplineRotationByUnitDistanceWS" => (1, 4),
            "FindClosestUnitDistanceFromPositionWS" => (3, 1),
            "GetSplineLocalToWorld" or "GetSplineLocalToWorldInverseTransposed" => (0, 16),
            "GetSplineLength" => (0, 1),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Spline function {name} ({inputs}, {outputs})");
    }
}
