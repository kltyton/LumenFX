#include "StaticShadowDepthExport.h"

#include "Dom/JsonObject.h"
#include "Engine/MapBuildDataRegistry.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"

DEFINE_LOG_CATEGORY_STATIC(LogStaticShadowDepthExport, Log, All);

bool ExportStaticShadowDepth(const FString& Input)
{
    FString Text, RegistryPath, LightGuid, Output;
    TSharedPtr<FJsonObject> Request;
    if (!FFileHelper::LoadFileToString(Text, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Request) || !Request.IsValid()
        || !Request->TryGetStringField(TEXT("registry"), RegistryPath) || !FPackageName::IsValidObjectPath(RegistryPath)
        || !Request->TryGetStringField(TEXT("light_guid"), LightGuid)
        || !Request->TryGetStringField(TEXT("output"), Output) || FPaths::IsRelative(Output)
        || !FPaths::CollapseRelativeDirectories(Output)) return false;

    FGuid Id;
    if (!FGuid::Parse(LightGuid, Id) || !Id.IsValid()) return false;
    const UMapBuildDataRegistry* Registry = LoadObject<UMapBuildDataRegistry>(nullptr, *RegistryPath);
    const FLightComponentMapBuildData* Light = Registry ? Registry->GetLightBuildData(Id) : nullptr;
    if (!Light)
    {
        UE_LOG(LogStaticShadowDepthExport, Error, TEXT("Missing light %s in build registry %s"), *LightGuid, *RegistryPath);
        return false;
    }

    const FStaticShadowDepthMapData& Depth = Light->DepthMap;
    if (Depth.WorldToLight.ContainsNaN() || Depth.ShadowMapSizeX <= 0 || Depth.ShadowMapSizeY <= 0
        || int64(Depth.ShadowMapSizeX) * int64(Depth.ShadowMapSizeY) != Depth.DepthSamples.Num())
    {
        UE_LOG(LogStaticShadowDepthExport, Error, TEXT("Light %s has no valid static shadow depth map"), *LightGuid);
        return false;
    }
    FBufferArchive Archive;
    uint32 Magic = 0x4453464c, Version = 1;
    int32 Width = Depth.ShadowMapSizeX, Height = Depth.ShadowMapSizeY;
    Archive << Magic << Version << Width << Height;
    for (int32 Row = 0; Row < 4; ++Row)
        for (int32 Column = 0; Column < 4; ++Column)
        {
            double Value = Depth.WorldToLight.M[Row][Column];
            Archive << Value;
        }
    for (const FFloat16& Sample : Depth.DepthSamples)
    {
        uint16 Bits = Sample.Encoded;
        Archive << Bits;
    }
    return FFileHelper::SaveArrayToFile(Archive, *Output);
}
