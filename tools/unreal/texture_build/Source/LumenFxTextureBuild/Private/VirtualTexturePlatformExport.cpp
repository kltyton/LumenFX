#include "VirtualTexturePlatformExport.h"
#include "DerivedDataCache.h"
#include "DerivedDataRequestOwner.h"
#include "Engine/Texture.h"
#include "Engine/Texture2D.h"
#include "Engine/TextureLODSettings.h"
#include "HAL/FileManager.h"
#include "Interfaces/ITargetPlatform.h"
#include "Serialization/Archive.h"
#include "VT/VirtualTextureBuiltData.h"
#include "VT/VirtualTextureScalability.h"

namespace
{
    bool WriteChunk(FArchive& Archive, FVirtualTextureDataChunk& Chunk, const FString& Asset)
    {
        if (Chunk.BulkData.GetBulkDataSize() > 0)
        {
            if (Chunk.BulkData.GetBulkDataSize() != Chunk.SizeInBytes) return false;
            const void* Data = Chunk.BulkData.LockReadOnly();
            if (Data) Archive.Serialize(const_cast<void*>(Data), Chunk.SizeInBytes);
            Chunk.BulkData.Unlock();
            return Data && !Archive.IsError();
        }

        // Match FVirtualTextureBuiltData's DDC path when the cooker left the chunk out of BulkData.
        if (Chunk.DerivedDataKey.IsEmpty()) return false;
        using namespace UE::DerivedData;
        FSharedBuffer Payload;
        FRequestOwner Owner(EPriority::Blocking);
        GetCache().GetValue({{{FStringView(Asset)}, ConvertLegacyCacheKey(Chunk.DerivedDataKey)}}, Owner,
            [&Payload](FCacheGetValueResponse&& Response) { Payload = Response.Value.GetData().Decompress(); });
        Owner.Wait();
        if (Payload.IsNull() || Payload.GetSize() != Chunk.SizeInBytes) return false;
        Archive.Serialize(const_cast<void*>(Payload.GetData()), Chunk.SizeInBytes);
        return !Archive.IsError();
    }

    void WriteFormat(FArchive& Archive, EPixelFormat PixelFormat)
    {
        FTCHARToUTF8 Name(GPixelFormats[PixelFormat].Name);
        uint32 Length = Name.Length();
        Archive << Length;
        Archive.Serialize(const_cast<ANSICHAR*>(Name.Get()), Length);
    }
}

