#pragma once

#include "CoreMinimal.h"
#include "Dom/JsonObject.h"
#include "Engine/EngineTypes.h"
#include "Engine/StaticMesh.h"
#include "MeshDescription.h"
#include "MeshReductionSettings.h"
#include "Serialization/CustomVersion.h"
#include "UObject/ObjectVersion.h"

struct FSourceMeshLod
{
    TArray<uint8> Archive;
    FMeshBuildSettings Build;
    FMeshReductionSettings Reduction;
};

struct FSourceMeshRequest
{
    FPackageFileVersion Version;
    int32 LicenseeVersion = 0;
    FCustomVersionContainer CustomVersions;
    FString LodGroup;
    TArray<FName> ImportedMaterialSlots;
    FMeshSectionInfoMap SectionInfo;
    FMeshSectionInfoMap OriginalSectionInfo;
    int32 LightmapVersion = 0;
    TArray<FSourceMeshLod> Lods;
    TSharedPtr<FJsonObject> AssetSettings;
    FSourceMeshLod HiRes;
    int32 TriangleOrderMode = 1;
    TMap<FString, FString> ContentRoots;
    TArray<FString> ObjectPaths;
};

struct FPreparedMeshLod
{
    FMeshDescription Description;
    float MaximumDeviation = 0;
    float PixelError = 0;
    int32 BaseLod = 0;
    bool HadSource = false;
    bool Reduced = false;
};

bool ReadMeshDescriptionInput(const FString& Path, FSourceMeshRequest& Request);
bool WriteMeshDescriptionOutput(const FString& Path, FSourceMeshRequest& Request, TArray<FPreparedMeshLod>& Lods);
bool BuildMeshDescriptions(FSourceMeshRequest& Request, TArray<FPreparedMeshLod>& Lods);
bool OptimizeMeshIndices(const FString& Input, const FString& Output);
bool BuildNativeRenderMesh(const FString& Output, const FSourceMeshRequest& Request);
