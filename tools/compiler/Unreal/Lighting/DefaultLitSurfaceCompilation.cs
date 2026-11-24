using System.Text;
using LumenFx.Compiler.Unreal.Attributes;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record DefaultLitSurface(Expr BaseColor, Expr Metallic, Expr Specular, Expr Roughness,
        Expr AmbientOcclusion, Expr WorldNormal, Expr Anisotropy, Expr? WorldTangent, int DecalResponse,
        int ShadingModel, string CustomData, Expr? BottomNormal, Expr? EyePlane, Expr? IrisDistance)
    {
        public Expr? DynamicProfile { get; init; }
        public Expr? WorldBentNormal { get; init; }
    }

    private DefaultLitSurface CompileDefaultLitSurface(Context context, SourceObject data, string shadingModel)
    {
        Expr Read(MaterialAttribute attribute, int width) =>
            Cast(MaterialInput(context, data, attribute, () => DefaultAttribute(attribute)).Value, width);
        int decalResponse = CompileSurfaceDecalResponse();
        Expr anisotropy = Read(MaterialAttribute.Anisotropy, 1);
        Expr? worldTangent = null;
        if (!anisotropy.ZeroConstant) {
            Expr tangent = Read(MaterialAttribute.Tangent, 3);
            worldTangent = compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") == false ? tangent
                : new Expr($"({MaterialTangentBasis()} * {tangent.Code})", 3);
        }
        int model = shadingModel switch {
            "MSM_Subsurface" => 2, "MSM_PreintegratedSkin" => 3, "MSM_ClearCoat" => 4,
            "MSM_SubsurfaceProfile" => 5, "MSM_TwoSidedFoliage" => 6,
            "MSM_Hair" => 7, "MSM_Cloth" => 8, "MSM_Eye" => 9, _ => 1
        };
        string customData = "vec4(0.0)";
        Expr? bottomNormal = null;
        Expr? eyePlane = null, irisDistance = null;
        if (model is 2 or 3 or 6 or 8) {
            Expr color = Read(MaterialAttribute.SubsurfaceColor, 3);
            Expr amount = Read(model == 8 ? MaterialAttribute.CustomData0 : MaterialAttribute.Opacity, 1);
            customData = $"vec4(sqrt(clamp({color.Code}, vec3(0.0), vec3(1.0))), clamp({amount.Code}, 0.0, 1.0))";
        } else if (model == 5) {
            Expr opacity = Read(MaterialAttribute.Opacity, 1);
            customData = $"vec4(0.0, 0.0, 0.0, clamp({opacity.Code}, 0.0, 1.0))";
        } else if (model == 7) {
            Expr backlit = Read(MaterialAttribute.CustomData0, 1);
            customData = $"vec4(0.0, 0.0, clamp({backlit.Code}, 0.0, 1.0), 0.0)";
        } else if (model is 4 or 9) {
            Expr data0 = Read(MaterialAttribute.CustomData0, 1), data1 = Read(MaterialAttribute.CustomData1, 1);
            if (model == 4) customData = $"vec4(clamp(vec2({data0.Code}, {data1.Code}), 0.0, 1.0), vec2(0.0))";
            else {
                customData = $"vec4(0.0, 0.0, 0.0, 1.0 - clamp({data0.Code}, 0.0, 1.0))";
                irisDistance = data1;
                var tangentOutput = FindUniqueCustomOutput(context, data, "MaterialExpressionTangentOutput");
                var eye = MaterialInput(context, data, MaterialAttribute.CustomEyeTangent, () => DefaultAttribute(MaterialAttribute.CustomEyeTangent));
                if (tangentOutput is not null || eye.Connected)
                    eyePlane = tangentOutput is null ? Cast(eye.Value, 3) : Cast(RequiredInput(context, tangentOutput.Properties, "Input"), 3);
            }
            bottomNormal = CompileClearCoatBottomNormal(context, data);
        }
        return new DefaultLitSurface(Read(MaterialAttribute.BaseColor, 3), Read(MaterialAttribute.Metallic, 1),
            Read(MaterialAttribute.Specular, 1), Read(MaterialAttribute.Roughness, 1),
            Read(MaterialAttribute.AmbientOcclusion, 1), PixelWorldNormal(), anisotropy, worldTangent, decalResponse, model,
            customData, bottomNormal, eyePlane, irisDistance) { WorldBentNormal = CompileBentNormal(context, data) };
    }

    private Expr? CompileClearCoatBottomNormal(Context context, SourceObject data)
    {
        var output = FindUniqueCustomOutput(context, data, "MaterialExpressionClearCoatNormalCustomOutput");
        var resolved = MaterialInput(context, data, MaterialAttribute.ClearCoatBottomNormal,
            () => DefaultAttribute(MaterialAttribute.ClearCoatBottomNormal));
        if (output is null && !resolved.Connected) return null;
        Expr normal = Cast(output is null ? resolved.Value : RequiredInput(context, output.Properties, "Input"), 3);
        return compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") == false ? normal
                : new Expr($"normalize({MaterialTangentBasis()} * {normal.Code})", 3);
    }

    private Expr? CompileBentNormal(Context context, SourceObject data)
    {
        var output = FindUniqueCustomOutput(context, data, "MaterialExpressionBentNormalCustomOutput");
        var attribute = MaterialInput(context, data, MaterialAttribute.BentNormal,
            () => DefaultAttribute(MaterialAttribute.BentNormal));
        if (output is null && !attribute.Connected) return null;
        var normal = Cast(output is null ? attribute.Value : RequiredInput(context, output.Properties, "Input"), 3);
        return compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") == false ? normal
            : new Expr($"normalize({MaterialTangentBasis()} * {normal.Code})", 3);
    }

    private string CompileSubsurfaceProfile(SourceAsset rootAsset, SourceObject rootMaterial)
    {
        // UMaterialInstance::GetSubsurfaceProfile_Internal stops on the first override, including an explicit null.
        foreach (var instance in materialInstanceSources)
            if (instance.Instance.Properties.Value<bool?>("bOverrideSubsurfaceProfile") == true)
                return ProfilePath(instance.Asset, instance.Instance);
        return ProfilePath(rootAsset, rootMaterial);

        static string ProfilePath(SourceAsset owner, SourceObject material)
        {
            int reference = material.Properties.Value<int?>("SubsurfaceProfile") ?? 0;
            return reference == 0 ? string.Empty : owner.ObjectPath(reference);
        }
    }

    private int CompileSurfaceDecalResponse()
    {
        string response = (compiledMaterialProperties.Value<string>("MaterialDecalResponse") ?? "MDR_ColorNormalRoughness").Split("::").Last();
        int result = response switch {
            "MDR_None" => 0, "MDR_Color" => 1, "MDR_Normal" => 2, "MDR_ColorNormal" => 3,
            "MDR_Roughness" => 4, "MDR_ColorRoughness" => 5, "MDR_NormalRoughness" => 6, "MDR_ColorNormalRoughness" => 7,
            _ => throw new ConversionException($"Unknown material decal response {response}")
        };
        dbufferTextures |= result;
        viewUniformUsed |= result != 0;
        return result;
    }

    private static void AppendDefaultLitSurfaceOutput(StringBuilder shader, DefaultLitSurface surface)
    {
        shader.AppendLine("#if LFX_PARTICLE_SURFACE && !LFX_PARTICLE_MOTION");
        shader.AppendLine($"    lfx_write_lit_surface({surface.BaseColor.Code}, {surface.Metallic.Code}, {surface.Specular.Code},");
        shader.AppendLine($"        {surface.Roughness.Code}, {surface.AmbientOcclusion.Code}, ({surface.WorldNormal.Code}).xzy, effectPosition, {surface.DecalResponse},");
        string tangent = surface.WorldTangent is { } worldTangent ? worldTangent.Code : "vec3(0.0)";
        string bottomNormal = surface.BottomNormal is { } bottom ? bottom.Code : "vec3(0.0)";
        if (surface.WorldBentNormal is { } bent) {
            string plane = surface.EyePlane?.Code ?? "vec3(0.0)";
            string distance = surface.IrisDistance?.Code ?? "0.0";
            string profileIndex = surface.DynamicProfile is { } dynamicProfile ? $"int({dynamicProfile.Code})" : "LFX_PARTICLE_PROFILE_INDEX";
            shader.AppendLine($"        {surface.Anisotropy.Code}, ({tangent}).xzy, {surface.ShadingModel}, {surface.CustomData},");
            shader.AppendLine($"        ({bottomNormal}).xzy, ({plane}).xzy, {distance}, {profileIndex}, ({bent.Code}).xzy, true);");
        } else if (surface.DynamicProfile is { } profile) {
            shader.AppendLine($"        {surface.Anisotropy.Code}, ({tangent}).xzy, {surface.ShadingModel}, {surface.CustomData},");
            shader.AppendLine($"        ({bottomNormal}).xzy, vec3(0.0), 0.0, int({profile.Code}));");
        } else if (surface.IrisDistance is { } irisDistance) {
            string eyePlane = surface.EyePlane is { } plane ? plane.Code : "vec3(0.0)";
            shader.AppendLine($"        {surface.Anisotropy.Code}, ({tangent}).xzy, {surface.ShadingModel}, {surface.CustomData},");
            shader.AppendLine($"        ({bottomNormal}).xzy, ({eyePlane}).xzy, {irisDistance.Code});");
        } else
            shader.AppendLine($"        {surface.Anisotropy.Code}, ({tangent}).xzy, {surface.ShadingModel}, {surface.CustomData}, ({bottomNormal}).xzy);");
        shader.AppendLine("#endif");
    }
}
