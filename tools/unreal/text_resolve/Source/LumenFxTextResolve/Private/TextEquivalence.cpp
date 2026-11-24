#include "TextEquivalence.h"
#include "LocalizationRequest.h"
#include "Dom/JsonObject.h"
#include "Internationalization/StringTable.h"
#include "Internationalization/StringTableCore.h"
#include "Internationalization/StringTableRegistry.h"
#include "Internationalization/Text.h"
#include "Misc/Base64.h"
#include "Misc/FileHelper.h"
#include "Serialization/CustomVersion.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"
#include "Serialization/MemoryReader.h"
#include "Serialization/NameAsStringProxyArchive.h"
#include "UObject/ObjectVersion.h"
#include "UObject/StrongObjectPtr.h"
#include "UObject/UObjectGlobals.h"

static_assert(UE_ENABLE_ICU, "Unreal text equivalence requires the engine ICU backend");

namespace
{
    bool LoadDependencies(const FJsonObject& Request, TArray<TStrongObjectPtr<UStringTable>>& Tables)
    {
        const TArray<TSharedPtr<FJsonValue>>* TableNames = nullptr;
        const TArray<TSharedPtr<FJsonValue>>* Generators = nullptr;
        if (!Request.TryGetArrayField(TEXT("tables"), TableNames)
            || !Request.TryGetArrayField(TEXT("generators"), Generators)) return false;
        for (const auto& Item : *TableNames)
        {
            FString Path;
            if (!Item->TryGetString(Path)) return false;
            FName TableId(*Path);
            FStringTableRedirects::RedirectTableId(TableId);
            Path = TableId.ToString();
            if (FStringTableRegistry::Get().FindStringTable(TableId)) continue;
            if (Path.StartsWith(TEXT("/")))
            {
                UStringTable* Asset = LoadObject<UStringTable>(nullptr, *Path);
                if (Asset) Tables.Emplace(Asset);
            }
            if (!FStringTableRegistry::Get().FindStringTable(TableId))
            {
                UE_LOG(LogTemp, Error, TEXT("Unable to resolve Unreal string table %s"), *Path);
                return false;
            }
        }
        for (const auto& Item : *Generators)
        {
            FString Type;
            if (!Item->TryGetString(Type)) return false;
            if (!FText::FindRegisteredTextGenerator(FName(*Type)).IsBound())
            {
                UE_LOG(LogTemp, Error, TEXT("Unreal text generator %s is not registered"), *Type);
                return false;
            }
        }
        return true;
    }

    bool ReadText(const FJsonValue& Item, FText& Text)
    {
        FString Encoded;
        TArray<uint8> Bytes;
        if (!Item.TryGetString(Encoded) || !FBase64::Decode(Encoded, Bytes)) return false;
        FMemoryReader Reader(Bytes, true);
        Reader.SetUEVer(GPackageFileUEVersion);
        Reader.SetCustomVersions(FCurrentCustomVersions::GetAll());
        FNameAsStringProxyArchive Archive(Reader);
        FText::SerializeText(Archive, Text);
        return !Archive.IsError() && !Reader.IsError() && Reader.Tell() == Reader.TotalSize();
    }
}

bool ResolveTextEquivalence(const FString& Input, const FString& Output)
{
    FString Json;
    TSharedPtr<FJsonObject> Request;
    const TArray<TSharedPtr<FJsonValue>>* Archives = nullptr;
    TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
    TArray<TStrongObjectPtr<UStringTable>> Tables;
    if (!FFileHelper::LoadFileToString(Json, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Request)
        || !Request || !Request->TryGetArrayField(TEXT("archives"), Archives)
        || !MountLocalizationContent(*Request, Mounts) || !LoadLocalization(*Request)
        || !LoadDependencies(*Request, Tables)) return false;

    TArray<FText> Texts;
    Texts.SetNum(Archives->Num());
    for (int32 Index = 0; Index < Archives->Num(); ++Index)
        if (!ReadText(*(*Archives)[Index], Texts[Index])) return false;

    TArray<int32> Representatives;
    FString Result;
    auto Writer = TJsonWriterFactory<>::Create(&Result);
    Writer->WriteArrayStart();
    for (int32 Index = 0; Index < Texts.Num(); ++Index)
    {
        const int32 Representative = Representatives.IndexOfByPredicate(
            [&](int32 Other) { return Texts[Index].EqualTo(Texts[Other]); });
        if (Representative == INDEX_NONE)
        {
            Representatives.Add(Index);
            Writer->WriteValue(Index);
        }
        else Writer->WriteValue(Representatives[Representative]);
    }
    Writer->WriteArrayEnd();
    return Writer->Close() && FFileHelper::SaveStringToFile(Result, *Output, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM);
}
