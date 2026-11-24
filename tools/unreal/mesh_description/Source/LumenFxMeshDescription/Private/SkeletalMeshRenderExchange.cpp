#include "SkeletalMeshRenderExchange.h"
#include "MeshRenderVertexExchange.h"
#include "Engine/SkeletalMesh.h"
#include "Engine/SkeletalMeshSampling.h"
#include "Engine/SkeletalMeshSocket.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "Rendering/SkeletalMeshRenderData.h"
#include "Rendering/SkinWeightVertexBuffer.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "UObject/UObjectGlobals.h"

namespace
{
    void Text(FArchive& Ar, const FString& Value)
    {
        const FTCHARToUTF8 Utf8(*Value);
        uint32 Bytes = Utf8.Length();
        Ar << Bytes;
        Ar.Serialize(const_cast<ANSICHAR*>(Utf8.Get()), Bytes);
    }

    void Pose(FArchive& Ar, const FTransform& Value)
    {
        const FVector P = Value.GetTranslation(), S = Value.GetScale3D();
        const FQuat Q = Value.GetRotation();
        float X = P.X, Y = P.Y, Z = P.Z, Qx = Q.X, Qy = Q.Y, Qz = Q.Z, Qw = Q.W;
        float Sx = S.X, Sy = S.Y, Sz = S.Z;
        Ar << X << Y << Z << Qx << Qy << Qz << Qw << Sx << Sy << Sz;
    }

    template<typename Integer>
    void Indices(FArchive& Ar, const TArray<Integer>& Values)
    {
        uint32 Count = Values.Num();
        Ar << Count;
        for (auto Value : Values)
        {
            int32 Index = static_cast<int32>(Value);
            Ar << Index;
        }
    }

    void Sampler(FArchive& Ar, const FWeightedRandomSampler* Source)
    {
        uint32 Count = Source ? Source->GetNumEntries() : 0;
        float Total = Source ? Source->GetTotalWeight() : 0.0f;
        Ar << Count << Total;
        for (uint32 Index = 0; Index < Count; ++Index)
        {
            float Probability = Source->GetProb()[Index];
            int32 Alias = Source->GetAlias()[Index];
            Ar << Probability << Alias;
        }
    }

    uint32 LodFlags(const FSkeletalMeshLODRenderData& Lod, bool bAllowCPUAccess, bool bSupportUniformlyDistributedSampling, uint32 IndexCount)
    {
        const auto& Buffers = Lod.StaticVertexBuffers;
        uint32 Flags = bAllowCPUAccess && Lod.GetNumVertices() != 0
                && Buffers.PositionVertexBuffer.GetNumVertices() != 0
                && IndexCount != 0
                && Lod.MultiSizeIndexContainer.IsIndexBufferValid() ? 1 : 0;
        const auto& Colors = Buffers.ColorVertexBuffer;
        if (Colors.GetNumVertices() != 0) Flags |= 2;
        if ((Flags & 2) != 0 && Colors.GetVertexData() != nullptr && Colors.GetAllowCPUAccess()) Flags |= 4;
        const auto* Skin = Lod.GetSkinWeightVertexBuffer();
        if (Skin && Skin->GetBoneInfluenceType() == GPUSkinBoneInfluenceType::UnlimitedBoneInfluence) Flags |= 8;
        if (bSupportUniformlyDistributedSampling) Flags |= 16;
        return Flags;
    }

