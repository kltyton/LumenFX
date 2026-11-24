#include "SpecularProfileExport.h"
#include "../TextureSliceReadback.h"

#include "Dom/JsonObject.h"
#include "DynamicRHI.h"
#include "Engine/SpecularProfile.h"
#include "Engine/Texture2D.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "Misc/ScopeExit.h"
#include "RenderGraphBuilder.h"
#include "RenderingThread.h"
#include "RHICommandList.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "SubstrateDefinitions.h"
#include "UObject/StrongObjectPtr.h"

DEFINE_LOG_CATEGORY_STATIC(LogSpecularProfileExport, Log, All);

namespace
{
    constexpr int32 MaxResolution = 1024;

    struct FProfileInput
    {
        const USpecularProfile* Profile;
        FGuid Guid;
        FSpecularProfileStruct Settings;
        const FTextureReference* Texture;
    };

    struct FProfileJob
    {
        TStrongObjectPtr<USpecularProfile> Profile;
        FString Output;
    };

    bool ReadProfiles(FRHICommandListImmediate& Commands, TConstArrayView<FProfileInput> Inputs,
        TArray<TArray<uint16>>& Profiles, int32& Resolution)
    {
        TArray<int32> Ids;
        for (const FProfileInput& Input : Inputs)
        {
            const int32 Id = SpecularProfile::AddOrUpdateProfile(Input.Profile, Input.Guid, Input.Settings, Input.Texture);
            if (Id <= 0 || Id >= MAX_SPECULAR_PROFILE_COUNT) return false;
            Ids.Add(Id);
        }
        FRDGBuilder GraphBuilder(Commands);
        SpecularProfile::UpdateSpecularProfileTextureAtlas(GraphBuilder, GMaxRHIShaderPlatform);
        GraphBuilder.Execute();
        FRHITexture* Atlas = SpecularProfile::GetSpecularProfileTextureAtlas();
        if (!Atlas || (Atlas->GetFormat() != PF_FloatRGBA && Atlas->GetFormat() != PF_FloatR11G11B10)) return false;
        const auto& Desc = Atlas->GetDesc();
        Resolution = Desc.Extent.X;
        if (Resolution < 2 || Resolution > MaxResolution || Desc.Extent.Y != Resolution) return false;

        Commands.Transition(FRHITransitionInfo(Atlas, ERHIAccess::SRVMask, ERHIAccess::CopySrc));
        ON_SCOPE_EXIT { Commands.Transition(FRHITransitionInfo(Atlas, ERHIAccess::CopySrc, ERHIAccess::SRVMask)); };
        Profiles.SetNum(Inputs.Num());
        const int32 SliceValues = Resolution * Resolution * 4;
        for (int32 Index = 0; Index < Ids.Num(); ++Index)
        {
            const uint32 First = Ids[Index] * SUBSTRATE_SPECULAR_PROFILE_ENTRY_COUNT;
            if (First + SUBSTRATE_SPECULAR_PROFILE_ENTRY_ENV >= Desc.ArraySize) return false;
            auto& Data = Profiles[Index];
            Data.SetNumUninitialized(SliceValues * SUBSTRATE_SPECULAR_PROFILE_ENTRY_COUNT);
            const FIntPoint Extent(Resolution, Resolution);
            const FName DebugName(TEXT("LumenFxSpecularProfile"));
            if (!ReadHalfColorTextureSlice(Commands, Atlas, Extent, First + SUBSTRATE_SPECULAR_PROFILE_ENTRY_LIGHT, DebugName,
                    MakeArrayView(Data.GetData(), SliceValues))
                || !ReadHalfColorTextureSlice(Commands, Atlas, Extent, First + SUBSTRATE_SPECULAR_PROFILE_ENTRY_ENV, DebugName,
                    MakeArrayView(Data.GetData() + SliceValues, SliceValues))) return false;
        }
        return true;
    }

