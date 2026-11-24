#include "LumenFxSparseVolumeCache.h"

#include "HAL/PlatformProcess.h"
#include "SparseVolumeTexture/SparseVolumeTexture.h"
#include "Containers/StringConv.h"
#include "SparseVolumeTextureUtility.h"

namespace
{
    struct FPage
    {
        FIntVector Origin;
        uint32 Tile;
    };

    void Text(FArchive& Archive, const FString& Value)
    {
        FTCHARToUTF8 Utf8(*Value);
        int32 Length = Utf8.Length();
        Archive << Length;
        if (Length != 0) Archive.Serialize(const_cast<ANSICHAR*>(Utf8.Get()), Length);
    }

    void Vector(FArchive& Archive, const FVector& Value)
    {
        double X = Value.X, Y = Value.Y, Z = Value.Z;
        Archive << X << Y << Z;
    }

    struct FStreamableTiles
    {
        UE::SVT::FResources& Resource;
        const uint8* Data = nullptr;
        int64 Size = 0;
        bool bLocked = false;

        explicit FStreamableTiles(UE::SVT::FResources& InResource) : Resource(InResource) {}
        ~FStreamableTiles() { if (bLocked) Resource.StreamableMipLevels.Unlock(); }

        bool Open(USparseVolumeTextureFrame* Frame, bool bRequired)
        {
            if (!bRequired) return true;
#if WITH_EDITORONLY_DATA
            if ((Resource.ResourceFlags & UE::SVT::EResourceFlag_StreamingDataInDDC) != 0)
            {
                bool bFailed = false;
                const double Deadline = FPlatformTime::Seconds() + 120;
                while (!Resource.RebuildBulkDataFromCacheAsync(Frame, bFailed))
                {
                    if (FPlatformTime::Seconds() >= Deadline) return false;
                    FPlatformProcess::Sleep(.001f);
                }
                if (bFailed) return false;
            }
#endif
            Size = Resource.StreamableMipLevels.GetBulkDataSize();
            if (Size <= 0) return false;
            Data = static_cast<const uint8*>(Resource.StreamableMipLevels.LockReadOnly());
            bLocked = true;
            return Data != nullptr;
        }
    };

    void NormalizePixel(uint8* Pixel, EPixelFormat Format)
    {
        if (Format == PF_B8G8R8A8) Swap(Pixel[0], Pixel[2]);
    }

    bool MissingFrame(FArchive& Archive)
    {
        int32 Format = -1;
        Archive << Format;
        return !Archive.IsError();
    }

    bool Frame(FArchive& Archive, int32 Format, FIntVector Extent, bool bWritePixels, FVector4f Fallback, int32 NumTiles,
        TArray<FPage>& Pages, TArray<uint8>& Voxels)
    {
        int32 PageCount = Pages.Num();
        Archive << Format << Extent.X << Extent.Y << Extent.Z;
        uint32 Flags = bWritePixels ? 1 : 0;
        Archive << Flags;
        Archive << Fallback.X << Fallback.Y << Fallback.Z << Fallback.W;
        Archive << NumTiles << PageCount;
        for (FPage& Page : Pages)
            Archive << Page.Origin.X << Page.Origin.Y << Page.Origin.Z << Page.Tile;
        int32 ByteCount = Voxels.Num();
        Archive << ByteCount;
        if (ByteCount != 0) Archive.Serialize(Voxels.GetData(), ByteCount);
        return !Archive.IsError();
    }

