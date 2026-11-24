using UnrealBuildTool;

public class LumenFxUvLayout : ModuleRules
{
    public LumenFxUvLayout(ReadOnlyTargetRules target) : base(target)
    {
        PublicIncludePathModuleNames.Add("Launch");
        PrivateDependencyModuleNames.AddRange(new[] { "Core", "Projects", "MeshUtilitiesCommon" });
    }
}
