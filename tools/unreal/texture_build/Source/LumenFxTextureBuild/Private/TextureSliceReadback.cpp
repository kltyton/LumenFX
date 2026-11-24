#include "TextureSliceReadback.h"

#include "Math/IntVector.h"
#include "Misc/ScopeExit.h"
#include "RHICommandList.h"
#include "RHIGPUReadback.h"
#include "RHIResources.h"

bool ReadHalfColorTextureSlice(FRHICommandListImmediate& Commands, FRHITexture* Texture, FIntPoint Extent,
    uint32 Slice, const FName& DebugName, TArrayView<uint16> Destination)
{
    if (!Texture || Extent.X <= 0 || Extent.Y <= 0 || Extent.X > Texture->GetDesc().Extent.X
        || Extent.Y > Texture->GetDesc().Extent.Y || Slice >= Texture->GetDesc().ArraySize
        || int64(Extent.X) * Extent.Y * 4 != Destination.Num())
        return false;
    const EPixelFormat Format = Texture->GetFormat();
    if (Format != PF_FloatRGBA && Format != PF_FloatR11G11B10) return false;

    FRHIGPUTextureReadback Readback(DebugName);
    Readback.EnqueueCopy(Commands, Texture, FIntVector::ZeroValue, Slice, FIntVector(Extent.X, Extent.Y, 1));
    Readback.Wait(Commands, Readback.GetLastCopyGPUMask());
    int32 RowPitch = 0, BufferHeight = 0;
    const void* Mapped = Readback.Lock(RowPitch, &BufferHeight);
    if (!Mapped) return false;
    ON_SCOPE_EXIT { Readback.Unlock(); };
    if (RowPitch < Extent.X || BufferHeight < Extent.Y) return false;

    for (int32 Y = 0; Y < Extent.Y; ++Y)
    {
        uint16* Row = Destination.GetData() + Y * Extent.X * 4;
        if (Format == PF_FloatRGBA)
        {
            const auto* Source = static_cast<const uint16*>(Mapped) + Y * RowPitch * 4;
            FMemory::Memcpy(Row, Source, Extent.X * 4 * sizeof(uint16));
        }
        else
        {
            const auto* Source = static_cast<const uint32*>(Mapped) + Y * RowPitch;
            // The unsigned 11/11/10 channels have the same exponent as binary16.
            for (int32 X = 0; X < Extent.X; ++X)
            {
                const uint32 Packed = Source[X];
                Row[X * 4] = uint16((Packed & 2047u) << 4);
                Row[X * 4 + 1] = uint16(((Packed >> 11) & 2047u) << 4);
                Row[X * 4 + 2] = uint16((Packed >> 22) << 5);
                Row[X * 4 + 3] = 0x3c00;
            }
        }
    }
    return true;
}
