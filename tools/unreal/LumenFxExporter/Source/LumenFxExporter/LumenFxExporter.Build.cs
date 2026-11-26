using UnrealBuildTool;

public class LumenFxExporter : ModuleRules
{
    public LumenFxExporter(ReadOnlyTargetRules Target) : base(Target)
    {
        PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;

        PublicDependencyModuleNames.AddRange(new string[]
        {
            "Core",
            "CoreUObject",
            "Engine",
            "DeveloperSettings"
        });

        PrivateDependencyModuleNames.AddRange(new string[]
        {
            "Slate",
            "SlateCore",
            "InputCore",
            "UnrealEd",
            "ContentBrowser",
            "ToolMenus",
            "Niagara",
            "NiagaraEditor",
            "DesktopPlatform",
            "Json",
            "JsonUtilities",
            "Projects"
        });
    }
}
