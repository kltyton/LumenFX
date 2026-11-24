#pragma once

#include "CoreMinimal.h"
#include "LayoutUV.h"

class FUvLayoutMeshView final : public FLayoutUV::IMeshView
{
public:
    TArray<FVector3f> Positions;
    TArray<FVector3f> Normals;
    TArray<FVector2f> Coordinates;
    TArray<FVector2f> Output;

    uint32 GetNumIndices() const override { return static_cast<uint32>(Positions.Num()); }
    FVector3f GetPosition(uint32 index) const override { return Positions[index]; }
    FVector3f GetNormal(uint32 index) const override { return Normals[index]; }
    FVector2f GetInputTexcoord(uint32 index) const override { return Coordinates[index]; }
    void InitOutputTexcoords(uint32 count) override { Output.SetNumZeroed(count); }
    void SetOutputTexcoord(uint32 index, const FVector2f& value) override { Output[index] = value; }
};
