using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private bool pixelNaniteInstanceDataUsed, vertexNaniteInstanceDataUsed;

    private Expr PerInstanceCustomData(Context context, JObject properties, bool vector)
    {
        int index = properties.Value<int?>("DataIndex") ?? 0;
        int width = vector ? 3 : 1;
        Expr fallback = Cast(Input(context, properties["DefaultValue"], () => vector
                ? Vector(Color(properties["ConstDefaultValue"], 3))
                : Constant(SourceAsset.Number(properties["ConstDefaultValue"]))), width);
        if (!meshCoordinates) return fallback;
        if (compilingVertex) vertexNaniteInstanceDataUsed = true;
        else pixelNaniteInstanceDataUsed = true;
        if (!vector) return new Expr($"lfx_material_instance_data({index}, {fallback.Code})", 1);
        return new Expr($"vec3(lfx_material_instance_data({index}, ({fallback.Code}).x), "
                + $"lfx_material_instance_data({index + 1}, ({fallback.Code}).y), "
                + $"lfx_material_instance_data({index + 2}, ({fallback.Code}).z))", 3);
    }

    private void AppendNaniteInstanceMaterialData(StringBuilder shader, bool vertex)
    {
        if (vertex) {
            if (!vertexNaniteInstanceDataUsed) return;
            shader.AppendLine("#if LFX_NANITE_VERTEX_MATERIAL");
            shader.AppendLine("#moj_import <lumenfx:nanite/instance_data.glsl>");
            shader.AppendLine("uint lfxVertexNaniteInstanceId;");
            shader.AppendLine("float lfx_material_instance_data(int index, float fallback) { return lfx_nanite_instance_float(lfxVertexNaniteInstanceId, index, fallback); }");
            shader.AppendLine("#else");
            shader.AppendLine("float lfx_material_instance_data(int index, float fallback) { return fallback; }");
            shader.AppendLine("#endif");
            return;
        }
        if (!naniteCoordinates && !pixelNaniteInstanceDataUsed && !pixelMeshMaterialMatricesUsed && !rasterNaniteTwoSidedUsed) return;
        shader.AppendLine("#if LFX_NANITE_MATERIAL");
        shader.AppendLine("#moj_import <lumenfx:nanite/material_geometry.glsl>");
        shader.AppendLine("LfxNaniteMaterialGeometry lfxNaniteGeometry;");
        if (naniteCoordinates) {
            shader.AppendLine("vec4 lfxNaniteUvDx[4]; vec4 lfxNaniteUvDy[4];");
            if (vertexMaterialUsed) shader.AppendLine("vec3 lfxNaniteNoOffsetDx, lfxNaniteNoOffsetDy;");
        }
        if (pixelNaniteInstanceDataUsed)
            shader.AppendLine("float lfx_material_instance_data(int index, float fallback) { return lfx_nanite_instance_float(lfxNaniteGeometry.instanceId, index, fallback); }");
        shader.AppendLine("#else");
        if (pixelNaniteInstanceDataUsed)
            shader.AppendLine("float lfx_material_instance_data(int index, float fallback) { return fallback; }");
        shader.AppendLine("#endif");
    }
}
