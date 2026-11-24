using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Execution;

namespace LumenFx.Compiler.Unreal;

internal static class NiagaraRenderer
{
    internal static string SourceEnum(JObject? source, string field, string fallback, params string[] names)
    {
        string? value = source?.Value<string>(field)?.Split("::").Last();
        if (value is null) return fallback;
        if (int.TryParse(value, out int ordinal) && ordinal >= 0 && ordinal < names.Length) return names[ordinal];
        if (names.Contains(value, StringComparer.Ordinal)) return value;
        throw new ConversionException($"Unknown Niagara renderer {field}: {value}");
    }

    internal static string MotionVectorSetting(JObject source) => SourceEnum(source, "MotionVectorSetting", "AutoDetect",
        "AutoDetect", "Precise", "Approximate", "Disable");

    public static JObject SpriteCoverage(JObject renderer, int materialChannels, bool defaultEnabled)
    {
        string mode = SourceEnum(renderer, "PixelCoverageMode", "Automatic",
            "Automatic", "Disabled", "Enabled", "Enabled_RGBA", "Enabled_RGB", "Enabled_A");
        bool automatic = mode == "Automatic" && defaultEnabled;
        int channels = mode switch {
            "Automatic" => automatic ? materialChannels : 0,
            "Disabled" or "Enabled" => 0, "Enabled_RGBA" => 15, "Enabled_RGB" => 7, "Enabled_A" => 8,
            _ => throw new ConversionException($"Unsupported sprite pixel coverage {mode}") };
        return new JObject { ["channels"] = channels, ["enabled"] = mode == "Enabled" || channels != 0,
            ["automatic"] = automatic,
            ["blend"] = Math.Clamp(SourceAsset.Number(renderer["PixelCoverageBlend"], 1), 0, 1) };
    }

    internal static RendererFieldBindings.Variable? RibbonInterface(SourceAsset asset, JObject renderer, string emitterNamespace)
        => renderer["RibbonInterfaceBinding"]?["ResolvedParameter"] is JObject resolved
            ? RendererFieldBindings.Variable.From(asset, resolved, emitterNamespace) : null;

    public static JObject Ribbon(SourceAsset asset, JObject renderer, string emitterNamespace, int niagaraVersion)
    {
        string mode = SourceEnum(renderer, "TessellationMode", "Automatic", "Automatic", "Custom", "Disabled");
        bool disabled = mode == "Disabled";
        bool custom = mode == "Custom";
        var binding = RibbonInterface(asset, renderer, emitterNamespace);
        return new JObject
        {
            ["tension"] = SourceAsset.Number(renderer["CurveTension"]),
            ["tessellation_mode"] = mode,
            ["gpu_initialization"] = renderer.Value<bool>("bUseGPUInit"),
            ["draw_direction"] = SourceEnum(renderer, "DrawDirection", "FrontToBack", "FrontToBack", "BackToFront"),
            ["minimum_segment_length"] = .01,
            ["maximum_tessellation"] = disabled ? 1 : custom ? Math.Clamp(renderer.Value<int?>("TessellationFactor") ?? 16, 1, 16) : 16,
            ["tessellation_angle"] = Angle(custom ? SourceAsset.Number(renderer["TessellationAngle"], 15) : 15),
            ["constant_tessellation"] = custom && renderer.Value<bool>("bUseConstantFactor"),
            ["screen_space"] = !custom || renderer.Value<bool?>("bScreenSpaceTessellation") != false,
            ["shape"] = SourceEnum(renderer, "Shape", "Plane", "Plane", "MultiPlane", "Tube", "Custom"),
            ["facing"] = SourceEnum(renderer, "FacingMode", "Screen", "Screen", "Custom", "CustomSideVector"),
            ["width_segments"] = Math.Clamp(renderer.Value<int?>("WidthSegmentationCount") ?? 1, 1, 16),
            ["plane_count"] = renderer.Value<int?>("MultiPlaneCount") ?? 2,
            ["tube_subdivisions"] = renderer.Value<int?>("TubeSubdivisions") ?? 3,
            ["accurate_geometry"] = renderer.Value<bool?>("bEnableAccurateGeometry") ?? false,
            ["material_backface_culling"] = renderer.Value<bool?>("bUseMaterialBackfaceCulling") ?? false,
            ["custom_shape_closed"] = renderer.Value<bool?>("bCustomShapeClosed") ?? true,
            ["shape_interface"] = binding is null ? null : new JObject { ["name"] = binding.Name, ["type"] = binding.Type,
                ["underlying"] = binding.UnderlyingType, ["flags"] = binding.Flags },
            ["use_geometry_normals"] = niagaraVersion >= 81 && (renderer.Value<bool?>("bUseGeometryNormals") ?? true),
            ["custom_vertices"] = new JArray((renderer["CustomVertices"] as JArray ?? new JArray()).OfType<JObject>().Select(vertex =>
                new JObject { ["x"] = SourceAsset.Number(vertex["Position"]?["X"]), ["y"] = SourceAsset.Number(vertex["Position"]?["Y"]),
                    ["normal_x"] = SourceAsset.Number(vertex["Normal"]?["X"]), ["normal_y"] = SourceAsset.Number(vertex["Normal"]?["Y"]),
                    ["texture_v"] = SourceAsset.Number(vertex["TextureV"]) })),
            ["uv0"] = Uv(UvSource(renderer, niagaraVersion, "UV0")),
            ["uv1"] = Uv(UvSource(renderer, niagaraVersion, "UV1"))
        };
    }

