using UnrealBuildTool;

public class LumenFxTextureBuild : ModuleRules
{
    public LumenFxTextureBuild(ReadOnlyTargetRules target) : base(target)
    {
        PublicIncludePathModuleNames.Add("Launch");
        PrivateDependencyModuleNames.AddRange(new[] {
            "Core", "CoreUObject", "Engine", "ImageCore", "Projects", "Json", "TargetPlatform", "RenderCore", "Renderer", "RHI", "DerivedDataCache"
        });
        // The native cooker owns the private, version-pinned VT tile layout.
        PrivateIncludePaths.Add(System.IO.Path.Combine(EngineDirectory, "Source/Runtime/Engine/Private"));
        DynamicallyLoadedModuleNames.AddRange(new[] {
            "TextureCompressor", "TextureFormatOodle", "TextureFormatDXT", "TextureFormatUncompressed"
        });
    }
}
