using UnrealBuildTool;

[SupportedPlatforms(UnrealPlatformClass.Desktop)]
public class LumenFxTextResolveTarget : TargetRules
{
    public LumenFxTextResolveTarget(TargetInfo target) : base(target)
    {
        Type = TargetType.Program;
        IncludeOrderVersion = EngineIncludeOrderVersion.Latest;
        DefaultBuildSettings = BuildSettingsVersion.Latest;
        LinkType = TargetLinkType.Monolithic;
        LaunchModuleName = "LumenFxTextResolve";
        bBuildWithEditorOnlyData = true;
        bCompileAgainstEngine = true;
        bCompileAgainstCoreUObject = true;
        bCompileICU = true;
        bIsBuildingConsoleApplication = true;
    }
}
