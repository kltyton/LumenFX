#include "SubsurfaceProfileExport.h"

#include "CoreMinimal.h"
#include "Dom/JsonObject.h"
#include "DynamicRHI.h"
#include "Engine/SubsurfaceProfile.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "Misc/ScopeExit.h"
#include "RHIGPUReadback.h"
#include "RenderingThread.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "UObject/StrongObjectPtr.h"

DEFINE_LOG_CATEGORY_STATIC(LogSubsurfaceProfileExport, Log, All);

namespace
{
    // SubsurfaceProfile.cpp / SubsurfaceProfileCommon.ush: metadata, three kernels, transmission.
    constexpr int32 ProfileWidth = 6 + 13 + 9 + 6 + 32;
    constexpr int32 ValuesPerRow = ProfileWidth * 4;
    constexpr int32 ProfileLimit = 256;

    struct FProfileJob
    {
        TStrongObjectPtr<USubsurfaceProfile> Profile;
        FString ObjectPath;
        FString Output;
    };

    struct FProfileInput
    {
        const USubsurfaceProfile* Profile;
        FSubsurfaceProfileStruct Settings;
    };

    bool ReadProfileRows(FRHICommandListImmediate& Commands, TConstArrayView<FProfileInput> Inputs,
        TArray<uint16>& Rows)
    {
        if (GPixelFormats[PF_A16B16G16R16].PlatformFormat == GPixelFormats[PF_FloatRGBA].PlatformFormat)
            return false;

        TArray<int32> ProfileIds;
        for (const FProfileInput& Input : Inputs)
        {
            ProfileIds.Add(Input.Profile
                ? GSubsurfaceProfileTextureObject.AddOrUpdateProfile(Input.Settings, Input.Profile) : 0);
        }

        GSubsurfaceProfileTextureObject.GetTexture(Commands);
        FRHITexture* Atlas = SubsurfaceProfile::GetSubsurfaceProfileTexture();
        if (!Atlas || Atlas->GetFormat() != PF_A16B16G16R16 || Atlas->GetSizeXYZ().X != ProfileWidth)
            return false;
        int32 Height = 1;
        for (int32 Id : ProfileIds)
        {
            if (Id < 0 || Id >= ProfileLimit || Id >= Atlas->GetSizeXYZ().Y) return false;
            Height = FMath::Max(Height, Id + 1);
        }

        FRHIGPUTextureReadback Readback(TEXT("LumenFxSubsurfaceProfiles"));
        Commands.Transition(FRHITransitionInfo(Atlas, ERHIAccess::Unknown, ERHIAccess::CopySrc));
        Readback.EnqueueCopy(Commands, Atlas, FIntVector::ZeroValue, 0, FIntVector(ProfileWidth, Height, 1));
        Commands.Transition(FRHITransitionInfo(Atlas, ERHIAccess::CopySrc, ERHIAccess::SRVMask));
        Readback.Wait(Commands, Readback.GetLastCopyGPUMask());
        int32 RowPitch = 0, BufferHeight = 0;
        const uint16* Mapped = static_cast<const uint16*>(Readback.Lock(RowPitch, &BufferHeight));
        if (!Mapped) return false;
        ON_SCOPE_EXIT { Readback.Unlock(); };
        if (RowPitch < ProfileWidth || BufferHeight < Height) return false;

        Rows.SetNumUninitialized(Inputs.Num() * ValuesPerRow);
        for (int32 Index = 0; Index < ProfileIds.Num(); ++Index)
        {
            FMemory::Memcpy(Rows.GetData() + Index * ValuesPerRow,
                Mapped + ProfileIds[Index] * RowPitch * 4, ValuesPerRow * sizeof(uint16));
        }
        return true;
    }