    bool ExpandTile(const UE::SVT::FResources& Resource, uint32 Index, const uint8* Bulk, int64 BulkSize,
        uint8* Output, EPixelFormat Format, uint32 FormatBytesA, uint32 FormatBytesB, int32 Attributes = 0, bool bPadded = false)
    {
        const auto Info = Resource.StreamingMetaData.GetTileInfo(Index, FormatBytesA, FormatBytesB);
        const uint32 PixelBytes = Attributes == 0 ? FormatBytesA : FormatBytesB;
        const bool bRoot = Index < Resource.StreamingMetaData.FirstStreamingTileIndex;
        const uint8* Source = bRoot ? Resource.RootData.GetData() : Bulk;
        int64 Size = bRoot ? Resource.RootData.Num() : BulkSize;
        if (!Source || int64(Info.Offset) + Info.Size > Size) return false;
        Source += Info.Offset;
        const uint8* Occupancy = Source + Info.OccupancyBitsOffsets[Attributes];
        const uint8* Data = Source + Info.VoxelDataOffsets[Attributes];
        const auto& Fallback = Resource.Header.FallbackValues[Attributes];
        uint8 DefaultPixel[16] = {};
        UE::SVT::WriteVoxel(0, DefaultPixel, Format, Fallback);
        NormalizePixel(DefaultPixel, Format);
        uint32 Cursor = 0;
        for (int32 Z = 0; Z < SPARSE_VOLUME_TILE_RES_PADDED; ++Z)
            for (int32 Y = 0; Y < SPARSE_VOLUME_TILE_RES_PADDED; ++Y)
                for (int32 X = 0; X < SPARSE_VOLUME_TILE_RES_PADDED; ++X)
                {
                    int32 Voxel = (Z * SPARSE_VOLUME_TILE_RES_PADDED + Y) * SPARSE_VOLUME_TILE_RES_PADDED + X;
                    uint32 Bits;
                    FMemory::Memcpy(&Bits, Occupancy + (Voxel / 32) * sizeof(uint32), sizeof(Bits));
                    bool bPresent = (Bits & (1u << (Voxel & 31))) != 0;
                    if (bPresent && Cursor >= Info.NumVoxels[Attributes]) return false;
                    if (bPadded || (X > 0 && X <= SPARSE_VOLUME_TILE_RES && Y > 0 && Y <= SPARSE_VOLUME_TILE_RES && Z > 0 && Z <= SPARSE_VOLUME_TILE_RES))
                    {
                        int32 IndexInTile = bPadded ? Voxel : ((Z - 1) * SPARSE_VOLUME_TILE_RES + (Y - 1)) * SPARSE_VOLUME_TILE_RES + X - 1;
                        uint8* Destination = Output + IndexInTile * PixelBytes;
                        FMemory::Memcpy(Destination, bPresent ? Data + Cursor * PixelBytes : DefaultPixel, PixelBytes);
                        if (bPresent) NormalizePixel(Destination, Format);
                    }
                    Cursor += bPresent ? 1 : 0;
                }
        return Cursor == Info.NumVoxels[Attributes];
    }
}

