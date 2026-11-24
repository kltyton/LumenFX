#include "MeshDistanceFieldExchange.h"
#include "MeshDescriptionExchange.h"
#include "DistanceFieldAtlas.h"
#include "Materials/MaterialInterface.h"
#include "MeshUtilities.h"
#include "Modules/ModuleManager.h"
#include "Rendering/NaniteResources.h"
#include "Serialization/BufferArchive.h"
#include "StaticMeshResources.h"
#include "UObject/UObjectGlobals.h"

namespace
{
    bool WriteField(FDistanceFieldVolumeData& Field, TArray<uint8>& Output)
    {
        if (!Field.IsValid()) return true;
        FBufferArchive Ar;
        uint32 Magic = 0x4644464c, Version = 1, MipCount = DistanceField::NumMips;
        uint32 Flags = Field.bMostlyTwoSided ? 1 : 0;
        Ar << Magic << Version << Flags << MipCount;
        auto Min = Field.LocalSpaceMeshBounds.Min, Max = Field.LocalSpaceMeshBounds.Max;
        Ar << Min.X << Min.Y << Min.Z << Max.X << Max.Y << Max.Z;

        const int64 StreamSize = Field.StreamableMips.GetBulkDataSize();
        const uint8* Stream = StreamSize != 0 ? static_cast<const uint8*>(Field.StreamableMips.LockReadOnly()) : nullptr;
        bool Valid = StreamSize == 0 || Stream != nullptr;
        for (uint32 Index = 0; Valid && Index < MipCount; ++Index)
        {
            auto Mip = Field.Mips[Index];
            const bool Inline = Index + 1 == MipCount;
            uint32 Bytes = Inline ? static_cast<uint32>(Field.AlwaysLoadedMip.Num()) : Mip.BulkSize;
            if (Bytes == 0 || (!Inline && static_cast<int64>(Mip.BulkOffset) + Bytes > StreamSize))
            {
                Valid = false;
                break;
            }
            const uint8* Payload = Inline ? Field.AlwaysLoadedMip.GetData() : Stream + Mip.BulkOffset;
            Ar << Mip.IndirectionDimensions.X << Mip.IndirectionDimensions.Y << Mip.IndirectionDimensions.Z
               << Mip.NumDistanceFieldBricks
               << Mip.VolumeToVirtualUVScale.X << Mip.VolumeToVirtualUVScale.Y << Mip.VolumeToVirtualUVScale.Z
               << Mip.VolumeToVirtualUVAdd.X << Mip.VolumeToVirtualUVAdd.Y << Mip.VolumeToVirtualUVAdd.Z
               << Mip.DistanceFieldToVolumeScaleBias.X << Mip.DistanceFieldToVolumeScaleBias.Y << Bytes;
            Ar.Serialize(const_cast<uint8*>(Payload), Bytes);
        }
        if (StreamSize != 0) Field.StreamableMips.Unlock();
        if (!Valid || Ar.IsError()) return false;
        Output = MoveTemp(static_cast<TArray<uint8>&>(Ar));
        return true;
    }
}

bool BuildMeshDistanceFieldExchange(const FStaticMeshRenderData& RenderData, const FSourceMeshRequest& Request, TArray<uint8>& Output)
{
    bool Generate = false;
    if (!Request.AssetSettings->TryGetBoolField(TEXT("bGenerateMeshDistanceField"), Generate) || !Generate) return true;

    const auto& Build = Request.Lods[0].Build;
    if (Build.DistanceFieldResolutionScale <= 0.0f) return true;
    const UStaticMesh* Replacement = Build.DistanceFieldReplacementMesh;
    FString ReplacementPath;
    if (Request.AssetSettings->TryGetStringField(TEXT("LumenFxDistanceFieldReplacementMesh"), ReplacementPath))
    {
        Replacement = LoadObject<UStaticMesh>(nullptr, *ReplacementPath);
        if (!Replacement) return false;
    }
    const FStaticMeshRenderData* Source = Replacement ? Replacement->GetRenderData() : &RenderData;
    if (!Source || Source->LODResources.Num() == 0) return false;

    TArray<UMaterialInterface*> Materials;
    if (Replacement)
    {
        for (const auto& Material : Replacement->GetStaticMaterials()) Materials.Add(Material.MaterialInterface);
    }
    else
    {
        TArray<FString> Paths;
        if (!Request.AssetSettings->TryGetStringArrayField(TEXT("LumenFxDistanceFieldMaterials"), Paths)
            || Paths.Num() != Request.ImportedMaterialSlots.Num()) return false;
        for (const FString& Path : Paths)
        {
            if (Path.IsEmpty() || Path == TEXT("None")) Materials.Add(nullptr);
            else
            {
                UMaterialInterface* Material = LoadObject<UMaterialInterface>(nullptr, *Path);
                if (!Material) return false;
                Materials.Add(Material);
            }
        }
    }

    const auto& Lod = Source->LODResources[0];
    FMeshDataForDerivedDataTask Mesh;
    Mesh.SourceMeshData = nullptr;
    Mesh.LODModel = &Lod;
    Mesh.Bounds = FBoxSphereBounds3f(Source->Bounds);
    TArray<FSignedDistanceFieldBuildSectionData> Sections;
    for (const auto& Section : Lod.Sections)
    {
        auto& Data = Sections.AddDefaulted_GetRef();
        Data.bAffectDistanceFieldLighting = Section.bAffectDistanceFieldLighting;
        const int32 Slot = Section.MaterialIndex;
        if (Materials.IsValidIndex(Slot) && Materials[Slot])
        {
            Data.BlendMode = Materials[Slot]->GetBlendMode();
            Data.bTwoSided = Materials[Slot]->IsTwoSided();
        }
        if (Source->NaniteResourcesPtr.IsValid() && Slot >= 0 && Slot < 64)
            Data.bTwoSided |= (Source->NaniteResourcesPtr->VoxelMaterialsMask & (1ull << Slot)) != 0;
    }
    Mesh.SectionData = Sections;
    FString Name = TEXT("LumenFX static mesh");
    Request.AssetSettings->TryGetStringField(TEXT("LumenFxDistanceFieldMeshName"), Name);
    auto& Utilities = FModuleManager::LoadModuleChecked<IMeshUtilities>(TEXT("MeshUtilities"));
    FDistanceFieldVolumeData Field;
    Utilities.GenerateSignedDistanceFieldVolumeData(Name, Mesh, Build.DistanceFieldResolutionScale,
        Build.bGenerateDistanceFieldAsIfTwoSided, Field);
    return WriteField(Field, Output);
}
