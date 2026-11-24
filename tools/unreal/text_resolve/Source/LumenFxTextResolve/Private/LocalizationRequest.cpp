#include "LocalizationRequest.h"
#include "ProjectLocalization.h"
#include "Dom/JsonObject.h"
#include "Internationalization/Culture.h"
#include "Internationalization/Internationalization.h"
#include "Internationalization/TextLocalizationManager.h"
#include "Internationalization/TextLocalizationResource.h"
#include "Misc/Paths.h"

bool MountLocalizationContent(const FJsonObject& Request, TArray<TRefCountPtr<UE::PackageName::IMountPoint>>& Mounts)
{
    const TSharedPtr<FJsonObject>* Roots = nullptr;
    if (!Request.TryGetObjectField(TEXT("mounts"), Roots)) return false;
    for (const auto& Root : (*Roots)->Values)
    {
        FString Directory;
        if (!Root.Key.StartsWith(TEXT("/")) || !Root.Key.EndsWith(TEXT("/"))
            || !Root.Value->TryGetString(Directory) || FPaths::IsRelative(Directory)
            || !FPaths::DirectoryExists(Directory)) return false;
        FPaths::NormalizeDirectoryName(Directory);
        Mounts.Add(FPackageName::RegisterMountPoint(Root.Key, Directory + TEXT("/")));
    }
    return true;
}

bool LoadLocalization(const FJsonObject& Request)
{
    FSourceProjectLocalization Project;
    Project.Read(Request);
    FString Language, Locale;
    auto& Internationalization = FInternationalization::Get();
    Request.TryGetStringField(TEXT("language"), Language);
    Request.TryGetStringField(TEXT("locale"), Locale);
    if (Language.IsEmpty()) Language = Project.Language;
    if (Locale.IsEmpty()) Locale = Project.Locale;
    if ((!Language.IsEmpty() && !Internationalization.SetCurrentLanguage(Language))
        || (!Locale.IsEmpty() && !Internationalization.SetCurrentLocale(Locale))) return false;

    auto& Manager = FTextLocalizationManager::Get();
    Manager.WaitForAsyncTasks();
    const TArray<TSharedPtr<FJsonValue>>* Files = nullptr;
    if (!Request.TryGetArrayField(TEXT("locres"), Files)) return false;
    FTextLocalizationResource Resources;
    for (int32 Index = 0; Index < Files->Num(); ++Index)
    {
        FString Path;
        if (!(*Files)[Index]->TryGetString(Path) || FPaths::IsRelative(Path) || !Resources.LoadFromFile(Path, Index)) return false;
    }
    if (!Project.Load(Resources, Internationalization.GetCurrentLanguage()->GetName(), Files->Num())) return false;
    if (!Resources.IsEmpty()) Manager.UpdateFromLocalizationResource(Resources);
    return true;
}
