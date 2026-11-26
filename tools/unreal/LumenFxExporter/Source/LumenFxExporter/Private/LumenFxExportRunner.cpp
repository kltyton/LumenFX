#include "LumenFxExportRunner.h"
#include "LumenFxExporterSettings.h"
#include "Async/Async.h"
#include "HAL/FileManager.h"
#include "HAL/PlatformProcess.h"
#include "Misc/FileHelper.h"
#include "Misc/Paths.h"

static FString QuoteArg(const FString& Value)
{
    return FString::Printf(TEXT("\"%s\""), *Value.Replace(TEXT("\""), TEXT("\\\"")));
}

bool FLumenFxExportRunner::RunProcessAndStream(
    const FString& Executable,
    const FString& Arguments,
    const FString& WorkingDir,
    const FOnLogLine& OnLog,
    int32& OutReturnCode)
{
    void* ReadPipe = nullptr;
    void* WritePipe = nullptr;
    FPlatformProcess::CreatePipe(ReadPipe, WritePipe);

    FProcHandle Proc = FPlatformProcess::CreateProc(
        *Executable,
        *Arguments,
        false,
        true,
        true,
        nullptr,
        0,
        WorkingDir.IsEmpty() ? nullptr : *WorkingDir,
        WritePipe,
        nullptr);

    if (!Proc.IsValid())
    {
        FPlatformProcess::ClosePipe(ReadPipe, WritePipe);
        return false;
    }

    auto EmitOutput = [&]()
    {
        FString Chunk = FPlatformProcess::ReadPipe(ReadPipe);
        if (!Chunk.IsEmpty())
        {
            TArray<FString> Lines;
            Chunk.ParseIntoArrayLines(Lines, false);
            for (const FString& Line : Lines)
            {
                AsyncTask(ENamedThreads::GameThread, [OnLog, Line]()
                {
                    OnLog.ExecuteIfBound(Line);
                });
            }
        }
    };

    while (FPlatformProcess::IsProcRunning(Proc))
    {
        EmitOutput();
        FPlatformProcess::Sleep(0.05f);
    }
    EmitOutput();

    FPlatformProcess::GetProcReturnCode(Proc, &OutReturnCode);
    FPlatformProcess::CloseProc(Proc);
    FPlatformProcess::ClosePipe(ReadPipe, WritePipe);
    return true;
}

void FLumenFxExportRunner::WritePackMcmetaIfMissing(const FString& OutputDirectory, int32 PackFormat, const FOnLogLine& OnLog)
{
    const FString MetaPath = FPaths::Combine(OutputDirectory, TEXT("pack.mcmeta"));
    if (FPaths::FileExists(MetaPath))
    {
        return;
    }
    const FString Content = FString::Printf(
        TEXT("{\n  \"pack\": {\n    \"pack_format\": %d,\n    \"description\": \"LumenFX Unreal Niagara Exported Pack\"\n  }\n}\n"),
        PackFormat);
    if (FFileHelper::SaveStringToFile(Content, *MetaPath, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM))
    {
        AsyncTask(ENamedThreads::GameThread, [OnLog, MetaPath]()
        {
            OnLog.ExecuteIfBound(FString::Printf(TEXT("[LumenFX] Generated %s"), *MetaPath));
        });
    }
}

