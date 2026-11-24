#pragma once

#include "CoreMinimal.h"

struct FSourceMeshRequest;
class FStaticMeshRenderData;

bool BuildMeshDistanceFieldExchange(const FStaticMeshRenderData& RenderData, const FSourceMeshRequest& Request, TArray<uint8>& Output);
