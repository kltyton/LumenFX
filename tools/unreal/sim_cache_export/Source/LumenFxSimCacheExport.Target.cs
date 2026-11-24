using UnrealBuildTool;

[SupportedPlatforms(UnrealPlatformClass.Desktop)]
public class LumenFxSimCacheExportTarget : TargetRules
{
    public LumenFxSimCacheExportTarget(TargetInfo target) : base(target)
    {
        Type = TargetType.Program;
        IncludeOrderVersion = EngineIncludeOrderVersion.Latest;
        DefaultBuildSettings = BuildSettingsVersion.Latest;
        LinkType = TargetLinkType.Monolithic;
        LaunchModuleName = "LumenFxSimCacheExport";
        bBuildDeveloperTools = true;
        bBuildWithEditorOnlyData = true;
        bCompileAgainstEditor = true;
        bCompileAgainstEngine = true;
        bCompileAgainstCoreUObject = true;
        bCompileAgainstApplicationCore = false;
        bCompileICU = false;
        bIsBuildingConsoleApplication = true;
        EnablePlugins.Add("Niagara");
    }
}
