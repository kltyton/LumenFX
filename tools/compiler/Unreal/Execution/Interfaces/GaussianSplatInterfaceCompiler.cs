using LumenFx.Compiler.Unreal.Parameters;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class GaussianSplatInterfaceCompiler
{
    internal const string ReaderClass = "NiagaraDataInterfaceGaussianSplatReader";
    internal const string WriterClass = "NiagaraDataInterfaceGaussianSplatWriter";

    internal static JObject Reader(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, ReaderClass, library);
        var properties = source?.Object?.Properties;
        var result = new JObject { ["max_sh_degree"] = ReadOptionalInt(properties?["MaxSHDegree"]) ?? 3 };
        int asset = properties?.Value<int?>("GaussianSplat") ?? 0;
        if (asset != 0) result["asset_source"] = source!.Asset.ObjectPath(asset);
        return result;
    }

    internal static void ValidateReaderCpu(string name, int inputs, int outputs)
    {
        var expected = name switch {
            "GetNumSplats" or "GetSHDegree" => (0, 1),
            "GetBounds" => (0, 6),
            "GetBaseData" => (1, 11),
            "GetSHCoefficient" => (2, 3),
            "CalculateColor" => (4, 3),
            _ => throw new ConversionException($"Niagara Gaussian Splat Reader function is GPU-only or unknown: {name}")
        };
        if (expected != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Gaussian Splat Reader signature: {name} ({inputs}, {outputs})");
    }

    internal static JObject Writer(SourceAsset owner, int reference, AssetLibrary? library)
    {
        var source = reference == 0 ? null : DataInterfaceInstanceSources.ResolveSource(owner, reference, WriterClass, library);
        var properties = source?.Object?.Properties;
        var result = new JObject {
            ["count"] = ReadOptionalInt(properties?["NumSplats"]),
            ["sh_degree"] = ReadOptionalInt(properties?["SHDegree"]),
            ["splat_scale"] = SourceAsset.Number(properties?["SplatScale"], 1),
            ["opacity_scale"] = SourceAsset.Number(properties?["OpacityScale"], 1),
            ["inverse_tone_curve"] = properties?.Value<bool?>("bInvertToneCurve") == true
                ? SourceAsset.Number(properties?["InverseToneCurveStrength"], 1) : 0,
            ["invert_expand_gamut"] = properties?.Value<bool?>("bInvertExpandGamut") ?? true
        };
        var coordinate = Optional(properties?["SHCoordinateSpace"]);
        if (coordinate is not null) result["sh_coordinate_space"] = coordinate.Type == JTokenType.Integer
            ? coordinate.Value<int>() : coordinate.Value<string>()?.Split("::").Last() switch {
                "LH_FRU" => 0, "RH_RUB" => 1, "RH_RDF" => 2,
                _ => throw new ConversionException($"Invalid Niagara Gaussian Splat coordinate space: {coordinate}")
            };
        var bounds = Optional(properties?["PositionBounds"]);
        if (bounds is not null) {
            result["position_min"] = Vector(bounds["Min"]);
            result["position_max"] = Vector(bounds["Max"]);
        }
        var scale = Optional(properties?["MaxScale"]);
        if (scale is not null) result["max_scale"] = SourceAsset.Number(scale);
        var coefficient = Optional(properties?["MaxSHCoefficient"]);
        if (coefficient is not null) result["max_sh_coefficient"] = SourceAsset.Number(coefficient);
        return result;
    }

    internal static void ValidateWriterCpu(string name, int inputs, int outputs)
    {
        var expected = name switch {
            "GetNumSplats" or "GetSHDegree" => (0, 1),
            "Clear" => (1, 0),
            _ => throw new ConversionException($"Niagara Gaussian Splat Writer function is GPU-only or unknown: {name}")
        };
        if (expected != (inputs, outputs))
            throw new ConversionException($"Invalid Niagara Gaussian Splat Writer signature: {name} ({inputs}, {outputs})");
    }

    internal static void BindWriters(SourceAsset asset, SourceObject system, IReadOnlyList<DataInterfaceInstanceSources.Entry> instances,
        JObject program, AssetLibrary? library)
    {
        var readerByEmitter = new Dictionary<Guid, DataInterfaceInstanceSources.Entry>();
        var emitterByWriter = new Dictionary<string, Guid>(SourceNameOrder.Instance);
        foreach (var entry in instances) {
            var parameter = entry.Parameter;
            var owner = parameter.ObjectAsset ?? asset;
            switch (owner.ReferencedClass(parameter.Reference)) {
                case ReaderClass when entry.Emitter != Guid.Empty: readerByEmitter.TryAdd(entry.Emitter, entry); break;
                case WriterClass when entry.Emitter != Guid.Empty:
                    emitterByWriter[parameter.InstanceId ?? ScriptDataInterfaceBindings.Identity(owner, parameter.Reference)] = entry.Emitter;
                    break;
            }
        }
        if (emitterByWriter.Count == 0) return;

        var localSpace = new Dictionary<Guid, bool>();
        foreach (JObject handle in system.Properties["EmitterHandles"] as JArray ?? new()) {
            if (!Guid.TryParse(handle.Value<string>("Id"), out var id) || !emitterByWriter.ContainsValue(id)) continue;
            localSpace.Add(id, EmitterHandleSource.Resolve(asset, handle).Settings.Value<bool>("bLocalSpace"));
        }
        foreach (var descriptor in program.Descendants().OfType<JObject>().ToArray()) {
            var writer = descriptor["gaussian_splat_writer"] as JObject
                ?? descriptor["interface_data"]?["gaussian_splat_writer"] as JObject;
            var identity = descriptor.Value<string>("interface_id") ?? descriptor.Value<string>("id");
            if (writer is null || identity is null || !emitterByWriter.TryGetValue(identity, out var emitter)) continue;
            writer["local_space"] = localSpace[emitter];
            if (!readerByEmitter.TryGetValue(emitter, out var reader)) continue;
            var parameter = reader.Parameter;
            writer["gaussian_splat_reader"] = Reader(parameter.ObjectAsset ?? asset, parameter.Reference, library);
        }
    }

    private static int? ReadOptionalInt(JToken? value)
    {
        value = Optional(value);
        if (value is null) return null;
        if (value.Type == JTokenType.Integer) return value.Value<int>();
        throw new ConversionException($"Invalid Niagara Gaussian Splat optional integer: {value.Type}");
    }

    private static JToken? Optional(JToken? value) => value?.Type == JTokenType.Null ? null : value;

    private static JArray Vector(JToken? value) => new(SourceAsset.Number(value?["X"]), SourceAsset.Number(value?["Y"]),
        SourceAsset.Number(value?["Z"]));
}
