#include "CoreMinimal.h"
#include "RequiredProgramMainCPPInclude.h"
#include "MeshDescriptionExchange.h"
#include "SkeletalMeshRenderExchange.h"
#include "GeometryCollection/GeometryCollectionExchange.h"
#include "GaussianSplatExchange.h"
#include "Misc/CommandLine.h"
#include "Misc/Parse.h"
#include "Misc/ScopeExit.h"
#include "Modules/ModuleManager.h"

IMPLEMENT_APPLICATION(LumenFxMeshDescription, "LumenFxMeshDescription");

INT32_MAIN_INT32_ARGC_TCHAR_ARGV()
{
    FTaskTagScope TaskScope(ETaskTag::EGameThread);
    ON_SCOPE_EXIT
    {
        RequestEngineExit(TEXT("Mesh description processing completed"));
        FEngineLoop::AppPreExit();
        FModuleManager::Get().UnloadModulesAtShutdown();
        FEngineLoop::AppExit();
    };
    if (int32 Status = GEngineLoop.PreInit(ArgC, ArgV)) return Status;
    FString Input, Output;
    if (!FParse::Value(FCommandLine::Get(), TEXT("input="), Input)
        || !FParse::Value(FCommandLine::Get(), TEXT("output="), Output)) return 2;
    FString Operation;
    FParse::Value(FCommandLine::Get(), TEXT("operation="), Operation);
    if (Operation == TEXT("indices")) return OptimizeMeshIndices(Input, Output) ? 0 : 6;
    if (Operation == TEXT("skeletal")) return ExportNativeSkeletalMesh(Input, Output) ? 0 : 8;
    if (Operation == TEXT("geometry-collection")) return ExportNativeGeometryCollection(Input, Output) ? 0 : 9;
    if (Operation == TEXT("gaussian-splat")) return ExportNativeGaussianSplat(Input, Output) ? 0 : 10;
    FSourceMeshRequest Request;
    if (!ReadMeshDescriptionInput(Input, Request)) return 3;
    if (Operation == TEXT("render")) return BuildNativeRenderMesh(Output, Request) ? 0 : 7;
    TArray<FPreparedMeshLod> Lods;
    if (!BuildMeshDescriptions(Request, Lods)) return 4;
    return WriteMeshDescriptionOutput(Output, Request, Lods) ? 0 : 5;
}