void FLumenFxExportRunner::RunAsync(
    const FLumenFxExportRequest& Request,
    const ULumenFxExporterSettings* Settings,
    FOnLogLine OnLog,
    FOnCompleted OnDone)
{
    const FString RepoRoot = Settings ? Settings->LumenFxRepositoryRoot.Path : FString();
    const FString ExportScript = Settings ? Settings->ResolveExportScriptPath() : FString();
    const FString CompilerDll = Settings ? Settings->ResolveCompilerDllPath() : FString();
    const FString UAssetExportDll = Settings ? Settings->UAssetExportDllPath.FilePath : FString();
    const FString UnrealSourceRoot = Settings ? Settings->UnrealSourceRoot.Path : FString();
    const FString DotNetExe = (Settings && !Settings->DotNetExecutable.IsEmpty()) ? Settings->DotNetExecutable : TEXT("dotnet");

    Async(EAsyncExecution::ThreadPool, [=]()
    {
        auto Log = [OnLog](const FString& Line)
        {
            AsyncTask(ENamedThreads::GameThread, [OnLog, Line]() { OnLog.ExecuteIfBound(Line); });
        };
        auto Finish = [OnDone](bool bOk, const FString& Summary)
        {
            AsyncTask(ENamedThreads::GameThread, [OnDone, bOk, Summary]() { OnDone.ExecuteIfBound(bOk, Summary); });
        };

        const FString WorkDir = FPaths::Combine(FPaths::ProjectSavedDir(), TEXT("LumenFX"), TEXT("ExportWork"),
            FGuid::NewGuid().ToString(EGuidFormats::Digits));
        const FString JsonRoot = FPaths::Combine(WorkDir, TEXT("uasset_json"));
        const FString PngRoot = FPaths::Combine(WorkDir, TEXT("png"));
        const FString VfRoot = FPaths::Combine(WorkDir, TEXT("vector_fields"));
        const FString TempRoot = FPaths::Combine(WorkDir, TEXT("tmp"));
        const FString AliasesFile = FPaths::Combine(WorkDir, TEXT("aliases.json"));
        const FString ProfileFile = FPaths::Combine(WorkDir, TEXT("render-profile.json"));

        IFileManager::Get().MakeDirectory(*JsonRoot, true);
        IFileManager::Get().MakeDirectory(*PngRoot, true);
        IFileManager::Get().MakeDirectory(*VfRoot, true);
        IFileManager::Get().MakeDirectory(*TempRoot, true);
        IFileManager::Get().MakeDirectory(*Request.OutputDirectory, true);
        FFileHelper::SaveStringToFile(TEXT("{}"), *AliasesFile, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM);
        FFileHelper::SaveStringToFile(TEXT("{}"), *ProfileFile, FFileHelper::EEncodingOptions::ForceUTF8WithoutBOM);

        // Extract package path without object suffix for Export-UnrealAssets.ps1
        FString PackagePath = Request.PackageObjectPath;
        int32 DotIndex = INDEX_NONE;
        if (PackagePath.FindLastChar(TEXT('.'), DotIndex))
        {
            PackagePath = PackagePath.Left(DotIndex);
        }

        Log(FString::Printf(TEXT("[LumenFX] Step 1/2: Exporting Unreal package metadata for %s ..."), *PackagePath));
        const FString PsArgs = FString::Printf(
            TEXT("-NoProfile -ExecutionPolicy Bypass -File %s -SourceContent %s -JsonRoot %s -Packages %s -UAssetExportDll %s -TemporaryDirectory %s -Dotnet %s"),
            *QuoteArg(ExportScript),
            *QuoteArg(Request.ProjectContentDir),
            *QuoteArg(JsonRoot),
            *QuoteArg(PackagePath),
            *QuoteArg(UAssetExportDll),
            *QuoteArg(TempRoot),
            *QuoteArg(DotNetExe));

        int32 ExitCode = -1;
        if (!RunProcessAndStream(TEXT("pwsh"), PsArgs, RepoRoot, OnLog, ExitCode) || ExitCode != 0)
        {
            // Fallback to Windows PowerShell if pwsh is not on PATH
            if (ExitCode == -1)
            {
                if (!RunProcessAndStream(TEXT("powershell.exe"), PsArgs, RepoRoot, OnLog, ExitCode) || ExitCode != 0)
                {
                    Finish(false, FString::Printf(TEXT("Export-UnrealAssets.ps1 failed with exit code %d."), ExitCode));
                    return;
                }
            }
            else
            {
                Finish(false, FString::Printf(TEXT("Export-UnrealAssets.ps1 failed with exit code %d."), ExitCode));
                return;
            }
        }

        const FString EffectId = FString::Printf(TEXT("%s:effects/%s"), *Request.Namespace, *Request.EffectName);
        Log(FString::Printf(TEXT("[LumenFX] Step 2/2: Compiling Niagara asset -> %s ..."), *EffectId));
        const FString CompilerArgs = FString::Printf(
            TEXT("%s niagara %s %s %s %s %s %s %s %s %s"),
            *QuoteArg(CompilerDll),
            *QuoteArg(JsonRoot),
            *QuoteArg(Request.PackageObjectPath),
            *QuoteArg(EffectId),
            *QuoteArg(PngRoot),
            *QuoteArg(Request.OutputDirectory),
            *QuoteArg(AliasesFile),
            *QuoteArg(ProfileFile),
            *QuoteArg(VfRoot),
            *QuoteArg(UnrealSourceRoot.IsEmpty() ? FPaths::EngineDir() : UnrealSourceRoot));

        ExitCode = -1;
        if (!RunProcessAndStream(DotNetExe, CompilerArgs, RepoRoot, OnLog, ExitCode) || ExitCode != 0)
        {
            Finish(false, FString::Printf(TEXT("LumenFx.Compiler exited with code %d. Check the log above for details."), ExitCode));
            return;
        }

        if (Request.bGeneratePackMcmeta)
        {
            WritePackMcmetaIfMissing(Request.OutputDirectory, Request.PackFormat, OnLog);
        }

        Finish(true, FString::Printf(TEXT("Successfully exported %s to %s"), *EffectId, *Request.OutputDirectory));
    });
}
