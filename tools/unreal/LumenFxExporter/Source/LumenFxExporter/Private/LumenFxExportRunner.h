#pragma once

#include "CoreMinimal.h"

class UNiagaraSystem;
class ULumenFxExporterSettings;

struct FLumenFxExportRequest
{
    FString PackageObjectPath;
    FString ProjectContentDir;
    FString Namespace;
    FString EffectName;
    FString OutputDirectory;
    bool bGeneratePackMcmeta = true;
    int32 PackFormat = 55;
};

class FLumenFxExportRunner
{
public:
    DECLARE_DELEGATE_OneParam(FOnLogLine, const FString&);
    DECLARE_DELEGATE_TwoParams(FOnCompleted, bool /*bSuccess*/, const FString& /*Summary*/);

    static void RunAsync(
        const FLumenFxExportRequest& Request,
        const ULumenFxExporterSettings* Settings,
        FOnLogLine OnLog,
        FOnCompleted OnDone);

private:
    static bool RunProcessAndStream(
        const FString& Executable,
        const FString& Arguments,
        const FString& WorkingDir,
        const FOnLogLine& OnLog,
        int32& OutReturnCode);

    static void WritePackMcmetaIfMissing(const FString& OutputDirectory, int32 PackFormat, const FOnLogLine& OnLog);
};
