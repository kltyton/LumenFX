using System.Buffers.Binary;
using LumenFx.Compiler.Unreal.Parameters.Structure;
using LumenFx.Compiler.Unreal.Types;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Parameters;

/** Execution stores convert unfriendly LWC structs independently of archive type restoration. */
internal static class ExecutionNumericParameterSource
{
    internal sealed record Value(string Name, NiagaraTypeSource.Definition Type, byte[] Data, bool? Lwc);

    internal static Value Read(SourceAsset asset, JObject variable, bool previous = false)
    {
        string name = variable.Value<string>("name") ?? throw new ConversionException("Execution parameter has no name");
        if (previous) name = "PREV_" + name;
        JObject sourceType = variable["type"] as JObject ?? throw new ConversionException($"Execution parameter has no type: {name}");
        var type = NiagaraTypeSource.Read(asset, sourceType);
        if (type.UnderlyingType == 1) return new(name, type, Array.Empty<byte>(), false);
        if (type.UnderlyingType is not (2 or 3)) throw new ConversionException($"Execution parameter has no numeric type: {name}");
        byte[] data = previous ? Array.Empty<byte>() : NumericParameterStoreSource.Bytes(variable["data"]);
        bool? lwc = NiagaraTypeSource.IsLwc(asset, sourceType);
        if (lwc == true) {
            StructLayout source = BuiltinStructLayouts.TryGet(type.Path, out var builtin) ? builtin!
                : StructLayoutSource.Find(asset, type.SourceReference)
                    ?? throw new ConversionException($"Execution conversion needs source fields: {name} ({type.Path})");
            string? known = NiagaraTypeSource.KnownSwc(type.Path);
            if (known is not null) {
                if (!BuiltinStructLayouts.TryGet(known, out var simulation))
                    throw new ConversionException($"Execution conversion has no builtin simulation layout: {known}");
                int size = simulation!.Size;
                if (data.Length != 0) {
                    if (data.Length < source.Size || source.Size != size * 2 || size % sizeof(float) != 0)
                        throw new ConversionException($"Execution LWC value does not match its source layout: {name}");
                    byte[] converted = new byte[size];
                    for (int offset = 0; offset < size; offset += sizeof(float)) {
                        double value = BitConverter.Int64BitsToDouble(BinaryPrimitives.ReadInt64LittleEndian(data.AsSpan(offset * 2)));
                        BinaryPrimitives.WriteInt32LittleEndian(converted.AsSpan(offset), BitConverter.SingleToInt32Bits((float)value));
                    }
                    data = converted;
                }
                type = new(known, 2, 0, size, type.SourceReference);
            } else {
                NiagaraSwcStructLayout projection = NiagaraTypeSource.Projection(source);
                if (data.Length != 0) {
                    byte[] converted = new byte[projection.Simulation.Size];
                    if (data.Length < projection.SourceStride || converted.Length < projection.SimulationStride)
                        throw new ConversionException($"Execution SWC conversion exceeds its linked struct layout: {name} ({type.Path})");
                    projection.ToSimulation(data, converted, 1);
                    data = converted;
                }
                type = new(projection.Simulation.Path, 2, 0, projection.Simulation.Size, type.SourceReference);
            }
            lwc = false;
        }
        int? width = type.UnderlyingType == 3 ? sizeof(int) : type.Size;
        if (width is null && BuiltinStructLayouts.TryGet(type.Path, out var layout)) width = layout!.Size;
        if (width is null && data.Length == 0)
            width = StructLayoutSource.Find(asset, type.SourceReference)?.Size;
        if (width is null) {
            if (data.Length == 0) throw new ConversionException($"Unallocated execution parameter needs its source size: {name} ({type.Path})");
            width = data.Length;
        }
        if (data.Length == 0) data = new byte[width.Value];
        else {
            if (data.Length < width.Value) throw new ConversionException($"Execution parameter data is shorter than its source type: {name}");
            if (data.Length != width.Value) data = data.AsSpan(0, width.Value).ToArray();
        }
        return new(name, type, data, lwc);
    }
}
