#include "MeshDescriptionExchange.h"
#include "MeshDistanceFieldExchange.h"
#include "MeshLodCookPolicy.h"
#include "MeshRenderVertexExchange.h"
#include "Nanite/NaniteRenderExchange.h"
#include "Containers/StringConv.h"
#include "HAL/IConsoleManager.h"
#include "IMeshBuilderModule.h"
#include "Interfaces/ITargetPlatform.h"
#include "Interfaces/ITargetPlatformManagerModule.h"
#include "JsonObjectConverter.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "RenderUtils.h"
#include "RHIShaderFormatDefinitions.inl"
#include "Serialization/BufferArchive.h"
#include "Serialization/MemoryReader.h"
#include "StaticMeshResources.h"
#include "UObject/StrongObjectPtr.h"
#include "UObject/UObjectGlobals.h"

namespace
{
    bool DecodeDescription(const FSourceMeshRequest& Request, const FSourceMeshLod& Source, FMeshDescription& Mesh)
    {
        FMemoryReader Ar(Source.Archive, true);
        Ar.SetUEVer(Request.Version);
        Ar.SetLicenseeUEVer(Request.LicenseeVersion);
        Ar.SetCustomVersions(Request.CustomVersions);
        Ar << Mesh;
        return !Ar.IsError() && Ar.Tell() == Ar.TotalSize();
    }

    void WriteShaderAccess(FArchive& Ar, const TArray<FName>& Formats, bool bEditor, bool bHasNanite,
                           bool bForceFallback, bool bCookedFallback)
    {
        uint32 Count = Formats.Num();
        Ar << Count;
        for (FName Format : Formats)
        {
            const EShaderPlatform ShaderPlatform = ShaderFormatToLegacyShaderPlatform(Format);
            FTCHARToUTF8 Name(*Format.ToString());
            uint32 NameBytes = Name.Length();
            Ar << NameBytes;
            Ar.Serialize(const_cast<void*>(static_cast<const void*>(Name.Get())), NameBytes);
            const bool bFallback = !bHasNanite || (bEditor
                ? bForceFallback || AreNaniteFallbackMeshesEnabledForPlatform(ShaderPlatform) : bCookedFallback);
            uint32 Flags = (AreBufferSRVsAlwaysCreatedByDefault(ShaderPlatform) ? 1u : 0u) | (bFallback ? 2u : 0u);
            Ar << Flags;
        }
    }

    bool WriteLodBufferPolicy(FArchive& Ar, const FStaticMeshLODResources& Lod, const TArray<FName>& Formats)
    {
        const auto& Positions = Lod.VertexBuffers.PositionVertexBuffer;
        const auto& Vertices = Lod.VertexBuffers.StaticMeshVertexBuffer;
        const auto& Colors = Lod.VertexBuffers.ColorVertexBuffer;
        const uint32 VertexCount = Positions.GetNumVertices();
        const uint32 IndexCount = Lod.IndexBuffer.GetNumIndices();
        const uint32 UvCount = Vertices.GetNumTexCoords();
        const uint32 ColorCount = Colors.GetNumVertices();
        if (VertexCount != Vertices.GetNumVertices() || (ColorCount != 0 && ColorCount != VertexCount)) return false;

        const bool CpuIndices = IndexCount != 0 && Lod.IndexBuffer.GetAllowCPUAccess();
        const bool CpuPositions = VertexCount != 0 && Positions.GetAllowCPUAccess();
        const bool CpuTangents = VertexCount != 0 && Vertices.GetAllowCPUAccess();
        const bool CpuUvs = UvCount != 0 && Vertices.GetAllowCPUAccess();
        const bool CpuColors = ColorCount != 0 && Colors.GetAllowCPUAccess();
        uint32 CpuFlags = (CpuIndices ? 1u : 0u) | (CpuPositions ? 2u : 0u) | (CpuTangents ? 4u : 0u)
            | (CpuUvs ? 8u : 0u) | (CpuColors ? 16u : 0u);
        Ar << CpuFlags;

        uint32 FormatCount = Formats.Num();
        Ar << FormatCount;
        for (FName Format : Formats)
        {
            const EShaderPlatform ShaderPlatform = ShaderFormatToLegacyShaderPlatform(Format);
            FTCHARToUTF8 Name(*Format.ToString());
            uint32 NameBytes = Name.Length();
            Ar << NameBytes;
            Ar.Serialize(const_cast<void*>(static_cast<const void*>(Name.Get())), NameBytes);

            const bool DefaultViews = AreBufferSRVsAlwaysCreatedByDefault(ShaderPlatform);
            const bool IndexSrv = IndexCount != 0 && (CpuIndices || DefaultViews);
            const bool PositionSrv = VertexCount != 0;
            const bool TangentSrv = VertexCount != 0 && (CpuTangents || DefaultViews);
            const bool UvSrv = UvCount != 0 && (CpuUvs || DefaultViews);
            const bool ColorSrv = ColorCount != 0 && RHISupportsManualVertexFetch(ShaderPlatform);
            uint32 GpuFlags = (IndexSrv ? 1u : 0u) | (PositionSrv ? 2u : 0u) | (TangentSrv ? 4u : 0u)
                | (UvSrv ? 8u : 0u) | (ColorSrv ? 16u : 0u);
            Ar << GpuFlags;
        }
        return !Ar.IsError();
    }

