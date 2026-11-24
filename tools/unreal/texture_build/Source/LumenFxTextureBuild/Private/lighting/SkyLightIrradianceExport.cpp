#include "SkyLightIrradianceExport.h"
#include "SpecularIntegrationExport.h"

#include "ColorManagement/ColorSpace.h"
#include "Components/SkyLightComponent.h"
#include "Dom/JsonObject.h"
#include "Dom/JsonValue.h"
#include "DynamicRHI.h"
#include "Engine/TextureCube.h"
#include "Engine/World.h"
#include "Math/SHMath.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "Misc/ScopeExit.h"
#include "RHICommandList.h"
#include "RenderingThread.h"
#include "SceneInterface.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "UObject/SoftObjectPath.h"

DEFINE_LOG_CATEGORY_STATIC(LogSkyLightIrradianceExport, Log, All);

namespace
{
    TSharedRef<FJsonValueArray> Vector(float X, float Y, float Z, float W)
    {
        TArray<TSharedPtr<FJsonValue>> Values;
        for (float Value : {X, Y, Z, W}) Values.Add(MakeShared<FJsonValueNumber>(Value));
        return MakeShared<FJsonValueArray>(MoveTemp(Values));
    }

    TArray<TSharedPtr<FJsonValue>> PackIrradiance(const FSHVectorRGB3& SH)
    {
        const float RootPi = FMath::Sqrt(UE_PI);
        const float C0 = 1.0f / (2.0f * RootPi);
        const float C1 = FMath::Sqrt(3.0f) / (3.0f * RootPi);
        const float C2 = FMath::Sqrt(15.0f) / (8.0f * RootPi);
        const float C3 = FMath::Sqrt(5.0f) / (16.0f * RootPi);
        TArray<TSharedPtr<FJsonValue>> Packed;
        for (const FSHVector3* Channel : {&SH.R, &SH.G, &SH.B})
        {
            const float* V = Channel->V;
            Packed.Add(Vector(-C1 * V[3], -C1 * V[1], C1 * V[2], C0 * V[0] - C3 * V[6]));
        }
        for (const FSHVector3* Channel : {&SH.R, &SH.G, &SH.B})
        {
            const float* V = Channel->V;
            Packed.Add(Vector(C2 * V[4], -C2 * V[5], 3.0f * C3 * V[6], -C2 * V[7]));
        }
        Packed.Add(Vector(0.5f * C2 * SH.R.V[8], 0.5f * C2 * SH.G.V[8], 0.5f * C2 * SH.B.V[8], 1.0f));
        return Packed;
    }

    bool SaveFilteredCube(const FString& Output, FSkyTextureCubeResource& Resource, int32 Size, int32 NumMips, bool bIncludeGF)
    {
        FBufferArchive Archive;
        uint32 Magic = 0x4b53464c, Version = bIncludeGF ? 2 : 1, Extent = Size, Levels = NumMips;
        Archive << Magic << Version << Extent << Levels;
        bool bSuccess = false;
        ENQUEUE_RENDER_COMMAND(LumenFxReadSkyCube)(
            [&Resource, &Archive, &bSuccess, Size, NumMips, bIncludeGF](FRHICommandListImmediate& RHICmdList)
            {
                auto* Texture = Resource.TextureRHI.GetReference();
                if (!Texture || Texture->GetFormat() != PF_FloatRGBA) return;
                for (int32 Mip = 0; Mip < NumMips; ++Mip)
                {
                    const int32 Width = FMath::Max(1, Size >> Mip);
                    for (int32 Face = 0; Face < CubeFace_MAX; ++Face)
                    {
                        TArray<FFloat16Color> Pixels;
                        RHICmdList.ReadSurfaceFloatData(Texture, FIntRect(0, 0, Width, Width), Pixels,
                            static_cast<ECubeFace>(Face), 0, Mip);
                        if (Pixels.Num() != Width * Width) return;
                        Archive.Serialize(Pixels.GetData(), Pixels.Num() * sizeof(FFloat16Color));
                    }
                }
                bSuccess = !Archive.IsError() && (!bIncludeGF || AppendPreintegratedSpecularGF(RHICmdList, Archive));
            });
        FlushRenderingCommands();
        return bSuccess && FFileHelper::SaveArrayToFile(Archive, *Output);
    }
}

