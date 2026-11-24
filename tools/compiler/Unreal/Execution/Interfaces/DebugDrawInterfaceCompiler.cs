using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class DebugDrawInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceDebugDraw";

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        long maximum = source?.Object?.Properties.Value<long?>("OverrideMaxLineInstances") ?? 0;
        return new JObject { ["line_limit"] = Math.Clamp(maximum, 0, int.MaxValue) };
    }

    internal static JObject CpuFunction(SourceAsset owner, int reference, AssetLibrary? library, JObject function, int inputs, int outputs)
    {
        string name = function.Value<string>("Name") ?? "";
        string? tag = PersistentDebugDrawSource.Prefix(function["FunctionSpecifiers"]);
        int words = name switch {
            "DrawLine" => 11, "DrawRectangle" => 19, "DrawCircle" => 16,
            "DrawBox" => 15, "DrawSphere" => 10, "DrawCylinder" => 15,
            "DrawCone" => 16, "DrawTorus" => 15, "DrawCoordinateSystem" => 9,
            "DrawGrid2D" => 16, "DrawGrid3D" => 18,
            "DrawLinePersistent" when tag is not null => 12,
            "DrawRectanglePersistent" when tag is not null => 25,
            "DrawCirclePersistent" when tag is not null => 21,
            "DrawBoxPersistent" when tag is not null => 21,
            "DrawSpherePersistent" when tag is not null => 29,
            "DrawCylinderPersistent" when tag is not null => 24,
            "DrawConePersistent" or "DrawTorusPersistent" when tag is not null => 23,
            "DrawCoordinateSystemPersistent" when tag is not null => 14,
            "DrawGrid2DPersistent" when tag is not null => 21,
            "DrawGrid3DPersistent" when tag is not null => 23,
            _ => -1
        };
        if (inputs != words || outputs != 0)
            throw new ConversionException($"Invalid Niagara Debug Draw function {name} ({inputs}, {outputs})");
        JObject data = Definition(owner, reference, library);
        if (name.EndsWith("Persistent", StringComparison.Ordinal)) data["compiler_tag"] = tag;
        return data;
    }
}
