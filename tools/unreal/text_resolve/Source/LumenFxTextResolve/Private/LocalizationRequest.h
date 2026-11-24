#pragma once

#include "CoreMinimal.h"
#include "Misc/PackageName.h"

class FJsonObject;

bool MountLocalizationContent(const FJsonObject& Request, TArray<TRefCountPtr<UE::PackageName::IMountPoint>>& Mounts);
bool LoadLocalization(const FJsonObject& Request);
