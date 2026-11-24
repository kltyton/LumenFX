using System.Globalization;
using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private abstract record SubstrateSurface(Expr Emissive, Expr Coverage);
    private sealed record UnlitSubstrate(Expr Emissive, Expr Transmittance, Expr Coverage) : SubstrateSurface(Emissive, Coverage)
    {
        public bool AllowBrightening { get; init; }
    }
    private sealed record LightFunctionSubstrate(Expr Emissive, Expr Coverage) : SubstrateSurface(Emissive, Coverage);
    private sealed record DecalSubstrate(SlabSubstrate Slab, Expr Coverage) : SubstrateSurface(Slab.Emissive, Coverage);

    private SubstrateSurface CompileSubstrateSurface(Context context, JToken? connection,
        HashSet<(int Context, int Expression, int Output)> path, bool parameterBlending = false, Expr? thickness = null,
        bool upperLayer = false)
    {
        if (connection is not JObject input || input.Value<int>("expression") == 0
            || input.Value<int>("mask") != 0 || input.Value<bool?>("constant") == true)
            throw new ConversionException($"Invalid Substrate connection in {context.Asset.Name}");
        int index = input.Value<int>("expression");
        int output = input.Value<int>("output");
        var key = (context.Id, index, output);
        if (!path.Add(key)) throw new ConversionException($"Cyclic Substrate expression in {context.Asset.Name}:{index}");
        try {
            var node = context.Asset.Object(index);
            var properties = node.Properties;
            SubstrateSurface Child(Context scope, JToken? value) =>
                CompileSubstrateSurface(scope, value, path, parameterBlending, thickness, upperLayer);
            if (node.ClassName == "MaterialExpressionMaterialFunctionCall") {
                FunctionScope scope = GetFunctionScope(context, node);
                return Child(scope.Context, GetFunctionOutput(scope, output).Properties["A"]);
            }
            if (output != 0)
                throw new ConversionException($"Unexpected Substrate output {output} in {context.Asset.Name}:{node.Name}");
            switch (node.ClassName) {
                case "MaterialExpressionSubstrateLightFunction" when lightFunctionCoordinates:
                    return new LightFunctionSubstrate(SubstrateFloat(context, properties, "Color", 0, 3), Constant(1));
                case "MaterialExpressionSubstrateVolumetricFogCloudBSDF" when volumeCoordinates:
                    return CompileSubstrateVolume(context, properties);
                case "MaterialExpressionSubstrateShadingModels":
                case "MaterialExpressionSubstrateConvertMaterialAttributes":
                    return CompileLegacySubstrate(context, node, thickness);
                case "MaterialExpressionSubstrateConvertToDecal" when decalCoordinates && !parameterBlending: {
                    if (CompileSubstrateSurface(context, properties["DecalMaterial"], path, true, thickness) is not SlabSubstrate decal)
                        throw new ConversionException($"Convert To Decal requires a parameter-blended Slab: {context.Asset.Name}:{node.Name}");
                    Expr weight = Saturate(SubstrateFloat(context, properties, "Coverage", 1));
                    return new DecalSubstrate(decal, Shared(Arithmetic(decal.Coverage, weight, "*")));
                }
                case "MaterialExpressionSubstrateUnlitBSDF":
                    return new UnlitSubstrate(
                        Cast(Input(context, properties["EmissiveColor"], () => Constant(0)), 3),
                        Cast(Input(context, properties["TransmittanceColor"], () => Constant(1)), 3),
                        Constant(1));
                case "MaterialExpressionSubstrateSlabBSDF":
                    return CompileSlabSubstrate(context, node, thickness ?? Constant(.01), upperLayer);
                case "MaterialExpressionSubstrateSimpleClearCoatBSDF":
                    return CompileSimpleClearCoat(context, node, thickness ?? Constant(.01));
                case "MaterialExpressionSubstrateHairBSDF":
                    return CompileSubstrateHair(context, properties);
                case "MaterialExpressionSubstrateEyeBSDF":
                    return CompileSubstrateEye(context, properties);
                case "MaterialExpressionSubstrateToonBSDF":
                    return CompileSubstrateToon(context, properties);
                case "MaterialExpressionSubstrateHorizontalMixing":
                case "MaterialExpressionSubstrateAdd":
                    if (parameterBlending || properties.Value<bool?>("bUseParameterBlending") == true)
                        return CompileSlabParameterBlend(context, node, path, thickness, upperLayer);
                    return CompileIndependentSlabs(context, node, path, thickness, upperLayer);
                case "MaterialExpressionSubstrateVerticalLayering":
                    if (parameterBlending || properties.Value<bool?>("bUseParameterBlending") == true)
                        return CompileSlabVerticalBlend(context, node, path, thickness, upperLayer);
                    return CompileIndependentSlabs(context, node, path, thickness, upperLayer);
                case "MaterialExpressionSubstrateSelect":
                    return CompileSlabSelect(context, node, path, thickness, upperLayer);
                case "MaterialExpressionSubstrateWeight": {
                    SubstrateSurface child = Child(context, properties["A"]);
                    Expr weight = Saturate(Cast(Input(context, properties["Weight"], () => Constant(1)), 1));
                    Expr coverage = Shared(Arithmetic(child.Coverage, weight, "*"));
                    if (child is LayeredSlabs layered) return WeightSlabs(layered, weight, coverage);
                    return child with { Coverage = coverage };
                }
                case "MaterialExpressionReroute":
                case "MaterialExpressionNamedRerouteDeclaration":
                case "MaterialExpressionNamedRerouteUsage":
                    return Child(context, RerouteInput(context, node));
                case string _ when TrySelectStaticSwitchInput(context, node, properties, out var selected):
                    return Child(context, selected);
                case "MaterialExpressionFunctionInput":
                    if (context.Inputs.TryGetValue(index, out FunctionInputBinding? argument) && argument.Attributes is null)
                        return Child(argument.Caller, argument.Connection);
                    if (properties.Value<bool?>("bUsePreviewValueAsDefault") == true)
                        return Child(context, properties["Preview"]);
                    break;
                case "MaterialExpressionStaticSwitchParameter" when properties.Value<bool?>("DynamicBranch") != true:
                    ValidateStaticSwitchInputs(properties);
                    return Child(context, properties[StaticBoolValue(context, properties) ? "A" : "B"]);
                case "MaterialExpressionStaticSwitch": {
                    Expr value = Input(context, properties["Value"], () => Constant(properties.Value<bool?>("DefaultValue") == true ? 1 : 0));
                    if (double.TryParse(value.Code, NumberStyles.Float, CultureInfo.InvariantCulture, out double constant))
                        return Child(context, properties[constant != 0 ? "A" : "B"]);
                    break;
                }
            }
            throw new ConversionException($"Unsupported Substrate operator {node.ClassName} in {context.Asset.Name}:{node.Name}");
        } finally {
            path.Remove(key);
        }
    }

    private static string UnlitSubstrateBlend(string mode, string sourceObject) => mode switch {
        "BLEND_Opaque" or "BLEND_Masked" => "opaque",
        "BLEND_Additive" => "add",
        "BLEND_Translucent" or "BLEND_TranslucentGreyTransmittance" or "BLEND_AlphaComposite" => "premultiplied",
        "BLEND_AlphaHoldout" => "holdout",
        "BLEND_Modulate" or "BLEND_ColoredTransmittanceOnly" => "modulate",
        "BLEND_TranslucentColoredTransmittance" => "colored_transmittance",
        _ => throw new ConversionException($"Unsupported Substrate blend {mode}: {sourceObject}")
    };

    private static void AppendColoredTransmittanceOutputs(StringBuilder shader)
    {
        shader.AppendLine("""
#if LFX_SUBSTRATE_DUAL_SOURCE && !LFX_PARTICLE_MOTION
#define LFX_COLOR_OUTPUT layout(location = 0, index = 0)
#define LFX_TRANSMITTANCE_OUTPUT layout(location = 0, index = 1) out
#else
#define LFX_COLOR_OUTPUT
#define LFX_TRANSMITTANCE_OUTPUT
#endif
LFX_COLOR_OUTPUT out vec4 fragColor;
LFX_TRANSMITTANCE_OUTPUT vec4 lfxDualTransmittance;
""");
    }

    private void AppendUnlitSubstrateColor(StringBuilder shader, UnlitSubstrate substrate, string mode)
    {
        if (compiledMaterialProperties.Value<bool?>("bAllowNegativeEmissiveColor") != true)
            shader.AppendLine("    emission = max(emission, vec3(0.0));");
        shader.AppendLine($"    emission *= {substrate.Coverage.Code};");
        switch (mode) {
            case "BLEND_Opaque":
            case "BLEND_Masked":
                shader.AppendLine("    fragColor = vec4(emission, 1.0);");
                break;
            case "BLEND_Additive":
                shader.AppendLine("    fragColor = vec4(emission * lfx_material_fog().a, 0.0);");
                break;
            case "BLEND_AlphaComposite":
                shader.AppendLine("    fragColor = lfx_apply_material_fog(vec4(emission, opacity));");
                break;
            case "BLEND_Modulate":
            case "BLEND_ColoredTransmittanceOnly":
                string transmission = substrate.AllowBrightening ? $"max({substrate.Transmittance.Code}, vec3(0.0))"
                    : $"clamp({substrate.Transmittance.Code}, 0.0, 1.0)";
                shader.AppendLine($"    fragColor = vec4(mix(vec3(1.0), {transmission}, lfx_material_fog().a * opacity), 1.0);");
                break;
            default:
                shader.AppendLine("    vec4 fog = lfx_material_fog();");
                shader.AppendLine($"    vec3 transmission = mix(vec3(1.0), fog.a * clamp({substrate.Transmittance.Code}, 0.0, 1.0), opacity);");
                if (mode == "BLEND_TranslucentColoredTransmittance") {
                    shader.AppendLine("""
#if LFX_SUBSTRATE_MODULATION_ONLY && !LFX_PARTICLE_MOTION
    fragColor = vec4(transmission, 1.0);
#elif LFX_SUBSTRATE_DUAL_SOURCE && !LFX_PARTICLE_MOTION
    fragColor = vec4(opacity * fog.rgb + fog.a * emission, 0.0);
    lfxDualTransmittance = vec4(transmission, 1.0);
#else
    float alpha = clamp(1.0 - dot(transmission, vec3(1.0 / 3.0)), 0.0, 1.0);
    fragColor = vec4(opacity * fog.rgb + fog.a * emission, alpha);
#endif
""");
                    break;
                }
                shader.AppendLine("    float alpha = clamp(1.0 - dot(transmission, vec3(1.0 / 3.0)), 0.0, 1.0);");
                shader.AppendLine(mode == "BLEND_AlphaHoldout"
                    ? "    fragColor = vec4(0.0, 0.0, 0.0, alpha);"
                    : "    fragColor = vec4(opacity * fog.rgb + fog.a * emission, alpha);");
                break;
        }
    }
}
