using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr SceneColor(Context context, JObject properties, int output)
    {
        string domain = (compiledMaterialProperties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last();
        if (domain != "MD_Surface")
            throw new ConversionException("SceneColor lookups are only available on surface materials");
        if (output is < 0 or > 1) throw new ConversionException($"Invalid SceneColor output {output}");
        sceneColorUsed = true;
        materialViewUsed |= 2;
        viewUniformUsed = true;
        string mode = (properties.Value<string>("InputMode") ?? "Coordinates").Split("::").Last();
        bool connected = properties["Input"] is JObject source && source.Value<int>("expression") != 0;
        string uv;
        switch (mode)
        {
            case "Coordinates":
            case "0":
                uv = connected ? $"lfx_scene_color_uv({Cast(RequiredInput(context, properties, "Input"), 2).Code})"
                    : "lfx_scene_color_default_uv()";
                break;
            case "OffsetFraction":
            case "1":
                Expr offset = connected ? Cast(RequiredInput(context, properties, "Input"), 2)
                    : Vector(new[] { SourceAsset.Number(properties["ConstInput"]?["X"]), SourceAsset.Number(properties["ConstInput"]?["Y"]) });
                uv = $"lfx_scene_color_offset_uv({offset.Code})";
                break;
            default:
                throw new ConversionException($"Unknown SceneColor input mode {mode}");
        }
        string sample = $"lfx_material_scene_color({uv})";
        return output == 0 ? new Expr($"({sample}).rgb", 3) : new Expr($"({sample}).a", 1);
    }

    private void AppendSceneColorLookup(StringBuilder shader)
    {
        if (sceneColorUsed) shader.AppendLine("#moj_import <lumenfx:material_scene_color.glsl>");
    }
}