    bool WriteRenderData(const FString& Path, FStaticMeshRenderData& Mesh, UStaticMesh& Owner, const ITargetPlatform& Platform,
                         const FSourceMeshRequest& Request,
                         const FStaticMeshLODGroup& Group, TArray<uint8>& DistanceField, uint32 RenderFlags,
                         FMeshLodCookPolicy LodPolicy, const TArray<FName>& Formats, bool bEditor,
                         bool bHasNanite, bool bForceFallback, bool bCookedFallback)
    {
        FBufferArchive Ar;
        uint32 Magic = 0x524d4e4c, Version = 8, LodCount = Mesh.LODResources.Num();
        Ar << Magic << Version << LodCount;
        Ar << RenderFlags << LodPolicy.FirstInline << LodPolicy.FirstAvailable << LodPolicy.FirstNonOptional;
        WriteShaderAccess(Ar, Formats, bEditor, bHasNanite, bForceFallback, bCookedFallback);
        double X = Mesh.Bounds.Origin.X, Y = Mesh.Bounds.Origin.Y, Z = Mesh.Bounds.Origin.Z;
        double Ex = Mesh.Bounds.BoxExtent.X, Ey = Mesh.Bounds.BoxExtent.Y, Ez = Mesh.Bounds.BoxExtent.Z, Radius = Mesh.Bounds.SphereRadius;
        Ar << X << Y << Z << Ex << Ey << Ez << Radius;
        for (int32 LodIndex = 0; LodIndex < Mesh.LODResources.Num(); ++LodIndex)
        {
            const FStaticMeshLODResources& Lod = Mesh.LODResources[LodIndex];
            const auto& Positions = Lod.VertexBuffers.PositionVertexBuffer;
            const auto& Vertices = Lod.VertexBuffers.StaticMeshVertexBuffer;
            TArray<uint32> Indices;
            Lod.IndexBuffer.GetCopy(Indices);
            uint32 VertexCount = Positions.GetNumVertices(), IndexCount = Indices.Num();
            uint32 UvCount = Vertices.GetNumTexCoords(), SectionCount = Lod.Sections.Num();
            float Deviation = Lod.MaxDeviation;
            float PixelError = Group.GetSettings(Request.Lods[LodIndex].Reduction, LodIndex).PixelError;
            uint32 HasVertexColors = Lod.VertexBuffers.ColorVertexBuffer.GetNumVertices() != 0 ? 1u : 0u;
            Ar << Deviation << PixelError << VertexCount << IndexCount << UvCount << SectionCount << HasVertexColors;
            if (!WriteRenderVertexChannels(Ar, Lod.VertexBuffers)) return false;
            for (uint32 Index : Indices) Ar << Index;
            for (const FStaticMeshSection& Section : Lod.Sections)
            {
                int32 Material = Section.MaterialIndex;
                uint32 First = Section.FirstIndex, Count = Section.NumTriangles * 3;
                uint32 Flags = (Section.bEnableCollision ? 1u : 0u) | (Section.bCastShadow ? 2u : 0u)
                    | (Section.bVisibleInRayTracing ? 4u : 0u) | (Section.bAffectDistanceFieldLighting ? 8u : 0u)
                    | (Section.bForceOpaque ? 16u : 0u);
                Ar << Material << First << Count << Flags;
            }
            if (!WriteLodBufferPolicy(Ar, Lod, Formats)) return false;
        }
        uint32 DistanceFieldBytes = DistanceField.Num();
        Ar << DistanceFieldBytes;
        if (DistanceFieldBytes != 0) Ar.Serialize(DistanceField.GetData(), DistanceFieldBytes);
        auto* Nanite = bHasNanite && (bEditor || DoesTargetPlatformSupportNanite(&Platform)) ? Mesh.NaniteResourcesPtr.Get() : nullptr;
        if (!WriteNaniteRenderExchange(Ar, Nanite, Owner, Platform)) return false;
        return !Ar.IsError() && FFileHelper::SaveArrayToFile(Ar, *Path);
    }
}

