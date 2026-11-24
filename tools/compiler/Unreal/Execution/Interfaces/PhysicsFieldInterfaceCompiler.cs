namespace LumenFx.Compiler.Unreal.Execution;

internal static class PhysicsFieldInterfaceCompiler
{
    internal const string ClassName = "NiagaraDataInterfacePhysicsField";

    internal static void ValidateCpu(string function, int inputs, int outputs)
    {
        var shape = function switch {
            "SamplePhysicsVectorField" or "EvalPhysicsVectorField" => (4, 3),
            "SamplePhysicsScalarField" or "EvalPhysicsScalarField" or
            "SamplePhysicsIntegerField" or "EvalPhysicsIntegerField" => (4, 1),
            "GetPhysicsFieldResolution" => (0, 3),
            "GetPhysicsFieldBounds" => (0, 6),
            _ => (-1, -1)
        };
        if (shape != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara physics field function {function} ({inputs}, {outputs})");
    }
}
