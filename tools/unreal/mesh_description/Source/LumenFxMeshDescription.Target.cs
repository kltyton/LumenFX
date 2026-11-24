using UnrealBuildTool;

[SupportedPlatforms(UnrealPlatformClass.Desktop)]
public class LumenFxMeshDescriptionTarget : TargetRules
{
    public LumenFxMeshDescriptionTarget(TargetInfo target) : base(target)
    {
        Type = TargetType.Program;
        IncludeOrderVersion = EngineIncludeOrderVersion.Latest;
        DefaultBuildSettings = BuildSettingsVersion.Latest;
        LinkType = TargetLinkType.Monolithic;
        LaunchModuleName = "LumenFxMeshDescription";
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
