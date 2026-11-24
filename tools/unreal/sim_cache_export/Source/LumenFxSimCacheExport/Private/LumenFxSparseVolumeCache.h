#pragma once

#include "CoreMinimal.h"
#include "PixelFormat.h"

class UStreamableSparseVolumeTexture;
class USparseVolumeTexture;

bool WriteLumenFxSparseVolumeFrame(FArchive& Archive, const UStreamableSparseVolumeTexture& Volume, int32 FrameIndex, int32 TargetFormat);
bool WriteLumenFxDenseVolumeFrame(FArchive& Archive, FIntVector Extent, EPixelFormat Format, int32 TargetFormat, TConstArrayView<uint8> Voxels);
bool WriteLumenFxVolumeControls(FArchive& Archive, FIntVector Extent, int32 TargetFormat);
bool WriteLumenFxSparseVolumeAsset(FArchive& Archive, USparseVolumeTexture& Volume);
