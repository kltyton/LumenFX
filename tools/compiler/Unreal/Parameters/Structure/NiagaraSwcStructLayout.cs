using System.Buffers.Binary;
using LumenFx.Compiler.Unreal.Types;

namespace LumenFx.Compiler.Unreal.Parameters.Structure;

/** Keeps native conversion strides separate from the transient struct's final linked layout. */
internal sealed class NiagaraSwcStructLayout
{
    internal enum Conversion { CopyOnly, DoubleToFloat, Vector2, Vector3, Vector4, Quat }
    internal sealed record Step(int SourceBytes, int SourceOffset, int SimulationBytes, int SimulationOffset, Conversion Kind);
    internal StructLayout Source { get; }
    internal StructLayout Simulation { get; }
    internal IReadOnlyList<Step> Steps { get; }
    internal int SourceStride { get; }
    internal int SimulationStride { get; }

    private NiagaraSwcStructLayout(StructLayout source, StructLayout simulation, Step[] steps)
    {
        Source = source; Simulation = simulation; Steps = Array.AsReadOnly(steps);
        SourceStride = steps.Length == 0 ? 0 : checked(steps[^1].SourceOffset + steps[^1].SourceBytes);
        SimulationStride = steps.Length == 0 ? 0 : checked(steps[^1].SimulationOffset + steps[^1].SimulationBytes);
    }

    internal static NiagaraSwcStructLayout Build(StructLayout source)
    {
        List<StructField> fields = new();
        List<Step> steps = new();
        int conversionOffset = 0, linkedOffset = 0, linkedAlignment = 1;
        foreach (var field in source.Fields) {
            StructField target = field with { Offset = 0 };
            Conversion conversion = Conversion.CopyOnly;
            int conversionAlignment = field.Alignment;
            switch (field.Kind) {
                case StructFieldKind.Double:
                    target = new(field.Name, StructFieldKind.Float, 0, sizeof(float), 1, sizeof(float));
                    conversion = Conversion.DoubleToFloat;
                    break;
                case StructFieldKind.Struct:
                    StructLayout structure = field.Structure ?? throw new ConversionException("Struct property has no linked structure");
                    string? path = VectorTarget(structure.Name);
                    if (path is not null) {
                        if (!BuiltinStructLayouts.TryGet(path, out var replacement)) throw new ConversionException($"Missing SWC vector layout {path}");
                        structure = replacement!;
                        conversion = path.EndsWith(".Vector2f", StringComparison.Ordinal) ? Conversion.Vector2
                            : path.EndsWith(".Vector3f", StringComparison.Ordinal) ? Conversion.Vector3
                            : path.EndsWith(".Vector4f", StringComparison.Ordinal) ? Conversion.Vector4 : Conversion.Quat;
                    }
                    target = new(field.Name, StructFieldKind.Struct, 0, structure.Size, 1, structure.Alignment, structure);
                    conversionAlignment = structure.Alignment;
                    break;
                case StructFieldKind.Float:
                case StructFieldKind.Int32:
                case StructFieldKind.UInt32:
                case StructFieldKind.Enum:
                case StructFieldKind.Byte:
                case StructFieldKind.Bool:
                    break;
                default: throw new ConversionException($"Unsupported property '{field.Name}' in custom struct '{source.Name}'");
            }
            conversionOffset = StructLayout.Align(conversionOffset, conversionAlignment);
            steps.Add(new(field.Size, field.Offset, target.Size, conversionOffset, conversion));
            conversionOffset = checked(conversionOffset + target.Size);

            linkedOffset = StructLayout.Align(linkedOffset, target.Alignment);
            fields.Add(target with { Offset = linkedOffset });
            linkedOffset = checked(linkedOffset + target.Size);
            linkedAlignment = Math.Max(linkedAlignment, target.Alignment);
        }
        string name = source.Name + "_SWC";
        StructLayout simulation = new("/Engine/Transient." + name, name, linkedOffset, linkedAlignment, Array.AsReadOnly(fields.ToArray()));
        return new(source, simulation, steps.ToArray());
    }

    private static string? VectorTarget(string name)
    {
        if (SourceNameOrder.Equal(name, "Vector2D") || SourceNameOrder.Equal(name, "Vector2d")) return "/Script/CoreUObject.Vector2f";
        if (SourceNameOrder.Equal(name, "Vector") || SourceNameOrder.Equal(name, "Vector3d")) return "/Script/CoreUObject.Vector3f";
        if (SourceNameOrder.Equal(name, "Vector4") || SourceNameOrder.Equal(name, "Vector4d")) return "/Script/CoreUObject.Vector4f";
        if (SourceNameOrder.Equal(name, "Quat") || SourceNameOrder.Equal(name, "Quat4d")) return "/Script/CoreUObject.Quat4f";
        return null;
    }

    internal void ToSimulation(ReadOnlySpan<byte> source, Span<byte> destination, int count)
        => Copy(source, destination, count, true);

    internal void FromSimulation(ReadOnlySpan<byte> source, Span<byte> destination, int count)
        => Copy(source, destination, count, false);

    private void Copy(ReadOnlySpan<byte> source, Span<byte> destination, int count, bool toSimulation)
    {
        if (count < 0) throw new ArgumentOutOfRangeException(nameof(count));
        if (count == 0 || Steps.Count == 0) return;
        foreach (Step step in Steps) {
            int components = step.Kind switch {
                Conversion.CopyOnly => 0, Conversion.DoubleToFloat => 1, Conversion.Vector2 => 2,
                Conversion.Vector3 => 3, Conversion.Vector4 or Conversion.Quat => 4,
                _ => throw new ConversionException("Unknown SWC conversion kind")
            };
            if (components == 0 ? step.SourceBytes != step.SimulationBytes
                    : step.SourceBytes != components * sizeof(double) || step.SimulationBytes != components * sizeof(float))
                throw new ConversionException($"Invalid native conversion byte widths for {Source.Name}.{step.Kind}");
            for (int index = 0; index < count; index++) {
                int input = toSimulation ? checked(index * SourceStride + step.SourceOffset)
                    : checked(index * SimulationStride + step.SimulationOffset);
                int output = toSimulation ? checked(index * SimulationStride + step.SimulationOffset)
                    : checked(index * SourceStride + step.SourceOffset);
                if (components == 0) {
                    source.Slice(input, step.SourceBytes).CopyTo(destination.Slice(output, step.SourceBytes));
                    continue;
                }
                for (int component = 0; component < components; component++) {
                    if (toSimulation) {
                        double value = BitConverter.Int64BitsToDouble(BinaryPrimitives.ReadInt64LittleEndian(source.Slice(input + component * sizeof(double))));
                        BinaryPrimitives.WriteInt32LittleEndian(destination.Slice(output + component * sizeof(float)), BitConverter.SingleToInt32Bits((float)value));
                    } else {
                        float value = BitConverter.Int32BitsToSingle(BinaryPrimitives.ReadInt32LittleEndian(source.Slice(input + component * sizeof(float))));
                        BinaryPrimitives.WriteInt64LittleEndian(destination.Slice(output + component * sizeof(double)), BitConverter.DoubleToInt64Bits(value));
                    }
                }
            }
        }
    }
}