    bool LodData(FArchive& Ar, const FSkeletalMeshLODRenderData& Lod, const FSkeletalMeshSamplingLODBuiltData* Sampling,
                 bool bAllowCPUAccess, bool bSupportUniformlyDistributedSampling,
                 uint32* OutVertices = nullptr, uint32* OutFlags = nullptr)
    {
        const auto& Buffers = Lod.StaticVertexBuffers;
        const auto* Skin = Lod.GetSkinWeightVertexBuffer();
        uint32 Vertices = Buffers.PositionVertexBuffer.GetNumVertices(), Channels = Buffers.StaticMeshVertexBuffer.GetNumTexCoords();
        if (!Skin || Skin->GetNumVertices() != Vertices) return false;
        TArray<uint32> IndexData;
        if (!Lod.MultiSizeIndexContainer.GetIndexBuffer()) return false;
        Lod.MultiSizeIndexContainer.GetIndexBuffer(IndexData);
        uint32 IndexCount = IndexData.Num(), Sections = Lod.RenderSections.Num();
        uint32 Flags = LodFlags(Lod, bAllowCPUAccess, bSupportUniformlyDistributedSampling, IndexCount);
        if (OutVertices) *OutVertices = Vertices;
        if (OutFlags) *OutFlags = Flags;
        uint32 BoneIndexBytes = Skin->GetBoneIndexByteSize(), BoneWeightBytes = Skin->GetBoneWeightByteSize();
        Ar << Vertices << IndexCount << Channels << Sections << Flags << BoneIndexBytes << BoneWeightBytes;
        if (!WriteRenderVertexChannels(Ar, Buffers)) return false;
        for (uint32 Index : IndexData) Ar << Index;
        for (const auto& Section : Lod.RenderSections)
        {
            int32 Material = Section.MaterialIndex;
            uint32 First = Section.BaseIndex, Triangles = Section.NumTriangles, Start = Section.BaseVertexIndex, Count = Section.NumVertices;
            Ar << Material << First << Triangles << Start << Count;
            Indices(Ar, Section.BoneMap);
        }
        for (uint32 Vertex = 0; Vertex < Vertices; ++Vertex)
        {
            uint32 Offset = 0, Count = 0;
            Skin->GetVertexInfluenceOffsetCount(Vertex, Offset, Count);
            Ar << Count;
            for (uint32 Influence = 0; Influence < Count; ++Influence)
            {
                uint32 Bone = Skin->GetBoneIndex(Vertex, Influence);
                uint16 Weight = Skin->GetBoneWeight(Vertex, Influence);
                Ar << Bone << Weight;
            }
        }
        Sampler(Ar, Sampling ? &Sampling->AreaWeightedTriangleSampler : nullptr);
        return !Ar.IsError();
    }

