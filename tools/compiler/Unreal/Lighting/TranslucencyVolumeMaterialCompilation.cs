using LumenFx.Compiler.Unreal.Attributes;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record SurfaceLightingInputs(Expr BaseColor, Expr SpecularColor, Expr Roughness, Expr View, Expr? Subsurface,
        Expr AmbientOcclusion, Expr? BentNormal, Expr Metallic, Expr ClearCoat, Expr ClearCoatRoughness, Expr? BottomNormal);
    private sealed record TranslucencyVolumeSurface(Expr Diffuse, Expr? WorldNormal, double DirectionalIntensity, string Mode,
        Expr BaseColor, Expr AmbientOcclusion, Expr? Subsurface, Expr SkyNormal, Expr? BentNormal, Expr View, SurfaceLightingInputs? Surface = null);

    private TranslucencyVolumeSurface? CompileTranslucencyVolumeSurface(Context context, SourceObject data, string shadingModel)
    {
        var authoredMode = compiledMaterialProperties["TranslucencyLightingMode"];
        string mode = authoredMode switch {
            null or { Type: JTokenType.Null } => "TLM_VolumetricNonDirectional",
            { Type: JTokenType.String } value => value.Value<string>()!.Split("::").Last(),
            { Type: JTokenType.Integer } value => value.Value<int>() switch {
                0 => "TLM_VolumetricNonDirectional",
                1 => "TLM_VolumetricDirectional",
                2 => "TLM_VolumetricPerVertexNonDirectional",
                3 => "TLM_VolumetricPerVertexDirectional",
                4 => "TLM_Surface",
                5 => "TLM_SurfacePerPixelLighting",
                _ => throw new ConversionException("Unknown numeric translucent lighting mode")
            },
            _ => throw new ConversionException("Invalid translucent lighting mode")
        };
        bool directional = mode is "TLM_VolumetricDirectional" or "TLM_VolumetricPerVertexDirectional" or "TLM_Surface";
        string? volumeMode = mode switch {
            "TLM_VolumetricNonDirectional" or "TLM_VolumetricDirectional" => "pixel",
            "TLM_Surface" => "surface_volume",
            "TLM_VolumetricPerVertexNonDirectional" => "vertex_non_directional",
            "TLM_VolumetricPerVertexDirectional" => "vertex_directional",
            "TLM_SurfacePerPixelLighting" => "surface_per_pixel",
            _ => null
        };
        if (volumeMode is null) return null;
        bool clearCoatForward = shadingModel == "MSM_ClearCoat" && volumeMode == "surface_per_pixel";
        Expr authoredBaseColor = Cast(MaterialInput(context, data, MaterialAttribute.BaseColor,
            () => DefaultAttribute(MaterialAttribute.BaseColor)).Value, 3);
        Expr baseColor = new($"clamp({authoredBaseColor.Code}, 0.0, 1.0)", 3);
        Expr metallic = Cast(MaterialInput(context, data, MaterialAttribute.Metallic,
            () => DefaultAttribute(MaterialAttribute.Metallic)).Value, 1);
        string metal = $"clamp({metallic.Code}, 0.0, 1.0)";
        Expr diffuse = new($"({baseColor.Code} * (1.0 - {metal}))", 3);
        double intensity = Math.Clamp(SourceAsset.Number(compiledMaterialProperties["TranslucencyDirectionalLightingIntensity"], 1), .1, 10);
        Expr? color = shadingModel == "MSM_Subsurface" ? Cast(MaterialInput(context, data, MaterialAttribute.SubsurfaceColor,
            () => DefaultAttribute(MaterialAttribute.SubsurfaceColor)).Value, 3) : null;
        Expr ambientOcclusion = Cast(MaterialInput(context, data, MaterialAttribute.AmbientOcclusion,
            () => DefaultAttribute(MaterialAttribute.AmbientOcclusion)).Value, 1);
        Expr worldNormal = PixelWorldNormal();
        Expr? bentNormal = CompileBentNormal(context, data);
        Expr view = CameraVector();
        if (volumeMode is "surface_per_pixel" or "surface_volume") {
            Expr specular = Cast(MaterialInput(context, data, MaterialAttribute.Specular,
                () => DefaultAttribute(MaterialAttribute.Specular)).Value, 1);
            Expr roughness = Cast(MaterialInput(context, data, MaterialAttribute.Roughness,
                () => DefaultAttribute(MaterialAttribute.Roughness)).Value, 1);
            if (shadingModel == "MSM_ClearCoat") roughness = new Expr($"clamp({roughness.Code}, 0.0, 254.0 / 255.0)", 1);
            Expr f0 = new($"mix(vec3(0.08 * clamp({specular.Code}, 0.0, 1.0)), {baseColor.Code}, {metal})", 3);
            Expr CoatInput(MaterialAttribute attribute, double fallback) {
                if (!clearCoatForward) return Constant(fallback);
                Expr value = Cast(MaterialInput(context, data, attribute, () => DefaultAttribute(attribute)).Value, 1);
                return new Expr($"clamp({value.Code}, 0.0, 1.0)", 1);
            }
            Expr? bottomNormal = clearCoatForward ? CompileClearCoatBottomNormal(context, data) : null;
            return new TranslucencyVolumeSurface(diffuse, worldNormal, intensity, volumeMode,
                baseColor, ambientOcclusion, color, worldNormal, bentNormal, view,
                new SurfaceLightingInputs(baseColor, f0, roughness, view, color, ambientOcclusion, bentNormal,
                    new Expr(metal, 1), CoatInput(MaterialAttribute.CustomData0, 0),
                    CoatInput(MaterialAttribute.CustomData1, .1), bottomNormal));
        }
        return new TranslucencyVolumeSurface(diffuse, directional ? worldNormal : null, intensity, volumeMode,
            baseColor, ambientOcclusion, color, worldNormal, bentNormal, view);
    }

    private TranslucencyVolumeSurface? CompileTranslucentSlabLighting(SlabSubstrate slab)
    {
        var authoredMode = compiledMaterialProperties["TranslucencyLightingMode"];
        string mode = authoredMode switch {
            null or { Type: JTokenType.Null } => "TLM_SurfacePerPixelLighting",
            { Type: JTokenType.String } value => value.Value<string>()!.Split("::").Last(),
            { Type: JTokenType.Integer } value => value.Value<int>() switch {
                0 => "TLM_VolumetricNonDirectional",
                1 => "TLM_VolumetricDirectional",
                2 => "TLM_VolumetricPerVertexNonDirectional",
                3 => "TLM_VolumetricPerVertexDirectional",
                4 => "TLM_Surface",
                5 => "TLM_SurfacePerPixelLighting",
                _ => throw new ConversionException("Unknown numeric translucent lighting mode")
            },
            _ => throw new ConversionException("Invalid translucent lighting mode")
        };
        bool directional = mode is "TLM_VolumetricDirectional" or "TLM_VolumetricPerVertexDirectional" or "TLM_Surface";
        string? volumeMode = mode switch {
            "TLM_VolumetricNonDirectional" or "TLM_VolumetricDirectional" => "pixel",
            "TLM_Surface" => "surface_volume",
            "TLM_VolumetricPerVertexNonDirectional" => "vertex_non_directional",
            "TLM_VolumetricPerVertexDirectional" => "vertex_directional",
            "TLM_SurfacePerPixelLighting" => "surface_per_pixel",
            _ => null
        };
        if (volumeMode is null) return null;
        Expr diffuse = new($"clamp({slab.Diffuse.Code}, 0.0, 1.0)", 3);
        Expr f0 = new($"clamp({slab.F0.Code}, 0.0, 1.0)", 3);
        Expr roughness = new($"clamp({slab.Roughness.Code}, 0.0, 1.0)", 1);
        Expr worldNormal = slab.WorldNormal;
        Expr view = CameraVector();
        double intensity = Math.Clamp(SourceAsset.Number(compiledMaterialProperties["TranslucencyDirectionalLightingIntensity"], 1), .1, 10);
        if (volumeMode is "surface_per_pixel" or "surface_volume") {
            return new TranslucencyVolumeSurface(diffuse, worldNormal, intensity, volumeMode,
                diffuse, slab.AmbientOcclusion, null, worldNormal, null, view,
                new SurfaceLightingInputs(diffuse, f0, roughness, view, null, slab.AmbientOcclusion, null,
                    Constant(0), slab.SimpleCoat.ZeroConstant ? Constant(0) : slab.SecondWeight,
                    slab.SimpleCoat.ZeroConstant ? Constant(.1) : slab.SecondRoughness, null));
        }
        return new TranslucencyVolumeSurface(diffuse, directional ? worldNormal : null, intensity, volumeMode,
            diffuse, slab.AmbientOcclusion, null, worldNormal, null, view);
    }
}
