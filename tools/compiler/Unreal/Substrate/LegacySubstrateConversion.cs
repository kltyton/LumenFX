using System.Globalization;
using LumenFx.Compiler.Unreal.Attributes;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    // Serialized EMaterialShadingModel values from EngineTypes.h.
    private enum LegacyShadingModel
    {
        MSM_Unlit, MSM_DefaultLit, MSM_Subsurface, MSM_PreintegratedSkin, MSM_ClearCoat, MSM_SubsurfaceProfile,
        MSM_TwoSidedFoliage, MSM_Hair, MSM_Cloth, MSM_Eye, MSM_SingleLayerWater, MSM_ThinTranslucent
    }

    private static Expr SourceShadingModel(JToken? value)
    {
        string name = (value?.Value<string>() ?? nameof(LegacyShadingModel.MSM_DefaultLit)).Split("::").Last();
        if (!Enum.TryParse(name, out LegacyShadingModel model) || !Enum.IsDefined(model))
            throw new ConversionException($"Unknown source shading model {name}");
        return Constant((int)model);
    }

    private Expr DefaultSourceShadingModel()
    {
        if (Enum.TryParse(compiledShadingModel, out LegacyShadingModel model) && Enum.IsDefined(model))
            return Constant((int)model);
        return Constant((int)LegacyShadingModel.MSM_DefaultLit);
    }

    private SubstrateSurface CompileLegacySubstrate(Context context, SourceObject node, Expr? thickness)
    {
        JObject properties = node.Properties;
        bool fromAttributes = node.ClassName == "MaterialExpressionSubstrateConvertMaterialAttributes";
        JToken? attributes = properties["MaterialAttributes"];

        ResolvedAttribute Read(MaterialAttribute attribute)
        {
            if (fromAttributes) return AttributeInput(context, attributes, attribute);
            string name = attribute == MaterialAttribute.SubsurfaceColor ? "SubSurfaceColor" : MaterialAttributes.Get(attribute).InputName;
            JToken? pin = properties[name];
            return new ResolvedAttribute(Input(context, pin, () => attribute == MaterialAttribute.SubsurfaceColor
                ? Cast(Constant(1), 3) : DefaultAttribute(attribute)), Connected(pin));
        }

        string overrideName = (properties.Value<string>("ShadingModelOverride") ?? "MSM_DefaultLit").Split("::").Last();
        var modelAttribute = fromAttributes ? AttributeConnection(context, attributes, MaterialAttribute.ShadingModel) : null;
        Expr? modelInput = fromAttributes ? modelAttribute is { Connected: true } ? modelAttribute.Value : null
            : Connected(properties["ShadingModel"]) ? Input(context, properties["ShadingModel"], () => Constant(1)) : null;
        Expr modelValue = modelInput ?? (overrideName == "MSM_FromMaterialExpression"
            ? DefaultAttribute(MaterialAttribute.ShadingModel) : SourceShadingModel(properties["ShadingModelOverride"]));
        if (!double.TryParse(modelValue.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double modelNumber)
            || modelNumber < 0 || modelNumber > (int)LegacyShadingModel.MSM_ThinTranslucent || modelNumber != Math.Truncate(modelNumber))
            throw new ConversionException($"Substrate dynamic legacy shading models require their mixed closure topology: {context.Asset.Name}:{node.Name}");
        var model = (LegacyShadingModel)(int)modelNumber;

        Expr Value(ResolvedAttribute input, int width) => Cast(DemoteLargeWorld(input.Value), width);
        Expr Scalar(MaterialAttribute attribute) => Value(Read(attribute), 1);
        Expr Vector3(MaterialAttribute attribute) => Value(Read(attribute), 3);
        Expr emissive = Vector3(MaterialAttribute.EmissiveColor);
        if (model == LegacyShadingModel.MSM_Unlit) {
            Expr white = Cast(Constant(1), 3), black = Cast(Constant(0), 3);
            if (compiledBlendMode is "BLEND_Opaque" or "BLEND_Masked" or "BLEND_AlphaComposite")
                return new UnlitSubstrate(emissive, black, Constant(1));
            if (compiledBlendMode is "BLEND_Modulate" or "BLEND_ColoredTransmittanceOnly")
                return new UnlitSubstrate(black, emissive, Constant(1)) { AllowBrightening = true };
            Expr opacity = Shared(Saturate(Scalar(MaterialAttribute.Opacity)));
            if (compiledBlendMode == "BLEND_Additive")
                return new UnlitSubstrate(Shared(Arithmetic(emissive, opacity, "*")), white, Constant(1));
            if (compiledBlendMode == "BLEND_AlphaHoldout")
                return new UnlitSubstrate(black, Cast(Arithmetic(Constant(1), opacity, "-"), 3), Constant(1));
            return new UnlitSubstrate(emissive, black, opacity);
        }
        if (model == LegacyShadingModel.MSM_Hair) {
            var hairNormal = Read(MaterialAttribute.Normal);
            Expr fiber = hairNormal.Connected ? SubstrateWorldBasis(Value(hairNormal, 3)) : VertexBasis(false);
            return new HairSubstrate(Vector3(MaterialAttribute.BaseColor), Scalar(MaterialAttribute.Metallic),
                Scalar(MaterialAttribute.Specular), Scalar(MaterialAttribute.Roughness), Scalar(MaterialAttribute.CustomData0),
                fiber, emissive, Constant(1));
        }
        if (model == LegacyShadingModel.MSM_Eye)
            return CompileLegacySubstrateEye(context, properties, fromAttributes, Read, emissive);
        bool wrapModel = model is LegacyShadingModel.MSM_Subsurface or LegacyShadingModel.MSM_TwoSidedFoliage;
        if (!wrapModel && model is not (LegacyShadingModel.MSM_DefaultLit or LegacyShadingModel.MSM_Cloth or LegacyShadingModel.MSM_ClearCoat))
            throw new ConversionException($"Substrate legacy {model} requires its native closure evaluator: {context.Asset.Name}:{node.Name}");

        var baseColor = Read(MaterialAttribute.BaseColor);
        var metallic = Read(MaterialAttribute.Metallic);
        var specular = Read(MaterialAttribute.Specular);
        var roughness = Read(MaterialAttribute.Roughness);
        var normal = Read(MaterialAttribute.Normal);
        var emission = Read(MaterialAttribute.EmissiveColor);
        Expr worldNormal = normal.Connected ? SubstrateWorldBasis(Value(normal, 3)) : VertexBasis(false);
        Expr anisotropy = Scalar(MaterialAttribute.Anisotropy);
        Expr? worldTangent = null;
        if (!anisotropy.ZeroConstant) {
            var tangent = Read(MaterialAttribute.Tangent);
            worldTangent = tangent.Connected ? SubstrateWorldBasis(Value(tangent, 3)) : VertexBasis(true);
        }
        Expr metal = Shared(Saturate(Value(metallic, 1)));
        Expr baseValue = Value(baseColor, 3);
        Expr diffuse = SlabDiffuseAlbedo(baseValue, metal);
        Expr f0 = SlabMetalnessF0(baseValue, metal, Value(specular, 1));
        Expr rough = Value(roughness, 1);
        Expr zero = Constant(0);
        Expr fuzzAmount = zero, fuzzRoughness = rough, fuzzColor = Cast(zero, 3);
        Expr secondRoughness = zero, secondWeight = zero, simpleCoat = zero;
        Expr? coatNormal = null;
        Expr meanFreePath = Cast(zero, 3);
        SlabWrap? wrap = null;
        if (model == LegacyShadingModel.MSM_Cloth) {
            fuzzAmount = Scalar(MaterialAttribute.CustomData0);
            fuzzRoughness = new Expr($"(0.5 + 0.5 * {rough.Code})", 1);
            fuzzColor = Vector3(MaterialAttribute.SubsurfaceColor);
            f0 = new Expr($"({f0.Code} * (1.0 - {fuzzAmount.Code}))", 3);
        } else if (model == LegacyShadingModel.MSM_ClearCoat) {
            secondWeight = Scalar(MaterialAttribute.CustomData0);
            secondRoughness = Scalar(MaterialAttribute.CustomData1);
            Expr? bottomNormal = null;
            if (fromAttributes) {
                var bottom = Read(MaterialAttribute.ClearCoatBottomNormal);
                if (bottom.Connected) bottomNormal = Value(bottom, 3);
            } else if (Connected(properties["ClearCoatNormal"])) {
                bottomNormal = SubstrateWorldNormal(context, properties["ClearCoatNormal"]);
            }
            simpleCoat = new Expr($"float({secondWeight.Code} > 0.0)", 1);
            if (bottomNormal is not null) {
                coatNormal = CompileSlabCoatNormal(worldNormal, bottomNormal);
                simpleCoat = new Expr($"lfx_slab_legacy_clear_coat({secondWeight.Code}, {worldNormal.Code}, {bottomNormal.Code})", 1);
            }
        } else if (wrapModel) {
            SubstrateInclude("media");
            bool twoSided = model == LegacyShadingModel.MSM_TwoSidedFoliage;
            thickness = Constant(twoSided ? .01 : 100);
            Expr color = Vector3(MaterialAttribute.SubsurfaceColor);
            meanFreePath = Shared(new Expr($"max(vec3(1e-5), lfx_substrate_transmittance_mfp({color.Code}, {thickness.Code}))", 3));
            Expr phase = Shared(Arithmetic(Constant(1), Saturate(Scalar(MaterialAttribute.Opacity)), "-"));
            wrap = CompileSlabWrap(meanFreePath, meanFreePath, thickness, twoSided ? 2 : 1, phase);
        }

        bool solid = compiledBlendMode is "BLEND_Opaque" or "BLEND_Masked";
        Expr coverage = solid && !decalCoordinates || compiledBlendMode == "BLEND_AlphaComposite"
            ? Constant(1) : Shared(Saturate(Scalar(MaterialAttribute.Opacity)));
        return new SlabSubstrate(diffuse, f0, Cast(Constant(1), 3), rough, Constant(1), worldNormal,
            anisotropy, worldTangent, secondRoughness, secondWeight, simpleCoat, coatNormal,
            fuzzColor, fuzzAmount, fuzzRoughness, meanFreePath, thickness ?? Constant(.01), false, false, false, 0,
            emissive, coverage) {
            Wrap = wrap,
            DecalOutputs = (baseColor.Connected ? 1 : 0) | (normal.Connected ? 2 : 0)
                | (metallic.Connected || specular.Connected || roughness.Connected ? 4 : 0) | (emission.Connected ? 8 : 0)
        };
    }
}
