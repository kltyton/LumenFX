using System.Text;
using LumenFx.Compiler.Unreal.Attributes;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record RuntimeVirtualTextureOutput(int AttributeMask, Expr BaseColor, Expr Specular, Expr Roughness,
        Expr Normal, Expr Height, Expr Opacity, Expr Mask, Expr Displacement, Expr Mask4);

    private bool compilingVirtualTextureOutput;
    private bool runtimeVirtualTextureWriter;
    private bool virtualTexturePageViewUsed;

    private string VirtualTextureReplaceInput(JObject properties)
    {
        if (!Connected(properties["Default"]) || !Connected(properties["VirtualTextureOutput"]))
            throw new ConversionException("RuntimeVirtualTextureReplace requires both Default and VirtualTextureOutput");
        return compilingVirtualTextureOutput ? "VirtualTextureOutput" : "Default";
    }

    private RuntimeVirtualTextureOutput CompileVirtualTextureOutput(Context context, SourceObject data)
    {
        const int baseColorBit = 1, normalBit = 2, roughnessBit = 4, specularBit = 8, maskBit = 16;
        const int mask4Bit = 32, heightBit = 64, displacementBit = 128;
        Expr defaultNormal = Vector([0, 0, 1]);
        Expr defaultMask4 = Vector([0, 0, 0, 0]);
        var output = FindUniqueCustomOutput(context, data, "MaterialExpressionRuntimeVirtualTextureOutput");
        if (output is not null) {
            JObject pins = output.Properties;
            int attributes = 0;
            foreach (var (name, bit) in new[] {
                ("BaseColor", baseColorBit), ("Normal", normalBit), ("Roughness", roughnessBit), ("Specular", specularBit),
                ("Mask", maskBit), ("WorldHeight", heightBit), ("Displacement", displacementBit), ("Mask4", mask4Bit)
            }) if (Connected(pins[name])) attributes |= bit;
            if (attributes != 0) {
                compilingVirtualTextureOutput = true;
                try {
                    Expr Pin(string name, Expr fallback) => Input(context, pins[name], () => fallback);
                    return new(attributes, Pin("BaseColor", Vector([0, 0, 0])), Pin("Specular", Constant(.5)),
                            Pin("Roughness", Constant(.5)), Pin("Normal", defaultNormal), Pin("WorldHeight", Constant(0)),
                            Pin("Opacity", Constant(1)), Pin("Mask", Constant(1)), Pin("Displacement", Constant(0)),
                            Pin("Mask4", defaultMask4));
                } finally {
                    compilingVirtualTextureOutput = false;
                }
            }
        }

        Expr Surface(MaterialAttribute attribute, Expr fallback) => MaterialInput(context, data, attribute, () => fallback).Value;
        Expr normal = Surface(MaterialAttribute.Normal, defaultNormal);
        if (compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") != false)
            normal = TransformMaterialValue(MaterialSpace.Tangent, MaterialSpace.World, Cast(normal, 3), false);
        return new(255, Surface(MaterialAttribute.BaseColor, Vector([0, 0, 0])), Surface(MaterialAttribute.Specular, Constant(.5)),
                Surface(MaterialAttribute.Roughness, Constant(.5)), normal, Swizzle(WorldPosition(new JObject()), "b"),
                Surface(MaterialAttribute.Opacity, Constant(1)), Constant(0), Constant(0), defaultMask4);
    }

    private Expr VirtualTexturePageView(string property, bool inverse)
    {
        bool derivative = property == "MEVP_RuntimeVirtualTextureOutputDerivative";
        int width = derivative ? 2 : 1;
        if (!runtimeVirtualTextureWriter) {
            string zeroOrInverse = inverse ? "uintBitsToFloat(0x7f800000u)" : "0.0";
            return new Expr(derivative ? $"vec2({zeroOrInverse})" : zeroOrInverse, width);
        }
        virtualTexturePageViewUsed = true;
        string value = property switch {
            "MEVP_RuntimeVirtualTextureOutputLevel" => "LfxRvtPageMip.x",
            "MEVP_RuntimeVirtualTextureMaxLevel" => "LfxRvtPageMip.y",
            "MEVP_RuntimeVirtualTextureOutputDerivative" => "LfxRvtPageMip.zw",
            _ => throw new ConversionException($"Unknown runtime virtual texture view property {property}")
        };
        return new Expr(inverse ? $"({(derivative ? "vec2(1.0)" : "1.0")} / {value})" : value, width);
    }

    private Expr RuntimeVirtualTextureCustomData() => new(compilingVirtualTextureOutput ? "LfxRvtCustomData" : "vec4(0.0)", 4);

    private static void AppendVirtualTexturePageView(StringBuilder shader)
    {
        shader.AppendLine("layout(std140) uniform LfxRuntimeVirtualTexturePage {");
        shader.AppendLine("    vec4 LfxRvtPageMip; vec4 LfxRvtHeight; vec4 LfxRvtFixedColor; vec4 LfxRvtMipDebug; vec4 LfxRvtCustomData;");
        shader.AppendLine("};");
    }

    private static void AppendVirtualTextureOutputDeclarations(StringBuilder shader)
    {
        shader.AppendLine("#moj_import <lumenfx:rvt_material_output.glsl>");
        shader.AppendLine("layout(location=0) out vec4 lfxRvtLayer0;");
        shader.AppendLine("#if LFX_RVT_RASTER_LAYERS > 1\nlayout(location=1) out vec4 lfxRvtLayer1;\n#endif");
        shader.AppendLine("#if LFX_RVT_RASTER_LAYERS > 2\nlayout(location=2) out vec4 lfxRvtLayer2;\n#endif");
    }

    private void AppendVirtualTextureOutput(StringBuilder shader, RuntimeVirtualTextureOutput output)
    {
        var height = Cast(output.Height, 1);
        string relativeHeight = height.LargeWorld
                ? $"lfx_lwc_demote(lfx_lwc_subtract({height.RawCode}, LfxLWC1(LfxRvtHeight.x, LfxRvtHeight.y)))"
                : $"(({height.Code} - LfxRvtHeight.y) - LfxRvtHeight.x * 2097152.0)";
        shader.AppendLine($"    LfxRvtMaterial surface = LfxRvtMaterial({Cast(output.BaseColor, 3).Code}, {Cast(output.Specular, 1).Code},");
        shader.AppendLine($"        {Cast(output.Roughness, 1).Code}, {Cast(output.Normal, 3).Code}, clamp({relativeHeight} * LfxRvtHeight.z, 0.0, 1.0),");
        shader.AppendLine($"        {Cast(output.Opacity, 1).Code}, {Cast(output.Mask, 1).Code}, {Cast(output.Displacement, 1).Code}, {Cast(output.Mask4, 4).Code});");
        shader.AppendLine("    if (LfxRvtFixedColor.w > 0.0) {");
        shader.AppendLine("        surface.baseColor = mix(surface.baseColor, LfxRvtFixedColor.xyz, LfxRvtFixedColor.w);");
        shader.AppendLine("        surface.displacement = mix(surface.displacement, LfxRvtFixedColor.x, LfxRvtFixedColor.w);");
        shader.AppendLine("    }");
        shader.AppendLine("    vec2 pagePosition = gl_FragCoord.xy * (2.0 * LfxRvtHeight.w) - 1.0;");
        shader.AppendLine("    if (LfxRvtMipDebug.w < 1.0 && any(greaterThan(abs(pagePosition), vec2(LfxRvtMipDebug.w)))) surface.baseColor = LfxRvtMipDebug.xyz;");
        shader.AppendLine("    lfxRvtLayer0 = lfx_rvt_material_raster(surface, LFX_RVT_LAYOUT, 0);");
        shader.AppendLine("#if LFX_RVT_RASTER_LAYERS > 1\n    lfxRvtLayer1 = lfx_rvt_material_raster(surface, LFX_RVT_LAYOUT, 1);\n#endif");
        shader.AppendLine("#if LFX_RVT_RASTER_LAYERS > 2\n    lfxRvtLayer2 = lfx_rvt_material_raster(surface, LFX_RVT_LAYOUT, 2);\n#endif");
    }
}