bool ExportSkyLightIrradiance(const FString& Input)
{
    FString Text, ComponentPath, WorldPath, Output, SpecularOutput, SpecularResource;
    TSharedPtr<FJsonObject> Request;
    if (!FFileHelper::LoadFileToString(Text, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Request) || !Request.IsValid()
        || !Request->TryGetStringField(TEXT("component"), ComponentPath)
        || !Request->TryGetStringField(TEXT("output"), Output) || FPaths::IsRelative(Output)
        || !FPaths::CollapseRelativeDirectories(Output)) return false;
    const bool bExportSpecular = Request->HasField(TEXT("specular_output")) || Request->HasField(TEXT("specular_resource"));
    if (bExportSpecular && (!Request->TryGetStringField(TEXT("specular_output"), SpecularOutput)
        || !Request->TryGetStringField(TEXT("specular_resource"), SpecularResource) || SpecularResource.IsEmpty()
        || FPaths::IsRelative(SpecularOutput) || !FPaths::CollapseRelativeDirectories(SpecularOutput)
        || SpecularOutput.Equals(Output, ESearchCase::IgnoreCase))) return false;
    if (!GDynamicRHI || RHIGetInterfaceType() == ERHIInterfaceType::Null)
    {
        UE_LOG(LogSkyLightIrradianceExport, Error, TEXT("Sky light capture requires an active rendering RHI"));
        return false;
    }

    UWorld* World = nullptr;
    if (Request->TryGetStringField(TEXT("world"), WorldPath))
    {
        if (!FPackageName::IsValidObjectPath(WorldPath)) return false;
        World = LoadObject<UWorld>(nullptr, *WorldPath);
        if (!World) return false;
    }
    const FSoftObjectPath ComponentReference(ComponentPath);
    const auto* Component = Cast<USkyLightComponent>(ComponentReference.TryLoad());
    if (!Component)
    {
        UE_LOG(LogSkyLightIrradianceExport, Error, TEXT("Could not load sky light component %s"), *ComponentPath);
        return false;
    }
    if (Component->IsRealTimeCaptureEnabled())
    {
        UE_LOG(LogSkyLightIrradianceExport, Error, TEXT("Use a frame capture for real-time sky light %s"), *ComponentPath);
        return false;
    }
    const bool bCaptureScene = Component->SourceType == SLS_CapturedScene;
    if (bCaptureScene && (!World || Component->GetTypedOuter<UWorld>() != World))
    {
        UE_LOG(LogSkyLightIrradianceExport, Error, TEXT("Captured-scene sky light requires its owning world"));
        return false;
    }
    if (!bCaptureScene && (Component->SourceType != SLS_SpecifiedCubemap || !Component->Cubemap)) return false;

    FString BlendPath, BlendOutput, BlendResource;
    double RequestedBlend = 0.0;
    const bool bRequestedBlend = Request->HasField(TEXT("blend_destination")) || Request->HasField(TEXT("blend_fraction"));
    if (bRequestedBlend && (bCaptureScene || Component->Mobility == EComponentMobility::Static
        || !Request->TryGetStringField(TEXT("blend_destination"), BlendPath)
        || !FPackageName::IsValidObjectPath(BlendPath)
        || !Request->TryGetNumberField(TEXT("blend_fraction"), RequestedBlend) || !FMath::IsFinite(RequestedBlend))) return false;
    const float Blend = static_cast<float>(FMath::Clamp(RequestedBlend, 0.0, 1.0));
    UTextureCube* Destination = Blend > 0.0f ? LoadObject<UTextureCube>(nullptr, *BlendPath) : nullptr;
    if (Blend > 0.0f && !Destination) return false;
    const bool bExportDestination = Destination && Blend < 1.0f && bExportSpecular;
    if (bExportDestination && (!Request->TryGetStringField(TEXT("blend_specular_output"), BlendOutput)
        || !Request->TryGetStringField(TEXT("blend_specular_resource"), BlendResource) || BlendResource.IsEmpty()
        || FPaths::IsRelative(BlendOutput) || !FPaths::CollapseRelativeDirectories(BlendOutput)
        || BlendOutput.Equals(Output, ESearchCase::IgnoreCase) || BlendOutput.Equals(SpecularOutput, ESearchCase::IgnoreCase)
        || BlendResource == SpecularResource)) return false;

    const auto Init = UWorld::InitializationValues().CreatePhysicsScene(false).ShouldSimulatePhysics(false)
        .CreateNavigation(false).CreateAISystem(false).CreateFXSystem(false);
    const bool bOwnTransient = World == nullptr;
    bool bInitialized = false;
    ON_SCOPE_EXIT
    {
        if (bOwnTransient && World) World->DestroyWorld(false);
        else if (bInitialized) World->CleanupWorld();
    };
    if (bOwnTransient)
        World = UWorld::CreateWorld(EWorldType::GamePreview, false, NAME_None, nullptr, true, GMaxRHIFeatureLevel, &Init);
    else if (!World->IsInitialized())
    {
        World->WorldType = EWorldType::GamePreview;
        World->InitWorld(Init);
        bInitialized = true;
        World->UpdateWorldComponents(false, false);
    }
    if (!World || !World->Scene || !World->Scene->GetRenderScene())
    {
        UE_LOG(LogSkyLightIrradianceExport, Error, TEXT("Could not initialize the renderer for sky light %s"), *ComponentPath);
        return false;
    }

    float AverageBrightness = 0.0f;
    FSHVectorRGB3 Irradiance;
    FLinearColor CubemapScale = FLinearColor::White;
    FSkyTextureCubeResource ProcessedCube;
    FSkyTextureCubeResource ProcessedDestination;
    const int32 Size = Component->CubemapResolution;
    if (Size <= 0 || !FMath::IsPowerOfTwo(Size)) return false;
    const int32 Mips = FMath::CeilLogTwo(Size) + 1;
    if (bExportSpecular)
    {
        ProcessedCube.SetupParameters(Size, Mips, PF_FloatRGBA);
        BeginInitResource(&ProcessedCube);
        if (Destination)
        {
            ProcessedDestination.SetupParameters(Size, Mips, PF_FloatRGBA);
            BeginInitResource(&ProcessedDestination);
        }
    }
    ON_SCOPE_EXIT
    {
        if (bExportSpecular)
        {
            BeginReleaseResource(&ProcessedCube);
            if (Destination) BeginReleaseResource(&ProcessedDestination);
            FlushRenderingCommands();
        }
    };
    World->Scene->UpdateSkyCaptureContents(Component, Component->bCaptureEmissiveOnly, Component->Cubemap,
        bExportSpecular ? &ProcessedCube : nullptr, AverageBrightness, Irradiance, nullptr, &CubemapScale);
    float DestinationBrightness = 0.0f;
    FSHVectorRGB3 DestinationIrradiance;
    if (Destination)
        World->Scene->UpdateSkyCaptureContents(Component, Component->bCaptureEmissiveOnly, Destination,
            bExportSpecular ? &ProcessedDestination : nullptr, DestinationBrightness, DestinationIrradiance, nullptr, &CubemapScale);
    FlushRenderingCommands();
    if (Destination)
    {
        Irradiance = Irradiance * (1.0f - Blend) + DestinationIrradiance * Blend;
        AverageBrightness = AverageBrightness * (1.0f - Blend) + DestinationBrightness * Blend;
    }
    if (bExportSpecular && !SaveFilteredCube(SpecularOutput, Blend == 1.0f ? ProcessedDestination : ProcessedCube, Size, Mips, true))
        return false;
    if (bExportDestination && !SaveFilteredCube(BlendOutput, ProcessedDestination, Size, Mips, false)) return false;

    auto Data = MakeShared<FJsonObject>();
    const FLinearColor Color = FLinearColor(Component->LightColor) * Component->Intensity * CubemapScale;
    TArray<TSharedPtr<FJsonValue>> Channels;
    for (float Channel : {Color.R, Color.G, Color.B}) Channels.Add(MakeShared<FJsonValueNumber>(Channel));
    Data->SetStringField(TEXT("source_component"), ComponentPath);
    Data->SetArrayField(TEXT("diffuse_sh"), PackIrradiance(Irradiance));
    Data->SetArrayField(TEXT("color"), MoveTemp(Channels));
    Data->SetNumberField(TEXT("volumetric_scattering"),
        Component->bAffectsWorld ? FMath::Max(0.0f, Component->VolumetricScatteringIntensity) : 0.0f);
    Data->SetBoolField(TEXT("static_lighting"), Component->HasStaticLighting());
    Data->SetBoolField(TEXT("static_shadowing"), Component->Mobility == EComponentMobility::Stationary && Component->CastShadows);
    Data->SetBoolField(TEXT("stationary"), Component->Mobility == EComponentMobility::Stationary);
    const FLinearColor OcclusionTint(Component->OcclusionTint);
    TArray<TSharedPtr<FJsonValue>> OcclusionChannels;
    OcclusionChannels.Add(MakeShared<FJsonValueNumber>(OcclusionTint.R));
    OcclusionChannels.Add(MakeShared<FJsonValueNumber>(OcclusionTint.G));
    OcclusionChannels.Add(MakeShared<FJsonValueNumber>(OcclusionTint.B));
    Data->SetArrayField(TEXT("occlusion_tint"), MoveTemp(OcclusionChannels));
    Data->SetBoolField(TEXT("affects_world"), Component->bAffectsWorld);
    if (bExportSpecular)
    {
        auto Reflection = MakeShared<FJsonObject>();
        Reflection->SetStringField(TEXT("texture"), SpecularResource);
        Reflection->SetNumberField(TEXT("mip_count"), Mips);
        Reflection->SetNumberField(TEXT("average_brightness"), AverageBrightness);
        Reflection->SetBoolField(TEXT("movable"), Component->Mobility == EComponentMobility::Movable);
        Reflection->SetBoolField(TEXT("preintegrated_gf"), true);
        if (bExportDestination)
        {
            auto BlendData = MakeShared<FJsonObject>();
            BlendData->SetStringField(TEXT("texture"), BlendResource);
            BlendData->SetNumberField(TEXT("fraction"), Blend);
            Reflection->SetObjectField(TEXT("blend"), BlendData);
        }
        const auto Weights = UE::Color::FColorSpace::GetWorking().GetLuminanceFactors();
        TArray<TSharedPtr<FJsonValue>> Luminance;
        for (float Factor : {Weights.R, Weights.G, Weights.B}) Luminance.Add(MakeShared<FJsonValueNumber>(Factor));
        Reflection->SetArrayField(TEXT("luminance_weights"), MoveTemp(Luminance));
        Data->SetObjectField(TEXT("specular_cubemap"), Reflection);
    }
    FString Serialized;
    return FJsonSerializer::Serialize(Data, TJsonWriterFactory<>::Create(&Serialized))
        && FFileHelper::SaveStringToFile(Serialized, *Output, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM);
}
