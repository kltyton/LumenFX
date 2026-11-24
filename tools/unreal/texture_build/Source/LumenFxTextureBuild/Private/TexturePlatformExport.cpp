#include "TexturePlatformExport.h"
#include "VirtualTexturePlatformExport.h"
#include "DeviceProfiles/DeviceProfile.h"
#include "DeviceProfiles/DeviceProfileManager.h"
#include "Dom/JsonObject.h"
#include "Engine/Texture2D.h"
#include "Engine/Texture2DArray.h"
#include "Engine/TextureCube.h"
#include "Engine/TextureCubeArray.h"
#include "Engine/VolumeTexture.h"
#include "Engine/TextureLODSettings.h"
#include "HAL/IConsoleManager.h"
#include "HAL/PlatformProcess.h"
#include "HAL/PlatformProperties.h"
#include "Interfaces/ITargetPlatform.h"
#include "Interfaces/ITargetPlatformManagerModule.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "Misc/Parse.h"
#include "Misc/ScopeExit.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "TextureResource.h"
#include "UObject/SoftObjectPath.h"
#include "UObject/StrongObjectPtr.h"

DEFINE_LOG_CATEGORY_STATIC(LogTextureExport, Log, All);

namespace
{
    bool WriteTexture(const TSharedPtr<FJsonObject>& Request, ITargetPlatform* Platform,
        const UTextureLODSettings& RuntimeLods)
    {
        FString Asset, Output, DigestHex, ExpectedFormat;
        if (!Request.IsValid() || !Request->TryGetStringField(TEXT("asset"), Asset)
            || !Request->TryGetStringField(TEXT("output"), Output)
            || !Request->TryGetStringField(TEXT("metadata_sha256"), DigestHex)
            || !Request->TryGetStringField(TEXT("format"), ExpectedFormat)
            || FPaths::IsRelative(Output) || DigestHex.Len() != 64) return false;
        const bool bArray = Request->HasField(TEXT("array_layers"));
        const bool bCubeArray = Request->HasField(TEXT("cube_array_layers"));
        bool bCube = false;
        if (Request->HasField(TEXT("cube")) && !Request->TryGetBoolField(TEXT("cube"), bCube)) return false;
        bool bVolume = false;
        if (Request->HasField(TEXT("volume")) && !Request->TryGetBoolField(TEXT("volume"), bVolume)) return false;
        bool bVirtual = false;
        if (Request->HasField(TEXT("virtual_texture")) && !Request->TryGetBoolField(TEXT("virtual_texture"), bVirtual)) return false;
        if (int32(bArray) + int32(bCube) + int32(bCubeArray) + int32(bVolume) + int32(bVirtual) > 1) return false;
        int32 ArrayLayers = bCube ? 6 : 1;
        if (bArray && (!Request->TryGetNumberField(TEXT("array_layers"), ArrayLayers) || ArrayLayers < 1)) return false;
        if (bCubeArray)
        {
            int32 Cubes;
            if (!Request->TryGetNumberField(TEXT("cube_array_layers"), Cubes) || Cubes < 1 || Cubes > MAX_int32 / 6) return false;
            ArrayLayers = Cubes * 6;
        }
        TArray<uint8> Digest;
        Digest.Reserve(32);
        for (int32 Index = 0; Index < 64; Index += 2)
        {
            if (!FChar::IsHexDigit(DigestHex[Index]) || !FChar::IsHexDigit(DigestHex[Index + 1])) return false;
            Digest.Add((FParse::HexDigit(DigestHex[Index]) << 4) | FParse::HexDigit(DigestHex[Index + 1]));
        }
        FText Reason;
        if (!FPackageName::IsValidObjectPath(Asset, &Reason))
        {
            UE_LOG(LogTextureExport, Error, TEXT("Invalid texture object path: %s (%s)"), *Asset, *Reason.ToString());
            return false;
        }
        TStrongObjectPtr<UTexture> Texture(Asset.Contains(TEXT(":"))
            ? Cast<UTexture>(FSoftObjectPath(Asset).TryLoad()) : LoadObject<UTexture>(nullptr, *Asset));
        if (!Texture.IsValid() || (bVolume ? !Texture->IsA<UVolumeTexture>() : bCubeArray ? !Texture->IsA<UTextureCubeArray>()
                : bCube ? !Texture->IsA<UTextureCube>()
                : bArray ? !Texture->IsA<UTexture2DArray>() : !Texture->IsA<UTexture2D>()))
        {
            UE_LOG(LogTextureExport, Error, TEXT("Texture source could not be loaded for the selected platform: %s"), *Asset);
            return false;
        }
        Texture->BeginCacheForCookedPlatformData(Platform);
        ON_SCOPE_EXIT { Texture->ClearAllCachedCookedPlatformData(); };
        TMap<FString, FTexturePlatformData*>* Cooked = Texture->GetCookedPlatformData();
        FTexturePlatformData* Selected = nullptr;
        ExpectedFormat.RemoveFromStart(TEXT("PF_"));
        if (!Cooked || Cooked->IsEmpty())
        {
            UE_LOG(LogTextureExport, Error, TEXT("Texture build produced no platform cache entries: %s"), *Asset);
            return false;
        }
        while (!Texture->IsCachedCookedPlatformDataLoaded(Platform)) FPlatformProcess::SleepNoStats(0.001f);
        for (const auto& Entry : *Cooked)
        {
            FTexturePlatformData* Data = Entry.Value;
            if (!Data) continue;
            if (bVirtual != (Data->VTData != nullptr)) continue;
            if (!bVirtual && (Data->PixelFormat <= PF_Unknown || Data->PixelFormat >= PF_MAX
                || (Cooked->Num() > 1 && ExpectedFormat != GPixelFormats[Data->PixelFormat].Name))) continue;
            if (Selected)
            {
                UE_LOG(LogTextureExport, Error, TEXT("More than one cooked texture matches %s: %s"), *ExpectedFormat, *Asset);
                return false;
            }
            Selected = Data;
        }
        if (bVirtual)
        {
            if (Selected && WriteVirtualTexturePlatformData(*Texture.Get(), *Selected, *Platform, Asset, Output, Digest)) return true;
            UE_LOG(LogTextureExport, Error, TEXT("Could not export native virtual texture tiles: %s"), *Asset);
            return false;
        }
        if (!Selected || Selected->Mips.IsEmpty() || Selected->VTData
            || (bVolume ? Selected->GetNumSlices() < 1 : Selected->GetNumSlices() != ArrayLayers)
            || Selected->IsCubemap() != (bCube || bCubeArray)
            || Selected->GetNumMipsInTail() > 1)
        {
            UE_LOG(LogTextureExport, Error, TEXT("Expected unpacked mip data with %d layers in %s for %s"),
                ArrayLayers, *ExpectedFormat, *Asset);
            return false;
        }
        if (!Selected->TryInlineMipData(0, Asset)) return false;
        const UTextureLODSettings& BuildLods = Platform->GetTextureLODSettings();
        const UTextureLODSettings& MipLods = Platform->HasEditorOnlyData() ? RuntimeLods : BuildLods;
        int32 FirstMip = MipLods.CalculateLODBias(Selected->SizeX, Selected->SizeY, Texture->GetMinTextureSize(),
            Texture->GetMaxTextureSize(), Texture->LODGroup, Texture->LODBias, 0, Texture->MipGenSettings, false, Texture->IsStreamable());
        FirstMip = FMath::Clamp(FirstMip, 0, Selected->Mips.Num() - 1);
        int32 Count = Selected->Mips.Num() - FirstMip;
        // Only cooked Texture2D resources discard the mip chain on load. WindowsEditor keeps it.
        if (!Platform->HasEditorOnlyData() && Texture->IsA<UTexture2D>()
            && BuildLods.GetMipLoadOptions(Texture.Get()) == ETextureMipLoadOptions::OnlyFirstMip
            && FMath::IsPowerOfTwo(Selected->Mips[FirstMip].SizeX) && FMath::IsPowerOfTwo(Selected->Mips[FirstMip].SizeY))
        {
            int32 RuntimeBias = 0;
            if (!FPlatformProperties::RequiresCookedData())
            {
                IConsoleVariable* GlobalBias = IConsoleManager::Get().FindConsoleVariable(TEXT("r.GlobalTextureLODBias"));
                if (!GlobalBias) return false;
                // The cooked branch omits these already-baked terms; keep native cinematic, size and UI rules.
                RuntimeBias = -BuildLods.GetTextureLODGroup(Texture->LODGroup).LODBias - GlobalBias->GetInt();
            }
            int32 RuntimeFirst = BuildLods.CalculateLODBias(Selected->Mips[FirstMip].SizeX, Selected->Mips[FirstMip].SizeY,
                Texture->GetMinTextureSize(), Texture->GetMaxTextureSize(), Texture->LODGroup, RuntimeBias,
                Texture->NumCinematicMipLevels, Texture->MipGenSettings, false, false);
            FirstMip += FMath::Clamp(RuntimeFirst, 0, Count - 1);
            Count = 1;
        }
        IConsoleVariable* Anisotropy = IConsoleManager::Get().FindConsoleVariable(TEXT("r.MaxAnisotropy"));
        if (!Anisotropy) return false;
        int32 MinMag, Mip;
        switch (RuntimeLods.GetSamplerFilter(Texture.Get()))
        {
            case ETextureSamplerFilter::Point: MinMag = 0; Mip = 0; break;
            case ETextureSamplerFilter::Bilinear: MinMag = 1; Mip = 0; break;
            case ETextureSamplerFilter::Trilinear: MinMag = 1; Mip = 1; break;
            case ETextureSamplerFilter::AnisotropicPoint: MinMag = 2; Mip = 0; break;
            case ETextureSamplerFilter::AnisotropicLinear: MinMag = 2; Mip = 1; break;
            default: return false;
        }
        FBufferArchive Ar;
        uint32 Magic = 0x5254464c, Version = bVolume ? 4 : 3;
        int32 Flags = Texture->SRGB ? 1 : 0;
        int32 MaxAnisotropy = FMath::Max(1, Anisotropy->GetInt());
        Ar << Magic << Version;
        Ar.Serialize(Digest.GetData(), Digest.Num());
        Ar << Count;
        FTCHARToUTF8 Format(GPixelFormats[Selected->PixelFormat].Name);
        int32 FormatLength = Format.Length();
        Ar << FormatLength;
        Ar.Serialize(const_cast<ANSICHAR*>(Format.Get()), FormatLength);
        Ar << Flags << MinMag << Mip << MaxAnisotropy;
        const FPixelFormatInfo& Info = GPixelFormats[Selected->PixelFormat];
        for (int32 Index = FirstMip; Index < FirstMip + Count; ++Index)
        {
            FTexture2DMipMap& Level = Selected->Mips[Index];
            int32 Width = Level.SizeX, Height = Level.SizeY;
            int32 Depth = bVolume ? FMath::Max(1, Selected->GetNumSlices() >> Index) : 1;
            int64 Expected = int64(FMath::DivideAndRoundUp(Width, int32(Info.BlockSizeX)))
                * FMath::DivideAndRoundUp(Height, int32(Info.BlockSizeY)) * Info.BlockBytes * ArrayLayers * Depth;
            if (Width != FMath::Max(1, Selected->SizeX >> Index)
                || Height != FMath::Max(1, Selected->SizeY >> Index)
                || (bVolume ? Level.SizeZ != Depth : bArray ? Level.SizeZ != ArrayLayers : !(bCube || bCubeArray) && Level.SizeZ > 1)
                || ((bCube || bCubeArray) && Width != Height)
                || Expected <= 0 || Level.BulkData.GetBulkDataSize() != Expected || Expected > MAX_int32) return false;
            int32 Bytes = int32(Expected);
            if (Ar.Num() > MAX_int32 - (bVolume ? 16 : 12) - Bytes) return false;
            Ar << Width << Height;
            if (bVolume) Ar << Depth;
            Ar << Bytes;
            const void* Pixels = Level.BulkData.LockReadOnly();
            ON_SCOPE_EXIT { Level.BulkData.Unlock(); };
            if (!Pixels) return false;
            Ar.Serialize(const_cast<void*>(Pixels), Bytes);
        }
        if (Ar.IsError() || !FFileHelper::SaveArrayToFile(Ar, *Output)) return false;
        UE_LOG(LogTextureExport, Display, TEXT("Built %s: %s, %dx%d, %d layers, %d mips"), *Asset,
            GPixelFormats[Selected->PixelFormat].Name, Selected->Mips[FirstMip].SizeX, Selected->Mips[FirstMip].SizeY, ArrayLayers, Count);
        return true;
    }
}

