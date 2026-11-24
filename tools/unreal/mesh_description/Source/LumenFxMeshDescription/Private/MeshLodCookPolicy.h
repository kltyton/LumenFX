#pragma once

#include "CoreTypes.h"

class FJsonObject;
class FStaticMeshLODGroup;
class ITargetPlatform;
class UStaticMesh;

struct FMeshLodCookPolicy
{
    uint32 FirstInline = 0;
    uint32 FirstAvailable = 0;
    uint32 FirstNonOptional = 0;
};

bool ReadMeshLodCookPolicy(const FJsonObject& Settings, const UStaticMesh& Mesh, const ITargetPlatform& Target,
                           const FStaticMeshLODGroup& Group, int32 LodCount, FMeshLodCookPolicy& Out);
