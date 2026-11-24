using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class NiagaraSpriteRendererSource
{
    private const int AutomaticFacingAndAlignment = 109;

    internal static string Alignment(int mainVersion, JObject? renderer) =>
        NiagaraRenderer.SourceEnum(renderer, "Alignment", Default(renderer, mainVersion, "Unaligned"),
            "Unaligned", "VelocityAligned", "CustomAlignment", "Automatic");

    internal static string Facing(int mainVersion, JObject? renderer) =>
        NiagaraRenderer.SourceEnum(renderer, "FacingMode", Default(renderer, mainVersion, "FaceCamera"),
            "FaceCamera", "FaceCameraPlane", "CustomFacingVector", "FaceCameraPosition", "FaceCameraDistanceBlend", "Automatic");

    private static string Default(JObject? renderer, int mainVersion, string legacyDefault) =>
        renderer is null || mainVersion >= AutomaticFacingAndAlignment ? "Automatic" : legacyDefault;

    internal static bool SubImageBlend(int niagaraVersion, JObject? renderer) =>
        renderer is null || (renderer.Value<bool?>("bSubImageBlend") ?? niagaraVersion >= 80);
}
