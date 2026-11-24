using UnrealBuildTool;

public class LumenFxAudioExport : ModuleRules
{
    public LumenFxAudioExport(ReadOnlyTargetRules target) : base(target)
    {
        PublicIncludePathModuleNames.Add("Launch");
        PrivateDependencyModuleNames.AddRange(new[] { "Core", "CoreUObject", "Engine", "Projects", "Json", "AudioExtensions" });
    }
}
