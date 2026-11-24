using Newtonsoft.Json.Linq;

namespace LumenFx.Compiler.Unreal.Execution;

internal static class SpriteRendererInfoCompiler
{
    internal static void ValidateCpu(string name, int inputs, int outputs)
    {
        bool valid = inputs == 0 && (name switch {
            "IsValid" or "GetSourceMode" or "GetAlignment" or "GetFacingMode" => outputs == 1,
            "GetSubUVDetails" => outputs == 3, _ => false });
        if (!valid) throw new ConversionException($"Invalid sprite renderer information signature {name}: {inputs}, {outputs}");
    }

    internal static JObject CompileInterface(AssetLibrary? library, SourceAsset asset, SourceObject? data, string? reference = null)
    {
        SourceAsset owner = asset;
        if (data is null && reference is not null && !reference.EndsWith(".Default__NiagaraDataInterfaceSpriteRendererInfo", StringComparison.Ordinal)) {
            owner = library?.Load(reference) ?? throw new ConversionException($"Sprite renderer information requires its source library: {reference}");
            data = owner.ResolveObject(reference, value => value.ClassName == "NiagaraDataInterfaceSpriteRendererInfo");
        }
        var renderer = Resolve(library, owner, data?.Properties.Value<int?>("SpriteRenderer") ?? 0);
        return Compile(renderer.Asset, renderer.Renderer);
    }

    public static (SourceAsset Asset, JObject? Renderer) Resolve(AssetLibrary? library, SourceAsset asset, int index)
        => RendererInfoSources.Resolve(library, asset, index, "NiagaraSpriteRendererProperties");

    public static JObject Compile(SourceAsset asset, JObject? renderer)
    {
        int mainVersion = RendererInfoSources.CustomVersion(asset, "{697DD581-E64F-41AB-AA4A-51ECBEB7B628}");
        int niagaraVersion = RendererInfoSources.CustomVersion(asset, "{FCF57AFA-5076-4283-B9A9-E658FFA02D32}");
        string alignment = NiagaraSpriteRendererSource.Alignment(mainVersion, renderer);
        string facing = NiagaraSpriteRendererSource.Facing(mainVersion, renderer);
        string source = renderer is null ? "Particles" : RendererAttributeBindings.SourceMode(renderer);
        int sourceMode = source switch { "Particles" => 0, "Emitter" => 1,
            _ => throw new ConversionException($"Unknown Niagara sprite source mode {source}") };
        int alignmentMode = alignment switch { "Unaligned" => 0, "VelocityAligned" => 1,
            "CustomAlignment" => 2, "Automatic" => 3,
            _ => throw new ConversionException($"Unknown Niagara sprite alignment {alignment}") };
        int facingMode = facing switch { "FaceCamera" => 0, "FaceCameraPlane" => 1,
            "CustomFacingVector" => 2, "FaceCameraPosition" => 3, "FaceCameraDistanceBlend" => 4,
            "Automatic" => 5, _ => throw new ConversionException($"Unknown Niagara sprite facing {facing}") };
        bool blend = NiagaraSpriteRendererSource.SubImageBlend(niagaraVersion, renderer);
        return new JObject {
            ["is_valid"] = renderer is not null,
            ["source_mode"] = sourceMode, ["alignment"] = alignmentMode, ["facing_mode"] = facingMode,
            ["sub_image_blend"] = blend,
            ["sub_image_size"] = new JArray(SourceAsset.Number(renderer?["SubImageSize"]?["X"], 1),
                SourceAsset.Number(renderer?["SubImageSize"]?["Y"], 1))
        };
    }

}
