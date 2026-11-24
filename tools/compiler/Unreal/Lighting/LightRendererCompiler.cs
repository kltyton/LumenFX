using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Lighting;

internal static class LightRendererCompiler
{
    internal static JObject Compile(JObject source)
    {
        JToken? add = source["ColorAdd"];
        return new JObject {
            ["inverse_squared"] = source.Value<bool?>("bUseInverseSquaredFalloff") ?? true,
            ["affects_translucency"] = source.Value<bool?>("bAffectsTranslucency") ?? false,
            ["allow_megalights"] = source.Value<bool?>("bAllowMegaLights") ?? false,
            ["megalights_cast_shadows"] = source.Value<bool?>("bMegaLightsCastShadows") ?? false,
            ["alpha_scales_brightness"] = source.Value<bool?>("bAlphaScalesBrightness") ?? false,
            ["radius_scale"] = SourceAsset.Number(source["RadiusScale"], 1),
            ["default_exponent"] = SourceAsset.Number(source["DefaultExponent"], 1),
            ["specular_scale"] = SourceAsset.Number(source["SpecularScale"], 1),
            ["diffuse_scale"] = SourceAsset.Number(source["DiffuseScale"], 1),
            ["color_add"] = new JArray(SourceAsset.Number(add?["X"]), SourceAsset.Number(add?["Y"]), SourceAsset.Number(add?["Z"])),
            ["override_inverse_exposure_blend"] = source.Value<bool?>("bOverrideInverseExposureBlend") ?? false,
            ["inverse_exposure_blend"] = SourceAsset.Number(source["InverseExposureBlend"]),
            ["inverse_exposure_default"] = 0
        };
    }
}
