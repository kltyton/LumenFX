#include "MeshDescriptionExchange.h"
#include "IMeshReductionInterfaces.h"
#include "IMeshReductionManagerModule.h"
#include "Interfaces/ITargetPlatform.h"
#include "Interfaces/ITargetPlatformManagerModule.h"
#include "MeshUtilitiesCommon.h"
#include "Modules/ModuleManager.h"
#include "OverlappingCorners.h"
#include "Serialization/MemoryReader.h"
#include "StaticMeshAttributes.h"
#include "StaticMeshOperations.h"
#include "StaticMeshResources.h"

namespace
{
    TArray<int32> MaterialIndices(const FMeshDescription& Mesh, const TArray<FName>& Slots)
    {
        auto Names = Mesh.PolygonGroupAttributes().GetAttributesRef<FName>(MeshAttribute::PolygonGroup::ImportedMaterialSlotName);
        TArray<int32> Result;
        for (FPolygonGroupID Group : Mesh.PolygonGroups().GetElementIDs())
        {
            int32 Index = Slots.IndexOfByKey(Names[Group]);
            Result.AddUnique(Index == INDEX_NONE ? Group.GetValue() : Index);
        }
        return Result;
    }

    void PrepareMesh(FMeshDescription& Mesh, FMeshBuildSettings& Settings, int32 LightmapVersion)
    {
        if (Mesh.NeedsCompact())
        {
            FElementIDRemappings Remappings;
            Mesh.Compact(Remappings);
        }
        FStaticMeshOperations::ValidateAndFixData(Mesh, TEXT("Source mesh"));
        float Threshold = Settings.bRemoveDegenerates ? THRESH_POINTS_ARE_SAME : 0;
        FStaticMeshOperations::ComputeTriangleTangentsAndNormals(Mesh, Threshold, TEXT("Source mesh"));
        FOverlappingCorners Overlapping;
        FStaticMeshOperations::FindOverlappingCorners(Overlapping, Mesh, Threshold);
        EComputeNTBsFlags Options = EComputeNTBsFlags::BlendOverlappingNormals;
        if (Settings.bComputeWeightedNormals) Options |= EComputeNTBsFlags::WeightedNTBs;
        if (Settings.bRecomputeNormals) Options |= EComputeNTBsFlags::Normals;
        if (Settings.bRecomputeTangents) Options |= EComputeNTBsFlags::Tangents;
        if (Settings.bUseMikkTSpace) Options |= EComputeNTBsFlags::UseMikkTSpace;
        if (Settings.bRemoveDegenerates) Options |= EComputeNTBsFlags::IgnoreDegenerateTriangles;
        FStaticMeshOperations::ComputeTangentsAndNormals(Mesh, Options);
        if (Settings.bGenerateLightmapUVs && Mesh.VertexInstances().Num() > 0)
        {
            auto Uvs = FStaticMeshAttributes(Mesh).GetVertexInstanceUVs();
            int32 PreviousChannels = Uvs.GetNumChannels();
            if (Settings.SrcLightmapIndex >= PreviousChannels) Settings.SrcLightmapIndex = 0;
            if (Settings.DstLightmapIndex >= PreviousChannels)
            {
                Settings.DstLightmapIndex = FMath::Min(Settings.DstLightmapIndex, MAX_MESH_TEXTURE_COORDS_MD - 1);
                Uvs.SetNumChannels(Settings.DstLightmapIndex + 1);
                Settings.DstLightmapIndex = PreviousChannels;
            }
            FStaticMeshOperations::CreateLightMapUVLayout(Mesh, Settings.SrcLightmapIndex, Settings.DstLightmapIndex,
                Settings.MinLightmapResolution, static_cast<ELightmapUVVersion>(LightmapVersion), Overlapping);
        }
    }
}

