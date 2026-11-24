using System.Text;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private readonly List<CompiledMaterialParameter> runtimeParameters = new();

    private static bool IsMaterial(SourceObject value) => value.ClassName is "Material" or "MaterialInstanceConstant" or "MaterialInstanceDynamic";

    private Expr RuntimeParameter(Context context, string name, string kind, double[] defaults, Expr value)
    {
        bool global = context.ParameterAssociation == "GlobalParameter" && context.ParameterIndex == -1;
        bool constant = global && (kind == "scalar" ? rendererConstants.Scalar(name) is not null : rendererConstants.Vector(name) is not null);
        bool dynamic = runtimeDynamicParameters || sourceDynamicMaterial;
        if (constant && !dynamic) return value;
        CompiledMaterialBinding? binding = global && !constant ? rendererBindings.Find(name, kind) : null;
        if (binding is null && !dynamic) return value;
        int index = runtimeParameters.FindIndex(parameter => parameter.Kind == kind
            && parameter.Association == context.ParameterAssociation && parameter.ParameterIndex == context.ParameterIndex
            && SourceNameOrder.Equal(parameter.Name, name));
        if (index < 0)
        {
            index = runtimeParameters.Count;
            float[] initial = new float[4];
            for (int i = 0; i < defaults.Length; i++) initial[i] = (float)defaults[i];
            runtimeParameters.Add(new CompiledMaterialParameter(binding, kind, initial) {
                Name = name, Association = context.ParameterAssociation, ParameterIndex = context.ParameterIndex,
                RendererConstant = constant
            });
        }
        int offset = runtimeParameters.Take(index).Sum(parameter => parameter.Default.Length / 4);
        return UniformExpression($"LfxMaterialParameterValues[{offset}]" + (kind == "scalar" ? ".x" : ""), kind == "scalar" ? 1 : 4);
    }

    private void AppendRuntimeParameters(StringBuilder shader)
    {
        if (runtimeParameters.Count != 0)
            shader.AppendLine($"layout(std140) uniform MaterialInstance {{ vec4 LfxMaterialParameterValues[{runtimeParameters.Sum(parameter => parameter.Default.Length / 4)}]; }};");
    }
}
