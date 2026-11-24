using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class ArrayDataInterfaceCompiler
{
    private sealed record Layout(string Field, string? CookedField, int Components, bool Integer, int[] Default);

    private static readonly IReadOnlyDictionary<string, Layout> Layouts = new Dictionary<string, Layout> {
        ["NiagaraDataInterfaceArrayFloat"] = new("FloatData", null, 1, false, new[] { 0 }),
        ["NiagaraDataInterfaceArrayFloat2"] = new("FloatData", "InternalFloatData", 2, false, new[] { 0, 0 }),
        ["NiagaraDataInterfaceArrayFloat3"] = new("FloatData", "InternalFloatData", 3, false, new[] { 0, 0, 0 }),
        ["NiagaraDataInterfaceArrayPosition"] = new("PositionData", null, 3, false, new[] { 0, 0, 0 }),
        ["NiagaraDataInterfaceArrayFloat4"] = new("FloatData", "InternalFloatData", 4, false, new[] { 0, 0, 0, 0 }),
        ["NiagaraDataInterfaceArrayColor"] = new("ColorData", null, 4, false, Enumerable.Repeat(BitConverter.SingleToInt32Bits(1), 4).ToArray()),
        ["NiagaraDataInterfaceArrayQuat"] = new("QuatData", "InternalQuatData", 4, false, new[] { 0, 0, 0, BitConverter.SingleToInt32Bits(1) }),
        ["NiagaraDataInterfaceArrayMatrix"] = new("MatrixData", "InternalMatrixData", 16, false,
            Enumerable.Range(0, 16).Select(i => BitConverter.SingleToInt32Bits(i % 5 == 0 ? 1 : 0)).ToArray()),
        ["NiagaraDataInterfaceArrayInt32"] = new("IntData", null, 1, true, new[] { 0 }),
        ["NiagaraDataInterfaceArrayUInt8"] = new("IntData", "InternalIntData", 1, true, new[] { 0 }),
        ["NiagaraDataInterfaceArrayBool"] = new("BoolData", null, 1, true, new[] { 0 }),
        ["NiagaraDataInterfaceArrayNiagaraID"] = new("IntData", null, 2, true, new[] { -1, -1 })
    };

    public static bool Supports(string type) => Layouts.ContainsKey(type);

    private static readonly string[] GpuWriteFunctions = { "Clear", "Resize", "ResizeAndFill", "SetArrayElem", "Add",
        "RemoveLastElem", "AtomicAdd", "AtomicMin", "AtomicMax" };

    internal static bool IsGpuWriteFunction(string name)
        => GpuWriteFunctions.Any(value => SourceNameOrder.Equal(name, value));

    public static JObject Compile(SourceObject data, string function, int inputs, int outputs)
        => Compile(data.ClassName, data.Properties, data.Name, function, inputs, outputs);

    internal static JObject Compile(string type, JObject properties, string source, string function, int inputs, int outputs)
    {
        Layout layout = Layouts[type];
        int width = layout.Components;
        bool valid = function switch {
            "Length" or "LastIndex" => inputs == 0 && outputs == 1,
            "IsValidIndex" => inputs == 1 && outputs == 1,
            "Get" => inputs == 1 && outputs == width,
            "Clear" => inputs == 0 && outputs == 0,
            "Resize" => inputs == 1 && outputs == 0,
            "ResizeAndFill" => inputs == width + 2 && outputs == 0,
            "SetArrayElem" => inputs == width + 2 && outputs == 0,
            "Add" => inputs == width + 1 && outputs == 0,
            "RemoveLastElem" => inputs == 1 && outputs == width + 1,
            "AtomicAdd" or "AtomicMin" or "AtomicMax" => type == "NiagaraDataInterfaceArrayInt32" && inputs == 3 && outputs == 2,
            _ => false };
        if (!valid) throw new ConversionException($"Unsupported array function signature: {source} ({type}):{function}");
        return Definition(type, properties, source);
    }

    public static JObject Definition(SourceObject data)
        => Definition(data.ClassName, data.Properties, data.Name);

    internal static JObject Definition(string type, JObject properties, string name)
    {
        Layout layout = Layouts[type];
        int width = layout.Components;
        JArray values = properties[layout.Field] as JArray
            ?? (layout.CookedField is null ? null : properties[layout.CookedField] as JArray) ?? new JArray();
        JArray words = new();
        foreach (JToken value in values)
        {
            if (layout.Components == 1) words.Add(Scalar(type, value, layout.Integer));
            else if (type == "NiagaraDataInterfaceArrayMatrix" && value is JObject matrix)
            {
                foreach (string row in new[] { "XPlane", "YPlane", "ZPlane", "WPlane" })
                {
                    if (matrix[row] is not JObject plane) throw new ConversionException($"Missing matrix row {row}: {name}");
                    foreach (string component in new[] { "X", "Y", "Z", "W" })
                    {
                        if (plane[component] is not JToken element) throw new ConversionException($"Missing matrix component {row}.{component}: {name}");
                        words.Add(Scalar(type, element, false));
                    }
                }
            }
            else if (value is JObject vector)
            {
                string[] names = type switch {
                    "NiagaraDataInterfaceArrayColor" => new[] { "R", "G", "B", "A" },
                    "NiagaraDataInterfaceArrayNiagaraID" => new[] { "Index", "AcquireTag" },
                    _ => new[] { "X", "Y", "Z", "W" } };
                for (int i = 0; i < width; i++) {
                    if (vector[names[i]] is not JToken element) throw new ConversionException($"Missing array component {names[i]}: {name}");
                    words.Add(Scalar(type, element, layout.Integer));
                }
            }
            else throw new ConversionException($"Invalid array element in {name}");
        }
        return new JObject { ["class"] = type, ["components"] = width, ["integer"] = layout.Integer,
            ["default"] = new JArray(layout.Default), ["words"] = words,
            ["maximum_elements"] = properties.Value<int?>("MaxElements") ?? 0,
            ["gpu_sync_mode"] = (properties.Value<string>("GpuSyncMode") ?? "SyncCpuToGpu").Split("::").Last() };
    }

    private static int Scalar(string type, JToken value, bool integer)
    {
        if (type == "NiagaraDataInterfaceArrayBool") return value.Value<bool>() ? -1 : 0;
        if (integer) {
            int number = value.Value<int>();
            return type == "NiagaraDataInterfaceArrayUInt8" ? number & 255 : number;
        }
        return BitConverter.SingleToInt32Bits((float)SourceAsset.Number(value));
    }
}
