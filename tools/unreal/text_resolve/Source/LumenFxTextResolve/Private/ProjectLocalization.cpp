#include "ProjectLocalization.h"
#include "Dom/JsonObject.h"
#include "HAL/FileManager.h"
#include "HAL/PlatformProperties.h"
#include "Internationalization/Culture.h"
#include "Internationalization/Internationalization.h"
#include "Internationalization/TextLocalizationResource.h"
#include "Misc/ConfigCacheIni.h"
#include "Misc/Paths.h"

namespace
{
    constexpr const TCHAR* Section = TEXT("Internationalization");

    FString RequestedCulture(const FConfigFile& Game, const FConfigFile& Engine, const TCHAR* Key)
    {
        FString Value;
        for (const FConfigFile* Config : { &Game, &Engine })
        {
            Config->GetString(Section, Key, Value);
            if (!Value.IsEmpty()) return Value;
            Config->GetString(Section, TEXT("Culture"), Value);
            if (!Value.IsEmpty()) return Value;
        }
        return FString();
    }

    FString PlatformDirectory(const FString& Target)
    {
        const FString Platform = ANSI_TO_TCHAR(FPlatformProperties::IniPlatformName());
        return Target / FPaths::GetPlatformLocalizationFolderName() / Platform;
    }

    bool LoadCulture(FTextLocalizationResource& Resources, const FString& Directory,
        const FString& Culture, const FString& Filename, int32 Priority)
    {
        const FString Common = Directory / Culture / Filename;
        const FString PlatformSpecific = PlatformDirectory(Directory) / Culture / Filename;
        return (!FPaths::FileExists(Common) || Resources.LoadFromFile(Common, Priority))
            && (!FPaths::FileExists(PlatformSpecific) || Resources.LoadFromFile(PlatformSpecific, Priority));
    }
}

void FSourceProjectLocalization::Read(const FJsonObject& Request)
{
    const TSharedPtr<FJsonObject>* Mounts = nullptr;
    FString Content;
    if (!Request.TryGetObjectField(TEXT("mounts"), Mounts) || !(*Mounts)->TryGetStringField(TEXT("/Game/"), Content)) return;
    FPaths::NormalizeDirectoryName(Content);
    const FString Project = FPaths::GetPath(Content);
    const FString Config = Project / TEXT("Config");
    if (!FPaths::DirectoryExists(Config)) return;

    FConfigFile Game, Engine;
    const FString EngineConfig = FPaths::EngineConfigDir();
    const FString Platform = ANSI_TO_TCHAR(FPlatformProperties::IniPlatformName());
    FConfigCacheIni::LoadExternalIniFile(Game, TEXT("Game"), *EngineConfig, *Config, true, *Platform, true, false, false);
    FConfigCacheIni::LoadExternalIniFile(Engine, TEXT("Engine"), *EngineConfig, *Config, true, *Platform, true, false, false);
    Language = RequestedCulture(Game, Engine, TEXT("Language"));
    Locale = RequestedCulture(Game, Engine, TEXT("Locale"));

    const auto* Internationalization = Game.FindSection(Section);
    if (Internationalization == nullptr) return;
    TArray<FConfigValue> Paths;
    Internationalization->MultiFind(FName(TEXT("LocalizationPaths")), Paths, true);
    TArray<FString> Disabled, GameDisabled;
    Engine.GetArray(Section, TEXT("DisabledLocalizationTargets"), Disabled);
    Game.GetArray(Section, TEXT("DisabledLocalizationTargets"), GameDisabled);
    Disabled.Append(MoveTemp(GameDisabled));

    TArray<FString> ProjectFiles;
    IFileManager::Get().FindFiles(ProjectFiles, *(Project / TEXT("*.uproject")), true, false);
    const FString ProjectName = ProjectFiles.Num() == 1 ? FPaths::GetBaseFilename(ProjectFiles[0]) : FPaths::GetCleanFilename(Project);
    const FString ProjectPrefix = Project + TEXT("/");
    const FString SavedPrefix = Project + TEXT("/Saved/");
    for (const auto& Entry : Paths)
    {
        FString Path = Entry.GetSavedValue();
        Path.ReplaceInline(TEXT("%GAMEDIR%"), *ProjectPrefix, ESearchCase::CaseSensitive);
        Path.ReplaceInline(TEXT("%GAMESAVEDDIR%"), *SavedPrefix, ESearchCase::CaseSensitive);
        Path.ReplaceInline(TEXT("%GAME%"), *ProjectName, ESearchCase::CaseSensitive);
        Path = FConfigValue(MoveTemp(Path)).GetValue();
        if (Path.IsEmpty()) continue;
        Path = FPaths::ConvertRelativePathToFull(Project, Path);
        FPaths::NormalizeDirectoryName(Path);
        if (!Disabled.Contains(FPaths::GetBaseFilename(Path))) Targets.AddUnique(MoveTemp(Path));
    }
}

bool FSourceProjectLocalization::GetResourceCultures(TArray<FString>& Cultures) const
{
    Cultures = TextLocalizationResourceUtil::GetLocalizedCultureNames(Targets);
    for (const FString& Target : Targets)
    {
        const FString TargetName = FPaths::GetBaseFilename(Target);
        const FString LocRes = TargetName + TEXT(".locres");
        IFileManager::Get().IterateDirectory(*PlatformDirectory(Target), [&](const TCHAR* Path, bool bDirectory)
        {
            if (bDirectory && FPaths::FileExists(FString(Path) / LocRes))
            {
                const FString Culture = FCulture::GetCanonicalName(FPaths::GetCleanFilename(Path));
                if (FInternationalization::Get().IsCultureAllowed(Culture)) Cultures.AddUnique(Culture);
            }
            return true;
        });
        const FString Meta = Target / (TargetName + TEXT(".locmeta"));
        if (FPaths::FileExists(Meta))
        {
            FTextLocalizationMetaDataResource Metadata;
            if (!Metadata.LoadFromFile(Meta)) return false;
            if (!Metadata.NativeCulture.IsEmpty()) Cultures.AddUnique(MoveTemp(Metadata.NativeCulture));
        }
    }
    return true;
}

bool FSourceProjectLocalization::Load(FTextLocalizationResource& Resources, const FString& CurrentLanguage, int32 FirstPriority) const
{
    if (Targets.IsEmpty()) return true;
    const auto Cultures = FInternationalization::Get().GetPrioritizedCultureNames(CurrentLanguage);
    for (const FString& Target : Targets)
    {
        const FString Meta = Target / (FPaths::GetBaseFilename(Target) + TEXT(".locmeta"));
        if (!FPaths::FileExists(Meta)) continue;
        FTextLocalizationMetaDataResource Metadata;
        if (!Metadata.LoadFromFile(Meta)) return false;
        if (!Metadata.NativeCulture.IsEmpty() && !Cultures.Contains(Metadata.NativeCulture)
            && !LoadCulture(Resources, Target, Metadata.NativeCulture, FPaths::GetCleanFilename(Metadata.NativeLocRes),
                FirstPriority + Cultures.Num()))
            return false;
    }
    for (int32 Culture = 0; Culture < Cultures.Num(); ++Culture)
        for (const FString& Target : Targets)
            if (!LoadCulture(Resources, Target, Cultures[Culture], FPaths::GetBaseFilename(Target) + TEXT(".locres"), FirstPriority + Culture))
                return false;
    return true;
}
