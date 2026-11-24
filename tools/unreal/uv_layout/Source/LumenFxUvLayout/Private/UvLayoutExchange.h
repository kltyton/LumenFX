#pragma once

#include "CoreMinimal.h"
#include "UvLayoutMeshView.h"

struct FUvLayoutOptions
{
    uint32 Resolution;
    ELightmapUVVersion Version;
    float ComparisonThreshold;
};

bool ReadUvLayoutInput(const FString& path, FUvLayoutMeshView& view, FUvLayoutOptions& options);
bool WriteUvLayoutOutput(const FString& path, const FUvLayoutMeshView& view, bool packed);
