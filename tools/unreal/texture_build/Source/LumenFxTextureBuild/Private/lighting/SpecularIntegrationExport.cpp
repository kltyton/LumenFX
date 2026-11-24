#include "SpecularIntegrationExport.h"

#include "Misc/ScopeExit.h"
#include "RHIGPUReadback.h"
#include "SystemTextures.h"

bool AppendPreintegratedSpecularGF(FRHICommandListImmediate& Commands, FBufferArchive& Archive)
{
    GSystemTextures.InitializeTextures(Commands, GMaxRHIFeatureLevel);
    if (!GSystemTextures.PreintegratedGF.IsValid()) return false;
    auto* Texture = GSystemTextures.PreintegratedGF->GetRHI();
    if (!Texture) return false;
    uint32 Encoding = Texture->GetFormat() == PF_G16R16 ? 2u : Texture->GetFormat() == PF_R8G8 ? 1u : 0u;
    const auto& Desc = Texture->GetDesc();
    if (Encoding == 0u || Desc.Extent.X <= 0 || Desc.Extent.Y <= 0) return false;
    uint32 Width = Desc.Extent.X, Height = Desc.Extent.Y;
    Archive << Encoding << Width << Height;
    FRHIGPUTextureReadback Readback(TEXT("LumenFxPreintegratedGF"));
    Readback.EnqueueCopy(Commands, Texture, FIntVector::ZeroValue, 0, FIntVector(Width, Height, 1));
    Readback.Wait(Commands, Readback.GetLastCopyGPUMask());
    int32 RowPitch = 0, BufferHeight = 0;
    const auto* Data = static_cast<const uint8*>(Readback.Lock(RowPitch, &BufferHeight));
    if (!Data) return false;
    ON_SCOPE_EXIT { Readback.Unlock(); };
    if (RowPitch < static_cast<int32>(Width) || BufferHeight < static_cast<int32>(Height)) return false;
    const uint32 PixelBytes = Encoding * 2;
    for (uint32 Y = 0; Y < Height; ++Y)
        Archive.Serialize(const_cast<uint8*>(Data + Y * RowPitch * PixelBytes), Width * PixelBytes);
    return !Archive.IsError();
}
