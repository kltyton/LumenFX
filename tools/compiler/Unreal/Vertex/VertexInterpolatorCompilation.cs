using System.Text;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private sealed record VertexInterpolatorCode(string Name, int Width, string Functions);
    private readonly Dictionary<(int Context, int Node), VertexInterpolatorCode> vertexInterpolators = new();

    private Expr VertexInterpolator(Context context, SourceObject node, int output)
    {
        if (compilingVertex) throw new ConversionException("Custom interpolator outputs are only available in pixel shaders");
        if (decalCoordinates) throw new ConversionException("Deferred decal custom interpolators require a projected vertex factory");
        if (output != 0) throw new ConversionException("Invalid vertex interpolator output");
        var key = (context.Id, node.Index);
        if (!vertexInterpolators.TryGetValue(key, out var interpolator)) {
            interpolator = CompileVertexInterpolator(context, node, "lfxInterpolator" + vertexInterpolators.Count);
            vertexInterpolators.Add(key, interpolator);
        }
        return new Expr(interpolator.Name, interpolator.Width,
                DerivativeX: naniteCoordinates ? interpolator.Name + "_dx()" : null,
                DerivativeY: naniteCoordinates ? interpolator.Name + "_dy()" : null);
    }

    private VertexInterpolatorCode CompileVertexInterpolator(Context context, SourceObject node, string name)
    {
        var pixelCache = cache.ToArray();
        var pixelVisiting = visiting.ToArray();
        var pixelAttributes = attributeCache.ToArray();
        var pixelAttributeVisiting = attributeVisiting.ToArray();
        var pixelScopes = functionScopes.ToArray();
        var pixelFunctions = functions.ToArray();
        var pixelExpressions = expressions.ToArray();
        var pixelValues = expressionValues.ToArray();
        bool previous = compilingPreviousFrame;
        try {
            ResetExpressionGraph();
            compilingVertex = true;
            compilingPreviousFrame = false;
            Expr input = RequiredInput(context, node.Properties, "Input");
            if (input.Sampler || input.Width is < 1 or > 4)
                throw new ConversionException("Vertex interpolators require a numeric value with one to four components");
            var source = new StringBuilder();
            foreach (string function in functions) source.AppendLine(function);
            source.AppendLine($"{Type(input.Width)} {name}_vertex() {{");
            foreach (string expression in expressions) source.AppendLine(expression);
            source.AppendLine($"return {input.Code};");
            source.AppendLine("}");
            return new VertexInterpolatorCode(name, input.Width, source.ToString());
        } finally {
            ResetExpressionGraph();
            foreach (var pair in pixelCache) cache.Add(pair.Key, pair.Value);
            foreach (var value in pixelVisiting) visiting.Add(value);
            foreach (var pair in pixelAttributes) attributeCache.Add(pair.Key, pair.Value);
            foreach (var value in pixelAttributeVisiting) attributeVisiting.Add(value);
            foreach (var pair in pixelScopes) functionScopes.Add(pair.Key, pair.Value);
            functions.AddRange(pixelFunctions);
            expressions.AddRange(pixelExpressions);
            foreach (var pair in pixelValues) expressionValues.Add(pair.Key, pair.Value);
            compilingVertex = false;
            compilingPreviousFrame = previous;
        }
    }

    private void AppendPixelInterpolators(StringBuilder shader)
    {
        foreach (var interpolator in vertexInterpolators.Values) {
            AppendNanitePixelInput(shader, $"{Type(interpolator.Width)} {interpolator.Name}");
            if (naniteCoordinates) {
                string type = Type(interpolator.Width), name = interpolator.Name;
                shader.AppendLine("#if LFX_NANITE_MATERIAL");
                shader.AppendLine($"{type} {name}_gradientX, {name}_gradientY;");
                shader.AppendLine($"{type} {name}_dx() {{ return {name}_gradientX; }}");
                shader.AppendLine($"{type} {name}_dy() {{ return {name}_gradientY; }}");
                shader.AppendLine("#else");
                shader.AppendLine($"{type} {name}_dx() {{ return dFdx({name}); }}");
                shader.AppendLine($"{type} {name}_dy() {{ return dFdy({name}); }}");
                shader.AppendLine("#endif");
            }
        }
    }
}
