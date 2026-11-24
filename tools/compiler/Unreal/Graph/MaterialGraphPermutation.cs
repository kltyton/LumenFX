namespace LumenFx.Compiler.Unreal;

// These indices address the UPROPERTY static input arrays in the fixed UE source.
internal enum MaterialQualityInput { Low, High, Medium, Epic, Default }
internal enum MaterialFeatureInput { Es3_1 = 1, Sm5 = 3, Sm6 = 4 }
internal enum MaterialShadingInput { Deferred, Forward, Mobile }

internal sealed record MaterialGraphPermutation(MaterialQualityInput Quality = MaterialQualityInput.High,
    MaterialFeatureInput FeatureLevel = MaterialFeatureInput.Sm5, MaterialShadingInput? ShadingPathOverride = null,
    bool SimulateMobileForLowQuality = false, MaterialShaderPlatformFlags? PlatformFlags = null,
    int SubstrateByteLimit = 80, int SubstrateClosureLimit = 4)
{
    private bool SimulatesMobile => SimulateMobileForLowQuality && Quality == MaterialQualityInput.Low
        && (int)FeatureLevel > (int)MaterialFeatureInput.Es3_1;
    internal MaterialFeatureInput EffectiveFeatureLevel => SimulatesMobile ? MaterialFeatureInput.Es3_1 : FeatureLevel;
    internal MaterialShaderPlatformFlags ShaderPlatform => PlatformFlags ?? MaterialShaderPlatformFlags.Desktop;
    internal MaterialShadingInput ShadingPath => ShadingPathOverride ?? ((int)EffectiveFeatureLevel < (int)MaterialFeatureInput.Sm5
        ? MaterialShadingInput.Mobile : MaterialShadingInput.Deferred);
    internal bool AdaptiveSubstrate => ShadingPath == MaterialShadingInput.Deferred
        && EffectiveFeatureLevel != MaterialFeatureInput.Es3_1
        && (PlatformFlags?.SupportsAdaptiveGBuffer ?? EffectiveFeatureLevel == MaterialFeatureInput.Sm6);
    internal int SubstrateBytes => AdaptiveSubstrate ? Math.Clamp((SubstrateByteLimit + 3) & ~3, 20, 256) : 20;
    internal int SubstrateClosures => AdaptiveSubstrate ? Math.Clamp(SubstrateClosureLimit, 1, 8) : 1;
}
