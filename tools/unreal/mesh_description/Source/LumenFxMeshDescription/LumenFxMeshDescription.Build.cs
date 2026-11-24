using UnrealBuildTool;

public class LumenFxMeshDescription : ModuleRules
{
    public LumenFxMeshDescription(ReadOnlyTargetRules target) : base(target)
    {
        PublicIncludePathModuleNames.Add("Launch");
        PrivateDependencyModuleNames.AddRange(new[] {
            "Core", "CoreUObject", "Engine", "Projects", "Json", "JsonUtilities", "RenderCore", "RHI",
            "GaussianSplat",
            "Chaos", "GeometryCollectionEngine",
            "MeshDescription", "StaticMeshDescription", "MeshUtilities", "MeshUtilitiesCommon", "MeshReductionInterface", "MeshBuilderCommon", "MeshBuilder", "TargetPlatform"
        });
        PrivateIncludePaths.Add(System.IO.Path.Combine(EngineDirectory, "Plugins", "GaussianSplat", "Source", "GaussianSplat", "Internal"));
        PrivateIncludePaths.Add(System.IO.Path.Combine(EngineDirectory, "Plugins", "GaussianSplat", "Shaders", "Shared"));
        DynamicallyLoadedModuleNames.Add("QuadricMeshReduction");
    }
}