bool WriteLumenFxSparseVolumeFrame(FArchive& Archive, const UStreamableSparseVolumeTexture& Volume, int32 FrameIndex, int32 TargetFormat)
{
    auto* TextureFrame = Volume.GetFrame(FrameIndex);
    if (!TextureFrame) return WriteLumenFxVolumeControls(Archive, Volume.GetVolumeResolution(), TargetFormat);
    auto& Resource = *TextureFrame->GetResources();
    const auto Format = Resource.Header.AttributesFormats[0], SecondaryFormat = Resource.Header.AttributesFormats[1];
    const FIntVector Extent = Volume.GetVolumeResolution();
    if (TargetFormat < 0 || !UE::SVT::IsSupportedFormat(Format) || !Resource.Topology.MipInfo.IsValidIndex(0)
        || Extent.X <= 0 || Extent.Y <= 0 || Extent.Z <= 0) return false;
    uint32 PixelBytes = GPixelFormats[Format].BlockBytes;
    uint32 SecondaryBytes = SecondaryFormat == PF_Unknown ? 0 : GPixelFormats[SecondaryFormat].BlockBytes;
    if (PixelBytes == 0 || PixelBytes > 16) return false;
    const auto& Mip = Resource.Topology.MipInfo[0];
    TArray<FPage> Pages;
    TArray<uint8> Data;
    TMap<uint32, uint32> Tiles;
    bool bStreaming = false;
    for (uint32 PageIndex = 0; PageIndex < Mip.PageCount; ++PageIndex)
    {
        uint32 Page = Mip.PageOffset + PageIndex;
        if (!Resource.Topology.IsValidPageIndex(Page) || !Resource.Topology.TileIndices.IsValidIndex(Page)) return false;
        uint32 Tile = Resource.Topology.TileIndices[Page];
        if (Tile >= Resource.StreamingMetaData.GetNumTiles() || !Resource.StreamingMetaData.TileDataOffsets.IsValidIndex(Tile + 1)) return false;
        bStreaming |= Tile >= Resource.StreamingMetaData.FirstStreamingTileIndex;
    }
    FStreamableTiles Bulk(Resource);
    if (!Bulk.Open(TextureFrame, bStreaming)) return false;

    for (uint32 PageIndex = 0; PageIndex < Mip.PageCount; ++PageIndex)
    {
        uint32 Page = Mip.PageOffset + PageIndex;
        uint32 Tile = Resource.Topology.TileIndices[Page];
        uint32 Packed = Resource.Topology.PackedPageTableCoords[Page];
        FIntVector Origin = (FIntVector(Packed & 2047u, (Packed >> 11u) & 2047u, Packed >> 22u)
            + Resource.Header.PageTableVolumeAABBMin) * SPARSE_VOLUME_TILE_RES;
        if (Origin.X >= Extent.X || Origin.Y >= Extent.Y || Origin.Z >= Extent.Z
            || Origin.X <= -SPARSE_VOLUME_TILE_RES || Origin.Y <= -SPARSE_VOLUME_TILE_RES || Origin.Z <= -SPARSE_VOLUME_TILE_RES)
            continue;
        uint32 OutputIndex;
        if (const uint32* Existing = Tiles.Find(Tile))
        {
            OutputIndex = *Existing;
        }
        else
        {
            OutputIndex = Tiles.Num();
            Tiles.Add(Tile, OutputIndex);
            uint32 TileBytes = UE::SVT::NumVoxelsPerTile * PixelBytes;
            if (int64(Data.Num()) + TileBytes > MAX_int32) return false;
            int32 Start = Data.AddUninitialized(TileBytes);
            if (!ExpandTile(Resource, Tile, Bulk.Data, Bulk.Size, Data.GetData() + Start, Format, PixelBytes, SecondaryBytes)) return false;
        }
        Pages.Add({Origin, OutputIndex});
    }
    return Frame(Archive, TargetFormat, Extent, true, Resource.Header.FallbackValues[0], Tiles.Num(), Pages, Data);
}

bool WriteLumenFxVolumeControls(FArchive& Archive, FIntVector Extent, int32 TargetFormat)
{
    if (Extent.X <= 0 || Extent.Y <= 0 || Extent.Z <= 0) return MissingFrame(Archive);
    if (TargetFormat < 0) return false;
    TArray<FPage> Pages;
    TArray<uint8> Data;
    return Frame(Archive, TargetFormat, Extent, false, FVector4f(0, 0, 0, 0), 0, Pages, Data);
}

