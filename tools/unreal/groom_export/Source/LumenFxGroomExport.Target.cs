using UnrealBuildTool;

[SupportedPlatforms(UnrealPlatformClass.Desktop)]
public class LumenFxGroomExportTarget : TargetRules
{
    public LumenFxGroomExportTarget(TargetInfo target) : base(target)
    {
        Type = TargetType.Program;
        IncludeOrderVersion = EngineIncludeOrderVersion.Latest;
        DefaultBuildSettings = BuildSettingsVersion.Latest;
        LinkType = TargetLinkType.Monolithic;
        LaunchModuleName = "LumenFxGroomExport";
        bBuildDeveloperTools = true;
        bBuildWithEditorOnlyData = true;
        bCompileAgainstEditor = true;
        bCompileAgainstEngine = true;
        bCompileAgainstCoreUObject = true;
        bCompileICU = false;
        bIsBuildingConsoleApplication = true;
    }
}