    bool SaveProfileRow(const FProfileJob& Job, TConstArrayView<uint16> Row)
    {
        const FTCHARToUTF8 ObjectPath(*Job.ObjectPath);
        FBufferArchive Archive;
        uint32 Magic = 0x5053464c, Version = 1;
        int32 Width = ProfileWidth, PathSize = ObjectPath.Length();
        Archive << Magic << Version << Width << PathSize;
        Archive.Serialize(const_cast<ANSICHAR*>(ObjectPath.Get()), PathSize);
        for (uint16 Value : Row) Archive << Value;
        return !Archive.IsError() && FFileHelper::SaveArrayToFile(Archive, *Job.Output);
    }
}

bool ExportSubsurfaceProfiles(const FString& Input)
{
    FString Text;
    TSharedPtr<FJsonObject> Request;
    const TArray<TSharedPtr<FJsonValue>>* Jobs = nullptr;
    if (!FFileHelper::LoadFileToString(Text, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Request) || !Request.IsValid()
        || !Request->TryGetArrayField(TEXT("profiles"), Jobs)
        || Jobs->IsEmpty() || Jobs->Num() > ProfileLimit) return false;
    if (!GDynamicRHI || RHIGetInterfaceType() == ERHIInterfaceType::Null)
    {
        UE_LOG(LogSubsurfaceProfileExport, Error, TEXT("Subsurface profile export requires an active RGBA16 UNORM RHI"));
        return false;
    }

    TSet<FString> Outputs;
    TSet<FName> Profiles;
    TArray<FProfileJob> ProfileJobs;
    TArray<FProfileInput> Inputs;
    for (const auto& Value : *Jobs)
    {
        if (Value->Type != EJson::Object) return false;
        const auto Job = Value->AsObject();
        FString Asset, Output;
        if (!Job->TryGetStringField(TEXT("output"), Output) || FPaths::IsRelative(Output)
            || !FPaths::CollapseRelativeDirectories(Output) || Outputs.Contains(Output)) return false;
        Outputs.Add(Output);
        TStrongObjectPtr<USubsurfaceProfile> Profile;
        if (Job->HasField(TEXT("asset")))
        {
            if (!Job->TryGetStringField(TEXT("asset"), Asset) || !FPackageName::IsValidObjectPath(Asset)) return false;
            Profile.Reset(LoadObject<USubsurfaceProfile>(nullptr, *Asset));
            if (!Profile.IsValid())
            {
                UE_LOG(LogSubsurfaceProfileExport, Error, TEXT("Could not load subsurface profile %s"), *Asset);
                return false;
            }
            Asset = Profile->GetPathName();
        }
        const FName Identity(*Asset);
        if (Profiles.Contains(Identity)) return false;
        Profiles.Add(Identity);
        Inputs.Add({Profile.Get(), Profile.IsValid() ? Profile->Settings : FSubsurfaceProfileStruct{}});
        ProfileJobs.Add({MoveTemp(Profile), MoveTemp(Asset), MoveTemp(Output)});
    }

    TArray<uint16> Rows;
    bool bRead = false;
    ENQUEUE_RENDER_COMMAND(LumenFxReadSubsurfaceProfiles)(
        [Inputs = MoveTemp(Inputs), &Rows, &bRead](FRHICommandListImmediate& Commands)
        {
            bRead = ReadProfileRows(Commands, Inputs, Rows);
        });
    FlushRenderingCommands();
    if (!bRead)
    {
        UE_LOG(LogSubsurfaceProfileExport, Error, TEXT("Could not read the native RGBA16 subsurface profile atlas"));
        return false;
    }
    for (int32 Index = 0; Index < ProfileJobs.Num(); ++Index)
    {
        const FProfileJob& Job = ProfileJobs[Index];
        if (!SaveProfileRow(Job, MakeArrayView(Rows.GetData() + Index * ValuesPerRow, ValuesPerRow)))
        {
            UE_LOG(LogSubsurfaceProfileExport, Error, TEXT("Could not save subsurface profile row to %s"), *Job.Output);
            return false;
        }
    }
    return true;
}
