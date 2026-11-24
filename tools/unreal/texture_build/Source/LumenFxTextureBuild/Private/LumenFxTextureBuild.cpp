#include "CoreMinimal.h"
#include "RequiredProgramMainCPPInclude.h"
#include "energy/ShadingEnergyExport.h"
#include "lighting/SkyLightIrradianceExport.h"
#include "lighting/ReflectionCaptureExport.h"
#include "lighting/StaticShadowDepthExport.h"
#include "lighting/VolumetricLightmapExport.h"
#include "specular/SpecularProfileExport.h"
#include "subsurface/SubsurfaceProfileExport.h"
#include "toon/ToonProfileExport.h"
#include "TexturePlatformExport.h"
#include "Misc/CommandLine.h"
#include "Misc/Parse.h"
#include "Misc/ScopeExit.h"
#include "Modules/ModuleManager.h"
#include "Runtime/Launch/Resources/Version.h"

static_assert(ENGINE_MAJOR_VERSION == 5, "The texture build requires Unreal Engine 5");

IMPLEMENT_APPLICATION(LumenFxTextureBuild, "LumenFxTextureBuild");

INT32_MAIN_INT32_ARGC_TCHAR_ARGV()
{
    FTaskTagScope TaskScope(ETaskTag::EGameThread);
    ON_SCOPE_EXIT
    {
        RequestEngineExit(TEXT("Texture processing completed"));
        FEngineLoop::AppPreExit();
        FModuleManager::Get().UnloadModulesAtShutdown();
        FEngineLoop::AppExit();
    };
    if (int32 Status = GEngineLoop.PreInit(ArgC, ArgV)) return Status;
    FString Input, EnergyRequest, ProfileRequest, SpecularProfileRequest, StaticShadowRequest, SkyLightRequest, LightmapRequest;
    if (FParse::Value(FCommandLine::Get(), TEXT("shading-energy="), EnergyRequest))
        return ExportShadingEnergyTables(EnergyRequest) ? 0 : 3;
    if (FParse::Value(FCommandLine::Get(), TEXT("subsurface-profiles="), ProfileRequest))
        return ExportSubsurfaceProfiles(ProfileRequest) ? 0 : 3;
    if (FParse::Value(FCommandLine::Get(), TEXT("specular-profiles="), SpecularProfileRequest))
        return ExportSpecularProfiles(SpecularProfileRequest) ? 0 : 3;
    FString ToonProfileRequest;
    if (FParse::Value(FCommandLine::Get(), TEXT("toon-profiles="), ToonProfileRequest))
        return ExportToonProfiles(ToonProfileRequest) ? 0 : 3;
    if (FParse::Value(FCommandLine::Get(), TEXT("static-shadow-depth="), StaticShadowRequest))
        return ExportStaticShadowDepth(StaticShadowRequest) ? 0 : 3;
    if (FParse::Value(FCommandLine::Get(), TEXT("sky-light-irradiance="), SkyLightRequest))
        return ExportSkyLightIrradiance(SkyLightRequest) ? 0 : 3;
    FString ReflectionRequest;
    if (FParse::Value(FCommandLine::Get(), TEXT("reflection-captures="), ReflectionRequest))
        return ExportReflectionCaptures(ReflectionRequest) ? 0 : 3;
    if (FParse::Value(FCommandLine::Get(), TEXT("volumetric-lightmap="), LightmapRequest))
        return ExportVolumetricLightmap(LightmapRequest) ? 0 : 3;
    if (!FParse::Value(FCommandLine::Get(), TEXT("input="), Input)) return 2;
    return ExportPlatformTextures(Input) ? 0 : 3;
}
