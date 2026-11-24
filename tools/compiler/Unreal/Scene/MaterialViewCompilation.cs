using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private const int AtmosphereLightViewInput = 4096;
    private const int DistantSkyLightViewInput = 8192;
    private const int AtmosphereIlluminanceViewInput = 16384;
    private const int AtmosphereSkyViewInput = 32768;
    private const int AtmosphereDiskViewInput = 65536;
    private const int AtmosphereAerialViewInput = 131072;
    private const int EyeAdaptationViewInput = 262144;
    private const int PostVolumeFlagsViewInput = 524288;

    private void MarkSkyAtmosphereViewUsed() => materialViewUsed |= AtmosphereLightViewInput;

    private static int SkyAtmosphereLightIndex(JObject properties, string expression)
    {
        int index = properties.Value<int?>("LightIndex") ?? 0;
        if (index is < 0 or > 1)
            throw new ConversionException($"{expression} light index must be 0 or 1, got {index}");
        return index;
    }

    private Expr SkyAtmosphereLightDirection(JObject properties)
    {
        MarkSkyAtmosphereViewUsed();
        int index = SkyAtmosphereLightIndex(properties, "SkyAtmosphereLightDirection");
        string field = $"MaterialAtmosphereDirection{index}";
        return new Expr($"({field}.xyz * {field}.w)", 3);
    }

    private Expr SkyAtmosphereLightIlluminanceOnGround(JObject properties)
    {
        if (compilingVertex)
            throw new ConversionException("SkyAtmosphereLightIlluminanceOnGround is only available in the pixel material stage");
        MarkSkyAtmosphereViewUsed();
        int index = SkyAtmosphereLightIndex(properties, "SkyAtmosphereLightIlluminanceOnGround");
        return new Expr($"MaterialAtmosphereGroundIlluminance{index}.xyz", 3);
    }

    private Expr AtmosphericLightVector()
    {
        MarkSkyAtmosphereViewUsed();
        return new Expr("MaterialAtmosphereDirection0.xyz", 3);
    }

    private Expr AtmosphericLightColor()
    {
        if (compilingVertex)
            throw new ConversionException("AtmosphericLightColor is only available in the pixel material stage");
        MarkSkyAtmosphereViewUsed();
        return new Expr("MaterialAtmosphereGroundIlluminance0.xyz", 3);
    }

    private Expr SkyAtmosphereDistantLightScatteredLuminance()
    {
        if (compilingVertex)
            throw new ConversionException("SkyAtmosphereDistantLightScatteredLuminance is only available in the pixel material stage");
        materialViewUsed |= DistantSkyLightViewInput;
        return new Expr("MaterialAtmosphereDistantSkyLuminance.xyz", 3);
    }

    private Expr SkyAtmosphereLightIlluminance(Context context, JObject properties)
    {
        if (compilingVertex)
            throw new ConversionException("SkyAtmosphereLightIlluminance is only available in the pixel material stage");
        int index = SkyAtmosphereLightIndex(properties, "SkyAtmosphereLightIlluminance");
        string translated = AtmospherePosition(context, properties, "SkyAtmosphereLightIlluminance");
        materialViewUsed |= AtmosphereIlluminanceViewInput;
        return new Expr($"lfx_material_atmosphere_illuminance({translated}, {index})", 3);
    }

    private string AtmospherePosition(Context context, JObject properties, string expression)
    {
        string origin = (properties.Value<string>("WorldPositionOriginType") ?? "Absolute").Split("::").Last();
        bool relative = origin is "CameraRelative" or "1";
        if (!relative && origin is not ("Absolute" or "0"))
            throw new ConversionException($"Unknown {expression} position origin {origin}");
        Expr position = Cast(Input(context, properties["WorldPosition"], () => WorldPosition(new JObject {
            ["WorldPositionShaderOffset"] = relative ? "WPT_CameraRelative" : "WPT_Default"
        })), 3);
        if (relative) return DemoteLargeWorld(position).Code;
        materialViewUsed |= 8;
        return $"lfx_lwc_demote(lfx_lwc_add({Promote(position)}, " +
                "LfxLWC3(-MaterialWorldTile.xyz, MaterialPreViewTranslation.xyz)))";
    }

    private string AtmosphereViewportUv() => volumeCoordinates ? "lfx_volume_viewport_uv()" : ScreenPosition(0).Code;

    private Expr SkyAtmosphereLightDiskLuminance(Context context, JObject properties)
    {
        if (compilingVertex)
            throw new ConversionException("SkyAtmosphereLightDiskLuminance is only available in the pixel material stage");
        int index = SkyAtmosphereLightIndex(properties, "SkyAtmosphereLightDiskLuminance");
        var input = properties["DiskAngularDiameterOverride"];
        string overrideCos = "-1.0";
        if (Connected(input)) {
            Expr angle = DemoteLargeWorld(Cast(RequiredInput(context, properties, "DiskAngularDiameterOverride"), 1));
            overrideCos = $"cos(radians({angle.Code}) * 0.5)";
        }
        Expr camera = CameraVector();
        materialViewUsed |= AtmosphereDiskViewInput;
        return new Expr($"lfx_material_atmosphere_disk(-({camera.Code}), {AtmosphereViewportUv()}, {index}, {overrideCos})", 3);
    }

    private Expr SkyAtmosphereAerialPerspective(Context context, JObject properties)
    {
        if (compilingVertex)
            throw new ConversionException("SkyAtmosphereAerialPerspective is only available in the pixel material stage");
        string translated = AtmospherePosition(context, properties, "SkyAtmosphereAerialPerspective");
        // A volume sample has a physical world position even when the graph's camera-relative field is unset.
        string surface = volumeCoordinates ? "(effectPosition.xzy * 100.0)"
                : WorldPosition(new JObject { ["WorldPositionShaderOffset"] = "WPT_CameraRelative" }).Code;
        materialViewUsed |= AtmosphereAerialViewInput;
        return new Expr($"lfx_material_atmosphere_aerial({translated}, {surface})", 4);
    }

    private Expr SkyAtmosphereViewLuminance(Context context, JObject properties)
    {
        if (compilingVertex)
            throw new ConversionException("SkyAtmosphereViewLuminance is only available in the pixel material stage");
        Expr direction = Input(context, properties["WorldDirection"], () => {
            Expr camera = CameraVector();
            return new Expr($"(-{camera.Code})", 3);
        });
        direction = DemoteLargeWorld(Cast(direction, 3));
        materialViewUsed |= AtmosphereSkyViewInput;
        return new Expr($"lfx_material_atmosphere_view_luminance({direction.Code}, {AtmosphereViewportUv()})", 3);
    }

    private Expr MaterialViewProperty(JObject properties, int output)
    {
        if (output is < 0 or > 1) throw new ConversionException($"Invalid view property output {output}");
        string property = (properties.Value<string>("Property") ?? "MEVP_FieldOfView").Split("::").Last();
        string[] names = { "MEVP_BufferSize", "MEVP_FieldOfView", "MEVP_TanHalfFieldOfView", "MEVP_ViewSize",
            "MEVP_WorldSpaceViewPosition", "MEVP_WorldSpaceCameraPosition", "MEVP_ViewportOffset",
            "MEVP_TemporalSampleCount", "MEVP_TemporalSampleIndex", "MEVP_TemporalSampleOffset",
            "MEVP_RuntimeVirtualTextureOutputLevel", "MEVP_RuntimeVirtualTextureOutputDerivative", "MEVP_PreExposure",
            "MEVP_RuntimeVirtualTextureMaxLevel", "MEVP_ResolutionFraction", "MEVP_PostVolumeUserFlags",
            "MEVP_FirstPersonFieldOfView", "MEVP_FirstPersonTanHalfFieldOfView", "MEVP_FirstPersonScale", "MEVP_NearPlane" };
        if (int.TryParse(property, out int ordinal))
            property = ordinal >= 0 && ordinal < names.Length ? names[ordinal]
                : throw new ConversionException($"Unknown view property index {ordinal}");
        bool inverse = output == 1;
        string value;
        int width = 1;
        switch (property)
        {
            case "MEVP_FirstPersonFieldOfView":
            case "MEVP_FirstPersonTanHalfFieldOfView":
            case "MEVP_FirstPersonScale":
                return FirstPersonViewProperty(property, inverse);
            case "MEVP_WorldSpaceCameraPosition":
                Expr camera = WorldCameraPosition();
                return inverse ? Arithmetic(Constant(1), camera, "/") : camera;
            case "MEVP_WorldSpaceViewPosition":
                Expr origin = WorldViewPosition();
                return inverse ? Arithmetic(Constant(1), origin, "/") : origin;
            case "MEVP_TemporalSampleCount": value = "MaterialTemporalAA.y"; break;
            case "MEVP_TemporalSampleIndex": value = "MaterialTemporalAA.x"; break;
            case "MEVP_TemporalSampleOffset": value = "MaterialTemporalAA.zw"; width = 2; break;
            case "MEVP_NearPlane": value = "MaterialCamera.x"; break;
            case "MEVP_PreExposure":
                materialViewUsed |= 2;
                return new Expr(inverse ? "MaterialCamera.z" : "MaterialCamera.y", 1);
            case "MEVP_PostVolumeUserFlags":
                materialViewUsed |= PostVolumeFlagsViewInput;
                return new Expr(inverse ? "(1.0 / float(MaterialPostVolume.x))" : "float(MaterialPostVolume.x)", 1);
            case "MEVP_ViewportOffset":
                return new Expr("vec2(0.0)", 2);
            case "MEVP_RuntimeVirtualTextureOutputLevel":
            case "MEVP_RuntimeVirtualTextureMaxLevel":
            case "MEVP_RuntimeVirtualTextureOutputDerivative":
                return VirtualTexturePageView(property, inverse);
            default:
                JObject normalized = (JObject)properties.DeepClone();
                normalized["Property"] = property;
                return ViewProperty(normalized, output);
        }
        materialViewUsed |= property == "MEVP_NearPlane" ? 4 : 1;
        return new Expr(inverse ? $"({(width == 1 ? "1.0" : "vec2(1.0)")} / {value})" : value, width);
    }

    private Expr EyeAdaptation()
    {
        materialViewUsed |= EyeAdaptationViewInput;
        return new Expr("lfx_eye_adaptation()", 1);
    }

    private Expr EyeAdaptationInverse(Context context, JObject properties)
    {
        if (compilingVertex)
            throw new ConversionException("EyeAdaptationInverse is only available in the pixel material stage");
        Expr lightValue = Input(context, properties["LightValueInput"], () => Vector(new[] { 1d, 1d, 1d }));
        Expr alpha = Input(context, properties["AlphaInput"], () => Constant(1));
        if (lightValue.Sampler || alpha.Sampler)
            throw new ConversionException("EyeAdaptationInverse inputs must be numeric");
        materialViewUsed |= EyeAdaptationViewInput;
        Expr scale = new($"lfx_eye_adaptation_inverse_scale({Cast(DemoteLargeWorld(alpha), 1).Code})", 1);
        return Arithmetic(lightValue, scale, "*");
    }

    private Expr PostVolumeUserFlagTest(Context context, JObject properties)
    {
        Expr bitIndex = Input(context, properties["BitIndex"], () => Constant(SourceAsset.Number(properties["ConstBitIndex"])));
        if (bitIndex.Sampler)
            throw new ConversionException("PostVolumeUserFlagTest bit index must be numeric");
        materialViewUsed |= PostVolumeFlagsViewInput;
        return ZeroGradient(new Expr($"lfx_post_volume_flag_test({Cast(DemoteLargeWorld(bitIndex), 1).Code})", 1));
    }

    private void AppendMaterialView(StringBuilder shader)
    {
        if (MaterialDeclarationViewInputs != 0) shader.AppendLine("#moj_import <lumenfx:material_view.glsl>");
        if ((MaterialDeclarationViewInputs & AtmosphereIlluminanceViewInput) != 0)
            shader.AppendLine("#moj_import <lumenfx:atmosphere/material_illuminance.glsl>");
        if ((MaterialDeclarationViewInputs & AtmosphereSkyViewInput) != 0)
            shader.AppendLine("#moj_import <lumenfx:atmosphere/material_sky_view.glsl>");
        if ((MaterialDeclarationViewInputs & AtmosphereDiskViewInput) != 0)
            shader.AppendLine("#moj_import <lumenfx:atmosphere/material_disk.glsl>");
        if ((MaterialDeclarationViewInputs & AtmosphereAerialViewInput) != 0)
            shader.AppendLine("#moj_import <lumenfx:atmosphere/material_aerial.glsl>");
    }
}
