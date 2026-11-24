namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private SubstrateSurface FitSlabClosureBudget(SubstrateSurface material)
    {
        var permutation = library.MaterialPermutation;
        while (material is LayeredSlabs layered && (layered.Layers.Count > permutation.SubstrateClosures
                || NativeSlabClosureBytes(layered) > permutation.SubstrateBytes)) {
            var candidates = new List<(SlabTopology Topology, int Depth)>();
            CollectSlabBudgetCandidates(layered, 0, candidates);
            var selected = candidates.AsEnumerable().Reverse().OrderByDescending(candidate => candidate.Depth).First().Topology;
            material = FoldSlabClosure(material, selected);
        }
        return material;
    }

    private static void CollectSlabBudgetCandidates(SubstrateSurface surface, int depth,
        List<(SlabTopology Topology, int Depth)> candidates)
    {
        if (surface is not LayeredSlabs { Topology: { } op }) return;
        CollectSlabBudgetCandidates(op.Left, depth, candidates);
        CollectSlabBudgetCandidates(op.Right, depth + (op.Operator == SlabOperator.Vertical ? 1 : 0), candidates);
        candidates.Add((op, depth));
    }

    private SubstrateSurface FoldSlabClosure(SubstrateSurface surface, SlabTopology selected)
    {
        if (surface is not LayeredSlabs { Topology: { } topology } graph) return surface;
        if (ReferenceEquals(topology, selected)) {
            SlabSubstrate folded = topology.Operator == SlabOperator.Vertical
                ? CompileSlabVerticalBlend(topology.Context, topology.Source, new(), topology.Thickness, topology.UpperLayer)
                : CompileSlabParameterBlend(topology.Context, topology.Source, new(), topology.Thickness, topology.UpperLayer);
            return graph.OutsideWeight is { } weight
                ? folded with { Coverage = Shared(Arithmetic(folded.Coverage, weight, "*")) } : folded;
        }
        var left = FoldSlabClosure(topology.Left, selected);
        var right = FoldSlabClosure(topology.Right, selected);
        if (ReferenceEquals(left, topology.Left) && ReferenceEquals(right, topology.Right)) return surface;
        var combined = CombineSlabs(topology with { Left = left, Right = right });
        return graph.OutsideWeight is { } inherited
            ? WeightSlabs(combined, inherited, Shared(Arithmetic(combined.Coverage, inherited, "*"))) : combined;
    }

    private static int NativeSlabClosureBytes(LayeredSlabs graph)
    {
        // The material budget excludes the top-layer texture and reserves one shared basis per closure.
        int words = 1 + graph.Layers.Count;
        foreach (var layer in graph.Layers) {
            var slab = layer.Slab;
            words += layer.IsTop ? 3 : 5;
            if (slab.AuthoredF90 || !slab.SecondWeight.ZeroConstant || !slab.SimpleCoat.ZeroConstant) words++;
            if (slab.Wrap is not null || slab.RequiresDeferredMedium || layer.VolumeMfp is not null) words++;
            if (!slab.FuzzAmount.ZeroConstant) words++;
            if (slab.SpecularProfile is not null) words++;
        }
        return words * sizeof(uint);
    }
}
