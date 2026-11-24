using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class SceneCapture2DInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfaceSceneCapture2D";

    private static readonly string[] SourceModes = ["UserParameterThenAttachParent", "UserParameterOnly", "AttachParentOnly", "Managed"];
    private static readonly string[] OffsetModes = ["Disabled", "RelativeLocal", "RelativeWorld", "AbsoluteWorld"];
    private static readonly string[] Projections = ["Perspective", "Orthographic"];
    private static readonly string[] CaptureSources = [
        "SCS_SceneColorHDR", "SCS_SceneColorHDRNoAlpha", "SCS_FinalColorLDR", "SCS_SceneColorSceneDepth", "SCS_SceneDepth",
        "SCS_DeviceDepth", "SCS_Normal", "SCS_BaseColor", "SCS_FinalColorHDR", "SCS_FinalToneCurveHDR"
    ];
    private static readonly string[] TargetFormats = [
        "RTF_R8", "RTF_RG8", "RTF_RGBA8", "RTF_RGBA8_SRGB", "RTF_R16f", "RTF_RG16f", "RTF_RGBA16f", "RTF_R32f",
        "RTF_RG32f", "RTF_RGBA32f", "RTF_RGB10A2"
    ];

    internal static JObject Definition(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ClassName, library);
        var properties = source?.Object?.Properties ?? new JObject();
        var asset = source?.Asset ?? owner;
        var size = properties["ManagedTextureSize"];
        return new JObject {
            ["mode"] = EnumValue(properties, "SourceMode", SourceModes),
            ["parameter"] = properties["SceneCaptureUserParameter"]?["Parameter"]?.Value<string>("name") ?? "",
            ["auto_move"] = properties.Value<bool>("bAutoMoveWithComponent"),
            ["location_mode"] = EnumValue(properties, "AutoMoveOffsetLocationMode", OffsetModes),
            ["rotation_mode"] = EnumValue(properties, "AutoMoveOffsetRotationMode", OffsetModes),
            ["location"] = Vector(properties["AutoMoveOffsetLocation"], "X", "Y", "Z"),
            ["rotation"] = Vector(properties["AutoMoveOffsetRotation"], "Pitch", "Yaw", "Roll"),
            ["managed"] = new JObject {
                ["source"] = EnumValue(properties, "ManagedCaptureSource", CaptureSources),
                ["width"] = size?.Value<int?>("X") ?? 128,
                ["height"] = size?.Value<int?>("Y") ?? 128,
                ["format"] = EnumValue(properties, "ManagedTextureFormat", TargetFormats, 2),
                ["projection"] = EnumValue(properties, "ManagedProjectionType", Projections),
                ["fov"] = properties.Value<float?>("ManagedFOVAngle") ?? 90,
                ["ortho_width"] = properties.Value<float?>("ManagedOrthoWidth") ?? 512,
                ["lod_distance_factor"] = properties.Value<float?>("ManagedLODDistanceFactor") ?? 1,
                ["every_frame"] = properties.Value<bool?>("bManagedCaptureEveryFrame") ?? true,
                ["on_movement"] = properties.Value<bool>("bManagedCaptureOnMovement"),
                ["hide_attach"] = properties.Value<bool?>("bManagedHideAttachComponent") ?? true,
                ["hidden_actors"] = ActorPaths(asset, properties, properties.ContainsKey("ManagedHiddenActors")
                    ? "ManagedHiddenActors" : "ManagedHidenActors"),
                ["show_only_actors"] = ActorPaths(asset, properties, "ManagedShowOnlyActors")
            }
        };
    }

    private static JArray Vector(JToken? value, string first, string second, string third) =>
        new(SourceAsset.Number(value?[first], 0), SourceAsset.Number(value?[second], 0), SourceAsset.Number(value?[third], 0));

    private static JArray ActorPaths(SourceAsset asset, JObject properties, string field) =>
        new((properties[field] as JArray ?? new()).Select(value => ObjectPropertyReaderCompiler.ObjectPath(asset, value))
            .Where(path => path.Length != 0));

    private static string EnumValue(JObject properties, string field, string[] values, int fallback = 0)
    {
        string value = (properties.Value<string>(field) ?? values[fallback]).Split("::").Last();
        if (int.TryParse(value, out int index) && index >= 0 && index < values.Length) return values[index];
        if (values.Contains(value, StringComparer.Ordinal)) return value;
        throw new ConversionException($"Invalid Niagara Scene Capture 2D {field}: {value}");
    }

    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        var shape = name switch {
            "GetTextureSize" => (0, 3),
            "Project" or "Deproject" => (3, 3),
            "GetCaptureTransform" => (0, 7),
            "SetCaptureTransform" => (8, 0),
            "SetRequestCapture" => (1, 0),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Scene Capture 2D CPU function {name} ({inputs}, {outputs})");
    }
}
