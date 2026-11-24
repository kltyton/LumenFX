using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr DBufferTexture(Context context, JObject properties, int output)
    {
        string domain = (compiledMaterialProperties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last();
        if (domain != "MD_Surface" || compiledBlendMode is not ("BLEND_Opaque" or "BLEND_Masked"))
            throw new ConversionException("DBuffer scene textures are only available on opaque or masked surfaces");
        string id = (properties.Value<string>("DBufferTextureId") ?? "DBT_A").Split("::").Last();
        int texture = id switch {
            "DBT_A" or "0" => 0, "DBT_B" or "1" => 1, "DBT_C" or "2" => 2,
            _ => throw new ConversionException($"Unknown DBuffer texture {id}")
        };
        if (output is < 0 or > 2) throw new ConversionException($"Invalid DBuffer texture output {output}");
        dbufferTextures |= 1 << texture;
        viewUniformUsed = true;
        bool connected = properties["Coordinates"] is JObject source && source.Value<int>("expression") != 0;
        string uv = connected ? $"lfx_material_dbuffer_uv({Cast(RequiredInput(context, properties, "Coordinates"), 2).Code})"
                : "lfx_material_dbuffer_default_uv()";
        string sample = $"lfx_material_dbuffer_{texture}({uv})";
        return output switch {
            0 => new Expr(sample, 4), 1 => new Expr($"({sample}).rgb", 3),
            2 => new Expr($"({sample}).a", 1), _ => throw new ConversionException("Invalid DBuffer output")
        };
    }

    private void AppendDBufferLookup(StringBuilder shader)
    {
        if (dbufferTextures != 0) shader.AppendLine("#moj_import <lumenfx:material_dbuffer.glsl>");
    }
}