    bool SaveProfile(const FProfileJob& Job, TConstArrayView<uint16> Values, int32 Resolution)
    {
        const FTCHARToUTF8 ObjectPath(*Job.Profile->GetPathName());
        FBufferArchive Archive;
        uint32 Magic = 0x4c53464c, Version = 1;
        int32 Mode = Job.Profile->Settings.Format == ESpecularProfileFormat::HalfVector ? 1 : 0;
        int32 PathSize = ObjectPath.Length();
        Archive << Magic << Version << Resolution << Mode << PathSize;
        Archive.Serialize(const_cast<ANSICHAR*>(ObjectPath.Get()), PathSize);
        for (uint16 Value : Values) Archive << Value;
        return !Archive.IsError() && FFileHelper::SaveArrayToFile(Archive, *Job.Output);
    }
}

bool ExportSpecularProfiles(const FString& Input)
{
    FString Text;
    TSharedPtr<FJsonObject> Request;
    const TArray<TSharedPtr<FJsonValue>>* Jobs = nullptr;
    if (!FFileHelper::LoadFileToString(Text, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Request) || !Request.IsValid()
        || !Request->TryGetArrayField(TEXT("profiles"), Jobs)
        || Jobs->IsEmpty() || Jobs->Num() >= MAX_SPECULAR_PROFILE_COUNT) return false;
    if (!GDynamicRHI || RHIGetInterfaceType() == ERHIInterfaceType::Null)
    {
        UE_LOG(LogSpecularProfileExport, Error, TEXT("Specular profile export requires an active Substrate RHI"));
        return false;
    }

    TSet<FString> Outputs;
    TSet<FName> Assets;
    TArray<FProfileJob> Profiles;
    TArray<FProfileInput> Inputs;
    for (const auto& Value : *Jobs)
    {
        if (Value->Type != EJson::Object) return false;
        const auto Job = Value->AsObject();
        FString Asset, Output;
        if (!Job->TryGetStringField(TEXT("asset"), Asset) || !FPackageName::IsValidObjectPath(Asset)
            || !Job->TryGetStringField(TEXT("output"), Output) || FPaths::IsRelative(Output)
            || !FPaths::CollapseRelativeDirectories(Output) || Outputs.Contains(Output)) return false;
        TStrongObjectPtr<USpecularProfile> Profile(LoadObject<USpecularProfile>(nullptr, *Asset));
        if (!Profile.IsValid())
        {
            UE_LOG(LogSpecularProfileExport, Error, TEXT("Could not load specular profile %s"), *Asset);
            return false;
        }
        const FName Identity(*Profile->GetPathName());
        if (Assets.Contains(Identity)) return false;
        Assets.Add(Identity);
        Outputs.Add(Output);
        const FSpecularProfileStruct& Settings = Profile->Settings;
        if (Settings.Texture)
        {
            Settings.Texture->SetForceMipLevelsToBeResident(30.0f);
            Settings.Texture->WaitForStreaming();
        }
        Inputs.Add({Profile.Get(), Profile->Guid, Settings, Settings.Texture ? &Settings.Texture->TextureReference : nullptr});
        Profiles.Add({MoveTemp(Profile), MoveTemp(Output)});
    }

    TArray<TArray<uint16>> Data;
    int32 Resolution = 0;
    bool bRead = false;
    ENQUEUE_RENDER_COMMAND(LumenFxReadSpecularProfiles)(
        [Inputs = MoveTemp(Inputs), &Data, &Resolution, &bRead](FRHICommandListImmediate& Commands)
        {
            bRead = ReadProfiles(Commands, Inputs, Data, Resolution);
        });
    FlushRenderingCommands();
    if (!bRead)
    {
        UE_LOG(LogSpecularProfileExport, Error, TEXT("Could not read the native Substrate specular profile atlas"));
        return false;
    }
    for (int32 Index = 0; Index < Profiles.Num(); ++Index)
    {
        const FProfileJob& Job = Profiles[Index];
        if (!SaveProfile(Job, Data[Index], Resolution))
        {
            UE_LOG(LogSpecularProfileExport, Error, TEXT("Could not save specular profile to %s"), *Job.Output);
            return false;
        }
    }
    return true;
}
