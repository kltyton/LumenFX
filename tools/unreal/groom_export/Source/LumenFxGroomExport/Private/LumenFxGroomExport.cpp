#include "CoreMinimal.h"
#include "RequiredProgramMainCPPInclude.h"
#include "GroomAsset.h"
#include "Misc/CommandLine.h"
#include "Misc/FileHelper.h"
#include "Misc/PackageName.h"
#include "Misc/Parse.h"
#include "Misc/Paths.h"
#include "Misc/ScopeExit.h"
#include "Modules/ModuleManager.h"
#include "Serialization/BufferArchive.h"
#include "Serialization/BulkData.h"
#include "Serialization/JsonReader.h"
#include "Serialization/JsonSerializer.h"
#include "UObject/UObjectGlobals.h"

IMPLEMENT_APPLICATION(LumenFxGroomExport, "LumenFxGroomExport");

namespace
{
    bool ReadRequest(const FString& File, FString& Source, TArray<TRefCountPtr<UE::PackageName::IMountPoint>>& Mounts)
    {
        FString Json;
        TSharedPtr<FJsonObject> Request;
        const TSharedPtr<FJsonObject>* Roots = nullptr;
        if (!FFileHelper::LoadFileToString(Json, *File)
            || !FJsonSerializer::Deserialize(TJsonReaderFactory<>::Create(Json), Request)
            || !Request.IsValid() || !Request->TryGetStringField(TEXT("groom"), Source)
            || !Request->TryGetObjectField(TEXT("mounts"), Roots)) return false;

        for (const auto& Root : (*Roots)->Values)
        {
            FString Directory;
            if (!Root.Key.StartsWith(TEXT("/")) || !Root.Key.EndsWith(TEXT("/"))
                || !Root.Value->TryGetString(Directory) || FPaths::IsRelative(Directory)
                || !FPaths::DirectoryExists(Directory)) return false;
            FPaths::NormalizeDirectoryName(Directory);
            Mounts.Add(FPackageName::RegisterMountPoint(Root.Key, Directory + TEXT("/")));
        }
        return true;
    }

    void WriteVector(FArchive& Archive, FVector Vector)
    {
        Archive << Vector.X << Vector.Y << Vector.Z;
    }

    bool WriteBulk(FArchive& Archive, const FByteBulkData& Bulk, int64 Bytes)
    {
        if (Bytes == 0) return true;
        if (Bulk.GetBulkDataSize() < Bytes) return false;
        const void* Data = Bulk.LockReadOnly();
        ON_SCOPE_EXIT { Bulk.Unlock(); };
        if (!Data) return false;
        Archive.Serialize(const_cast<void*>(Data), Bytes);
        return !Archive.IsError();
    }

    void WriteScale(FArchive& Archive, const FRichCurve& Curve, int32 Size)
    {
        for (int32 Index = 0; Index < 32; ++Index)
        {
            float Value = Index < Size ? Curve.Eval(static_cast<float>(Index) / (Size - 1.0f)) : 1.0f;
            Archive << Value;
        }
    }

