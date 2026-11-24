#include "DialogueCultureGroups.h"
#include "LocalizationRequest.h"
#include "ProjectLocalization.h"
#include "Dom/JsonObject.h"
#include "HAL/FileManager.h"
#include "Internationalization/Culture.h"
#include "Internationalization/Internationalization.h"
#include "Misc/FileHelper.h"
#include "Misc/Paths.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "Serialization/JsonWriter.h"

namespace
{
    TSet<FString> PackageCultureFolders(const FJsonObject& Request)
    {
        TSet<FString> Folders;
        for (const auto& Root : Request.GetObjectField(TEXT("mounts"))->Values)
        {
            FString Path;
            Root.Value->TryGetString(Path);
            IFileManager::Get().IterateDirectory(*(Path / TEXT("L10N")), [&](const TCHAR* Entry, bool bDirectory)
            {
                if (bDirectory) Folders.Add(FPaths::GetCleanFilename(Entry));
                return true;
            });
        }
        return Folders;
    }
}

bool ResolveDialogueCultureGroups(const FString& Input, const FString& Output)
{
    FString Json;
    TSharedPtr<FJsonObject> Request;
    TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
    if (!FFileHelper::LoadFileToString(Json, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Request) || !Request
        || !MountLocalizationContent(*Request, Mounts)) return false;

    FSourceProjectLocalization Project;
    Project.Read(*Request);
    TArray<FString> ResourceCultures;
    if (!Project.GetResourceCultures(ResourceCultures)) return false;
    const TSet<FString> PackageCultures = PackageCultureFolders(*Request);

    struct FCultureGroup
    {
        TArray<FString> Aliases;
        bool bNativeFallback;
    };
    TArray<FCultureGroup> Groups;
    if (!ResourceCultures.IsEmpty() || !PackageCultures.IsEmpty())
    {
        auto& I18N = FInternationalization::Get();
        TArray<FString> Cultures;
        I18N.GetCultureNames(Cultures);
        for (const FString& Culture : ResourceCultures) Cultures.AddUnique(Culture);
        for (const FString& Folder : PackageCultures) Cultures.AddUnique(FCulture::GetCanonicalName(Folder));
        Cultures.Sort();
        TMap<FString, int32> GroupByResources;
        for (const FString& Culture : Cultures)
        {
            if (Culture.IsEmpty() || !I18N.GetCulture(Culture).IsValid()) continue;
            // Include both the source LocRes chain and the localized package lookup chain.
            const auto Resources = I18N.GetPrioritizedCultureNames(Culture).FilterByPredicate(
                [&](const FString& Parent)
                {
                    return ResourceCultures.Contains(Parent) || PackageCultures.Contains(Parent)
                        || PackageCultures.Contains(FCulture::CultureNameToVerseIdentifier(Parent));
                });
            const FString Signature = FString::Join(Resources, TEXT("|"));
            int32* Existing = GroupByResources.Find(Signature);
            const int32 Index = Existing ? *Existing : Groups.Add(FCultureGroup{{}, Resources.IsEmpty()});
            if (!Existing) GroupByResources.Add(Signature, Index);
            Groups[Index].Aliases.Add(Culture);
        }
    }

    FString Result;
    auto Writer = TJsonWriterFactory<>::Create(&Result);
    Writer->WriteArrayStart();
    for (const auto& Group : Groups)
    {
        Writer->WriteObjectStart();
        Writer->WriteValue(TEXT("language"), Group.Aliases[0]);
        Writer->WriteValue(TEXT("native_fallback"), Group.bNativeFallback);
        Writer->WriteArrayStart(TEXT("aliases"));
        for (const FString& Culture : Group.Aliases) Writer->WriteValue(Culture);
        Writer->WriteArrayEnd();
        Writer->WriteObjectEnd();
    }
    Writer->WriteArrayEnd();
    return Writer->Close() && FFileHelper::SaveStringToFile(Result, *Output, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM);
}