bool WriteLumenFxDenseVolumeFrame(FArchive& Archive, FIntVector Extent, EPixelFormat Format, int32 TargetFormat, TConstArrayView<uint8> Voxels)
{
    if (Extent.X <= 0 || Extent.Y <= 0 || Extent.Z <= 0) return MissingFrame(Archive);
    uint32 PixelBytes = GPixelFormats[Format].BlockBytes;
    if (TargetFormat < 0 || PixelBytes == 0 || PixelBytes > 16 || int64(Extent.X) * Extent.Y * Extent.Z * PixelBytes != Voxels.Num()) return false;
    TArray<FPage> Pages;
    TArray<uint8> Data;
    const int32 TileBytes = UE::SVT::NumVoxelsPerTile * PixelBytes;
    TArray<uint8> Tile;
    Tile.SetNumZeroed(TileBytes);
    for (int32 Z = 0; Z < Extent.Z; Z += SPARSE_VOLUME_TILE_RES)
        for (int32 Y = 0; Y < Extent.Y; Y += SPARSE_VOLUME_TILE_RES)
            for (int32 X = 0; X < Extent.X; X += SPARSE_VOLUME_TILE_RES)
            {
                FMemory::Memzero(Tile.GetData(), TileBytes);
                bool bPresent = false;
                for (int32 TileZ = 0; TileZ < SPARSE_VOLUME_TILE_RES && Z + TileZ < Extent.Z; ++TileZ)
                    for (int32 TileY = 0; TileY < SPARSE_VOLUME_TILE_RES && Y + TileY < Extent.Y; ++TileY)
                        for (int32 TileX = 0; TileX < SPARSE_VOLUME_TILE_RES && X + TileX < Extent.X; ++TileX)
                        {
                            int64 Source = ((int64(Z + TileZ) * Extent.Y + Y + TileY) * Extent.X + X + TileX) * PixelBytes;
                            int32 Destination = ((TileZ * SPARSE_VOLUME_TILE_RES + TileY) * SPARSE_VOLUME_TILE_RES + TileX) * PixelBytes;
                            uint8* Pixel = Tile.GetData() + Destination;
                            FMemory::Memcpy(Pixel, Voxels.GetData() + Source, PixelBytes);
                            NormalizePixel(Pixel, Format);
                            for (uint32 Byte = 0; Byte < PixelBytes; ++Byte) bPresent |= Pixel[Byte] != 0;
                        }
                if (!bPresent) continue;
                if (int64(Data.Num()) + TileBytes > MAX_int32) return false;
                uint32 Index = Pages.Num();
                Pages.Add({FIntVector(X, Y, Z), Index});
                Data.Append(Tile);
            }
    return Frame(Archive, TargetFormat, Extent, true, FVector4f(0, 0, 0, 0), Pages.Num(), Pages, Data);
}

