#pragma once

#include "CoreMinimal.h"

class FArchive;
class UVolumeCache;

bool WriteLumenFxVolumeCache(FArchive& Archive, const UVolumeCache& Cache, const FString& SavedDirectory);
