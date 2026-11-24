using System.Text;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private bool meshMaterialMatricesUsed, pixelMeshMaterialMatricesUsed;

    private Expr MeshMaterialTransform(MaterialSpace space, Expr input, bool toWorld, bool position)
    {
        if (!meshCoordinates) {
            if (space != MaterialSpace.Particle)
                throw new ConversionException("Instance material transforms require the component primitive matrices");
            // Sprite and Ribbon initialize ParticleToWorld/WorldToParticle to zero and never assign them.
            if (toWorld && position) {
                materialViewUsed |= 8;
                return new Expr("LfxLWC3(vec3(0.0), vec3(0.0))", 3, LargeWorld: true, ZeroConstant: true);
            }
            return Vector([0, 0, 0]);
        }
        meshMaterialMatricesUsed = true;
        pixelMeshMaterialMatricesUsed |= !compilingVertex;
        bool previous = toWorld && compilingVertex && compilingPreviousFrame && space == MaterialSpace.Instance;
        string prefix = compilingVertex ? previous ? "lfxVertexPreviousInstance" : "lfxVertexInstance" : "effectMaterialInstance";
        string linear = prefix + "Linear", center = prefix + "Center";
        if (toWorld) {
            string result = $"({linear} * {input.Code})";
            return position ? AbsolutePosition($"({result} + {center})", previous) : new Expr(result, 3);
        }
        string value = input.Code;
        if (position) {
            Expr absoluteCenter = AbsolutePosition(center, previous);
            value = $"lfx_lwc_demote(lfx_lwc_subtract({Promote(input)}, {absoluteCenter.RawCode}))";
        }
        string inverseScale = compilingVertex ? "lfxVertexInstanceInverseScale" : "effectMaterialInstanceInverseScale";
        return new Expr($"(lfx_material_instance_inverse({linear}, {inverseScale}) * {value})", 3);
    }

    private void AppendMeshMaterialMatrices(StringBuilder shader, bool vertex)
    {
        if (!meshMaterialMatricesUsed) return;
        if (!vertex) shader.AppendLine("#moj_import <lumenfx:niagara_mesh_transform.glsl>");
        shader.AppendLine(vertex ? "#if LFX_NANITE_VERTEX_MATERIAL" : "#if LFX_NANITE_MATERIAL");
        shader.AppendLine("mat3 lfx_material_instance_inverse(mat3 linear, vec3 inverseScale) { return inverse(linear); }");
        shader.AppendLine("#else");
        shader.AppendLine("mat3 lfx_material_instance_inverse(mat3 linear, vec3 inverseScale) { return lfx_mesh_inverse_linear(linear, inverseScale); }");
        shader.AppendLine("#endif");
        if (vertex) {
            shader.AppendLine("#define LFX_MATERIAL_INSTANCE_TRANSFORMS 1");
            shader.AppendLine("mat3 lfxVertexInstanceLinear; vec3 lfxVertexInstanceCenter; vec3 lfxVertexInstanceInverseScale;");
            shader.AppendLine("#if LFX_PARTICLE_MOTION\nmat3 lfxVertexPreviousInstanceLinear; vec3 lfxVertexPreviousInstanceCenter;\n#endif");
        }
        if (pixelMeshMaterialMatricesUsed) {
            if (vertex) shader.AppendLine("#if LFX_NANITE_VERTEX_MATERIAL\nmat3 effectMaterialInstanceLinear; vec3 effectMaterialInstanceCenter; vec3 effectMaterialInstanceInverseScale;\n#else");
            string direction = vertex ? "out" : "in";
            if (!vertex) {
                shader.AppendLine("#if LFX_NANITE_MATERIAL");
                shader.AppendLine("mat3 effectMaterialInstanceLinear; vec3 effectMaterialInstanceCenter; vec3 effectMaterialInstanceInverseScale;");
                shader.AppendLine("#else");
            }
            shader.AppendLine($"flat {direction} mat3 effectMaterialInstanceLinear; flat {direction} vec3 effectMaterialInstanceCenter; flat {direction} vec3 effectMaterialInstanceInverseScale;");
            if (!vertex) shader.AppendLine("#endif");
            else shader.AppendLine("#endif");
        }
    }

    private void AppendNaniteMeshMaterialMatrices(StringBuilder shader)
    {
        if (!naniteCoordinates && !pixelMeshMaterialMatricesUsed && !pixelNaniteInstanceDataUsed && !rasterNaniteTwoSidedUsed) return;
        shader.AppendLine("#if LFX_NANITE_MATERIAL");
        shader.AppendLine("#if LFX_NANITE_SOFTWARE_RASTER");
        shader.AppendLine("    if (!lfx_nanite_material_geometry(lfxNaniteGeometry)) return false;");
        shader.AppendLine("#else");
        shader.AppendLine("    if (!lfx_nanite_material_geometry(lfxNaniteGeometry)) discard;");
        shader.AppendLine("#endif");
        if (pixelMeshMaterialMatricesUsed)
            shader.AppendLine("    lfx_nanite_instance_transform(lfxNaniteGeometry.instanceId, effectMaterialInstanceLinear, effectMaterialInstanceCenter, effectMaterialInstanceInverseScale);");
        shader.AppendLine("#endif");
    }
}
