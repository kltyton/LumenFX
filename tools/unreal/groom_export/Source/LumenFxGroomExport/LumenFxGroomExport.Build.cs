using UnrealBuildTool;

public class LumenFxGroomExport : ModuleRules
{
    public LumenFxGroomExport(ReadOnlyTargetRules target) : base(target)
    {
        PublicIncludePathModuleNames.Add("Launch");
        PrivateDependencyModuleNames.AddRange(new[] { "Core", "CoreUObject", "Engine", "Projects", "Json", "HairStrandsCore" });
    }
}
