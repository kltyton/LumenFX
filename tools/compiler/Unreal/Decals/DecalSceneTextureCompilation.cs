using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr DecalSceneTexture(Context context, JObject properties, int output)
    {
        if (!decalCoordinates || compilingVertex)
            throw new ConversionException("SceneTexture requires a supported raster material domain");
        int texture = ParseSceneTextureId(properties);
        if (UsesMirTranslation || output is not (1 or 2)) ValidateParticleSceneTextureLookup(texture, true);
        if (output == 1) return new Expr("(1.0 / DecalViewport.xy)", 2);
        if (output == 2) return new Expr("DecalViewport.xy", 2);
        if (output is not (0 or 3)) throw new ConversionException($"Invalid SceneTexture output {output}");
        if (output == 3) return new Expr("vec4(0.0)", 4, ZeroConstant: true);
        string uv = Connected(properties["Coordinates"])
            ? $"lfx_decal_scene_uv({Cast(RequiredInput(context, properties, "Coordinates"), 2).Code})"
            : "(gl_FragCoord.xy * DecalViewport.xy)";
        return texture switch {
            8 => new Expr($"lfx_decal_scene_world_normal({uv})", 4),
            13 => new Expr($"vec4(lfx_decal_custom_depth({uv}))", 4),
            25 => new Expr($"vec4(lfx_decal_custom_stencil({uv}))", 4),
            _ => new Expr($"vec4(lfx_linear_depth(lfx_decal_depth_at({uv})))", 4)
        };
    }
}
