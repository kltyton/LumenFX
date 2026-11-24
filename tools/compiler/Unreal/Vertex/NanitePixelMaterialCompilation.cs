using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private void AppendNanitePixelInput(StringBuilder shader, string declaration, string interpolation = "")
    {
        if (naniteCoordinates) {
            shader.AppendLine("#if LFX_NANITE_MATERIAL");
            shader.AppendLine(declaration + ";");
            shader.AppendLine("#else");
        }
        shader.AppendLine(interpolation + "in " + declaration + ";");
        if (naniteCoordinates) shader.AppendLine("#endif");
    }

    private Expr MaterialScreenDerivative(Context context, JObject properties, bool horizontal)
    {
        Expr value = RequiredInput(context, properties, "Value");
        string? derivative = horizontal ? value.DerivativeX : value.DerivativeY;
        if (!naniteCoordinates || compilingVertex || derivative is null)
            return PrecisionUnary(value, horizontal ? "dFdx" : "dFdy");
        return new Expr($"lfx_material_{(horizontal ? "ddx" : "ddy")}({value.Code}, {derivative})", value.Width);
    }

    private void AppendNaniteDerivativeFunctions(StringBuilder shader)
    {
        if (!naniteCoordinates) return;
        for (int width = 1; width <= 4; width++) {
            string type = Type(width);
            shader.AppendLine("#if LFX_NANITE_MATERIAL");
            shader.AppendLine($"{type} lfx_material_derivative_value({type} finite, {type} analytic) {{ return analytic; }}");
            shader.AppendLine($"{type} lfx_material_ddx({type} value, {type} analytic) {{ return analytic; }}");
            shader.AppendLine($"{type} lfx_material_ddy({type} value, {type} analytic) {{ return analytic; }}");
            shader.AppendLine("#else");
            shader.AppendLine($"{type} lfx_material_derivative_value({type} finite, {type} analytic) {{ return finite; }}");
            shader.AppendLine($"{type} lfx_material_ddx({type} value, {type} analytic) {{ return dFdx(value); }}");
            shader.AppendLine($"{type} lfx_material_ddy({type} value, {type} analytic) {{ return dFdy(value); }}");
            shader.AppendLine("#endif");
        }
    }

    private static void AppendNaniteOpacityMaskDither(StringBuilder shader)
    {
        shader.AppendLine("""
            #if LFX_NANITE_RASTER_MATERIAL
            uniform sampler2D LfxNaniteScalarBlueNoise;
            float lfx_nanite_dither_mask(float coverage) {
                #if LFX_NANITE_SOFTWARE_RASTER
                ivec2 pixel = ivec2(lfxNaniteSoftwareFragCoord.xy);
                #else
                ivec2 pixel = ivec2(gl_FragCoord.xy);
                #endif
                if (NaniteShadingViewport.z > 0.0) pixel.y = int(round(1.0 / NaniteShadingViewport.y)) - 1 - pixel.y;
                pixel += NaniteShadingPixelOffset.xy;
                int frame = (NaniteShadingInstanceLayout.w >> 1) & 63;
                ivec2 texel = (pixel & ivec2(127)) + ivec2(0, frame * 128);
                return coverage + 0.83 * texelFetch(LfxNaniteScalarBlueNoise, texel, 0).r - 0.5;
            }
            #endif
            """);
    }

    private void AppendNanitePixelInputs(StringBuilder shader)
    {
        if (!naniteCoordinates) return;
        shader.AppendLine("#if LFX_NANITE_MATERIAL");
        shader.AppendLine("    effectPosition = lfxNaniteGeometry.position.xzy * 0.01;");
        shader.AppendLine("    gl_FragDepth = lfxNaniteGeometry.deviceDepth;");
        shader.AppendLine("    vec4 lfxNaniteDx, lfxNaniteDy;");
        if (vertexMaterialUsed)
        {
            shader.AppendLine("    effectPositionNoOffsets = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 0, lfxNaniteDx, lfxNaniteDy).xyz.xzy * 0.01;");
            shader.AppendLine("    lfxNaniteNoOffsetDx = lfxNaniteDx.xyz; lfxNaniteNoOffsetDy = lfxNaniteDy.xyz * -NaniteShadingViewport.z;");
        }
        if (pixelVertexNormalUsed) shader.AppendLine("    effectMaterialVertexNormal = lfxNaniteGeometry.normal;");
        if (pixelVertexTangentUsed) shader.AppendLine("    effectMaterialVertexTangent = lfxNaniteGeometry.tangent;");
        if (pixelBasisMatrixUsed) shader.AppendLine("    effectMaterialBasisDeterminant = lfxNaniteGeometry.tangentSign * lfxNaniteGeometry.instanceDeterminantSign;");
        shader.AppendLine("#if LFX_NANITE_RASTER_MATERIAL && !LFX_NANITE_SOFTWARE_RASTER");
        shader.AppendLine("    effectMeshUV01 = lfxNaniteCoverageUV01; lfxNaniteUvDx[0] = dFdx(effectMeshUV01); lfxNaniteUvDy[0] = dFdy(effectMeshUV01);");
        shader.AppendLine("#else");
        shader.AppendLine("    effectMeshUV01 = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 1, lfxNaniteUvDx[0], lfxNaniteUvDy[0]);");
        shader.AppendLine("#endif");
        shader.AppendLine("    effectMeshUV23 = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 2, lfxNaniteUvDx[1], lfxNaniteUvDy[1]);");
        shader.AppendLine("    effectMeshUV45 = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 3, lfxNaniteUvDx[2], lfxNaniteUvDy[2]);");
        shader.AppendLine("    effectMeshUV67 = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, 4, lfxNaniteUvDx[3], lfxNaniteUvDy[3]);");
        shader.AppendLine("    for (int i = 0; i < 4; i++) lfxNaniteUvDy[i] *= -NaniteShadingViewport.z;");
        shader.AppendLine("    effectUV = effectMeshUV01.xy; effectUV1 = effectMeshUV01.zw;");
        shader.AppendLine("    effectColor = vec4(1.0); effectVertexColor = lfxNaniteGeometry.color;");
        shader.AppendLine("    effectParticle = effectCustom = effectMeshVelocity = vec4(0.0); effectDynamicValid = 0.0; effectParticleCenter = vec3(0.0);");
        shader.AppendLine("    effectUnmirrored = lfxNaniteGeometry.tangentSign;");
        if ((dynamicSlots & 14) != 0) shader.AppendLine("    effectCustom1 = effectCustom2 = effectCustom3 = vec4(0.0);");
        if (subUVUsed) shader.AppendLine("    effectMeshSubUV0 = effectMeshSubUV1 = effectUV; effectSubImageLerp = 0.0;");
        for (int index = 0; index < 8; index++) if ((customizedUVMask & (1 << index)) != 0) {
            string source = index switch { < 2 => "effectMeshUV01", < 4 => "effectMeshUV23", < 6 => "effectMeshUV45", _ => "effectMeshUV67" };
            shader.AppendLine($"    lfxCustomizedUV{index} = {source}.{(index % 2 == 0 ? "xy" : "zw")};");
        }
        int word = 5;
        foreach (var interpolator in vertexInterpolators.Values) {
            string mask = interpolator.Width switch { 1 => ".x", 2 => ".xy", 3 => ".xyz", _ => "" };
            shader.AppendLine($"    {interpolator.Name} = lfx_nanite_vertex_interpolant(lfxNaniteGeometry, {word++}, lfxNaniteDx, lfxNaniteDy){mask};");
            shader.AppendLine($"    {interpolator.Name}_gradientX = lfxNaniteDx{mask}; {interpolator.Name}_gradientY = lfxNaniteDy{mask} * -NaniteShadingViewport.z;");
        }
        shader.AppendLine("#endif");
    }

    private void AppendNaniteWorldDerivatives(StringBuilder shader)
    {
        if (!naniteCoordinates) return;
        string original = worldNoOffsetsUsed && vertexMaterialUsed ? "lfx_material_position_no_offsets().xzy * 100.0" : "effectPosition.xzy * 100.0";
        string naniteDx = vertexMaterialUsed ? "(noOffsets ? lfxNaniteNoOffsetDx : lfxNaniteGeometry.positionDx)" : "lfxNaniteGeometry.positionDx";
        string naniteDy = vertexMaterialUsed ? "(noOffsets ? lfxNaniteNoOffsetDy : lfxNaniteGeometry.positionDy)" : "lfxNaniteGeometry.positionDy";
        shader.AppendLine("#if LFX_NANITE_MATERIAL");
        shader.AppendLine($"vec3 lfx_material_world_dx(bool noOffsets) {{ return {naniteDx}; }}");
        shader.AppendLine($"vec3 lfx_material_world_dy(bool noOffsets) {{ return {naniteDy}; }}");
        shader.AppendLine("#else");
        shader.AppendLine($"vec3 lfx_material_world_dx(bool noOffsets) {{ return dFdx(noOffsets ? ({original}) : effectPosition.xzy * 100.0); }}");
        shader.AppendLine($"vec3 lfx_material_world_dy(bool noOffsets) {{ return dFdy(noOffsets ? ({original}) : effectPosition.xzy * 100.0); }}");
        shader.AppendLine("#endif");
    }
}
