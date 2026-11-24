#include "ToonProfileExport.h"
#include "../TextureSliceReadback.h"

#include "Dom/JsonObject.h"
#include "DynamicRHI.h"
#include "Engine/ToonProfile.h"
#include "Engine/Texture2D.h"
#include "Engine/TextureCube.h"
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
#include "ToonProfileDefinitions.h"
#include "UObject/StrongObjectPtr.h"

DEFINE_LOG_CATEGORY_STATIC(LogToonProfileExport, Log, All);

namespace
{
    constexpr int32 MaxProfileCount = 256;

    struct FToonProfileJob
    {
        TStrongObjectPtr<UToonProfile> Profile;
        FString Asset;
        FString Output;
    };

    struct FToonProfileInput
    {
        const UToonProfile* Profile = nullptr;
        FGuid Guid;
        FToonProfileStruct Settings;
    };

    void LoadProfileTextures(const FToonProfileStruct& Settings)
    {
        TSet<UTexture*> Textures = {Settings.DiffuseRampOffsetTexture.Get(), Settings.SpecularRampOffsetTexture.Get(),
            Settings.ShadowHatchingPatternTexture.Get(), Settings.SpecularIndirectReflectionCubemapTexture.Get(),
            Settings.SpecularIndirectReflection2DTexture.Get()};
        Textures.Remove(nullptr);
        for (UTexture* Texture : Textures)
        {
            Texture->SetForceMipLevelsToBeResident(30.0f);
            Texture->WaitForStreaming();
        }
    }

    bool ReadProfiles(FRHICommandListImmediate& Commands, TConstArrayView<FToonProfileInput> Inputs,
        TArray<TArray<uint16>>& Profiles, FIntPoint& Extent)
    {
        TArray<int32> Ids;
        Ids.Reserve(Inputs.Num());
        for (const FToonProfileInput& Input : Inputs)
        {
            int32 Id = int32(ToonProfile::GetToonProfileId(Input.Profile));
            if (Input.Profile && Id <= 0)
                Id = ToonProfile::AddOrUpdateProfile(Input.Profile, Input.Guid, Input.Settings);
            if (Id < 0 || Id >= MaxProfileCount) return false;
            Ids.Add(Id);
        }

        FRDGBuilder GraphBuilder(Commands);
        ToonProfile::UpdateToonProfileTextureAtlas(GraphBuilder, GMaxRHIShaderPlatform);
        GraphBuilder.Execute();
        FRHITexture* Atlas = ToonProfile::GetToonProfileTextureAtlas();
        if (!Atlas || Atlas->GetFormat() != PF_FloatRGBA) return false;
        Extent = Atlas->GetDesc().Extent;
        const int64 ValuesPerProfile = int64(Extent.X) * Extent.Y * 4;
        if (Extent.X < 64 || Extent.Y != TOON_PROFILE_TEXTURES_Y + TOON_PROFILE_TEXTURE_COUNT * Extent.X
            || ValuesPerProfile > MAX_int32) return false;

        Commands.Transition(FRHITransitionInfo(Atlas, ERHIAccess::SRVMask, ERHIAccess::CopySrc));
        ON_SCOPE_EXIT { Commands.Transition(FRHITransitionInfo(Atlas, ERHIAccess::CopySrc, ERHIAccess::SRVMask)); };
        Profiles.SetNum(Ids.Num());
        for (int32 Index = 0; Index < Ids.Num(); ++Index)
        {
            TArray<uint16>& Values = Profiles[Index];
            Values.SetNumUninitialized(int32(ValuesPerProfile));
            if (!ReadHalfColorTextureSlice(Commands, Atlas, Extent, Ids[Index], FName(TEXT("LumenFxToonProfile")), Values))
                return false;
        }
        return true;
    }

