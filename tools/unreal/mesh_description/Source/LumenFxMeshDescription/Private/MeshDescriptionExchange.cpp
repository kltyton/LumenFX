#include "MeshDescriptionExchange.h"
#include "JsonObjectConverter.h"
#include "Misc/FileHelper.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/MemoryReader.h"
#include "Serialization/MemoryWriter.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "StaticMeshResources.h"

namespace
{
    bool ReadWord(FMemoryReader& Ar, int32& Value)
    {
        if (Ar.Tell() + 4 > Ar.TotalSize()) return false;
        Ar << Value;
        return !Ar.IsError();
    }

    bool ReadBytes(FMemoryReader& Ar, TArray<uint8>& Value)
    {
        int32 Size;
        if (!ReadWord(Ar, Size) || Size < 0 || Size > Ar.TotalSize() - Ar.Tell()) return false;
        Value.SetNumUninitialized(Size);
        if (Size != 0) Ar.Serialize(Value.GetData(), Size);
        return !Ar.IsError();
    }

    bool ReadText(FMemoryReader& Ar, FString& Value)
    {
        TArray<uint8> Bytes;
        if (!ReadBytes(Ar, Bytes)) return false;
        FUTF8ToTCHAR Text(reinterpret_cast<const ANSICHAR*>(Bytes.GetData()), Bytes.Num());
        Value = FString(Text.Length(), Text.Get());
        return true;
    }

    bool ReadSections(FMemoryReader& Ar, FMeshSectionInfoMap& Map)
    {
        int32 Count;
        if (!ReadWord(Ar, Count) || Count < 0 || Count > (Ar.TotalSize() - Ar.Tell()) / 12) return false;
        for (int32 Index = 0; Index < Count; ++Index)
        {
            uint32 Key, Flags;
            FMeshSectionInfo Info;
            Ar << Key << Info.MaterialIndex << Flags;
            if ((Flags & ~31u) != 0) return false;
            Info.bEnableCollision = (Flags & 1) != 0; Info.bCastShadow = (Flags & 2) != 0;
            Info.bVisibleInRayTracing = (Flags & 4) != 0; Info.bAffectDistanceFieldLighting = (Flags & 8) != 0;
            Info.bForceOpaque = (Flags & 16) != 0;
            Map.Map.Add(Key, Info);
        }
        return !Ar.IsError();
    }

    bool ReadLod(FMemoryReader& Ar, FSourceMeshLod& Lod)
    {
        FString Build, Reduction;
        if (!ReadBytes(Ar, Lod.Archive) || !ReadText(Ar, Build) || !ReadText(Ar, Reduction)) return false;
        FText Reason;
        if (!FJsonObjectConverter::JsonObjectStringToUStruct(Build, &Lod.Build, 0, 0, false, &Reason)
            || !FJsonObjectConverter::JsonObjectStringToUStruct(Reduction, &Lod.Reduction, 0, 0, false, &Reason))
        {
            UE_LOG(LogTemp, Error, TEXT("Mesh settings could not be read: %s"), *Reason.ToString());
            return false;
        }
        return true;
    }

    void WriteSections(FArchive& Ar, FMeshSectionInfoMap& Map)
    {
        int32 Count = Map.Map.Num();
        Ar << Count;
        for (auto& Pair : Map.Map)
        {
            uint32 Key = Pair.Key;
            auto& Info = Pair.Value;
            uint32 Flags = (Info.bEnableCollision ? 1 : 0) | (Info.bCastShadow ? 2 : 0)
                | (Info.bVisibleInRayTracing ? 4 : 0) | (Info.bAffectDistanceFieldLighting ? 8 : 0) | (Info.bForceOpaque ? 16 : 0);
            Ar << Key << Info.MaterialIndex << Flags;
        }
    }
}

