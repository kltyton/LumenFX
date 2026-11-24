#include "SoundRoutingExport.h"
#include "SoundSubmixExport.h"
#include "Dom/JsonObject.h"
#include "Misc/FileHelper.h"
#include "Serialization/JsonSerializer.h"
#include "Sound/AudioSettings.h"
#include "Sound/SoundBase.h"
#include "Sound/SoundClass.h"
#include "Sound/SoundMix.h"
#include "Sound/SoundSubmix.h"

namespace LumenFxAudioExport
{
namespace
{
    void SetObjectPath(FJsonObject& Result, const TCHAR* Key, const UObject* Object)
    {
        if (Object) Result.SetStringField(Key, Object->GetPathName());
    }

    TSharedRef<FJsonObject> DescribeMix(const USoundMix& Mix)
    {
        TSharedRef<FJsonObject> Result = MakeShared<FJsonObject>();
        SetObjectPath(*Result, TEXT("path"), &Mix);
        Result->SetNumberField(TEXT("delay"), Mix.InitialDelay);
        Result->SetNumberField(TEXT("fade_in"), Mix.FadeInTime);
        Result->SetNumberField(TEXT("duration"), Mix.Duration);
        Result->SetNumberField(TEXT("fade_out"), Mix.FadeOutTime);
        Result->SetNumberField(TEXT("eq_priority"), Mix.EQPriority);
        TArray<TSharedPtr<FJsonValue>> Adjustments;
        for (const FSoundClassAdjuster& Adjustment : Mix.SoundClassEffects)
        {
            if (!Adjustment.SoundClassObject) continue;
            TSharedRef<FJsonObject> Entry = MakeShared<FJsonObject>();
            SetObjectPath(*Entry, TEXT("class"), Adjustment.SoundClassObject);
            Entry->SetNumberField(TEXT("volume"), Adjustment.VolumeAdjuster);
            Entry->SetNumberField(TEXT("pitch"), Adjustment.PitchAdjuster);
            Entry->SetNumberField(TEXT("low_pass"), Adjustment.LowPassFilterFrequency);
            Entry->SetNumberField(TEXT("center_volume"), Adjustment.VoiceCenterChannelVolumeAdjuster);
            Entry->SetBoolField(TEXT("children"), Adjustment.bApplyToChildren);
            Adjustments.Add(MakeShared<FJsonValueObject>(Entry));
        }
        Result->SetArrayField(TEXT("adjustments"), MoveTemp(Adjustments));
        if (Mix.bApplyEQ)
        {
            const FAudioEQEffect& Eq = Mix.EQSettings;
            const float Bands[][3] = {
                {Eq.FrequencyCenter0, Eq.Gain0, Eq.Bandwidth0}, {Eq.FrequencyCenter1, Eq.Gain1, Eq.Bandwidth1},
                {Eq.FrequencyCenter2, Eq.Gain2, Eq.Bandwidth2}, {Eq.FrequencyCenter3, Eq.Gain3, Eq.Bandwidth3}
            };
            TArray<TSharedPtr<FJsonValue>> EqBands;
            for (const auto& Band : Bands)
            {
                TSharedRef<FJsonObject> Entry = MakeShared<FJsonObject>();
                Entry->SetNumberField(TEXT("frequency"), Band[0]);
                Entry->SetNumberField(TEXT("gain"), Band[1]);
                Entry->SetNumberField(TEXT("bandwidth"), Band[2]);
                EqBands.Add(MakeShared<FJsonValueObject>(Entry));
            }
            Result->SetArrayField(TEXT("eq"), MoveTemp(EqBands));
        }
        return Result;
    }

