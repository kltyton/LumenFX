using System.Globalization;
using System.Text;
using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private bool doubleVectorParameterUsed;

    private Expr DoubleVectorParameter(Context context, JObject properties, int output)
    {
        string name = properties.Value<string>("ParameterName") ?? "Param";
        JToken? source = ParameterValue(context, InstanceParameterType.DoubleVector, name) ?? properties["DefaultValue"];
        double[] values = Enumerable.Range(0, 4).Select(i => SourceAsset.Number(source?["XYZW"[i].ToString()], i == 3 ? 1 : 0)).ToArray();
        float[] encoded = new float[8];
        for (int i = 0; i < 4; i++) { encoded[i] = (float)values[i]; encoded[i + 4] = (float)(values[i] - encoded[i]); }
        doubleVectorParameterUsed = true;
        string high = FloatBits(encoded.Take(4)), low = FloatBits(encoded.Skip(4));
        bool global = context.ParameterAssociation == "GlobalParameter" && context.ParameterIndex == -1;
        CompiledMaterialBinding? binding = global ? rendererBindings.Find(name, "double_vector") : null;
        bool runtime = binding is not null || runtimeDynamicParameters || sourceDynamicMaterial;
        if (runtime)
        {
            int index = runtimeParameters.FindIndex(parameter => parameter.Kind == "double_vector"
                && parameter.Association == context.ParameterAssociation && parameter.ParameterIndex == context.ParameterIndex
                && SourceNameOrder.Equal(parameter.Name, name));
            if (index < 0) {
                index = runtimeParameters.Count;
                runtimeParameters.Add(new CompiledMaterialParameter(binding, "double_vector", encoded) {
                    Name = name, Association = context.ParameterAssociation, ParameterIndex = context.ParameterIndex
                });
            }
            int offset = runtimeParameters.Take(index).Sum(parameter => parameter.Default.Length / 4);
            high = $"LfxMaterialParameterValues[{offset}]"; low = $"LfxMaterialParameterValues[{offset + 1}]";
        }
        Expr value = UniformExpression($"lfx_lwc_parameter_df({high}, {low})", 4, largeWorld: true)
                with { ParameterLiteral = !runtime };
        if (output is < 0 or > 4) throw new ConversionException($"Invalid double-vector parameter output {output}");
        return Swizzle(value, output == 0 ? "xyz" : "xyzw"[(output - 1)..output]);
    }

    private static string FloatBits(IEnumerable<float> values) => "uintBitsToFloat(uvec4(" + string.Join(", ", values.Select(value =>
        "0x" + unchecked((uint)BitConverter.SingleToInt32Bits(value)).ToString("x8", CultureInfo.InvariantCulture) + "u")) + "))";

    private void AppendDoubleVectorMath(StringBuilder shader)
    {
        if (!doubleVectorParameterUsed) return;
        shader.AppendLine("LfxLWC4 lfx_lwc_parameter_df(vec4 high, vec4 low) { precise vec4 tile = roundEven(high / LFX_LWC_TILE_SIZE);");
        if (precision.SupportsFma && !precision.ForceFloatOperations)
            shader.AppendLine("precise vec4 base = fma(tile, vec4(-LFX_LWC_TILE_SIZE), high);");
        else shader.AppendLine("precise vec4 product = tile * vec4(-LFX_LWC_TILE_SIZE); precise vec4 base = product + high;");
        shader.AppendLine("precise vec4 offset = base + low; return LfxLWC4(tile, offset); }");
    }
}
