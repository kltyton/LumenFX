using UnrealBuildTool;

[SupportedPlatforms(UnrealPlatformClass.All)]
public class LumenFxUvLayoutTarget : TargetRules
{
    public LumenFxUvLayoutTarget(TargetInfo target) : base(target)
    {
        Type = TargetType.Program;
        IncludeOrderVersion = EngineIncludeOrderVersion.Latest;
        DefaultBuildSettings = BuildSettingsVersion.Latest;
        LinkType = TargetLinkType.Monolithic;
        LaunchModuleName = "LumenFxUvLayout";
        bBuildDeveloperTools = false;
        bBuildWithEditorOnlyData = false;
        bCompileAgainstEngine = false;
        bCompileAgainstCoreUObject = false;
        bCompileAgainstApplicationCore = false;
        bCompileICU = false;
        bIsBuildingConsoleApplication = true;
    }
}
