#include "NaniteRenderExchange.h"

#include "Engine/RendererSettings.h"
#include "Engine/StaticMesh.h"
#include "HAL/PlatformProcess.h"
#include "Interfaces/ITargetPlatform.h"
#include "Misc/Crc.h"
#include "Rendering/NaniteResources.h"
#include "Serialization/BufferArchive.h"

namespace
{
    template<typename T>
    void WritePackedArray(FArchive& Output, const TArray<T>& Values)
    {
        int32 Count = Values.Num();
        Output << Count;
        if (Count) Output.Serialize(const_cast<T*>(Values.GetData()), int64(Count) * sizeof(T));
    }

    bool InBuffer(uint32 Offset, uint32 Size, int64 Capacity)
    {
        return int64(Offset) + Size <= Capacity;
    }
}

bool WriteNaniteRenderExchange(FArchive& Output, Nanite::FResources* Resources, UStaticMesh& Owner, const ITargetPlatform& Platform)
{
    if (!Resources)
    {
        int32 NoIndex = 0;
        Output << NoIndex;
        return !Output.IsError();
    }

    auto& Data = *Resources;
    const int32 NumPages = Data.PageStreamingStates.Num();
    if (Data.NumRootPages == 0 || Data.NumRootPages > uint32(NumPages)) return false;
    const int32 NumStreamingPages = NumPages - Data.NumRootPages;
    if (NumStreamingPages > 0 && Data.StreamablePages.GetBulkDataSize() == 0)
    {
        bool bFailed = false;
        while (!Data.RebuildBulkDataFromCacheAsync(&Owner, bFailed))
        {
            FPlatformProcess::SleepNoStats(0.001f);
        }
        if (bFailed || Data.StreamablePages.GetBulkDataSize() <= 0) return false;
    }

    FBufferArchive Index;
    uint32 Magic = 0x544e464c, Version = 2;
    uint32 Flags = Data.ResourceFlags & ~NANITE_RESOURCE_FLAG_STREAMING_DATA_IN_DDC;
    uint32 bOptional = !Platform.HasEditorOnlyData()
        && GetDefault<URendererSettings>()->bNaniteStreamableBulkDataOptional.GetValueForPlatform(*Platform.IniPlatformName());
    Index << Magic << Version << Flags << bOptional;
    Index << Data.NumRootPages << Data.NumClusters << Data.NumInputTriangles << Data.NumInputVertices << Data.NumInputCurves;
    Index << Data.PositionPrecision << Data.NormalPrecision << Data.TangentPrecision << Data.VoxelMaterialsMask;
    FVector3f Origin = Data.MeshBounds.Origin, Extent = Data.MeshBounds.BoxExtent;
    float Radius = Data.MeshBounds.SphereRadius;
    Index << Origin.X << Origin.Y << Origin.Z << Extent.X << Extent.Y << Extent.Z << Radius;
    uint32 PersistentHash = FMath::Max(FCrc::StrCrc32<TCHAR>(*Owner.GetName()), 1u);
    Index << PersistentHash;
    int32 PageCount = NumPages;
    Index << PageCount;
    const int64 StreamSize = Data.StreamablePages.GetBulkDataSize();
    for (int32 Page = 0; Page < NumPages; ++Page)
    {
        auto State = Data.PageStreamingStates[Page];
        const int64 Capacity = Data.IsRootPage(Page) ? Data.RootData.Num() : StreamSize;
        if (!State.BulkSize || State.PageSize >= State.BulkSize || !InBuffer(State.BulkOffset, State.BulkSize, Capacity)
            || int64(State.DependenciesStart) + State.DependenciesNum > Data.PageDependencies.Num())
            return false;
        Index << State.BulkOffset << State.BulkSize << State.PageSize << State.DependenciesStart;
        Index << State.DependenciesNum << State.MaxHierarchyDepth << State.Flags;
    }
    WritePackedArray(Index, Data.HierarchyNodes);
    WritePackedArray(Index, Data.HierarchyRootOffsets);
    WritePackedArray(Index, Data.PageDependencies);
    WritePackedArray(Index, Data.AssemblyTransforms);
    WritePackedArray(Index, Data.AssemblyBoneAttachmentData);
    WritePackedArray(Index, Data.BoneIndices);
    WritePackedArray(Index, Data.PageRangeLookup);
    WritePackedArray(Index, Data.RootData);
    if (Index.IsError() || Index.TotalSize() > MAX_int32) return false;
    int32 IndexBytes = Index.Num(), PayloadCount = NumStreamingPages;
    Output << IndexBytes;
    Output.Serialize(Index.GetData(), IndexBytes);
    Output << PayloadCount;
    if (PayloadCount)
    {
        const auto* Bytes = static_cast<const uint8*>(Data.StreamablePages.LockReadOnly());
        if (!Bytes)
        {
            Data.StreamablePages.Unlock();
            return false;
        }
        for (int32 Page = Data.NumRootPages; Page < NumPages; ++Page)
        {
            auto State = Data.PageStreamingStates[Page];
            Output << State.BulkSize;
            Output.Serialize(const_cast<uint8*>(Bytes + State.BulkOffset), State.BulkSize);
        }
        Data.StreamablePages.Unlock();
    }
    return !Output.IsError();
}
