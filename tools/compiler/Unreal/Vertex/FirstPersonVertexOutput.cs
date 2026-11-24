using System.Text;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private int firstPersonRootInputs;
    private bool previousClockUsed;
    private int MaterialDeclarationViewInputs => materialViewUsed | (firstPersonRootInputs & (255 | AtmosphereLightViewInput));

    private SourceObject? FindFirstPersonOutput(Context context, SourceObject data)
        => FindUniqueCustomOutput(context, data, "MaterialExpressionFirstPersonOutput");

    private static SourceObject? FindUniqueCustomOutput(Context context, SourceObject data, string type)
    {
        JToken? collection = data.Properties["ExpressionCollection"];
        if (collection is null || collection.Type == JTokenType.Null) return null;
        if (collection is not JObject expressions) throw new ConversionException("Material expression collection is not an object");
        if (expressions["Expressions"] is not { Type: not JTokenType.Null } values) return null;
        if (values is not JArray references) throw new ConversionException("Material expression references are not an array");
        SourceObject? result = null;
        foreach (int reference in references.Values<int>()) {
            if (reference == 0) continue;
            SourceObject node = context.Asset.Object(reference);
            if (node.ClassName != type) continue;
            if (result is not null) throw new ConversionException($"Material has multiple {type} nodes: {context.Asset.Name}");
            result = node;
        }
        return result;
    }

    private Expr IsFirstPerson()
    {
        string domain = (compiledMaterialProperties.Value<string>("MaterialDomain") ?? "MD_Surface").Split("::").Last();
        if (domain is not ("MD_Surface" or "MD_Volume"))
            throw new ConversionException("IsFirstPerson requires the Surface or Volume material domain");
        return new Expr("EffectTimeData.w", 1);
    }

    private void AppendFirstPersonRootStage(StringBuilder stage, List<string> stageFunctions, Context caller,
            SourceObject? output, bool previous)
    {
        if (firstPersonRootInputs == 0) return;
        int viewInputs = materialViewUsed;
        bool projection = previousViewUsed, matrices = previousMatricesUsed, firstPerson = previousFirstPersonUsed,
                clock = previousClockUsed;
        try {
            ResetExpressionGraph();
            materialViewUsed = 0;
            previousViewUsed = previousMatricesUsed = previousFirstPersonUsed = previousClockUsed = false;
            Context context = caller with { Id = nextContext++ };
            Expr alpha = output is null ? Constant(1) : Input(context, output.Properties["FirstPersonInterpolationAlpha"],
                    () => Constant((float)SourceAsset.Number(output.Properties["ConstFirstPersonInterpolationAlpha"], 1)));
            alpha = Saturate(DemoteLargeWorld(Cast(alpha, 1)));
            firstPersonRootInputs |= materialViewUsed;
            if (previous) firstPersonRootInputs |= (previousClockUsed ? 256 : 0) | (previousViewUsed ? 512 : 0)
                    | (previousMatricesUsed ? 1024 : 0) | (previousFirstPersonUsed ? 2048 : 0);
            stageFunctions.AddRange(functions);
            string label = previous ? "previous" : "current", view = previous ? "Motion" : "Material";
            stage.AppendLine($"float lfx_first_person_alpha_{label}() {{");
            foreach (string expression in expressions) stage.AppendLine(expression);
            stage.AppendLine($"return {alpha.Code}; }}");
            stage.AppendLine($"vec3 lfx_material_first_person_{label}(vec3 position) {{");
            stage.AppendLine("if (EffectTimeData.w < 0.5) return position;");
            stage.AppendLine("vec3 translated = position.xzy * 100.0;");
            stage.AppendLine($"vec3 firstPerson = mat3({view}FirstPersonTransform0.xyz, {view}FirstPersonTransform1.xyz, {view}FirstPersonTransform2.xyz) * translated;");
            if (naniteCoordinates) {
                stage.AppendLine("#if LFX_NANITE_VERTEX_MATERIAL");
                stage.AppendLine("if (lfx_nanite_fixed_raster()) return firstPerson.xzy * 0.01;");
                stage.AppendLine("#endif");
            }
            stage.AppendLine($"return mix(translated, firstPerson, lfx_first_person_alpha_{label}()).xzy * 0.01; }}");
        } finally {
            materialViewUsed = viewInputs;
            previousViewUsed = projection; previousMatricesUsed = matrices;
            previousFirstPersonUsed = firstPerson; previousClockUsed = clock;
        }
    }
}