bool BuildNativeRenderMesh(const FString& Output, const FSourceMeshRequest& Request)
{
    if (Request.Lods.IsEmpty() || !Request.AssetSettings.IsValid()) return false;
    FString TargetName;
    ITargetPlatform* Platform = Request.AssetSettings->TryGetStringField(TEXT("LumenFxMeshTargetPlatform"), TargetName)
        ? GetTargetPlatformManagerRef().FindTargetPlatform(*TargetName) : GetTargetPlatformManagerRef().GetRunningTargetPlatform();
    if (!Platform)
    {
        UE_LOG(LogTemp, Error, TEXT("Static mesh target platform could not be resolved: %s"), *TargetName);
        return false;
    }
    TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
    for (const auto& Root : Request.ContentRoots)
    {
        if (!Root.Key.StartsWith(TEXT("/")) || !Root.Key.EndsWith(TEXT("/")) || FPaths::IsRelative(Root.Value)
            || !FPaths::DirectoryExists(Root.Value)) return false;
        FString Directory = Root.Value;
        FPaths::NormalizeDirectoryName(Directory);
        Mounts.Add(FPackageName::RegisterMountPoint(Root.Key, Directory + TEXT("/")));
    }
    TArray<TStrongObjectPtr<UObject>> References;
    for (const FString& Path : Request.ObjectPaths)
    {
        FText PathReason;
        if (!FPackageName::IsValidObjectPath(Path, &PathReason))
        {
            UE_LOG(LogTemp, Error, TEXT("Static mesh build reference is invalid: %s (%s)"), *Path, *PathReason.ToString());
            return false;
        }
        UObject* Object = LoadObject<UObject>(nullptr, *Path);
        if (!Object)
        {
            UE_LOG(LogTemp, Error, TEXT("Static mesh build reference could not be loaded: %s"), *Path);
            return false;
        }
        References.Emplace(Object);
    }
    TStrongObjectPtr<UStaticMesh> Mesh(NewObject<UStaticMesh>(GetTransientPackage(), NAME_None, RF_Transient));
    TSharedRef<FJsonObject> Settings = MakeShared<FJsonObject>();
    for (const TCHAR* Name : { TEXT("NaniteSettings"), TEXT("LightmapUVVersion"), TEXT("bUseLegacyTangentScaling"),
                               TEXT("bSupportRayTracing"), TEXT("bAllowCPUAccess") })
        if (const TSharedPtr<FJsonValue>* Value = Request.AssetSettings->Values.Find(Name)) Settings->SetField(Name, *Value);
    Settings->SetNumberField(TEXT("LightmapUVVersion"), Request.LightmapVersion);
    FText Reason;
    // Serialized UPROPERTY values include private build settings; descriptions and section maps have explicit APIs.
    if (!FJsonObjectConverter::JsonObjectToUStruct(Settings, UStaticMesh::StaticClass(), Mesh.Get(), 0, 0, false, &Reason))
    {
        UE_LOG(LogTemp, Error, TEXT("Static mesh build properties could not be read: %s"), *Reason.ToString());
        return false;
    }
    Mesh->SetNumSourceModels(Request.Lods.Num());
    UStaticMesh::FCommitMeshDescriptionParams Commit;
    Commit.bMarkPackageDirty = false;
    for (int32 Index = 0; Index < Request.Lods.Num(); ++Index)
    {
        const FSourceMeshLod& Source = Request.Lods[Index];
        auto& Model = Mesh->GetSourceModel(Index);
        Model.BuildSettings = Source.Build; Model.ReductionSettings = Source.Reduction;
        if (!Source.Archive.IsEmpty())
        {
            FMeshDescription Description;
            if (!DecodeDescription(Request, Source, Description)) return false;
            Mesh->CreateMeshDescription(Index, MoveTemp(Description));
            Mesh->CommitMeshDescription(Index, Commit);
        }
    }
    if (!Request.HiRes.Archive.IsEmpty())
    {
        FMeshDescription Description;
        if (!DecodeDescription(Request, Request.HiRes, Description)) return false;
        Mesh->GetHiResSourceModel().BuildSettings = Request.HiRes.Build;
        Mesh->GetHiResSourceModel().ReductionSettings = Request.HiRes.Reduction;
        Mesh->CreateHiResMeshDescription(MoveTemp(Description));
        Mesh->CommitHiResMeshDescription(Commit);
    }
    for (FName Name : Request.ImportedMaterialSlots) Mesh->GetStaticMaterials().Emplace(nullptr, Name, Name);
    Mesh->GetSectionInfoMap() = Request.SectionInfo;
    Mesh->GetOriginalSectionInfoMap() = Request.OriginalSectionInfo;
    auto& Builder = IMeshBuilderModule::GetForPlatform(Platform);
    IConsoleVariable* Ordering = IConsoleManager::Get().FindConsoleVariable(TEXT("r.TriangleOrderOptimization"));
    if (!Ordering) return false;
    Ordering->Set(Request.TriangleOrderMode, ECVF_SetByCode);
    const auto& Group = Platform->GetStaticMeshLODSettings().GetLODGroup(FName(*Request.LodGroup));
    FStaticMeshRenderData RenderData;
    if (!Builder.BuildMesh(RenderData, FStaticMeshBuildParameters(Mesh.Get(), Platform, Group))) return false;
    const bool bHasNanite = RenderData.HasValidNaniteData();
    const bool bEditor = Platform->HasEditorOnlyData();
    const bool bForceFallback = Mesh->GetNaniteSettings().GenerateFallback == ENaniteGenerateFallback::Enabled;
    const bool bCookedFallback = !bHasNanite || bForceFallback || !Platform->ShouldStripNaniteFallbackMeshes();
    const bool bFallbackStripped = bHasNanite
        && DoesTargetPlatformSupportNanite(Platform) && Platform->ShouldStripNaniteFallbackMeshes()
        && !bForceFallback && !Mesh->bAllowCPUAccess && !bEditor;
    TArray<uint8> DistanceField;
    if (!BuildMeshDistanceFieldExchange(RenderData, Request, DistanceField)) return false;
    TArray<FName> Formats;
    Platform->GetAllTargetedShaderFormats(Formats);
    const bool bOmitsDefaultViews = !Formats.IsEmpty() && !Formats.ContainsByPredicate([](FName Format)
    {
        return AreBufferSRVsAlwaysCreatedByDefault(ShaderFormatToLegacyShaderPlatform(Format));
    });
    uint32 RenderFlags = (bHasNanite ? 1u : 0u) | (bFallbackStripped ? 2u : 0u)
        | (bOmitsDefaultViews ? 4u : 0u) | (!bEditor && !bCookedFallback ? 8u : 0u);
    FMeshLodCookPolicy LodPolicy;
    if (!ReadMeshLodCookPolicy(*Request.AssetSettings, *Mesh.Get(), *Platform, Group, RenderData.LODResources.Num(), LodPolicy))
    {
        UE_LOG(LogTemp, Error, TEXT("Invalid static mesh LOD streaming settings"));
        return false;
    }
    return WriteRenderData(Output, RenderData, *Mesh.Get(), *Platform, Request, Group, DistanceField, RenderFlags, LodPolicy,
                           Formats, bEditor, bHasNanite, bForceFallback, bCookedFallback);
}