bool ReadMeshDescriptionInput(const FString& Path, FSourceMeshRequest& Request)
{
    TArray<uint8> Bytes;
    if (!FFileHelper::LoadFileToArray(Bytes, *Path)) return false;
    FMemoryReader Ar(Bytes, true);
    int32 Magic, Format, Ue4, Ue5, Count;
    if (!ReadWord(Ar, Magic) || Magic != 0x49444d4c || !ReadWord(Ar, Format) || Format < 2 || Format > 4
        || !ReadWord(Ar, Ue4) || !ReadWord(Ar, Ue5) || !ReadWord(Ar, Request.LicenseeVersion)
        || !ReadWord(Ar, Count) || Count < 0 || Count > (Ar.TotalSize() - Ar.Tell()) / 20) return false;
    Request.Version = FPackageFileVersion(Ue4, static_cast<EUnrealEngineObjectUE5Version>(Ue5));
    for (int32 Index = 0; Index < Count; ++Index)
    {
        uint32 A, B, C, D;
        int32 Version;
        Ar << A << B << C << D << Version;
        Request.CustomVersions.SetVersion(FGuid(A, B, C, D), Version, NAME_None);
    }
    if (!ReadWord(Ar, Request.LightmapVersion) || !ReadText(Ar, Request.LodGroup)
        || !ReadWord(Ar, Count) || Count < 0 || Count > (Ar.TotalSize() - Ar.Tell()) / 4) return false;
    for (int32 Index = 0; Index < Count; ++Index)
    {
        FString Name;
        if (!ReadText(Ar, Name)) return false;
        Request.ImportedMaterialSlots.Add(FName(*Name));
    }
    if (!ReadSections(Ar, Request.SectionInfo) || !ReadSections(Ar, Request.OriginalSectionInfo)
        || !ReadWord(Ar, Count) || Count < 1 || Count > MAX_STATIC_MESH_LODS) return false;
    Request.Lods.SetNum(Count);
    for (FSourceMeshLod& Lod : Request.Lods)
    {
        if (!ReadLod(Ar, Lod)) return false;
    }
    Request.AssetSettings = MakeShared<FJsonObject>();
    if (Format >= 3)
    {
        FString Settings;
        if (!ReadText(Ar, Settings) || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Settings), Request.AssetSettings)
            || !ReadWord(Ar, Request.TriangleOrderMode) || Request.TriangleOrderMode < 0 || Request.TriangleOrderMode > 2
            || !ReadLod(Ar, Request.HiRes)) return false;
    }
    if (Format >= 4)
    {
        if (!ReadWord(Ar, Count) || Count < 0 || Count > (Ar.TotalSize() - Ar.Tell()) / 8) return false;
        for (int32 Index = 0; Index < Count; ++Index)
        {
            FString Root, Directory;
            if (!ReadText(Ar, Root) || !ReadText(Ar, Directory) || Request.ContentRoots.Contains(Root)) return false;
            Request.ContentRoots.Add(MoveTemp(Root), MoveTemp(Directory));
        }
        if (!ReadWord(Ar, Count) || Count < 0 || Count > (Ar.TotalSize() - Ar.Tell()) / 4) return false;
        for (int32 Index = 0; Index < Count; ++Index)
        {
            FString Object;
            if (!ReadText(Ar, Object)) return false;
            Request.ObjectPaths.Add(MoveTemp(Object));
        }
    }
    return !Ar.IsError() && Ar.Tell() == Ar.TotalSize();
}

bool WriteMeshDescriptionOutput(const FString& Path, FSourceMeshRequest& Request, TArray<FPreparedMeshLod>& Lods)
{
    FBufferArchive Ar;
    uint32 Magic = 0x4f444d4c;
    int32 Format = 2, Count = Lods.Num();
    Ar << Magic << Format << Count;
    WriteSections(Ar, Request.SectionInfo); WriteSections(Ar, Request.OriginalSectionInfo);
    for (FPreparedMeshLod& Lod : Lods)
    {
        TArray<uint8> Bytes;
        FMemoryWriter Writer(Bytes, true);
        Writer.SetUEVer(GPackageFileUEVersion);
        Writer.SetLicenseeUEVer(GPackageFileLicenseeUEVersion);
        Writer.SetCustomVersions(FCurrentCustomVersions::GetAll());
        Writer << Lod.Description;
        if (Writer.IsError()) return false;
        int32 Flags = (Lod.HadSource ? 1 : 0) | (Lod.Reduced ? 2 : 0), Size = Bytes.Num();
        Ar << Lod.MaximumDeviation << Lod.PixelError << Lod.BaseLod << Flags << Size;
        if (Size != 0) Ar.Serialize(Bytes.GetData(), Size);
    }
    return !Ar.IsError() && FFileHelper::SaveArrayToFile(Ar, *Path);
}