bool BuildMeshDescriptions(FSourceMeshRequest& Request, TArray<FPreparedMeshLod>& Lods)
{
    auto& Manager = FModuleManager::LoadModuleChecked<IMeshReductionManagerModule>(TEXT("MeshReductionInterface"));
    IMeshReduction* Reduction = Manager.GetStaticMeshReductionInterface();
    ITargetPlatform* Platform = GetTargetPlatformManagerRef().GetRunningTargetPlatform();
    if (!Reduction || !Platform) return false;
    const FStaticMeshLODGroup& Group = Platform->GetStaticMeshLODSettings().GetLODGroup(FName(*Request.LodGroup));
    const FMeshSectionInfoMap BeforeSections = Request.SectionInfo, BeforeOriginal = Request.OriginalSectionInfo;
    Lods.SetNum(Request.Lods.Num());
    for (int32 Index = 0; Index < Lods.Num(); ++Index)
    {
        FSourceMeshLod& Source = Request.Lods[Index];
        FPreparedMeshLod& Lod = Lods[Index];
        FOverlappingCorners Overlapping;
        if (Source.Archive.Num() > 0)
        {
            FMemoryReader Ar(Source.Archive, true);
            Ar.SetUEVer(Request.Version);
            Ar.SetLicenseeUEVer(Request.LicenseeVersion);
            Ar.SetCustomVersions(Request.CustomVersions);
            Ar << Lod.Description;
            if (Ar.IsError() || Ar.Tell() != Ar.TotalSize()) return false;
            Lod.HadSource = !Lod.Description.IsEmpty();
        }
        if (Index == 0 && !Lod.HadSource) return false;
        FMeshBuildSettings Build = Source.Build;
        FMeshReductionSettings Settings = Group.GetSettings(Source.Reduction, Index);
        Lod.PixelError = Settings.PixelError;
        Lod.BaseLod = FMath::Clamp(Settings.BaseLODModel, 0, Lod.HadSource ? Index : Index - 1);
        uint32 Vertices = MAX_uint32, Triangles = MAX_uint32;
        if (Lod.HadSource)
        {
            PrepareMesh(Lod.Description, Build, Request.LightmapVersion);
            FStaticMeshOperations::FindOverlappingCorners(Overlapping, Lod.Description,
                Build.bRemoveDegenerates ? THRESH_POINTS_ARE_SAME : 0);
            Vertices = FStaticMeshOperations::GetUniqueVertexCount(Lod.Description, Overlapping);
            Triangles = Lod.Description.Triangles().Num();
        }
        Lod.Reduced = Reduction->IsReductionActive(Settings, Vertices, Triangles);
        if (!Lod.HadSource)
        {
            if (Lod.Reduced) FStaticMeshAttributes(Lod.Description).Register();
            else
            {
                Lod.Description = Lods[Lod.BaseLod].Description;
                int32 Count = Request.OriginalSectionInfo.GetSectionNumber(Lod.BaseLod);
                for (int32 Section = 0; Section < Count; ++Section)
                    if (!Request.SectionInfo.IsValidSection(Index, Section) && Request.SectionInfo.IsValidSection(Lod.BaseLod, Section))
                    {
                        Request.SectionInfo.Set(Index, Section, Request.SectionInfo.Get(Lod.BaseLod, Section));
                        Request.OriginalSectionInfo.Set(Index, Section, Request.OriginalSectionInfo.Get(Lod.BaseLod, Section));
                    }
            }
            Build = Request.Lods[Lod.BaseLod].Build;
        }
        if (Lod.Reduced)
        {
            int32 OldCount = Request.SectionInfo.GetSectionNumber(Index);
            const FMeshDescription Base = Lods[Lod.BaseLod].Description;
            FStaticMeshOperations::FindOverlappingCorners(Overlapping, Base, Build.bRemoveDegenerates ? THRESH_POINTS_ARE_SAME : 0);
            Lod.MaximumDeviation = Settings.MaxDeviation;
            Reduction->ReduceMeshDescription(Lod.Description, Lod.MaximumDeviation, Base, Overlapping, Settings);
            TArray<int32> BaseMaterials = MaterialIndices(Lods[Lod.BaseLod].Description, Request.ImportedMaterialSlots);
            TArray<int32> Materials = MaterialIndices(Lod.Description, Request.ImportedMaterialSlots);
            bool OldInvalid = OldCount != Lod.Description.PolygonGroups().Num();
            for (int32 Section = 0; Section < Materials.Num(); ++Section)
            {
                if (!OldInvalid && BeforeSections.IsValidSection(Index, Section)) continue;
                int32 BaseSection = BeforeSections.GetSectionNumber(Lod.BaseLod) > 0 ? BaseMaterials.IndexOfByKey(Materials[Section]) : INDEX_NONE;
                if (BaseSection != INDEX_NONE)
                {
                    Request.SectionInfo.Set(Index, Section, BeforeSections.Get(Lod.BaseLod, BaseSection));
                    Request.OriginalSectionInfo.Set(Index, BaseSection, BeforeOriginal.Get(Lod.BaseLod, BaseSection));
                }
                else
                {
                    Request.SectionInfo.Set(Index, Section, FMeshSectionInfo(Section));
                    Request.OriginalSectionInfo.Set(Index, Section, FMeshSectionInfo(Section));
                }
            }
        }
    }
    return true;
}