    private static double Angle(double degrees) => (degrees > 0 && degrees < 1 ? 1 : degrees) * Math.PI / 180;

    private static JObject? UvSource(JObject renderer, int niagaraVersion, string channel)
    {
        var settings = renderer[channel + "Settings"] as JObject;
        if (niagaraVersion >= 63) return settings;

        // UNiagaraRibbonRendererProperties::PostLoad upgrades the deprecated values for each channel.
        var migrated = settings is null ? new JObject() : (JObject)settings.DeepClone();
        double distance = SourceAsset.Number(Legacy("TilingDistance"));
        string edge = distance == 0 ? "SmoothTransition" : "Locked";
        migrated["DistributionMode"] = distance == 0 ? "ScaledUniformly" : "TiledOverRibbonLength";
        migrated["LeadingEdgeMode"] = edge;
        migrated["TrailingEdgeMode"] = edge;
        if (distance != 0) migrated["TilingLength"] = distance;
        migrated["Offset"] = Legacy("Offset")?.DeepClone();
        migrated["Scale"] = Legacy("Scale")?.DeepClone();
        return migrated;

        JToken? Legacy(string name) => renderer[channel + name + "_DEPRECATED"] ?? renderer[channel + name];
    }

    private static JObject Uv(JObject? value)
    {
        value ??= new JObject();
        string mode = SourceEnum(value, "DistributionMode", "ScaledUsingRibbonSegmentLength",
            "ScaledUniformly", "ScaledUsingRibbonSegmentLength", "TiledOverRibbonLength", "TiledFromStartOverRibbonLength");
        string distribution = mode switch
        {
            "ScaledUniformly" => "uniform", "ScaledUsingRibbonSegmentLength" => "length",
            "TiledOverRibbonLength" => "distance",
            "TiledFromStartOverRibbonLength" => "distance_from_start",
            var unknown => throw new ConversionException($"Ribbon UV distribution requires its source attribute adapter: {unknown}")
        };
        JObject scale = value["Scale"] as JObject ?? new JObject();
        JObject offset = value["Offset"] as JObject ?? new JObject();
        return new JObject
        {
            ["distribution"] = distribution, ["tile_length"] = SourceAsset.Number(value["TilingLength"], 100) * .01,
            ["scale_u"] = SourceAsset.Number(scale["X"], 1), ["scale_v"] = SourceAsset.Number(scale["Y"], 1),
            ["offset_u"] = SourceAsset.Number(offset["X"]), ["offset_v"] = SourceAsset.Number(offset["Y"]),
            ["leading_edge"] = SourceEnum(value, "LeadingEdgeMode", "Locked", "SmoothTransition", "Locked"),
            ["trailing_edge"] = SourceEnum(value, "TrailingEdgeMode", "Locked", "SmoothTransition", "Locked"),
            ["u_override"] = value.Value<bool>("bEnablePerParticleUOverride"),
            ["v_override"] = value.Value<bool>("bEnablePerParticleVRangeOverride")
        };
    }
}
