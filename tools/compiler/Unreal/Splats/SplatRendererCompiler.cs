using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Splats;

internal static class SplatRendererCompiler
{
    internal static JObject Compile(JObject renderer)
    {
        float splatScale = renderer.Value<float?>("SplatScale") ?? 1.0f;
        float opacityScale = renderer.Value<float?>("OpacityScale") ?? 1.0f;
        bool invertToneCurve = renderer.Value<bool?>("bInvertToneCurve") ?? false;
        float inverseToneCurveStrength = Math.Clamp(renderer.Value<float?>("InverseToneCurveStrength") ?? 1.0f, 0.0f, 1.0f);
        bool invertExpandGamut = renderer.Value<bool?>("bInvertExpandGamut") ?? true;
        int rendererVisibility = renderer.Value<int?>("RendererVisibility") ?? 0;
        return new JObject {
            ["splat_scale"] = splatScale,
            ["opacity_scale"] = opacityScale,
            ["invert_tone_curve"] = invertToneCurve,
            ["inverse_tone_curve_strength"] = inverseToneCurveStrength,
            ["invert_expand_gamut"] = invertExpandGamut,
            ["renderer_visibility"] = rendererVisibility
        };
    }
}