bool WriteVirtualTexturePlatformData(UTexture& Texture, FTexturePlatformData& PlatformData, ITargetPlatform& Platform,
    const FString& Asset, const FString& Output, const TArray<uint8>& Digest)
{
    auto& Data = *PlatformData.VTData;
    if (!Data.IsInitialized() || Data.NumMips == 0 || Data.NumLayers == 0 || Data.NumLayers > VIRTUALTEXTURE_DATA_MAXLAYERS
        || Data.NumMips > VIRTUALTEXTURE_DATA_MAXMIPS || Data.TileDataOffsetPerLayer.Num() != int32(Data.NumLayers)
        || Data.TileOffsetInChunk.Num() != 0 || Data.WidthInBlocks == 0 || Data.HeightInBlocks == 0) return false;

    // Serialize the cooker-selected mip range, using the same virtual LOD rules as SerializeCooked.
    const UTextureLODSettings& Lods = Platform.GetTextureLODSettings();
    const uint32 FirstMip = FMath::Clamp(Lods.CalculateLODBias(PlatformData.SizeX, PlatformData.SizeY,
        Texture.GetMinTextureSize(), Texture.GetMaxTextureSize(), Texture.LODGroup, Texture.LODBias, 0,
        Texture.MipGenSettings, true, Texture.IsStreamable()), 0, int32(Data.NumMips) - 1);
    if (!Data.ChunkIndexPerMip.IsValidIndex(FirstMip)) return false;
    const uint32 FirstChunk = Data.ChunkIndexPerMip[FirstMip];
    if (!Data.Chunks.IsValidIndex(FirstChunk)) return false;

    TUniquePtr<FArchive> Writer(IFileManager::Get().CreateFileWriter(*Output));
    if (!Writer) return false;
    FArchive& Ar = *Writer;
    uint32 Magic = 0x5456464c, Version = 1;
    uint32 Width = FMath::Max(1u, Data.Width >> FirstMip), Height = FMath::Max(1u, Data.Height >> FirstMip);
    uint32 Mips = Data.NumMips - FirstMip, Chunks = Data.Chunks.Num() - FirstChunk;
    uint32 TileStride = Data.TileDataOffsetPerLayer.Last();
    uint32 PagesX = FMath::DivideAndRoundUp(FMath::Max(1u, Data.GetWidthInTiles() >> FirstMip), Data.WidthInBlocks)
        * Data.WidthInBlocks;
    uint32 PagesY = FMath::DivideAndRoundUp(FMath::Max(1u, Data.GetHeightInTiles() >> FirstMip), Data.HeightInBlocks)
        * Data.HeightInBlocks;
    uint32 MaxAnisotropy = FMath::Max(0, VirtualTextureScalability::GetMaxAnisotropy());
    uint32 PhysicalAnisotropy = VirtualTextureScalability::IsAnisotropicFilteringEnabled()
        ? FMath::Min(MaxAnisotropy, Data.TileBorderSize) : 0;
    uint32 Filter;
    switch (Lods.GetSamplerFilter(&Texture))
    {
        case ETextureSamplerFilter::Point: Filter = 0; break;
        case ETextureSamplerFilter::Bilinear: Filter = 1; break;
        case ETextureSamplerFilter::Trilinear: Filter = 2; break;
        case ETextureSamplerFilter::AnisotropicPoint: Filter = PhysicalAnisotropy > 0 ? 3 : 1; break;
        case ETextureSamplerFilter::AnisotropicLinear: Filter = PhysicalAnisotropy > 0 ? 4 : 1; break;
        default: return false;
    }
    auto Address = [](TextureAddress Mode) -> uint32 { return Mode == TA_Wrap ? 0 : Mode == TA_Clamp ? 1 : 2; };
    const auto& Texture2D = *CastChecked<UTexture2D>(&Texture);
    uint32 AddressU = Address(Texture2D.AddressX), AddressV = Address(Texture2D.AddressY);
    Ar << Magic << Version;
    Ar.Serialize(const_cast<uint8*>(Digest.GetData()), Digest.Num());
    Ar << Width << Height << Mips << Data.WidthInBlocks << Data.HeightInBlocks
        << Data.TileSize << Data.TileBorderSize << Data.NumLayers << Chunks << TileStride;
    Ar << PagesX << PagesY << MaxAnisotropy << PhysicalAnisotropy << Filter << AddressU << AddressV;

    uint32 LayerStart = 0;
    const uint32 PhysicalSize = Data.GetPhysicalTileSize();
    for (uint32 Layer = 0; Layer < Data.NumLayers; ++Layer)
    {
        const EPixelFormat Format = Data.LayerTypes[Layer];
        if (Format <= PF_Unknown || Format >= PF_MAX) return false;
        const auto& Info = GPixelFormats[Format];
        if (!Info.BlockSizeX || !Info.BlockSizeY || !Info.BlockBytes) return false;
        uint32 TileBytes = FMath::DivideAndRoundUp(PhysicalSize, uint32(Info.BlockSizeX))
            * FMath::DivideAndRoundUp(PhysicalSize, uint32(Info.BlockSizeY)) * Info.BlockBytes;
        if (uint64(LayerStart) + TileBytes != Data.TileDataOffsetPerLayer[Layer]) return false;
        uint32 Flags = Texture.SRGB ? 1 : 0;
        uint32 Fallback = Data.LayerFallbackColors[Layer].ToFColor(false).DWColor();
        WriteFormat(Ar, Format);
        Ar << Flags << Fallback << LayerStart << TileBytes;
        LayerStart += TileBytes;
    }

    for (uint32 Mip = FirstMip; Mip < Data.NumMips; ++Mip)
    {
        if (!Data.ChunkIndexPerMip.IsValidIndex(Mip) || !Data.TileOffsetData.IsValidIndex(Mip)
            || !Data.BaseOffsetPerMip.IsValidIndex(Mip)) return false;
        uint32 Chunk = Data.ChunkIndexPerMip[Mip];
        if (Chunk < FirstChunk || !Data.Chunks.IsValidIndex(Chunk)) return false;
        Chunk -= FirstChunk;
        auto& Index = Data.TileOffsetData[Mip];
        if (Index.Addresses.Num() != Index.Offsets.Num()) return false;
        uint32 Runs = Index.Addresses.Num();
        Ar << Index.Width << Index.Height << Index.MaxAddress << Chunk << Data.BaseOffsetPerMip[Mip] << Runs;
        for (uint32 Run = 0; Run < Runs; ++Run) Ar << Index.Addresses[Run] << Index.Offsets[Run];
    }

    for (uint32 ChunkIndex = FirstChunk; ChunkIndex < uint32(Data.Chunks.Num()); ++ChunkIndex)
    {
        auto& Chunk = Data.Chunks[ChunkIndex];
        for (uint32 Layer = 0; Layer < Data.NumLayers; ++Layer)
            if (Chunk.CodecType[Layer] != EVirtualTextureCodec::RawGPU) return false;
        Ar << Chunk.SizeInBytes;
    }
    for (uint32 Chunk = FirstChunk; Chunk < uint32(Data.Chunks.Num()); ++Chunk)
        if (!WriteChunk(Ar, Data.Chunks[Chunk], Asset)) return false;
    return Ar.Close() && !Ar.IsError();
}
