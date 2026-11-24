#pragma once

#include "CoreMinimal.h"

class FJsonObject;
class FTextLocalizationResource;

struct FSourceProjectLocalization
{
    FString Language;
    FString Locale;
    TArray<FString> Targets;

    void Read(const FJsonObject& Request);
    bool GetResourceCultures(TArray<FString>& Cultures) const;
    bool Load(FTextLocalizationResource& Resources, const FString& CurrentLanguage, int32 FirstPriority) const;
};
