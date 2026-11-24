#pragma once

#include "CoreMinimal.h"

class ITargetPlatform;
class UTexture;
struct FTexturePlatformData;

bool WriteVirtualTexturePlatformData(UTexture& Texture, FTexturePlatformData& PlatformData, ITargetPlatform& Platform,
    const FString& Asset, const FString& Output, const TArray<uint8>& Digest);
