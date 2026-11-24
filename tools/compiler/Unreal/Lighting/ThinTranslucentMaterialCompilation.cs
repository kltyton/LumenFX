using System.Text;
using LumenFx.Compiler.Unreal.Attributes;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record ThinTranslucentSurface(Expr TransmittanceColor, Expr SurfaceCoverage,
        Expr WorldNormal, Expr View, Expr SpecularColor);

    private ThinTranslucentSurface CompileThinTranslucentSurface(Context context, SourceObject data)
    {
        SourceObject? customOutput = FindUniqueCustomOutput(context, data, "MaterialExpressionThinTranslucentMaterialOutput");
        Expr transmittanceColor = customOutput is not null && Connected(customOutput.Properties["TransmittanceColor"])
            ? Cast(DemoteLargeWorld(Input(context, customOutput.Properties["TransmittanceColor"],
                () => Vector(new[] { 0.5, 0.5, 0.5 }))), 3)
            : Cast(DemoteLargeWorld(MaterialInput(context, data, MaterialAttribute.TransmittanceColor,
                () => Vector(new[] { 0.5, 0.5, 0.5 })).Value), 3);
        Expr surfaceCoverage = customOutput is not null && Connected(customOutput.Properties["SurfaceCoverage"])
            ? Cast(DemoteLargeWorld(Input(context, customOutput.Properties["SurfaceCoverage"], () => Constant(1))), 1)
            : Cast(DemoteLargeWorld(MaterialInput(context, data, MaterialAttribute.ThinTranslucentSurfaceCoverage,
                () => Constant(1)).Value), 1);

        Expr authoredBaseColor = Cast(MaterialInput(context, data, MaterialAttribute.BaseColor,
            () => DefaultAttribute(MaterialAttribute.BaseColor)).Value, 3);
        Expr baseColor = new($"clamp({authoredBaseColor.Code}, 0.0, 1.0)", 3);
        Expr metallic = Cast(MaterialInput(context, data, MaterialAttribute.Metallic,
            () => DefaultAttribute(MaterialAttribute.Metallic)).Value, 1);
        string metal = $"clamp({metallic.Code}, 0.0, 1.0)";
        Expr specular = Cast(MaterialInput(context, data, MaterialAttribute.Specular,
            () => DefaultAttribute(MaterialAttribute.Specular)).Value, 1);
        Expr f0 = Shared(new Expr($"mix(vec3(0.08 * clamp({specular.Code}, 0.0, 1.0)), {baseColor.Code}, {metal})", 3));

        return new ThinTranslucentSurface(
            Shared(transmittanceColor),
            Shared(surfaceCoverage),
            Shared(PixelWorldNormal()),
            Shared(CameraVector()),
            f0);
    }

    private static void AppendThinTranslucentColor(StringBuilder shader, ThinTranslucentSurface thin, bool hasLighting)
    {
        shader.AppendLine($"    float lfxThinNoV = clamp(abs(dot({thin.WorldNormal.Code}, {thin.View.Code})) + 1e-5, 0.0, 1.0);");
        shader.AppendLine("    float lfxThinPathLength = 1.0 / lfxThinNoV;");
        shader.AppendLine("    float lfxThinFc = pow(clamp(1.0 - lfxThinNoV, 0.0, 1.0), 5.0);");
        shader.AppendLine($"    vec3 lfxThinF0 = {thin.SpecularColor.Code};");
        shader.AppendLine("    vec3 lfxThinFresnel = clamp(50.0 * lfxThinF0.g, 0.0, 1.0) * lfxThinFc + (1.0 - lfxThinFc) * lfxThinF0;");
        shader.AppendLine($"    vec3 lfxThinTc = clamp({thin.TransmittanceColor.Code}, 0.0, 1.0);");
        shader.AppendLine("    vec3 lfxThinAbsorption = mix(vec3(0.0), exp(log(max(lfxThinTc, vec3(1e-6))) * lfxThinPathLength), greaterThan(lfxThinTc, vec3(0.0)));");
        shader.AppendLine("    vec3 lfxThinTransmittance = lfxThinAbsorption * (vec3(1.0) - lfxThinFresnel) * (vec3(1.0) - lfxThinFresnel) * (1.0 - opacity);");
        shader.AppendLine($"    float lfxThinCoverage = clamp({thin.SurfaceCoverage.Code}, 0.0, 1.0);");
        shader.AppendLine(hasLighting
            ? "    vec3 lfxThinLuminance = emission * lfxThinCoverage;"
            : "    vec3 lfxThinLuminance = (emission * opacity) * lfxThinCoverage;");
        shader.AppendLine("    vec4 lfxThinFog = lfx_material_fog();");
        shader.AppendLine("    vec3 lfxThinAdd = lfxThinCoverage * lfxThinFog.rgb + lfxThinFog.a * lfxThinLuminance;");
        shader.AppendLine("    vec3 lfxThinMul = mix(vec3(1.0), lfxThinFog.a * lfxThinTransmittance, lfxThinCoverage);");
        shader.AppendLine("""
#if LFX_SUBSTRATE_MODULATION_ONLY && !LFX_PARTICLE_MOTION
    fragColor = vec4(lfxThinMul, 1.0);
#elif LFX_SUBSTRATE_DUAL_SOURCE && !LFX_PARTICLE_MOTION
    fragColor = vec4(lfxThinAdd, 0.0);
    lfxDualTransmittance = vec4(lfxThinMul, 1.0);
#else
    float lfxThinAlpha = clamp(1.0 - dot(lfxThinMul, vec3(1.0 / 3.0)), 0.0, 1.0);
    fragColor = vec4(lfxThinAdd, lfxThinAlpha);
#endif
""");
    }

    private Expr? CompileMotionVectorWorldOffset(Context context, SourceObject data)
    {
        SourceObject? customOutput = FindUniqueCustomOutput(context, data, "MaterialExpressionMotionVectorWorldOffsetOutput");
        Expr? offset = null;
        if (customOutput is not null && Connected(customOutput.Properties["Input"]))
        {
            offset = Cast(DemoteLargeWorld(Input(context, customOutput.Properties["Input"], () => Cast(Constant(0), 3))), 3);
        }
        else
        {
            ResolvedAttribute attr = MaterialInput(context, data, MaterialAttribute.MotionVectorWorldOffset, () => Cast(Constant(0), 3));
            if (attr.Connected) offset = Cast(DemoteLargeWorld(attr.Value), 3);
        }
        if (offset is null || offset.ZeroConstant) return null;
        viewProjectionUsed = true;
        return Shared(offset);
    }
}
