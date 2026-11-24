using UnrealBuildTool;

[SupportedPlatforms(UnrealPlatformClass.Desktop)]
public class LumenFxTextureBuildTarget : TargetRules
{
    public LumenFxTextureBuildTarget(TargetInfo target) : base(target)
    {
        Type = TargetType.Program;
        IncludeOrderVersion = EngineIncludeOrderVersion.Latest;
        DefaultBuildSettings = BuildSettingsVersion.Latest;
        LinkType = TargetLinkType.Monolithic;
        LaunchModuleName = "LumenFxTextureBuild";
        bBuildDeveloperTools = true;
        bBuildWithEditorOnlyData = true;
        bCompileAgainstEditor = true;
        bCompileAgainstEngine = true;
        bCompileAgainstCoreUObject = true;
        bCompileAgainstApplicationCore = false;
        bForceBuildTargetPlatforms = true;
        bCompileICU = false;
        bIsBuildingConsoleApplication = true;
    }
}
