using UnrealBuildTool;

[SupportedPlatforms(UnrealPlatformClass.Desktop)]
public class LumenFxAudioExportTarget : TargetRules
{
    public LumenFxAudioExportTarget(TargetInfo target) : base(target)
    {
        Type = TargetType.Program;
        IncludeOrderVersion = EngineIncludeOrderVersion.Latest;
        DefaultBuildSettings = BuildSettingsVersion.Latest;
        LinkType = TargetLinkType.Monolithic;
        LaunchModuleName = "LumenFxAudioExport";
        bBuildDeveloperTools = true;
        bBuildWithEditorOnlyData = true;
        bCompileAgainstEditor = true;
        bCompileAgainstEngine = true;
        bCompileAgainstCoreUObject = true;
        bCompileAgainstApplicationCore = false;
        bCompileICU = false;
        bIsBuildingConsoleApplication = true;
    }
}
