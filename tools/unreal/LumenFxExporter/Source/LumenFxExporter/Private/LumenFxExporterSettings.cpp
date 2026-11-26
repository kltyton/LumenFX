#include "LumenFxExporterSettings.h"
#include "Misc/Paths.h"

ULumenFxExporterSettings::ULumenFxExporterSettings()
{
    SectionName = TEXT("LumenFX Exporter");
}

FString ULumenFxExporterSettings::ResolveExportScriptPath() const
{
    if (LumenFxRepositoryRoot.Path.IsEmpty())
    {
        return FString();
    }
    return FPaths::Combine(LumenFxRepositoryRoot.Path, TEXT("tools"), TEXT("unreal"), TEXT("Export-UnrealAssets.ps1"));
}

FString ULumenFxExporterSettings::ResolveCompilerDllPath() const
{
    if (!CompilerDllPath.FilePath.IsEmpty())
    {
        return CompilerDllPath.FilePath;
    }
    if (LumenFxRepositoryRoot.Path.IsEmpty())
    {
        return FString();
    }
    return FPaths::Combine(LumenFxRepositoryRoot.Path, TEXT("tools"), TEXT("compiler"), TEXT("bin"),
        TEXT("Release"), TEXT("net10.0"), TEXT("LumenFx.Compiler.dll"));
}
