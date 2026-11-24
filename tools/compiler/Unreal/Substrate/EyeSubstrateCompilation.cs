using LumenFx.Compiler.Unreal.Attributes;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private const int SubstrateEyeSurfaceModel = 14;

    private sealed record EyeSubstrate(Expr Diffuse, Expr Roughness, Expr IrisMask, Expr IrisDistance,
        Expr Cornea, Expr Iris, Expr Plane, string? Profile, Expr Emissive, Expr Coverage) : SubstrateSurface(Emissive, Coverage);

    private EyeSubstrate CompileSubstrateEye(Context context, JObject properties)
    {
        return new EyeSubstrate(SubstrateFloat(context, properties, "DiffuseColor", 0, 3),
            SubstrateFloat(context, properties, "Roughness", .5), SubstrateFloat(context, properties, "IrisMask", 0),
            SubstrateFloat(context, properties, "IrisDistance", 0), SubstrateWorldBasis(context, properties["CorneaNormal"], true),
            SubstrateWorldNormal(context, properties["IrisNormal"]), SubstrateWorldNormal(context, properties["IrisPlaneNormal"]),
            SubstrateEyeProfile(context, properties), SubstrateFloat(context, properties, "EmissiveColor", 0, 3), Constant(1));
    }

    private EyeSubstrate CompileLegacySubstrateEye(Context context, JObject properties, bool attributes,
        Func<MaterialAttribute, ResolvedAttribute> read, Expr emissive)
    {
        Expr Value(ResolvedAttribute value, int width) => Cast(DemoteLargeWorld(value.Value), width);
        var normalInput = read(MaterialAttribute.Normal);
        Expr cornea = normalInput.Connected ? SubstrateWorldBasis(Value(normalInput, 3)) : VertexBasis(false);
        Expr iris = cornea, tangent = cornea;
        bool tangentConnected;
        if (attributes) {
            var normal = read(MaterialAttribute.ClearCoatBottomNormal);
            var eyeTangent = read(MaterialAttribute.CustomEyeTangent);
            if (normal.Connected) iris = Value(normal, 3);
            tangentConnected = eyeTangent.Connected;
            if (tangentConnected) tangent = Value(eyeTangent, 3);
        } else {
            if (Connected(properties["ClearCoatNormal"])) iris = SubstrateWorldNormal(context, properties["ClearCoatNormal"]);
            tangentConnected = Connected(properties["CustomTangent"]);
            if (tangentConnected) tangent = Cast(DemoteLargeWorld(RequiredInput(context, properties, "CustomTangent")), 3);
        }
        Expr plane = tangentConnected ? tangent : iris;
        Expr legacyNormal = rasterNormalSource is { } root
                && FindUniqueCustomOutput(root.Context, root.Data, "MaterialExpressionTangentOutput") is not null ? tangent : cornea;
        SubstrateInclude("legacy_eye_normals");
        Expr Normal(Expr preferred) => Shared(new Expr($"lfx_substrate_legacy_eye_normal({preferred.Code}, {legacyNormal.Code})", 3));
        return new EyeSubstrate(Value(read(MaterialAttribute.BaseColor), 3), Value(read(MaterialAttribute.Roughness), 1),
            Value(read(MaterialAttribute.CustomData0), 1), Value(read(MaterialAttribute.CustomData1), 1),
            cornea, Normal(iris), Normal(plane), SubstrateEyeProfile(context, properties), emissive, Constant(1));
    }

    private static string? SubstrateEyeProfile(Context context, JObject properties)
    {
        int profile = properties.Value<int?>("SubsurfaceProfile") ?? 0;
        return profile == 0 ? null : context.Asset.ObjectPath(profile);
    }

    private DefaultLitSurface CompileEyeSurface(EyeSubstrate eye, Expr ao)
        => new(eye.Diffuse, Constant(0), Constant(.028 / .08), eye.Roughness, ao, eye.Cornea,
            Constant(0), null, CompileSurfaceDecalResponse(), SubstrateEyeSurfaceModel,
            $"vec4(0.0, 0.0, {eye.IrisMask.Code}, {eye.Coverage.Code})", eye.Iris, eye.Plane, eye.IrisDistance);
}
