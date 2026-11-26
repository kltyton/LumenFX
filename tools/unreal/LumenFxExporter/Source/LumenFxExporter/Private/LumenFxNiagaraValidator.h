#pragma once

#include "CoreMinimal.h"

class UNiagaraSystem;
class ULumenFxExporterSettings;

enum class ELumenFxIssueSeverity : uint8
{
    Info,
    Warning,
    Error
};

struct FLumenFxValidationItem
{
    ELumenFxIssueSeverity Severity = ELumenFxIssueSeverity::Info;
    FString Context;
    FString Message;
};

struct FLumenFxValidationSummary
{
    int32 EnabledEmitterCount = 0;
    int32 CpuEmitterCount = 0;
    int32 GpuEmitterCount = 0;
    int32 SpriteRendererCount = 0;
    int32 RibbonRendererCount = 0;
    int32 MeshRendererCount = 0;
    int32 OtherRendererCount = 0;
    TArray<FLumenFxValidationItem> Items;

    bool HasErrors() const
    {
        for (const FLumenFxValidationItem& Item : Items)
        {
            if (Item.Severity == ELumenFxIssueSeverity::Error) return true;
        }
        return false;
    }
};

class FLumenFxNiagaraValidator
{
public:
    static FLumenFxValidationSummary Validate(
        UNiagaraSystem* System,
        const FString& Namespace,
        const FString& EffectName,
        const FString& OutputDirectory,
        const ULumenFxExporterSettings* Settings);

    static FString SanitizeId(const FString& Raw);
};