    bool DescribeClass(const USoundClass* SoundClass, TSharedRef<FJsonObject> Result, TSet<const USoundClass*>& Visited)
    {
        if (Visited.Contains(SoundClass))
        {
            UE_LOG(LogTemp, Error, TEXT("Cyclic sound class parents at %s"), *SoundClass->GetPathName());
            return false;
        }
        Visited.Add(SoundClass);
        const FSoundClassProperties& Properties = SoundClass->Properties;
        SetObjectPath(*Result, TEXT("path"), SoundClass);
        Result->SetNumberField(TEXT("volume"), Properties.Volume);
        Result->SetNumberField(TEXT("pitch"), Properties.Pitch);
        Result->SetNumberField(TEXT("low_pass"), Properties.LowPassFilterFrequency);
        Result->SetNumberField(TEXT("attenuation_distance_scale"), Properties.AttenuationDistanceScale);
        Result->SetNumberField(TEXT("lfe_bleed"), Properties.LFEBleed);
        Result->SetNumberField(TEXT("center_volume"), Properties.VoiceCenterChannelVolume);
        Result->SetNumberField(TEXT("radio_volume"), Properties.RadioFilterVolume);
        Result->SetNumberField(TEXT("radio_threshold"), Properties.RadioFilterVolumeThreshold);
        Result->SetNumberField(TEXT("reverb_send"), Properties.Default2DReverbSendAmount);
        Result->SetNumberField(TEXT("output_target"), static_cast<uint8>(Properties.OutputTarget.GetValue()));
        Result->SetNumberField(TEXT("loading"), static_cast<uint8>(Properties.LoadingBehavior));
        Result->SetBoolField(TEXT("master_eq"), Properties.bApplyEffects);
        Result->SetBoolField(TEXT("always_play"), Properties.bAlwaysPlay);
        Result->SetBoolField(TEXT("ui_sound"), Properties.bIsUISound);
        Result->SetBoolField(TEXT("music"), Properties.bIsMusic);
        Result->SetBoolField(TEXT("center_only"), Properties.bCenterChannelOnly);
        Result->SetBoolField(TEXT("ambient_volumes"), Properties.bApplyAmbientVolumes);
        Result->SetBoolField(TEXT("reverb"), Properties.bReverb);
        SetObjectPath(*Result, TEXT("submix"), Properties.DefaultSubmix);
        if (!DescribeSubmix(*Result, TEXT("submix_graph"), Properties.DefaultSubmix)) return false;
        TArray<TSharedPtr<FJsonValue>> PassiveMixes;
        for (const FPassiveSoundMixModifier& Modifier : SoundClass->PassiveSoundMixModifiers)
        {
            if (!Modifier.SoundMix) continue;
            TSharedRef<FJsonObject> Entry = MakeShared<FJsonObject>();
            Entry->SetNumberField(TEXT("minimum"), Modifier.MinVolumeThreshold);
            Entry->SetNumberField(TEXT("maximum"), Modifier.MaxVolumeThreshold);
            Entry->SetObjectField(TEXT("mix"), DescribeMix(*Modifier.SoundMix.Get()));
            PassiveMixes.Add(MakeShared<FJsonValueObject>(Entry));
        }
        Result->SetArrayField(TEXT("passive_mixes"), MoveTemp(PassiveMixes));
        if (const USoundClass* Parent = SoundClass->ParentClass)
        {
            const bool bPropagates = Parent->ChildClasses.ContainsByPredicate(
                [SoundClass](const TObjectPtr<USoundClass>& Child) { return Child.Get() == SoundClass; });
            Result->SetBoolField(TEXT("inherit_runtime"), bPropagates);
            TSharedRef<FJsonObject> ParentData = MakeShared<FJsonObject>();
            if (!DescribeClass(Parent, ParentData, Visited)) return false;
            Result->SetObjectField(TEXT("parent"), ParentData);
        }
        return true;
    }
}