bool ExportPlatformTextures(const FString& Input)
{
    FString Text;
    TSharedPtr<FJsonObject> Request;
    if (!FFileHelper::LoadFileToString(Text, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Text), Request) || !Request.IsValid()) return false;
    FString PlatformName;
    const TSharedPtr<FJsonObject>* Roots;
    const TArray<TSharedPtr<FJsonValue>>* Textures;
    if (!Request->TryGetStringField(TEXT("platform"), PlatformName)
        || !Request->TryGetObjectField(TEXT("content_roots"), Roots)
        || !Request->TryGetArrayField(TEXT("textures"), Textures) || Textures->IsEmpty()) return false;
    auto& Platforms = GetTargetPlatformManagerRef();
    auto& Profiles = UDeviceProfileManager::Get();
    ITargetPlatform* Platform = Platforms.FindTargetPlatform(*PlatformName);
    if (!Platform || Platform->IsServerOnly())
    {
        UE_LOG(LogTextureExport, Error, TEXT("Target platform is unavailable for texture production: %s"), *PlatformName);
        return false;
    }
    // A running editor's texture resource uses the active device profile for its mip bias and sampler.
    const UTextureLODSettings& RuntimeLods = Platform->HasEditorOnlyData() && Platform == Platforms.GetRunningTargetPlatform()
        ? *Profiles.GetActiveProfile()->GetTextureLODSettings() : Platform->GetTextureLODSettings();
    TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
    for (const auto& Root : (*Roots)->Values)
    {
        const FString RootName(*Root.Key);
        FString Directory;
        if (!Root.Value->TryGetString(Directory) || !RootName.StartsWith(TEXT("/"))
            || !RootName.EndsWith(TEXT("/")) || RootName.Contains(TEXT("..")) || RootName.Contains(TEXT("//"))
            || FPaths::IsRelative(Directory) || !FPaths::DirectoryExists(Directory)) return false;
        FPaths::NormalizeDirectoryName(Directory);
        auto Mount = FPackageName::RegisterMountPoint(RootName, Directory + TEXT("/"));
        if (!Mount.IsValid())
        {
            UE_LOG(LogTextureExport, Error, TEXT("Could not mount %s at %s"), *Directory, *RootName);
            return false;
        }
        Mounts.Add(MoveTemp(Mount));
    }
    for (const TSharedPtr<FJsonValue>& Value : *Textures)
        if (Value->Type != EJson::Object || !WriteTexture(Value->AsObject(), Platform, RuntimeLods)) return false;
    return true;
}
