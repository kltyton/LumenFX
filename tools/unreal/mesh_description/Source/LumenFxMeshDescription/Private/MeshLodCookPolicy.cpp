#include "MeshLodCookPolicy.h"

#include "Dom/JsonObject.h"
#include "Engine/Engine.h"
#include "Engine/StaticMesh.h"
#include "HAL/IConsoleManager.h"
#include "Interfaces/ITargetPlatform.h"
#include "StaticMeshResources.h"

namespace
{
    FPerPlatformInt PerPlatformInt(const FJsonObject& Settings, const TCHAR* Name, int32 Default)
    {
        FPerPlatformInt Value(Default);
        if (Settings.TryGetNumberField(Name, Value.Default)) return Value;
        const TSharedPtr<FJsonObject>* Object;
        if (Settings.TryGetObjectField(Name, Object))
        {
            (*Object)->TryGetNumberField(TEXT("default"), Value.Default);
            const TSharedPtr<FJsonObject>* Overrides;
            if ((*Object)->TryGetObjectField(TEXT("overrides"), Overrides))
                for (const auto& Entry : (*Overrides)->Values)
                    Value.PerPlatform.Add(FName(*Entry.Key), static_cast<int32>(Entry.Value->AsNumber()));
        }
        return Value;
    }

    int32 PlatformMinimum(const FJsonObject& Settings, const UStaticMesh& Mesh, const ITargetPlatform& Target)
    {
        if (!GEngine || !GEngine->UseStaticMeshMinLODPerQualityLevels)
            return PerPlatformInt(Settings, TEXT("MinLOD"), 0).GetValueForPlatform(*Target.IniPlatformName());

        FPerQualityLevelInt Value = Mesh.GetQualityLevelMinLOD();
        if (!Settings.TryGetNumberField(TEXT("MinQualityLevelLOD"), Value.Default))
        {
            const TSharedPtr<FJsonObject>* Object;
            if (Settings.TryGetObjectField(TEXT("MinQualityLevelLOD"), Object))
            {
                (*Object)->TryGetNumberField(TEXT("default"), Value.Default);
                const TSharedPtr<FJsonObject>* Overrides;
                if ((*Object)->TryGetObjectField(TEXT("overrides"), Overrides))
                    for (const auto& Entry : (*Overrides)->Values)
                        Value.PerQuality.Add(FCString::Atoi(*Entry.Key), static_cast<int32>(Entry.Value->AsNumber()));
            }
        }
        return Value.GetValueForPlatform(&Target);
    }

    bool StreamMode(const FJsonObject& Settings, EStaticMeshLODStreaming& Mode)
    {
        Mode = EStaticMeshLODStreaming::Default;
        if (!Settings.HasField(TEXT("LODStreaming"))) return true;
        int32 Number;
        if (Settings.TryGetNumberField(TEXT("LODStreaming"), Number))
        {
            if (Number < 0 || Number > static_cast<int32>(EStaticMeshLODStreaming::Disabled)) return false;
            Mode = static_cast<EStaticMeshLODStreaming>(Number);
            return true;
        }
        FString Name;
        if (!Settings.TryGetStringField(TEXT("LODStreaming"), Name)) return false;
        Name.RemoveFromStart(TEXT("EStaticMeshLODStreaming::"));
        if (Name == TEXT("Enabled")) Mode = EStaticMeshLODStreaming::Enabled;
        else if (Name == TEXT("Disabled")) Mode = EStaticMeshLODStreaming::Disabled;
        else if (Name != TEXT("Default")) return false;
        return true;
    }
}

bool ReadMeshLodCookPolicy(const FJsonObject& Settings, const UStaticMesh& Mesh, const ITargetPlatform& Target,
                           const FStaticMeshLODGroup& Group, int32 LodCount, FMeshLodCookPolicy& Out)
{
    if (LodCount < 1) return false;
    if (Target.HasEditorOnlyData()) return true;
    const int32 Minimum = FMath::Clamp(PlatformMinimum(Settings, Mesh, Target), 0, LodCount - 1);
    bool bNeverStream = false;
    Settings.TryGetBoolField(TEXT("NeverStream"), bNeverStream);
    EStaticMeshLODStreaming Mode;
    if (!StreamMode(Settings, Mode)) return false;
    const IConsoleVariable* Streaming = IConsoleManager::Get().FindConsoleVariable(TEXT("r.MeshStreaming"));
    const bool bCanStream = !bNeverStream && (!Streaming || Streaming->GetInt() != 0)
        && Target.SupportsFeature(ETargetPlatformFeatures::MeshLODStreaming)
        && (Mode == EStaticMeshLODStreaming::Enabled
            || (Mode == EStaticMeshLODStreaming::Default && Group.IsLODStreamingSupported()));
    if (bCanStream)
    {
        int32 Streamed = PerPlatformInt(Settings, TEXT("NumStreamedLODs"), -1).GetValueForPlatform(*Target.IniPlatformName());
        if (Streamed < 0) Streamed = Group.GetDefaultMaxNumStreamedLODs();
        Out.FirstInline = FMath::Max(Minimum, FMath::Clamp(Streamed, 0, LodCount - 1));
        Out.FirstAvailable = FMath::Max(0, Minimum - Group.GetDefaultMaxNumOptionalLODs());
        Out.FirstNonOptional = Minimum;
    }
    else
    {
        Out.FirstAvailable = Minimum;
    }
    return true;
}
