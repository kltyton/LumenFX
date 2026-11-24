#include "CoreMinimal.h"
#include "RequiredProgramMainCPPInclude.h"
#include "Dom/JsonObject.h"
#include "Misc/CommandLine.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"
#include "Misc/ScopeExit.h"
#include "Modules/ModuleManager.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Sound/SoundWave.h"
#include "routing/SoundRoutingExport.h"

IMPLEMENT_APPLICATION(LumenFxAudioExport, "LumenFxAudioExport");

namespace
{
    bool LoadRequest(const FString& Input, TSharedPtr<FJsonObject>& Request,
        TArray<TRefCountPtr<UE::PackageName::IMountPoint>>& Mounts)
    {
        FString Json;
        if (!FFileHelper::LoadFileToString(Json, *Input)
            || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Request)
            || !Request.IsValid()) return false;
        const TSharedPtr<FJsonObject>* Roots = nullptr;
        if (!Request->TryGetObjectField(TEXT("mounts"), Roots)) return false;
        for (const auto& Root : (*Roots)->Values)
        {
            FString Directory;
            if (!Root.Key.StartsWith(TEXT("/")) || !Root.Key.EndsWith(TEXT("/")) || !Root.Value->TryGetString(Directory)
                || FPaths::IsRelative(Directory) || !FPaths::DirectoryExists(Directory)) return false;
            FPaths::NormalizeDirectoryName(Directory);
            Mounts.Add(FPackageName::RegisterMountPoint(Root.Key, Directory + TEXT("/")));
        }
        return true;
    }

    int32 SpeakerTag(EAudioSpeakers Speaker)
    {
        switch (Speaker)
        {
            case SPEAKER_FrontLeft: return 0;
            case SPEAKER_FrontRight: return 1;
            case SPEAKER_FrontCenter: return 2;
            case SPEAKER_LowFrequency: return 3;
            case SPEAKER_LeftSurround: return 4;
            case SPEAKER_RightSurround: return 5;
            case SPEAKER_LeftBack: return 6;
            case SPEAKER_RightBack: return 7;
            default: return INDEX_NONE;
        }
    }

    bool ExportWave(const FJsonObject& Request, const FString& Output)
    {
        FString Path;
        if (!Request.TryGetStringField(TEXT("wave"), Path)) return false;

        USoundWave* Wave = LoadObject<USoundWave>(nullptr, *Path);
        uint32 SampleRate = 0;
        TArray<EAudioSpeakers> ChannelOrder;
        TArray<uint8> Pcm;
        if (!Wave || !Wave->GetImportedSoundWaveData(Pcm, SampleRate, ChannelOrder) || SampleRate == 0
            || ChannelOrder.IsEmpty() || Pcm.IsEmpty() || Pcm.Num() % (sizeof(int16) * ChannelOrder.Num()) != 0) return false;

        FBufferArchive Archive;
        uint32 Magic = 0x5741464c, Version = 2, Channels = ChannelOrder.Num(), Bytes = Pcm.Num();
        uint32 Flags = (Wave->IsLooping() ? 1u : 0u) | (Wave->bMature ? 2u : 0u);
        float Volume = Wave->GetVolumeMultiplier(), Pitch = Wave->GetPitchMultiplier();
        Archive << Magic << Version << SampleRate << Channels << Flags << Bytes << Volume << Pitch;
        for (EAudioSpeakers Speaker : ChannelOrder)
        {
            int32 Tag = SpeakerTag(Speaker);
            if (Tag == INDEX_NONE) return false;
            Archive << Tag;
        }
        Archive.Serialize(Pcm.GetData(), Pcm.Num());
        return !Archive.IsError() && FFileHelper::SaveArrayToFile(Archive, *Output);
    }
}

INT32_MAIN_INT32_ARGC_TCHAR_ARGV()
{
    FTaskTagScope TaskScope(ETaskTag::EGameThread);
    ON_SCOPE_EXIT
    {
        RequestEngineExit(TEXT("Niagara audio export completed"));
        FEngineLoop::AppPreExit();
        FModuleManager::Get().UnloadModulesAtShutdown();
        FEngineLoop::AppExit();
    };
    if (int32 Status = GEngineLoop.PreInit(ArgC, ArgV)) return Status;
    FString Input, Output;
    if (!FParse::Value(FCommandLine::Get(), TEXT("input="), Input)
        || !FParse::Value(FCommandLine::Get(), TEXT("output="), Output)) return 2;
    TSharedPtr<FJsonObject> Request;
    TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
    if (!LoadRequest(Input, Request, Mounts)) return 3;
    FString Operation;
    FParse::Value(FCommandLine::Get(), TEXT("operation="), Operation);
    if (Operation == TEXT("sound") || Operation == TEXT("sound_class"))
        return LumenFxAudioExport::ExportRouting(*Request, Output, Operation == TEXT("sound_class")) ? 0 : 3;
    return Operation.IsEmpty() && ExportWave(*Request, Output) ? 0 : 3;
}
