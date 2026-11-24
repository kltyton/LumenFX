#include "ShadingEnergyExport.h"
#include "Algo/Find.h"
#include "Dom/JsonObject.h"
#include "Engine/Texture2D.h"
#include "ImageCore.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "UObject/StrongObjectPtr.h"

DEFINE_LOG_CATEGORY_STATIC(LogShadingEnergyExport, Log, All);

namespace
{
    struct FTable
    {
        const TCHAR* Name;
        const TCHAR* Object;
        int32 Components;
        bool bFlattenedVolume;
        const TCHAR* Directory = TEXT("EnergyConservation");
    };

    // These are the default object names in BaseEngine.ini, with request overrides for authored projects.
    constexpr FTable Tables[] = {
        {TEXT("ggx_reflection"), TEXT("GGX_ReflectionEnergy"), 2, false},
        {TEXT("ggx_transmission"), TEXT("GGX_TransmissionEnergyArray"), 2, true},
        {TEXT("sheen"), TEXT("Sheen_Energy"), 2, false},
        {TEXT("sheen_legacy"), TEXT("SheenLegacy_Energy"), 2, false},
        {TEXT("diffuse"), TEXT("Diffuse_Energy"), 1, false},
        {TEXT("ggx_max_specular"), TEXT("GGX_MaxSpecEnergy"), 4, false},
        {TEXT("sheen_ltc"), TEXT("Sheen_LTC"), 4, false, TEXT("LTC")},
        {TEXT("ggx_ltc_matrix"), TEXT("GGX_LTCMat"), 4, false, TEXT("LTC")},
        {TEXT("ggx_ltc_amplitude"), TEXT("GGX_LTCAmp"), 2, false, TEXT("LTC")},
    };

    template <typename Color>
    void WriteChannels(FBufferArchive& Archive, TArrayView64<const Color> Pixels, int32 Components)
    {
        for (const Color& Pixel : Pixels)
        {
            float R = float(Pixel.R);
            Archive << R;
            if (Components > 1)
            {
                float G = float(Pixel.G);
                Archive << G;
            }
            if (Components == 4)
            {
                float B = float(Pixel.B), A = float(Pixel.A);
                Archive << B << A;
            }
        }
    }

    bool WriteTable(const FTable& Table, const FString& Asset, const FString& Output)
    {
        if (!FPackageName::IsValidObjectPath(Asset) || FPaths::IsRelative(Output)) return false;
        TStrongObjectPtr<UTexture2D> Texture(LoadObject<UTexture2D>(nullptr, *Asset));
        if (!Texture.IsValid() || Texture->Availability != ETextureAvailability::CPU || !Texture->GetPlatformData()) return false;
        FSharedImageConstRef Image = Texture->GetCPUCopy();
        const auto Expected = Table.bFlattenedVolume ? ERawImageFormat::RGBA32F : ERawImageFormat::RGBA16F;
        if (!Image || Image->Format != Expected || Image->NumSlices != 1 || Image->SizeX <= 0 || Image->SizeY <= 0
            || (Table.bFlattenedVolume && Image->SizeY % Image->SizeX != 0)) return false;
        const int64 BytesPerPixel = int64(Table.Components) * int64(sizeof(float));
        if (int64(Image->SizeX) * Image->SizeY > (MAX_int32 - 28) / BytesPerPixel) return false;

        FBufferArchive Archive;
        uint32 Magic = 0x4145464c, Version = 1;
        int32 Kind = int32(&Table - Tables), Width = Image->SizeX;
        int32 Height = Table.bFlattenedVolume ? Width : Image->SizeY;
        int32 Depth = Table.bFlattenedVolume ? Image->SizeY / Width : 1;
        int32 Components = Table.Components;
        Archive << Magic << Version << Kind << Width << Height << Depth << Components;
        // UE flattens z slices as contiguous square planes in the original 2D image.
        if (Table.bFlattenedVolume) WriteChannels<FLinearColor>(Archive, Image->AsRGBA32F(), Components);
        else WriteChannels<FFloat16Color>(Archive, Image->AsRGBA16F(), Components);
        return !Archive.IsError() && FFileHelper::SaveArrayToFile(Archive, *Output);
    }
}

bool ExportShadingEnergyTables(const FString& Input)
{
    FString Text;
    TSharedPtr<FJsonObject> Request;
    const TArray<TSharedPtr<FJsonValue>>* Jobs = nullptr;
    if (!FFileHelper::LoadFileToString(Text, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Request) || !Request.IsValid()
        || !Request->TryGetArrayField(TEXT("tables"), Jobs) || Jobs->IsEmpty()) return false;
    TSet<FString> Outputs;
    for (const auto& Value : *Jobs)
    {
        if (Value->Type != EJson::Object) return false;
        const auto Job = Value->AsObject();
        FString Name, Output, Asset;
        if (!Job->TryGetStringField(TEXT("table"), Name) || !Job->TryGetStringField(TEXT("output"), Output)) return false;
        const FTable* Table = Algo::FindByPredicate(Tables, [&Name](const FTable& Entry) { return Name == Entry.Name; });
        if (!Table || Outputs.Contains(Output)) return false;
        Outputs.Add(Output);
        if (!Job->TryGetStringField(TEXT("asset"), Asset))
        {
            if (Job->HasField(TEXT("asset"))) return false;
            Asset = FString::Printf(TEXT("/Engine/EngineMaterials/%s/%s.%s"), Table->Directory, Table->Object, Table->Object);
        }
        if (!WriteTable(*Table, Asset, Output))
        {
            UE_LOG(LogShadingEnergyExport, Error, TEXT("Could not export native CPU shading table %s from %s"), *Name, *Asset);
            return false;
        }
    }
    return true;
}
