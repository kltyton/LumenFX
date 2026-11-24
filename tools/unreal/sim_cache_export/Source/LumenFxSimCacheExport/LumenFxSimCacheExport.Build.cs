using UnrealBuildTool;

public class LumenFxSimCacheExport : ModuleRules
{
    public LumenFxSimCacheExport(ReadOnlyTargetRules target) : base(target)
    {
        PublicIncludePathModuleNames.Add("Launch");
        PrivateDependencyModuleNames.AddRange(new[] { "Core", "CoreUObject", "Engine", "Projects", "Json", "Niagara" });
        PrivateIncludePaths.Add(System.IO.Path.Combine(EngineDirectory, "Plugins", "FX", "Niagara", "Source", "Niagara", "Internal"));
        PrivateIncludePaths.Add(System.IO.Path.Combine(EngineDirectory, "Source", "Runtime", "Engine", "Private", "SparseVolumeTexture"));
        bool openVdb = target.IsInPlatformGroup(UnrealPlatformGroup.Windows) || target.Platform == UnrealTargetPlatform.Linux;
        PrivateDefinitions.Add(openVdb ? "LUMENFX_WITH_OPENVDB=1" : "LUMENFX_WITH_OPENVDB=0");
        if (openVdb)
        {
            bUseRTTI = target.IsInPlatformGroup(UnrealPlatformGroup.Windows);
            bEnableExceptions = true;
            bDisableAutoRTFMInstrumentation = true;
            AddEngineThirdPartyPrivateStaticDependencies(target, "IntelTBB", "Blosc", "zlib", "Boost", "OpenVDB");
        }
    }
}
