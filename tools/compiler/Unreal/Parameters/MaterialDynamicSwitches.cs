using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;
using LumenFx.Compiler.Unreal.Attributes;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private Expr DynamicSwitchValue(Context context, JObject properties)
    {
        string name = properties.Value<string>("ParameterName") ?? "Param";
        int index = runtimeParameters.FindIndex(parameter => parameter.Kind == "static_switch"
            && SourceNameOrder.Equal(parameter.Name, name) && parameter.Association == context.ParameterAssociation
            && parameter.ParameterIndex == context.ParameterIndex);
        if (index < 0) {
            index = runtimeParameters.Count;
            runtimeParameters.Add(new CompiledMaterialParameter(null, "static_switch",
                new[] { StaticBoolValue(context, properties, false) ? 1f : 0f, 0f, 0f, 0f }) {
                Name = name, Association = context.ParameterAssociation, ParameterIndex = context.ParameterIndex
            });
        }
        int offset = runtimeParameters.Take(index).Sum(parameter => parameter.Default.Length / 4);
        return new Expr($"LfxMaterialParameterValues[{offset}].x", 1);
    }

    private Expr DynamicSwitchParameter(Context context, JObject properties)
    {
        Expr condition = DynamicSwitchValue(context, properties);
        Expr? a = Connected(properties["A"]) ? RequiredInput(context, properties, "A") : null;
        Expr? b = Connected(properties["B"]) ? RequiredInput(context, properties, "B") : null;
        if (b is null) return a ?? throw new ConversionException("Dynamic switch parameter has no connected inputs");
        if (a is null) return b;
        if (ReferenceEquals(a, b)) return a;
        if (a.LargeWorld || b.LargeWorld) return DynamicSelection(condition, a, b);
        // Native floating-point DynamicBranch lowers to lerp(B, A, Condition), including both arithmetic inputs.
        return Interpolate(b, a, condition);
    }

    private AttributeResult DynamicSwitchAttribute(Context context, JObject properties, MaterialAttribute attribute)
    {
        Expr condition = DynamicSwitchValue(context, properties);
        if (!Connected(properties["B"])) return Connected(properties["A"])
            ? AttributeConnection(context, properties["A"], attribute)
            : throw new ConversionException("Dynamic material-attributes switch has no connected inputs");
        if (!Connected(properties["A"])) return AttributeConnection(context, properties["B"], attribute);
        Expr a = AttributeInput(context, properties["A"], attribute).Value;
        Expr b = AttributeInput(context, properties["B"], attribute).Value;
        if (ReferenceEquals(a, b)) return new AttributeResult(a);
        // Material Attributes is a struct branch in the source, so fields select values without float interpolation.
        return new AttributeResult(Shared(DynamicSelection(condition, a, b)));
    }

    private static Expr DynamicSelection(Expr condition, Expr a, Expr b)
    {
        int width = ArithmeticWidth(a, b);
        bool largeWorld = a.LargeWorld || b.LargeWorld;
        a = ArithmeticResult(a, width, largeWorld); b = ArithmeticResult(b, width, largeWorld);
        return new Expr($"({condition.Code} != 0.0 ? {a.RawCode} : {b.RawCode})", width, LargeWorld: largeWorld);
    }
}
