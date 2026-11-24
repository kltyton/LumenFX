#pragma once

#include "Containers/ArrayView.h"
#include "Math/IntPoint.h"
#include "UObject/NameTypes.h"

class FRHICommandListImmediate;
class FRHITexture;

bool ReadHalfColorTextureSlice(FRHICommandListImmediate& Commands, FRHITexture* Texture, FIntPoint Extent,
    uint32 Slice, const FName& DebugName, TArrayView<uint16> Destination);
