using LumenFx.Compiler.Unreal.Execution;
using LumenFx.Compiler.Unreal.Geometry;
using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal;

internal static class EmitterHandleSource
{
    internal sealed record Resolved(SourceObject Source, JObject Settings, string Namespace, bool Stateless, string Target);

    internal static Resolved Resolve(SourceAsset asset, JObject handle)
    {
        bool standard = IsStandard(handle);
        int standardReference = handle["VersionedInstance"]?.Value<int?>("Emitter") ?? 0;
        SourceObject source = asset.Object(standard ? standardReference : handle.Value<int>("StatelessEmitter"));
        // GetUniqueInstanceName still prefers a saved standard emitter when both representations are present.
        SourceObject namespaceSource = standardReference != 0 ? asset.Object(standardReference) : source;
        string sourceName = namespaceSource.Properties.Value<string>("UniqueEmitterName")
            ?? throw new ConversionException($"Missing Niagara emitter namespace: {namespaceSource.Name}");
        if (standard) {
            JObject version = EmitterVersionSource.Require(source, handle["VersionedInstance"]?.Value<string>("Version"));
            return new Resolved(source, version, sourceName, false, SimulationTarget(version));
        }
        JObject settings = source.Properties;
        string statelessTarget = "GPUComputeSim";
        foreach (int reference in (settings["RendererProperties"] as JArray ?? new JArray()).Values<int>()) {
            if (reference == 0) continue;
            SourceObject renderer = asset.Object(reference);
            JObject properties = RendererAttributeBindings.LoadedProperties(asset, renderer);
            if (properties.Value<bool?>("bIsEnabled") == false) continue;
            bool supportsGpu = renderer.ClassName switch {
                "NiagaraSpriteRendererProperties" or "NiagaraRibbonRendererProperties" or
                "NiagaraMeshRendererProperties" or NaniteRendererSource.ClassName or "NiagaraVolumeRendererProperties" or
                "NiagaraSplatRendererProperties" => true,
                "NiagaraLightRendererProperties" or "NiagaraDecalRendererProperties" =>
                    RendererAttributeBindings.SourceMode(properties) == "Emitter",
                _ => false
            };
            if (!supportsGpu) statelessTarget = "CPUSim";
        }
        settings["RendererProperties"] ??= new JArray();
        settings["bLocalSpace"] = true;
        settings["SimTarget"] = statelessTarget;
        return new Resolved(source, settings, sourceName, true, statelessTarget);
    }

    internal static string SimulationTarget(JObject settings) => settings.Value<string>("SimTarget")?.Split("::").Last() switch {
        null or "0" or "CPUSim" => "CPUSim",
        "1" or "GPUComputeSim" => "GPUComputeSim",
        var target => throw new ConversionException($"Unknown Niagara simulation target: {target}")
    };

    internal static string BoundsMode(JObject settings) => settings.Value<string>("CalculateBoundsMode")?.Split("::").Last() switch {
        null or "0" or "Dynamic" => "Dynamic",
        "1" or "Fixed" => "Fixed",
        "2" or "Programmable" => "Programmable",
        var mode => throw new ConversionException($"Unknown Niagara emitter bounds mode: {mode}")
    };

    internal static string AllocationMode(JObject settings) => settings.Value<string>("AllocationMode")?.Split("::").Last() switch {
        null or "0" or "AutomaticEstimate" => "AutomaticEstimate",
        "1" or "ManualEstimate" => "ManualEstimate",
        "2" or "FixedCount" => "FixedCount",
        var mode => throw new ConversionException($"Unknown Niagara particle allocation mode: {mode}")
    };

    internal static bool IsStandard(JObject handle)
    {
        string mode = (handle.Value<string>("EmitterMode") ?? "Standard").Split("::").Last();
        return mode switch {
            "Standard" or "0" => true,
            "Stateless" or "1" => false,
            _ => throw new ConversionException($"Invalid Niagara emitter mode {mode}: {handle.Value<string>("Name")}")
        };
    }
}
