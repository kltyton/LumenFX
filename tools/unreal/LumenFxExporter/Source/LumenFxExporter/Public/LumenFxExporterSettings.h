#pragma once

#include "CoreMinimal.h"
#include "Engine/DeveloperSettings.h"
#include "LumenFxExporterSettings.generated.h"

/**
 * Per-user project settings configuring local LumenFX toolchain paths and default Minecraft resource-pack options.
 */
UCLASS(Config = EditorPerProjectUserSettings, DefaultConfig, meta = (DisplayName = "LumenFX Exporter"))
class LUMENFXEXPORTER_API ULumenFxExporterSettings : public UDeveloperSettings
{
    GENERATED_BODY()

public:
    ULumenFxExporterSettings();

    virtual FName GetCategoryName() const override { return TEXT("Plugins"); }

    /** Root directory of the LumenFX repository containing tools/compiler and tools/unreal. */
    UPROPERTY(Config, EditAnywhere, Category = "Toolchain")
    FDirectoryPath LumenFxRepositoryRoot;

    /** Path to built LumenFx.Compiler.dll (defaults to <Repo>/tools/compiler/bin/Release/net10.0/LumenFx.Compiler.dll). */
    UPROPERTY(Config, EditAnywhere, Category = "Toolchain", meta = (FilePathFilter = "dll"))
    FFilePath CompilerDllPath;

    /** Path to UAssetExport.dll used by Export-UnrealAssets.ps1. */
    UPROPERTY(Config, EditAnywhere, Category = "Toolchain", meta = (FilePathFilter = "dll"))
    FFilePath UAssetExportDllPath;

    /** Optional Unreal Engine source root matching the active editor build for Niagara shader/material compilation. */
    UPROPERTY(Config, EditAnywhere, Category = "Toolchain")
    FDirectoryPath UnrealSourceRoot;

    /** Dotnet CLI executable (defaults to "dotnet" on PATH). */
    UPROPERTY(Config, EditAnywhere, Category = "Toolchain")
    FString DotNetExecutable = TEXT("dotnet");

    /** Default Minecraft resource-pack namespace (lowercase a-z, 0-9, _, -). */
    UPROPERTY(Config, EditAnywhere, Category = "Minecraft Output")
    FString DefaultNamespace = TEXT("lumenfx");

    /** Default Minecraft resource-pack output root directory. */
    UPROPERTY(Config, EditAnywhere, Category = "Minecraft Output")
    FDirectoryPath DefaultOutputDirectory;

    /** Automatically write pack.mcmeta so the output folder can be dropped straight into .minecraft/resourcepacks. */
    UPROPERTY(Config, EditAnywhere, Category = "Minecraft Output")
    bool bGeneratePackMcmeta = true;

    /** Minecraft pack_format written into generated pack.mcmeta (default 55 for modern packs; adjustable per MC version). */
    UPROPERTY(Config, EditAnywhere, Category = "Minecraft Output", meta = (ClampMin = "15", ClampMax = "99"))
    int32 DefaultPackFormat = 55;

    FString ResolveExportScriptPath() const;
    FString ResolveCompilerDllPath() const;
};