    void WritePhysics(FArchive& Archive, FHairGroupsPhysics Physics)
    {
        auto& Solver = Physics.SolverSettings;
        auto& Forces = Physics.ExternalForces;
        auto& Bend = Physics.MaterialConstraints.BendConstraint;
        auto& Stretch = Physics.MaterialConstraints.StretchConstraint;
        auto& Collision = Physics.MaterialConstraints.CollisionConstraint;
        auto& Strand = Physics.StrandsParameters;

        int32 Size = static_cast<uint8>(Strand.StrandsSize);
        uint32 Flags = (Solver.EnableSimulation ? 1u : 0u)
            | (Bend.SolveBend ? 2u : 0u) | (Bend.ProjectBend ? 4u : 0u)
            | (Stretch.SolveStretch ? 8u : 0u) | (Stretch.ProjectStretch ? 16u : 0u)
            | (Collision.SolveCollision ? 32u : 0u) | (Collision.ProjectCollision ? 64u : 0u);

        Archive << Size << Solver.SubSteps << Solver.IterationCount << Flags << Solver.GravityPreloading;
        WriteVector(Archive, Forces.GravityVector);
        Archive << Forces.AirDrag;
        WriteVector(Archive, Forces.AirVelocity);
        Archive << Bend.BendDamping << Bend.BendStiffness << Stretch.StretchDamping << Stretch.StretchStiffness;
        Archive << Collision.StaticFriction << Collision.KineticFriction << Collision.StrandsViscosity
            << Collision.GridDimension.X << Collision.GridDimension.Y << Collision.GridDimension.Z << Collision.CollisionRadius;
        Archive << Strand.StrandsDensity << Strand.StrandsSmoothing << Strand.StrandsThickness;

        WriteScale(Archive, *Stretch.StretchScale.GetRichCurveConst(), Size);
        WriteScale(Archive, *Bend.BendScale.GetRichCurveConst(), Size);
        WriteScale(Archive, *Collision.RadiusScale.GetRichCurveConst(), Size);
        WriteScale(Archive, *Strand.ThicknessScale.GetRichCurveConst(), Size);
    }

    bool ExportGroom(const FString& Input, const FString& Output)
    {
        FString Source;
        TArray<TRefCountPtr<UE::PackageName::IMountPoint>> Mounts;
        if (!ReadRequest(Input, Source, Mounts)) return false;

        FModuleManager::Get().LoadModule(TEXT("HairStrandsCore"));
        const auto* Groom = LoadObject<UGroomAsset>(nullptr, *Source);
        if (!Groom) return false;
        const auto& Platform = Groom->GetHairGroupsPlatformData();
        const auto& Physics = Groom->GetHairGroupsPhysics();
        if (Platform.IsEmpty() || Platform.Num() != Physics.Num()) return false;

        FBufferArchive Archive;
        uint32 Magic = 0x5247464c, Version = 1;
        int32 Groups = Platform.Num();
        Archive << Magic << Version << Groups;
        for (int32 Index = 0; Index < Groups; ++Index)
        {
            const auto& Guides = Platform[Index].Guides.BulkData;
            uint32 PointCount = Guides.GetNumPoints(), CurveCount = Guides.GetNumCurves();
            if ((PointCount == 0) != (CurveCount == 0)) return false;
            Archive << PointCount << CurveCount;
            WriteVector(Archive, PointCount ? Guides.GetPositionOffset() : FVector::ZeroVector);
            WritePhysics(Archive, Physics[Index]);
            if (!WriteBulk(Archive, Guides.Data.Positions, int64(PointCount) * 8)
                || !WriteBulk(Archive, Guides.Data.Curves, int64(CurveCount) * 4)
                || !WriteBulk(Archive, Guides.Data.PointToCurve, int64(FMath::DivideAndRoundUp(PointCount, 8u)) * 4)) return false;
        }
        return !Archive.IsError() && FFileHelper::SaveArrayToFile(Archive, *Output);
    }
}

INT32_MAIN_INT32_ARGC_TCHAR_ARGV()
{
    FTaskTagScope TaskScope(ETaskTag::EGameThread);
    ON_SCOPE_EXIT
    {
        RequestEngineExit(TEXT("Groom source exchange completed"));
        FEngineLoop::AppPreExit();
        FModuleManager::Get().UnloadModulesAtShutdown();
        FEngineLoop::AppExit();
    };
    if (int32 Status = GEngineLoop.PreInit(ArgC, ArgV)) return Status;
    FString Input, Output;
    if (!FParse::Value(FCommandLine::Get(), TEXT("input="), Input)
        || !FParse::Value(FCommandLine::Get(), TEXT("output="), Output)) return 2;
    return ExportGroom(Input, Output) ? 0 : 3;
}
