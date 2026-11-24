using Newtonsoft.Json.Linq;
using LumenFx.Compiler.Unreal.Parameters;

namespace LumenFx.Compiler.Unreal;

internal enum MaterialTextureSamplerSource { Asset, WorldWrap, WorldClamp, TerrainWeightmap, ViewWorldWrap, ViewWorldClamp }
internal sealed record CompiledTextureParameterSampler(string Name, string Association, int Index, string Sampler);

public sealed partial class MaterialCompiler
{
    private readonly Dictionary<string, SamplerKey> textureSamplerKeys = new(StringComparer.Ordinal);
    private readonly Dictionary<string, MaterialTextureSamplerSource> textureSamplerSources = new(StringComparer.Ordinal);
    private readonly HashSet<string> usedTextureSamplers = new(StringComparer.Ordinal);
    private readonly Dictionary<string, CompiledTextureParameterSampler> textureParameterSamplers = new(StringComparer.Ordinal);

    private IEnumerable<string> UsedTextureSamplers() => samplers.Values.Where(usedTextureSamplers.Contains);

    private Dictionary<string, string> CompiledTextureBindings() =>
        samplerSources.Where(binding => !textureSamplerKeys.ContainsKey(binding.Key) || usedTextureSamplers.Contains(binding.Key))
            .ToDictionary(binding => binding.Key, binding => binding.Value, StringComparer.Ordinal);

    private IReadOnlyDictionary<string, MaterialTextureSamplerSource> CompiledTextureSamplerSources() =>
        textureSamplerSources.Where(binding => usedTextureSamplers.Contains(binding.Key))
            .ToDictionary(binding => binding.Key, binding => binding.Value, StringComparer.Ordinal);

    private CompiledMaterialTexture[] CompiledRuntimeTextures() =>
        runtimeTextures.Where(binding => usedTextureSamplers.Contains(binding.Key)).Select(binding => binding.Value).ToArray();

    private CompiledTextureParameterSampler[] CompiledTextureParameterSamplers() =>
        textureParameterSamplers.Where(binding => usedTextureSamplers.Contains(binding.Key)).Select(binding => binding.Value).ToArray();

    private string RegisterTextureSampler(SamplerKey key)
    {
        if (samplers.TryGetValue(key, out string? sampler)) return sampler;
        ReadTextureSettings(key.Source);
        samplers.Add(key, sampler = "Sampler" + samplers.Count);
        samplerSources.Add(sampler, key.Source);
        textureSamplerKeys.Add(sampler, key);
        if (key.SamplerSource != MaterialTextureSamplerSource.Asset) textureSamplerSources.Add(sampler, key.SamplerSource);
        return sampler;
    }

    private Expr TextureWithSamplerSource(Expr texture, JObject properties, MaterialTextureShape shape, ref bool manualViewBias)
    {
        string mode = (properties.Value<string>("SamplerSource") ?? "SSM_FromTextureAsset").Split("::").Last();
        MaterialTextureSamplerSource source = mode switch {
            "SSM_FromTextureAsset" or "0" => MaterialTextureSamplerSource.Asset,
            "SSM_Wrap_WorldGroupSettings" or "1" => manualViewBias ? MaterialTextureSamplerSource.ViewWorldWrap : MaterialTextureSamplerSource.WorldWrap,
            "SSM_Clamp_WorldGroupSettings" or "2" => manualViewBias ? MaterialTextureSamplerSource.ViewWorldClamp : MaterialTextureSamplerSource.WorldClamp,
            "SSM_TerrainWeightmapGroupSettings" or "3" => MaterialTextureSamplerSource.TerrainWeightmap,
            _ => throw new ConversionException($"Unknown material texture sampler source {mode}")
        };
        if (source == MaterialTextureSamplerSource.Asset) return texture;
        if (source == MaterialTextureSamplerSource.TerrainWeightmap
                && shape is (MaterialTextureShape.Array2D or MaterialTextureShape.Cube or MaterialTextureShape.CubeArray))
            throw new ConversionException("Terrain weightmap sampling requires a non-array, non-cube material texture");
        manualViewBias = false;
        string sampler = RegisterTextureSampler(textureSamplerKeys[texture.Code] with { SamplerSource = source });
        if (runtimeTextures.TryGetValue(texture.Code, out var runtime))
            runtimeTextures[sampler] = runtime with { Sampler = sampler };
        if (textureParameterSamplers.TryGetValue(texture.Code, out var dynamic))
            textureParameterSamplers[sampler] = dynamic with { Sampler = sampler };
        return texture with { RawCode = sampler };
    }
}
