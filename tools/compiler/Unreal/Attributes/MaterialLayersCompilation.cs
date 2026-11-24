using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Attributes;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record LayerStackSource(SourceAsset Asset, JObject Runtime, JObject Editor);
    private readonly Dictionary<(int Context, int Node), Func<MaterialAttribute, AttributeResult>> layerGraphs = new();

    private static LayerStackSource? InstanceLayerSource(SourceAsset asset, SourceObject instance)
    {
        StaticParameterSetSource source = InstanceStaticParameters(asset, instance);
        JObject parameters = source.Runtime;
        if (parameters.Value<bool?>("bHasMaterialLayers") != true) return null;
        JObject runtime = parameters["MaterialLayers"] as JObject ?? throw new ConversionException($"Material instance has no layer stack: {instance.Name}");
        JObject states = source.Editor["MaterialLayers"] as JObject
                ?? throw new ConversionException($"Material instance layer stack has no editor state: {instance.Name}");
        return NormalizeLayerSource(asset, runtime, states);
    }

    private AttributeResult LayerAttribute(Context context, SourceObject node, int output, MaterialAttribute attribute)
    {
        if (output != 0) throw new ConversionException("Material layer stacks have one material-attributes output");
        var key = (context.Id, node.Index);
        if (!layerGraphs.TryGetValue(key, out var graph)) {
            graph = BuildLayerGraph(context, node);
            layerGraphs.Add(key, graph);
        }
        return graph(attribute);
    }

    private Func<MaterialAttribute, AttributeResult> BuildLayerGraph(Context caller, SourceObject node)
    {
        if (caller.FunctionChain.Count != 0) throw new ConversionException("Material layer stacks cannot be used within a material function");
        var parameters = new MaterialLayerParameters(DefaultLayerSource(caller.Asset, node.Properties["DefaultLayers"] as JObject),
            materialInstanceSources, library);
        LayerStackSource source = parameters.Stack;
        JArray layers = source.Runtime["Layers"] as JArray ?? new JArray();
        JArray blends = source.Runtime["Blends"] as JArray ?? new JArray();
        JArray states = source.Editor["LayerStates"] as JArray ?? new JArray();
        if (layers.Count == 0 && blends.Count == 0) return BaseInput;
        if (blends.Count != layers.Count - 1 || states.Count != layers.Count)
            throw new ConversionException($"Material layer stack has inconsistent layers, blends or states: {caller.Asset.Name}:{node.Name}");

        var functions = new ResolvedFunction?[layers.Count];
        var blending = new ResolvedFunction?[blends.Count];
        for (int index = 0; index < functions.Length; index++) {
            if (layers[index].Value<string>() is { } reference) functions[index] = LayerFunction(caller, reference, false);
        }
        for (int index = 0; index < blending.Length; index++) {
            if (blends[index].Value<string>() is { } reference) blending[index] = LayerFunction(caller, reference, true);
        }

        var active = new List<(int Index, ResolvedFunction Function)>();
        var activeBlends = new List<(int Index, ResolvedFunction? Function)>();
        for (int index = 0; index < functions.Length; index++) {
            if (functions[index] is not { } function || !states[index].Value<bool>()) continue;
            active.Add((index, function));
            if (index != 0) activeBlends.Add((index - 1, blending[index - 1]));
        }
        if (active.Count == 0 && activeBlends.Count == 0) return BaseInput;
        if (activeBlends.Count != active.Count - 1)
            throw new ConversionException($"Material layer stack has an invalid active blend layout: {caller.Asset.Name}:{node.Name}");

        var layerOutputs = new List<Func<MaterialAttribute, AttributeResult>>();
        foreach (var entry in active) {
            var input = Connected(node.Properties["Input"]) ? new[] { (Func<MaterialAttribute, AttributeResult>)BaseInput } : Array.Empty<Func<MaterialAttribute, AttributeResult>>();
            layerOutputs.Add(LayerFunctionOutput(caller, entry.Function, "LayerParameter", entry.Index, input, parameters));
        }
        Func<MaterialAttribute, AttributeResult> result = layerOutputs[0];
        for (int index = 0; index < activeBlends.Count; index++) {
            var entry = activeBlends[index];
            var upper = layerOutputs[index + 1];
            result = entry.Function is { } blend
                ? LayerFunctionOutput(caller, blend, "BlendParameter", entry.Index, new[] { result, upper }, parameters) : upper;
        }
        return result;

        AttributeResult BaseInput(MaterialAttribute requested) => Connected(node.Properties["Input"])
            ? AttributeConnection(caller, node.Properties["Input"], requested) : new AttributeResult(DefaultAttribute(requested), false);
    }

    private ResolvedFunction LayerFunction(Context caller, string path, bool blend)
    {
        ResolvedFunction function = ResolveFunction(caller, path);
        string usage = (function.Owner.Properties.Value<string>("MaterialFunctionUsage") ?? "Default").Split("::").Last();
        string expected = blend ? "MaterialLayerBlend" : "MaterialLayer";
        if (usage != expected && usage != (blend ? "2" : "1")) throw new ConversionException($"Material function is not configured for {expected}: {path}");
        if (!function.Instance) {
            int inputs = FunctionExpressions(function, "MaterialExpressionFunctionInput").Length;
            if (blend ? inputs != 2 : inputs > 1) throw new ConversionException($"Material layer function has invalid input count: {path}");
        }
        return function;
    }

    private Func<MaterialAttribute, AttributeResult> LayerFunctionOutput(Context caller, ResolvedFunction function,
        string association, int index, IReadOnlyList<Func<MaterialAttribute, AttributeResult>> arguments, MaterialLayerParameters parameters)
    {
        var inputs = FunctionExpressions(function, "MaterialExpressionFunctionInput");
        var outputs = FunctionExpressions(function, "MaterialExpressionFunctionOutput");
        bool blend = association == "BlendParameter";
        if ((blend ? inputs.Length != 2 : inputs.Length > 1) || outputs.Length != 1)
            throw new ConversionException($"Material layer function has invalid input/output counts: {function.Asset.Name}");
        foreach (var input in inputs)
            if ((input.Properties.Value<string>("InputType") ?? "FunctionInput_Vector3").Split("::").Last() != "FunctionInput_MaterialAttributes")
                throw new ConversionException($"Material layer function input is not material attributes: {function.Asset.Name}:{input.Name}");
        if (association == "LayerParameter" && inputs.Length == 0) arguments = Array.Empty<Func<MaterialAttribute, AttributeResult>>();
        if (arguments.Count > inputs.Length) throw new ConversionException("Material layer function has fewer inputs than its caller");
        Dictionary<int, FunctionInputBinding> bindings = new();
        for (int input = 0; input < arguments.Count; input++) bindings.Add(inputs[input].Index, new FunctionInputBinding(caller, null, arguments[input]));
        Context context = new(nextContext++, function.Asset, bindings, function.Chain, association, index, function.Overrides, parameters);
        int expression = outputs[0].Index;
        return attribute => AttributeNode(context, expression, 0, attribute);
    }

    private static SourceObject[] FunctionExpressions(ResolvedFunction function, string type)
    {
        int editor = function.Owner.Properties.Value<int?>("EditorOnlyData") ?? 0;
        JObject data = editor > 0 ? function.Asset.Object(editor).Properties : function.Owner.Properties;
        JArray references = data["ExpressionCollection"]?["Expressions"] as JArray
                ?? function.Owner.Properties["FunctionExpressions"] as JArray
                ?? throw new ConversionException($"Material function has no expression collection: {function.Asset.Name}");
        SourceObject[] expressions = references.Values<int>().Where(reference => reference != 0).Select(function.Asset.Object)
            .Where(expression => expression.ClassName == type).ToArray();
        if (expressions.Length == 2 && (expressions[0].Properties.Value<int?>("SortPriority") ?? 0) >= (expressions[1].Properties.Value<int?>("SortPriority") ?? 0))
            (expressions[0], expressions[1]) = (expressions[1], expressions[0]);
        return expressions;
    }
}