    bool SaveProfile(const FToonProfileJob& Job, TConstArrayView<uint16> Values, FIntPoint Extent)
    {
        const FTCHARToUTF8 Identity(*Job.Asset);
        FBufferArchive Archive;
        uint32 Magic = 0x5054464c, Version = 1;
        int32 IdentityLength = Identity.Length();
        Archive << Magic << Version << Extent.X << Extent.Y << IdentityLength;
        Archive.Serialize(const_cast<ANSICHAR*>(Identity.Get()), IdentityLength);
        for (uint16 Value : Values) Archive << Value;
        return !Archive.IsError() && FFileHelper::SaveArrayToFile(Archive, *Job.Output);
    }
}

bool ExportToonProfiles(const FString& Input)
{
    FString Text;
    TSharedPtr<FJsonObject> Request;
    const TArray<TSharedPtr<FJsonValue>>* Jobs = nullptr;
    if (!FFileHelper::LoadFileToString(Text, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Request) || !Request.IsValid()
        || !Request->TryGetArrayField(TEXT("profiles"), Jobs)
        || Jobs->IsEmpty() || Jobs->Num() > MaxProfileCount) return false;
    if (!GDynamicRHI || RHIGetInterfaceType() == ERHIInterfaceType::Null)
    {
        UE_LOG(LogToonProfileExport, Error, TEXT("Toon profile export requires an active Substrate RHI"));
        return false;
    }

    TSet<FName> Assets;
    TSet<FString> Outputs;
    TArray<FToonProfileJob> Profiles;
    TArray<FToonProfileInput> Inputs;
    for (const auto& Value : *Jobs)
    {
        if (Value->Type != EJson::Object) return false;
        const auto Job = Value->AsObject();
        FString Asset, Output;
        if (!Job->TryGetStringField(TEXT("asset"), Asset) || (Asset != TEXT("None") && !FPackageName::IsValidObjectPath(Asset))
            || !Job->TryGetStringField(TEXT("output"), Output) || FPaths::IsRelative(Output)
            || !FPaths::CollapseRelativeDirectories(Output) || Outputs.Contains(Output)) return false;
        TStrongObjectPtr<UToonProfile> Profile(Asset == TEXT("None") ? nullptr : LoadObject<UToonProfile>(nullptr, *Asset));
        if (Asset != TEXT("None"))
        {
            if (!Profile.IsValid())
            {
                UE_LOG(LogToonProfileExport, Error, TEXT("Could not load toon profile %s"), *Asset);
                return false;
            }
            Asset = Profile->GetPathName();
            LoadProfileTextures(Profile->Settings);
        }
        if (Assets.Contains(FName(*Asset))) return false;
        Assets.Add(FName(*Asset));
        Outputs.Add(Output);
        if (Profile.IsValid()) Inputs.Add({Profile.Get(), Profile->Guid, Profile->Settings});
        else Inputs.AddDefaulted();
        Profiles.Add({MoveTemp(Profile), MoveTemp(Asset), MoveTemp(Output)});
    }

    TArray<TArray<uint16>> Data;
    FIntPoint Extent = FIntPoint::ZeroValue;
    bool bRead = false;
    ENQUEUE_RENDER_COMMAND(LumenFxReadToonProfiles)(
        [Inputs = MoveTemp(Inputs), &Data, &Extent, &bRead](FRHICommandListImmediate& Commands)
        {
            bRead = ReadProfiles(Commands, Inputs, Data, Extent);
        });
    FlushRenderingCommands();
    if (!bRead)
    {
        UE_LOG(LogToonProfileExport, Error, TEXT("Could not read the native Substrate toon profile atlas"));
        return false;
    }
    for (int32 Index = 0; Index < Profiles.Num(); ++Index)
    {
        if (!SaveProfile(Profiles[Index], Data[Index], Extent))
        {
            UE_LOG(LogToonProfileExport, Error, TEXT("Could not save toon profile to %s"), *Profiles[Index].Output);
            return false;
        }
    }
    return true;
}
