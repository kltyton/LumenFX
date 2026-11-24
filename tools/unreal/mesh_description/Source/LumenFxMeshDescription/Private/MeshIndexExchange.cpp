#include "MeshDescriptionExchange.h"
#include "HAL/IConsoleManager.h"
#include "Misc/FileHelper.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/MemoryReader.h"
#include "ThirdPartyBuildOptimizationHelper.h"

bool OptimizeMeshIndices(const FString& Input, const FString& Output)
{
    TArray<uint8> Bytes;
    if (!FFileHelper::LoadFileToArray(Bytes, *Input)) return false;
    FMemoryReader Ar(Bytes, true);
    int32 Magic, Format, Mode, Lists;
    if (Bytes.Num() < 16) return false;
    Ar << Magic << Format << Mode << Lists;
    if (Magic != 0x49494d4c || Format != 1 || Mode < 0 || Mode > 2 || Lists < 0 || Lists > (Ar.TotalSize() - Ar.Tell()) / 8) return false;
    IConsoleVariable* Variable = IConsoleManager::Get().FindConsoleVariable(TEXT("r.TriangleOrderOptimization"));
    if (!Variable) return false;
    Variable->Set(Mode, ECVF_SetByCode);
    FBufferArchive Result;
    Magic = 0x4f494d4c;
    Result << Magic << Format << Lists;
    for (int32 List = 0; List < Lists; ++List)
    {
        int32 Vertices, Count;
        if (Ar.Tell() + 8 > Ar.TotalSize()) return false;
        Ar << Vertices << Count;
        if (Vertices < 0 || Count < 0 || Count % 3 != 0 || Count > (Ar.TotalSize() - Ar.Tell()) / 4) return false;
        TArray<uint32> Indices;
        Indices.SetNumUninitialized(Count);
        if (Count > 0) Ar.Serialize(Indices.GetData(), static_cast<int64>(Count) * 4);
        for (uint32 Index : Indices) if (Index >= static_cast<uint32>(Vertices)) return false;
        if (Count > 0) BuildOptimizationThirdParty::CacheOptimizeIndexBuffer(Indices);
        Count = Indices.Num();
        Result << Count;
        if (Count > 0) Result.Serialize(Indices.GetData(), static_cast<int64>(Count) * 4);
    }
    return !Ar.IsError() && Ar.Tell() == Ar.TotalSize() && FFileHelper::SaveArrayToFile(Result, *Output);
}
