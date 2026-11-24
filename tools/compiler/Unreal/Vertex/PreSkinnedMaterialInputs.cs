using System.Text;

namespace LumenFx.Compiler.Unreal;

public sealed partial class MaterialCompiler
{
    private int preSkinnedInputs;

    private Expr PreSkinnedInput(bool normal, int output)
    {
        if (!compilingVertex) throw new ConversionException("Pre-skinned material inputs are only available in vertex shaders");
        if (output != 0) throw new ConversionException("Invalid pre-skinned material input output");
        preSkinnedInputs |= normal ? 2 : 1;
        if (normal) {
            if (!meshCoordinates) vertexBasisUsed = true;
            return new Expr("lfxVertexPreSkinnedNormal", 3);
        }
        return meshCoordinates ? new Expr("lfxVertexPreSkinnedPosition", 3)
                : new Expr(AbsolutePosition("lfxVertexPreSkinnedPosition", false).Code, 3);
    }

    private void AppendPreSkinnedInputs(StringBuilder shader)
    {
        shader.AppendLine($"#define LFX_PRESKIN_POSITION {((preSkinnedInputs & 1) != 0 ? 1 : 0)}");
        shader.AppendLine($"#define LFX_PRESKIN_NORMAL {((preSkinnedInputs & 2) != 0 ? 1 : 0)}");
        if ((preSkinnedInputs & 1) != 0) shader.AppendLine("vec3 lfxVertexPreSkinnedPosition;");
        if ((preSkinnedInputs & 2) != 0) shader.AppendLine("vec3 lfxVertexPreSkinnedNormal;");
    }
}