bool WriteLumenFxSparseVolumeAsset(FArchive& Archive, USparseVolumeTexture& Volume)
{
    auto* Streamable = Cast<UStreamableSparseVolumeTexture>(&Volume);
    auto* Single = Cast<USparseVolumeTextureFrame>(&Volume);
    FIntVector Resolution = Volume.GetVolumeResolution();
    int32 Frames = Volume.GetNumFrames();
    if ((!Streamable && !Single) || Frames <= 0 || Resolution.X <= 0 || Resolution.Y <= 0 || Resolution.Z <= 0) return false;

    uint32 Magic = 0x5453464c, Version = 1;
    Archive << Magic << Version << Resolution.X << Resolution.Y << Resolution.Z << Frames;
    uint32 Flags = Single ? 1 : 0;
    Archive << Flags;
    int32 AddressX = Volume.GetTextureAddressX(), AddressY = Volume.GetTextureAddressY(), AddressZ = Volume.GetTextureAddressZ();
    Archive << AddressX << AddressY << AddressZ;
    EPixelFormat Formats[2] = {Volume.GetFormat(0), Volume.GetFormat(1)};
    uint32 Sizes[2] = {};
    for (int32 Attribute = 0; Attribute < 2; ++Attribute)
    {
        auto Format = Formats[Attribute];
        if (Format != PF_Unknown && !UE::SVT::IsSupportedFormat(Format)) return false;
        Sizes[Attribute] = Format == PF_Unknown ? 0 : GPixelFormats[Format].BlockBytes;
        if (Sizes[Attribute] > 16) return false;
        Text(Archive, Format == PF_Unknown ? FString() : FString(GPixelFormats[Format].Name));
        Archive << Sizes[Attribute];
    }

    for (int32 Index = 0; Index < Frames; ++Index)
    {
        USparseVolumeTextureFrame* Frame = Streamable ? Streamable->GetFrame(Index) : Single;
        if (!Frame) return false;
        auto& Resource = *Frame->GetResources();
        const auto& Meta = Resource.StreamingMetaData;
        int32 Mips = Resource.Topology.MipInfo.Num(), TileCount = Meta.GetNumTiles();
        if (Mips < 1 || Mips > 31 || Meta.FirstStreamingTileIndex > uint32(TileCount)) return false;
        auto PageExtent = Resource.Header.PageTableVolumeResolution;
        auto PageOffset = Resource.Header.PageTableVolumeAABBMin;
        if (PageExtent.X <= 0 || PageExtent.Y <= 0 || PageExtent.Z <= 0) return false;
        Archive << PageExtent.X << PageExtent.Y << PageExtent.Z << PageOffset.X << PageOffset.Y << PageOffset.Z;
        int32 FrameIndex = Frame->GetFrameIndex();
        Archive << FrameIndex << Mips << TileCount;
        auto Transform = Frame->GetFrameTransform();
        Vector(Archive, Transform.GetTranslation());
        auto Rotation = Transform.GetRotation();
        double X = Rotation.X, Y = Rotation.Y, Z = Rotation.Z, W = Rotation.W;
        Archive << X << Y << Z << W;
        Vector(Archive, Transform.GetScale3D());

        for (const auto& Mip : Resource.Topology.MipInfo)
        {
            uint32 Pages = Mip.PageCount;
            Archive << Pages;
            for (uint32 PageIndex = 0; PageIndex < Pages; ++PageIndex)
            {
                uint32 Page = Mip.PageOffset + PageIndex;
                if (!Resource.Topology.IsValidPageIndex(Page) || !Resource.Topology.TileIndices.IsValidIndex(Page)) return false;
                uint32 Coordinate = Resource.Topology.PackedPageTableCoords[Page], Tile = Resource.Topology.TileIndices[Page];
                if (Tile >= uint32(TileCount)) return false;
                Archive << Coordinate << Tile;
            }
        }
        for (int32 Attribute = 0; Attribute < 2; ++Attribute)
        {
            if (Formats[Attribute] != Resource.Header.AttributesFormats[Attribute]) return false;
            if (Sizes[Attribute] == 0) continue;
            uint8 Pixel[16] = {};
            UE::SVT::WriteVoxel(0, Pixel, Formats[Attribute], Resource.Header.FallbackValues[Attribute]);
            Archive.Serialize(Pixel, Sizes[Attribute]);
        }
        FStreamableTiles Bulk(Resource);
        if (!Bulk.Open(Frame, Meta.GetNumStreamingTiles() > 0)) return false;
        for (int32 TileIndex = 0; TileIndex < TileCount; ++TileIndex)
        {
            if (!Meta.TileDataOffsets.IsValidIndex(TileIndex + 1)) return false;
            const auto Tile = Meta.GetTileInfo(TileIndex, Sizes[0], Sizes[1]);
            const bool bRoot = uint32(TileIndex) < Meta.FirstStreamingTileIndex;
            const uint8* Source = bRoot ? Resource.RootData.GetData() : Bulk.Data;
            int64 Bytes = bRoot ? Resource.RootData.Num() : Bulk.Size;
            if (!Source || int64(Tile.Offset) + Tile.Size > Bytes) return false;
            Source += Tile.Offset;
            for (int32 Attribute = 0; Attribute < 2; ++Attribute)
            {
                if (Sizes[Attribute] == 0) continue;
                if (Tile.OccupancyBitsSizes[Attribute] != UE::SVT::OccupancyBitsSizePerPaddedTile) return false;
                uint32 Count = Tile.NumVoxels[Attribute];
                Archive << Count;
                Archive.Serialize(const_cast<uint8*>(Source + Tile.OccupancyBitsOffsets[Attribute]), Tile.OccupancyBitsSizes[Attribute]);
                if (Tile.VoxelDataSizes[Attribute] > 0)
                    Archive.Serialize(const_cast<uint8*>(Source + Tile.VoxelDataOffsets[Attribute]), Tile.VoxelDataSizes[Attribute]);
            }
        }
    }
    return !Archive.IsError();
}
