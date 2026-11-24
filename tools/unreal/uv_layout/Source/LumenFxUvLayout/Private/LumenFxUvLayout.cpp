#include "CoreMinimal.h"
#include "RequiredProgramMainCPPInclude.h"
#include "Misc/CommandLine.h"
#include "Misc/Parse.h"
#include "Misc/ScopeExit.h"
#include "Modules/ModuleManager.h"
#include "OverlappingCorners.h"
#include "UvLayoutExchange.h"

IMPLEMENT_APPLICATION(LumenFxUvLayout, "LumenFxUvLayout");

INT32_MAIN_INT32_ARGC_TCHAR_ARGV()
{
    FTaskTagScope taskScope(ETaskTag::EGameThread);
    ON_SCOPE_EXIT
    {
        RequestEngineExit(TEXT("UV layout completed"));
        FEngineLoop::AppPreExit();
        FModuleManager::Get().UnloadModulesAtShutdown();
        FEngineLoop::AppExit();
    };
    if (int32 status = GEngineLoop.PreInit(ArgC, ArgV)) return status;
    FString inputPath, outputPath;
    if (!FParse::Value(FCommandLine::Get(), TEXT("input="), inputPath)
        || !FParse::Value(FCommandLine::Get(), TEXT("output="), outputPath)) return 2;
    FUvLayoutMeshView mesh;
    FUvLayoutOptions options;
    if (!ReadUvLayoutInput(inputPath, mesh, options)) return 3;
    FOverlappingCorners overlapping(mesh, options.ComparisonThreshold);
    FLayoutUV layout(mesh);
    layout.SetVersion(options.Version);
    layout.FindCharts(overlapping);
    uint32 resolution = options.Resolution;
    if (options.Version >= ELightmapUVVersion::ForceLightmapPadding) resolution -= 2;
    bool packed = layout.FindBestPacking(resolution);
    if (packed) layout.CommitPackedUVs();
    return WriteUvLayoutOutput(outputPath, mesh, packed) ? 0 : 4;
}
