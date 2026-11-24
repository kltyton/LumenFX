using UnrealBuildTool;

public class LumenFxTextResolve : ModuleRules
{
    public LumenFxTextResolve(ReadOnlyTargetRules target) : base(target)
    {
        PublicIncludePathModuleNames.Add("Launch");
        PrivateDependencyModuleNames.AddRange(new[] { "Core", "CoreUObject", "Engine", "Projects", "Json" });
    }
}