    bool WriteMesh(USkeletalMesh& Mesh, const FString& Output)
    {
        const auto* Render = Mesh.GetResourceForRendering();
        if (!Render || Render->LODRenderData.Num() == 0) return false;
        const auto& Skeleton = Mesh.GetRefSkeleton();
        auto Sockets = Mesh.GetActiveSocketList();
        Sockets.RemoveAll([](const USkeletalMeshSocket* Socket) { return Socket == nullptr; });
        const auto& Sampling = Mesh.GetSamplingInfo();
        const auto& Built = Sampling.GetBuiltData();
        if (Sampling.Regions.Num() != Built.RegionBuiltData.Num()) return false;
        FBufferArchive Ar;
        uint32 Magic = 0x4b53464c, Version = 6, Lods = Render->LODRenderData.Num();
        uint32 Bones = Skeleton.GetNum(), SocketCount = Sockets.Num(), RegionCount = Sampling.Regions.Num();
        uint32 InlineLods = Render->NumInlinedLODs, RawBones = Skeleton.GetRawBoneNum();
        if (Mesh.GetRefBasesInvMatrix().Num() != RawBones) Mesh.CalculateInvRefMatrices();
        const auto& Inverse = Mesh.GetRefBasesInvMatrix();
        if (Inverse.Num() != RawBones || Skeleton.GetRefBonePose().Num() != Bones) return false;
        Ar << Magic << Version << Lods << Bones << SocketCount << RegionCount << InlineLods << RawBones;
        const auto Bounds = Mesh.GetBounds();
        double X = Bounds.Origin.X, Y = Bounds.Origin.Y, Z = Bounds.Origin.Z;
        double Ex = Bounds.BoxExtent.X, Ey = Bounds.BoxExtent.Y, Ez = Bounds.BoxExtent.Z, Radius = Bounds.SphereRadius;
        Ar << X << Y << Z << Ex << Ey << Ez << Radius;
        TArray<FTransform> ComponentReference;
        ComponentReference.SetNum(Bones);
        for (uint32 Index = 0; Index < Bones; ++Index)
        {
            int32 Parent = Skeleton.GetParentIndex(Index);
            if (Parent >= static_cast<int32>(Index)) return false;
            Ar << Parent;
            Text(Ar, Skeleton.GetBoneName(Index).ToString());
            const auto& Local = Skeleton.GetRefBonePose()[Index];
            ComponentReference[Index] = Parent >= 0 ? Local * ComponentReference[Parent] : Local;
            Pose(Ar, Local);
            Pose(Ar, ComponentReference[Index]);
            if (Index < RawBones)
                for (int32 Row = 0; Row < 4; ++Row) for (int32 Column = 0; Column < 4; ++Column)
                {
                    float Value = Inverse[Index].M[Row][Column];
                    Ar << Value;
                }
        }
        for (const auto* Socket : Sockets)
        {
            Text(Ar, Socket->SocketName.ToString());
            Text(Ar, Socket->BoneName.ToString());
            Pose(Ar, FTransform(Socket->RelativeRotation, Socket->RelativeLocation, Socket->RelativeScale));
        }
        const uint32 FirstInlineLOD = Lods - InlineLods;
        for (uint32 Index = 0; Index < Lods; ++Index)
        {
            const FSkeletalMeshLODInfo* Info = Mesh.GetLODInfo(Index);
            const bool bSupportUniformlyDistributedSampling = Info && Info->bSupportUniformlyDistributedSampling;
            const auto& Lod = Render->LODRenderData[Index];
            if (Index < FirstInlineLOD)
            {
                uint32 Storage = 1, Vertices = 0, Flags = 0;
                FBufferArchive Payload;
                if (!LodData(Payload, Lod, Built.WholeMeshBuiltData.IsValidIndex(Index)
                        ? &Built.WholeMeshBuiltData[Index] : nullptr, Info && Info->bAllowCPUAccess,
                        bSupportUniformlyDistributedSampling,
                        &Vertices, &Flags)) return false;
                Ar << Storage << Vertices << Flags;
                FString Sidecar = Output + FString::Printf(TEXT(".lod%u.lfsl"), Index);
                if (!FFileHelper::SaveArrayToFile(Payload, *Sidecar)) return false;
            }
            else
            {
                uint32 Storage = 0;
                Ar << Storage;
                if (!LodData(Ar, Lod, Built.WholeMeshBuiltData.IsValidIndex(Index)
                        ? &Built.WholeMeshBuiltData[Index] : nullptr, Info && Info->bAllowCPUAccess,
                        bSupportUniformlyDistributedSampling)) return false;
            }
        }
        for (uint32 Index = 0; Index < RegionCount; ++Index)
        {
            const auto& Region = Sampling.GetRegion(Index);
            const auto& Data = Sampling.GetRegionBuiltData(Index);
            Text(Ar, Region.Name.ToString());
            int32 Lod = Region.LODIndex;
            uint32 Flags = Region.bSupportUniformlyDistributedSampling ? 1 : 0;
            Ar << Lod << Flags;
            Indices(Ar, Data.TriangleIndices);
            Indices(Ar, Data.Vertices);
            Indices(Ar, Data.BoneIndices);
            Sampler(Ar, &Data.AreaWeightedSampler);
        }
        return !Ar.IsError() && FFileHelper::SaveArrayToFile(Ar, *Output);
    }
}

bool ExportNativeSkeletalMesh(const FString& Input, const FString& Output)
{
    FString Json, Source;
    TSharedPtr<FJsonObject> Request;
    if (!FFileHelper::LoadFileToString(Json, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Request)
        || !Request.IsValid() || !Request->TryGetStringField(TEXT("mesh"), Source)) return false;
    const TSharedPtr<FJsonObject>* Roots = nullptr;
    if (!Request->TryGetObjectField(TEXT("mounts"), Roots)) return false;
    TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
    for (const auto& Root : (*Roots)->Values)
    {
        const FString RootName(*Root.Key);
        FString Directory;
        if (!RootName.StartsWith(TEXT("/")) || !RootName.EndsWith(TEXT("/")) || !Root.Value->TryGetString(Directory)
            || FPaths::IsRelative(Directory) || !FPaths::DirectoryExists(Directory)) return false;
        FPaths::NormalizeDirectoryName(Directory);
        Mounts.Add(FPackageName::RegisterMountPoint(RootName, Directory + TEXT("/")));
    }
    USkeletalMesh* Mesh = LoadObject<USkeletalMesh>(nullptr, *Source);
    return Mesh && WriteMesh(*Mesh, Output);
}