    bool ExportRouting(const FJsonObject& Request, const FString& Output, bool bClassOnly)
    {
        FString Path;
        if (!Request.TryGetStringField(bClassOnly ? TEXT("sound_class") : TEXT("sound"), Path)) return false;
        const USoundBase* Sound = bClassOnly ? nullptr : LoadObject<USoundBase>(nullptr, *Path);
        const USoundClass* SoundClass = bClassOnly ? LoadObject<USoundClass>(nullptr, *Path)
            : Sound ? Sound->GetSoundClass() : nullptr;
        if (bClassOnly ? !SoundClass : !Sound) return false;
        const UAudioSettings* Settings = bClassOnly ? nullptr : GetDefault<UAudioSettings>();
        if (!SoundClass && Settings)
        {
            // This headless exporter has no audio device to load the default objects before mounts are registered.
            SoundClass = Cast<USoundClass>(Settings->DefaultSoundClassName.TryLoad());
            if (!SoundClass)
                SoundClass = LoadObject<USoundClass>(nullptr, TEXT("/Engine/EngineSounds/Master.Master"));
        }
        TSharedRef<FJsonObject> Result = MakeShared<FJsonObject>();
        TSet<const USoundClass*> Visited;
        if (bClassOnly)
        {
            if (!DescribeClass(SoundClass, Result, Visited)) return false;
        }
        else
        {
            if (SoundClass)
            {
                TSharedRef<FJsonObject> ClassData = MakeShared<FJsonObject>();
                if (!DescribeClass(SoundClass, ClassData, Visited)) return false;
                Result->SetObjectField(TEXT("class"), ClassData);
            }
            SetObjectPath(*Result, TEXT("submix"), Sound->GetSoundSubmix());
            if (!DescribeSubmix(*Result, TEXT("submix_graph"), Sound->GetSoundSubmix())) return false;
            if (Settings)
            {
                Result->SetNumberField(TEXT("minimum_pitch"), FMath::Max(Settings->GlobalMinPitchScale, 0.0001f));
                Result->SetNumberField(TEXT("maximum_pitch"), FMath::Max(Settings->GlobalMaxPitchScale, 0.0001f));
                if (Settings->EQSubmix.IsValid()) Result->SetStringField(TEXT("eq_submix"), Settings->EQSubmix.ToString());
                if (Settings->BaseDefaultSubmix.IsValid())
                    Result->SetStringField(TEXT("base_submix"), Settings->BaseDefaultSubmix.ToString());
                else if (Settings->MasterSubmix.IsValid())
                    Result->SetStringField(TEXT("base_submix"), Settings->MasterSubmix.ToString());
                if (Settings->ReverbSubmix.IsValid())
                    Result->SetStringField(TEXT("reverb_submix"), Settings->ReverbSubmix.ToString());
                if (!DescribeSubmix(*Result, TEXT("master_submix_graph"), Cast<USoundSubmix>(Settings->MasterSubmix.TryLoad()))
                    || !DescribeSubmix(*Result, TEXT("base_submix_graph"), Cast<USoundSubmix>(Settings->BaseDefaultSubmix.TryLoad()))
                    || !DescribeSubmix(*Result, TEXT("eq_submix_graph"), Cast<USoundSubmix>(Settings->EQSubmix.TryLoad()))
                    || !DescribeSubmix(*Result, TEXT("reverb_submix_graph"), Cast<USoundSubmix>(Settings->ReverbSubmix.TryLoad())))
                    return false;
                if (Settings->DefaultBaseSoundMix.IsValid())
                {
                    const FString MixPath = Settings->DefaultBaseSoundMix.ToString();
                    const USoundMix* BaseMix = LoadObject<USoundMix>(nullptr, *MixPath);
                    if (BaseMix) Result->SetObjectField(TEXT("base_mix"), DescribeMix(*BaseMix));
                }
            }
        }
        FString Json;
        auto Writer = TJsonWriterFactory<>::Create(&Json);
        return FJsonSerializer::Serialize(Result, Writer)
            && FFileHelper::SaveStringToFile(Json, *Output, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM);
    }
}
