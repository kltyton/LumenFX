using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private enum SlabOperator { Horizontal, Add, Vertical }

    private sealed record SlabLayer(SlabSubstrate Slab, Expr Luminance, Expr Coverage, Expr TopWeight,
        Expr CoverageAbove, Expr TransmittanceAbove, Expr? VolumeMfp, bool IsTop);

    private sealed record SlabTopology(SubstrateSurface Left, SubstrateSurface Right, SlabOperator Operator,
        Context Context, SourceObject Source, Expr? Thickness, Expr Mix, bool UpperLayer);

    private sealed record LayeredSlabs(IReadOnlyList<SlabLayer> Layers, Expr TransmittanceView,
        Expr TransmittanceNormal, Expr Emissive, Expr Coverage) : SubstrateSurface(Emissive, Coverage)
    {
        public SlabTopology? Topology { get; init; }
        public Expr? OutsideWeight { get; init; }
    }

    private SubstrateSurface CompileIndependentSlabs(Context context, SourceObject node,
        HashSet<(int Context, int Expression, int Output)> path, Expr? thickness, bool upperLayer)
    {
        var properties = node.Properties;
        SlabOperator op = node.ClassName switch {
            "MaterialExpressionSubstrateAdd" => SlabOperator.Add,
            "MaterialExpressionSubstrateVerticalLayering" => SlabOperator.Vertical,
            _ => SlabOperator.Horizontal
        };
        var permutation = library.MaterialPermutation;
        if (permutation.SubstrateClosures == 1)
            return op == SlabOperator.Vertical ? CompileSlabVerticalBlend(context, node, path, thickness, upperLayer)
                : CompileSlabParameterBlend(context, node, path, thickness, upperLayer);

        bool vertical = op == SlabOperator.Vertical;
        Expr? leftThickness = vertical ? SubstrateFloat(context, properties, "Thickness", .01) : thickness;
        SubstrateSurface left = CompileSubstrateSurface(context, properties[op switch {
            SlabOperator.Vertical => "Top", SlabOperator.Add => "A", _ => "Background"
        }], path, thickness: leftThickness, upperLayer: vertical || upperLayer);
        SubstrateSurface right = CompileSubstrateSurface(context, properties[op switch {
            SlabOperator.Vertical => "Base", SlabOperator.Add => "B", _ => "Foreground"
        }], path, thickness: thickness, upperLayer: upperLayer);
        if (left is not (SlabSubstrate or LayeredSlabs) || right is not (SlabSubstrate or LayeredSlabs))
            throw new ConversionException($"Independent Substrate operators require Slab closures: {context.Asset.Name}:{node.Name}");
        Expr mix = op == SlabOperator.Horizontal ? Shared(Saturate(SubstrateFloat(context, properties, "Mix", .5))) : Constant(1);
        return CombineSlabs(new SlabTopology(left, right, op, context, node, thickness, mix, upperLayer));
    }

    private LayeredSlabs CombineSlabs(SlabTopology topology)
    {
        LayeredSlabs left = SlabLayers(topology.Left), right = SlabLayers(topology.Right);
        Expr coverage, view, normal;
        SlabLayer[] layers;
        switch (topology.Operator) {
            case SlabOperator.Horizontal:
                Expr mix = topology.Mix;
                Expr inverse = Shared(Arithmetic(Constant(1), mix, "-"));
                coverage = Shared(SlabMix(left.Coverage, right.Coverage, mix));
                view = WeightedTransmission(left.TransmittanceView, right.TransmittanceView);
                normal = WeightedTransmission(left.TransmittanceNormal, right.TransmittanceNormal);
                layers = left.Layers.Select(layer => ScaleSlab(layer, inverse))
                    .Concat(right.Layers.Select(layer => ScaleSlab(layer, mix))).ToArray();
                break;
            case SlabOperator.Add:
                coverage = Shared(Saturate(Arithmetic(left.Coverage, right.Coverage, "+")));
                SubstrateInclude("parameter_blending");
                Expr leftMix = Shared(new Expr($"lfx_slab_parameter_weight({right.Coverage.Code}, {left.Coverage.Code}, 1.0, 1.0)", 1));
                view = Shared(SlabMix(right.TransmittanceView, left.TransmittanceView, leftMix));
                normal = Shared(SlabMix(right.TransmittanceNormal, left.TransmittanceNormal, leftMix));
                layers = left.Layers.Concat(right.Layers).ToArray();
                break;
            default:
                SubstrateInclude("closure_tree");
                coverage = Shared(new Expr($"lfx_slab_layer_coverage({left.Coverage.Code}, {right.Coverage.Code})", 1));
                view = LayeredTransmission(left.TransmittanceView, right.TransmittanceView);
                normal = LayeredTransmission(left.TransmittanceNormal, right.TransmittanceNormal);
                Expr visibility = Shared(SlabMix(Cast(Constant(1), 3), left.TransmittanceView, left.Coverage));
                Expr exposed = Shared(Arithmetic(Constant(1), left.Coverage, "-"));
                layers = left.Layers.Concat(right.Layers.Select(layer => layer with {
                    Luminance = Shared(Arithmetic(layer.Luminance, visibility, "*")),
                    TopWeight = Shared(Arithmetic(layer.TopWeight, exposed, "*")),
                    IsTop = false,
                    CoverageAbove = Shared(new Expr($"lfx_slab_layer_coverage({left.Coverage.Code}, {layer.CoverageAbove.Code})", 1)),
                    TransmittanceAbove = Shared(new Expr($"lfx_slab_layer_above({left.Coverage.Code}, {layer.CoverageAbove.Code},"
                        + $" {left.TransmittanceNormal.Code}, {layer.TransmittanceAbove.Code})", 3))
                })).ToArray();
                break;
        }
        return new LayeredSlabs(layers, view, normal, SlabLayerEmission(layers), coverage) { Topology = topology };

        Expr WeightedTransmission(Expr a, Expr b) => Shared(new Expr(
            $"(mix({left.Coverage.Code} * {a.Code}, {right.Coverage.Code} * {b.Code}, {topology.Mix.Code}) / max(1e-10, {coverage.Code}))", 3));
        Expr LayeredTransmission(Expr a, Expr b) => Shared(new Expr(
            $"lfx_slab_layer_transmission({left.Coverage.Code}, {right.Coverage.Code}, {a.Code}, {b.Code})", 3));
    }

    private LayeredSlabs SlabLayers(SubstrateSurface surface)
    {
        if (surface is LayeredSlabs layered) return layered;
        var slab = (SlabSubstrate)surface;
        Expr coverage = Shared(Saturate(slab.Coverage));
        Expr zero = Cast(Constant(0), 3);
        Expr one = Cast(Constant(1), 3);
        Expr view = zero, normal = zero;
        Expr? medium = null;
        if (slab.UpperLayer && !slab.MeanFreePath.ZeroConstant) {
            SubstrateInclude("closure_tree");
            Expr camera = CameraVector();
            medium = Shared(new Expr($"max({slab.MeanFreePath.Code} * (1.0 / max(1e-10, {slab.Thickness.Code})), vec3(0.0))", 3));
            string sign = rasterTwoSided && compiledMaterialProperties.Value<bool?>("bTangentSpaceNormal") != false
                ? RasterTwoSidedSign().Code : "1.0";
            Expr noV = Shared(new Expr($"(dot({slab.WorldNormal.Code}, {camera.Code}) * {sign})", 1));
            view = Shared(new Expr($"lfx_slab_layer_view_transmission({medium.Code}, {slab.F0.Code}, {slab.F90.Code},"
                + $" {slab.Roughness.Code}, {noV.Code}, {slab.FuzzAmount.Code}, {slab.FuzzRoughness.Code})", 3));
            normal = Shared(new Expr($"lfx_slab_layer_normal_transmission({medium.Code})", 3));
        }
        SlabLayer[] layers = [new(slab, Cast(coverage, 3), coverage, Constant(1), Constant(0), one, medium, true)];
        return new LayeredSlabs(layers, view, normal, SlabLayerEmission(layers), coverage);
    }

    private SlabLayer ScaleSlab(SlabLayer layer, Expr factor) => layer with {
        Luminance = Shared(Arithmetic(layer.Luminance, factor, "*")),
        Coverage = Shared(Arithmetic(layer.Coverage, factor, "*")),
        TopWeight = Shared(Arithmetic(layer.TopWeight, factor, "*"))
    };

    private LayeredSlabs WeightSlabs(LayeredSlabs source, Expr factor, Expr coverage)
    {
        var layers = source.Layers.Select(layer => ScaleSlab(layer, factor)).ToArray();
        return source with {
            Layers = layers, Coverage = coverage, Emissive = SlabLayerEmission(layers),
            OutsideWeight = source.OutsideWeight is { } existing ? Shared(Arithmetic(existing, factor, "*")) : factor
        };
    }

    private Expr SlabLayerEmission(IReadOnlyList<SlabLayer> layers) => Shared(layers.Select(layer =>
        Arithmetic(new Expr($"max({layer.Slab.Emissive.Code}, vec3(0.0))", 3), layer.Luminance, "*"))
        .Aggregate((a, b) => Arithmetic(a, b, "+")));
}
