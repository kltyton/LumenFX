#include "GeometryCollectionExchange.h"
#include "GeometryCollection/GeometryCollection.h"
#include "GeometryCollection/GeometryCollectionAlgo.h"
#include "GeometryCollection/GeometryCollectionObject.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Paths.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "UObject/UObjectGlobals.h"

namespace
{
    void WriteVector(FArchive& Archive, FVector Value)
    {
        Archive << Value.X << Value.Y << Value.Z;
    }

    void WriteTransform(FArchive& Archive, const FTransform& Value)
    {
        WriteVector(Archive, Value.GetTranslation());
        FQuat Rotation = Value.GetRotation();
        Archive << Rotation.X << Rotation.Y << Rotation.Z << Rotation.W;
        WriteVector(Archive, Value.GetScale3D());
    }
}

bool ExportNativeGeometryCollection(const FString& Input, const FString& Output)
{
    FString Json, Source;
    TSharedPtr<FJsonObject> Request;
    if (!FFileHelper::LoadFileToString(Json, *Input)
        || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Request)
        || !Request.IsValid() || !Request->TryGetStringField(TEXT("geometry_collection"), Source)) return false;

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

    const auto* Asset = LoadObject<UGeometryCollection>(nullptr, *Source);
    if (!Asset) return false;
    const auto Collection = Asset->GetGeometryCollection();
    if (!Collection.IsValid() || Collection->TransformIndex.Num() != Collection->BoundingBox.Num()
        || Collection->Parent.Num() != Collection->Transform.Num()) return false;

    TArray<FTransform> GlobalRest;
    GeometryCollectionAlgo::GlobalMatrices(Collection->Transform, Collection->Parent, GlobalRest);
    int32 RestCount = GlobalRest.Num(), PieceCount = Collection->BoundingBox.Num();
    FBufferArchive Archive;
    uint32 Magic = 0x4347464c, Version = 1;
    Archive << Magic << Version << RestCount << PieceCount;

    for (const auto& Transform : GlobalRest) WriteTransform(Archive, Transform);
    for (int32 Index = 0; Index < PieceCount; ++Index)
    {
        int32 TransformIndex = Collection->TransformIndex[Index];
        if (!Collection->Children.IsValidIndex(TransformIndex) || !GlobalRest.IsValidIndex(TransformIndex)) return false;
        uint32 Leaf = Collection->Children[TransformIndex].IsEmpty() ? 1u : 0u;
        Archive << TransformIndex << Leaf;
        WriteVector(Archive, Collection->BoundingBox[Index].Min);
        WriteVector(Archive, Collection->BoundingBox[Index].Max);
    }
    return !Archive.IsError() && FFileHelper::SaveArrayToFile(Archive, *Output);
}
