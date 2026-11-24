#include "DialogueResolution.h"
#include "LocalizationRequest.h"
#include "Dom/JsonObject.h"
#include "Internationalization/Culture.h"
#include "Internationalization/Internationalization.h"
#include "Misc/FileHelper.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"
#include "Sound/DialogueVoice.h"
#include "Sound/DialogueWave.h"
#include "Sound/SoundWave.h"
#include "UObject/UObjectGlobals.h"

namespace
{
    FString LocalizedObjectPath(const FString& ObjectPath)
    {
        const FString Package = FPackageName::ObjectPathToPackageName(ObjectPath);
        if (FPackageName::IsLocalizedPackage(Package)) return ObjectPath;
        return FPackageName::GetLocalizedPackagePath(Package) + ObjectPath.Mid(Package.Len());
    }
}

bool ResolveDialogue(const FString& Input, const FString& Output)
{
    FString Json, Path, Speaker;
    TSharedPtr<FJsonObject> Request;
    const TArray<TSharedPtr<FJsonValue>>* Targets = nullptr;
    TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
    if (!FFileHelper::LoadFileToString(Json, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Request) || !Request
        || !Request->TryGetStringField(TEXT("dialogue"), Path) || !Request->TryGetStringField(TEXT("speaker"), Speaker)
        || !Request->TryGetArrayField(TEXT("targets"), Targets)
        || !MountLocalizationContent(*Request, Mounts) || !LoadLocalization(*Request)) return false;

    const auto* Dialogue = LoadObject<UDialogueWave>(nullptr, *LocalizedObjectPath(Path));
    if (!Dialogue) return false;
    FDialogueContext Context;
    Context.Speaker = LoadObject<UDialogueVoice>(nullptr, *LocalizedObjectPath(Speaker));
    if (!Context.Speaker) return false;
    Context.Targets.Reset(Targets->Num());
    for (const auto& Entry : *Targets)
    {
        FString Target;
        if (!Entry->TryGetString(Target)) return false;
        UDialogueVoice* Voice = Target.IsEmpty() ? nullptr : LoadObject<UDialogueVoice>(nullptr, *LocalizedObjectPath(Target));
        if (!Target.IsEmpty() && !Voice) return false;
        Context.Targets.Add(Voice);
    }

    FString Result;
    auto Writer = TJsonWriterFactory<>::Create(&Result);
    Writer->WriteObjectStart();
    Writer->WriteValue(TEXT("language"), FInternationalization::Get().GetCurrentLanguage()->GetName());
    const auto* Mapping = Dialogue->ContextMappings.FindByPredicate(
        [&](const FDialogueContextMapping& Candidate) { return Candidate.Context == Context; });
    if (Mapping)
    {
        if (Mapping->SoundWave) Writer->WriteValue(TEXT("wave"), LocalizedObjectPath(Mapping->SoundWave->GetPathName()));
        Writer->WriteValue(TEXT("subtitle"), Dialogue->GetLocalizedSubtitle(*Mapping).ToString());
    }
    Writer->WriteObjectEnd();
    return Writer->Close() && FFileHelper::SaveStringToFile(Result, *Output, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM);
}
