#include "VolumetricLightmapExport.h"

#include "Dom/JsonObject.h"
#include "Engine/MapBuildDataRegistry.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "PrecomputedVolumetricLightmap.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"

DEFINE_LOG_CATEGORY_STATIC(LogVolumetricLightmapExport, Log, All);

namespace
{
    bool HasVolume(FIntVector Extent)
    {
        return Extent.X > 0 && Extent.Y > 0 && Extent.Z > 0;
    }

    bool WriteLayer(FBufferArchive& Archive, const FVolumetricLightmapDataLayer& Layer,
        FIntVector Dimensions, bool bOptional = false)
    {
        int32 Encoding = 0;
        int32 Bytes = 4;
        if (Layer.Data.IsEmpty() && bOptional)
        {
            Archive << Encoding;
            return true;
        }
        switch (Layer.Format)
        {
        case PF_R8G8B8A8_UINT:
        case PF_R8G8B8A8: Encoding = 1; break;
        case PF_B8G8R8A8: Encoding = 2; break;
        case PF_FloatR11G11B10: Encoding = 3; break;
        case PF_G8: Encoding = 4; Bytes = 1; break;
        default: return false;
        }
        const int64 Size = int64(Dimensions.X) * Dimensions.Y * Dimensions.Z * Bytes;
        if (Size <= 0 || Size != Layer.Data.Num()) return false;
        Archive << Encoding;
        Archive.Serialize(const_cast<uint8*>(Layer.Data.GetData()), Size);
        return !Archive.IsError();
    }
}

bool ExportVolumetricLightmap(const FString& Input)
{
    FString Text, RegistryPath, LevelGuid, Output;
    TSharedPtr<FJsonObject> Request;
    if (!FFileHelper::LoadFileToString(Text, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Request) || !Request.IsValid()
        || !Request->TryGetStringField(TEXT("registry"), RegistryPath) || !FPackageName::IsValidObjectPath(RegistryPath)
        || !Request->TryGetStringField(TEXT("level_guid"), LevelGuid)
        || !Request->TryGetStringField(TEXT("output"), Output) || FPaths::IsRelative(Output)
        || !FPaths::CollapseRelativeDirectories(Output)) return false;
    FGuid Id;
    if (!FGuid::Parse(LevelGuid, Id) || !Id.IsValid()) return false;

    const UMapBuildDataRegistry* Registry = LoadObject<UMapBuildDataRegistry>(nullptr, *RegistryPath);
    const auto* Data = Registry ? Registry->GetLevelPrecomputedVolumetricLightmapBuildData(Id) : nullptr;
    if (!Data || !Data->Bounds.IsValid || Data->Bounds.Min.ContainsNaN() || Data->Bounds.Max.ContainsNaN()
        || Data->BrickSize <= 0 || !HasVolume(Data->IndirectionTextureDimensions) || !HasVolume(Data->BrickDataDimensions))
    {
        UE_LOG(LogVolumetricLightmapExport, Error, TEXT("Level %s has no persistent volumetric lightmap in %s"), *LevelGuid, *RegistryPath);
        return false;
    }
    FBufferArchive Archive;
    uint32 Magic = 0x4c56464c, Version = 1;
    int32 BrickSize = Data->BrickSize;
    auto IndirectionSize = Data->IndirectionTextureDimensions;
    auto BrickDimensions = Data->BrickDataDimensions;
    Archive << Magic << Version << BrickSize;
    Archive << IndirectionSize.X << IndirectionSize.Y << IndirectionSize.Z;
    Archive << BrickDimensions.X << BrickDimensions.Y << BrickDimensions.Z;
    for (double Value : {Data->Bounds.Min.X, Data->Bounds.Min.Y, Data->Bounds.Min.Z,
                         Data->Bounds.Max.X, Data->Bounds.Max.Y, Data->Bounds.Max.Z}) Archive << Value;
    if (!WriteLayer(Archive, Data->IndirectionTexture, IndirectionSize)
        || !WriteLayer(Archive, Data->BrickData.AmbientVector, BrickDimensions)) return false;
    for (const auto& Layer : Data->BrickData.SHCoefficients)
        if (!WriteLayer(Archive, Layer, BrickDimensions)) return false;
    return WriteLayer(Archive, Data->BrickData.SkyBentNormal, BrickDimensions, true)
        && WriteLayer(Archive, Data->BrickData.DirectionalLightShadowing, BrickDimensions, true)
        && FFileHelper::SaveArrayToFile(Archive, *Output);
}
