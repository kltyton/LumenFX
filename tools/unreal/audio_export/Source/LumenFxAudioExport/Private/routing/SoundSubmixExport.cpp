#include "SoundSubmixExport.h"
#include "Dom/JsonObject.h"
#include "Sound/SoundEffectSubmix.h"
#include "Sound/SoundSubmix.h"

namespace LumenFxAudioExport
{
namespace
{
    bool BuildSubmix(const USoundSubmixBase& Submix, FJsonObject& Result, TSet<const USoundSubmixBase*>& Visited)
    {
        if (Visited.Contains(&Submix))
        {
            UE_LOG(LogTemp, Error, TEXT("Cyclic sound submix parents at %s"), *Submix.GetPathName());
            return false;
        }
        Visited.Add(&Submix);
        Result.SetStringField(TEXT("path"), Submix.GetPathName());
        Result.SetStringField(TEXT("class"), Submix.GetClass()->GetPathName());
        if (const USoundSubmixWithParentBase* WithParent = Cast<USoundSubmixWithParentBase>(&Submix))
        {
            Result.SetBoolField(TEXT("dynamic"), WithParent->IsDynamic(false));
            if (const USoundSubmixBase* Parent = WithParent->ParentSubmix)
            {
                TSharedRef<FJsonObject> ParentData = MakeShared<FJsonObject>();
                if (!BuildSubmix(*Parent, *ParentData, Visited)) return false;
                Result.SetObjectField(TEXT("parent"), ParentData);
            }
        }
        if (const USoundSubmix* WaveSubmix = Cast<USoundSubmix>(&Submix))
        {
            const auto& Output = WaveSubmix->OutputVolumeModulation;
            const auto& Wet = WaveSubmix->WetLevelModulation;
            const auto& Dry = WaveSubmix->DryLevelModulation;
            Result.SetNumberField(TEXT("output_db"), Output.Value);
            Result.SetNumberField(TEXT("wet_db"), Wet.Value);
            Result.SetNumberField(TEXT("dry_db"), Dry.Value);
            Result.SetBoolField(TEXT("modulated"), !Output.Modulators.IsEmpty() || !Wet.Modulators.IsEmpty() || !Dry.Modulators.IsEmpty());
            TArray<TSharedPtr<FJsonValue>> Effects;
            for (const USoundEffectSubmixPreset* Effect : WaveSubmix->SubmixEffectChain)
            {
                if (!Effect) continue;
                TSharedRef<FJsonObject> Preset = MakeShared<FJsonObject>();
                Preset->SetStringField(TEXT("path"), Effect->GetPathName());
                Preset->SetStringField(TEXT("class"), Effect->GetClass()->GetPathName());
                Effects.Add(MakeShared<FJsonValueObject>(Preset));
            }
            Result.SetArrayField(TEXT("effects"), MoveTemp(Effects));
        }
        return true;
    }
}

    bool DescribeSubmix(FJsonObject& Owner, const TCHAR* Field, const USoundSubmixBase* Submix)
    {
        if (!Submix) return true;
        TSharedRef<FJsonObject> Result = MakeShared<FJsonObject>();
        TSet<const USoundSubmixBase*> Visited;
        if (!BuildSubmix(*Submix, *Result, Visited)) return false;
        Owner.SetObjectField(Field, Result);
        return true;
    }
}
