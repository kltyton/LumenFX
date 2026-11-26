#include "LumenFxNiagaraValidator.h"
#include "LumenFxExporterSettings.h"
#include "Misc/Paths.h"
#include "NiagaraEmitter.h"
#include "NiagaraEmitterHandle.h"
#include "NiagaraLightRendererProperties.h"
#include "NiagaraMeshRendererProperties.h"
#include "NiagaraRibbonRendererProperties.h"
#include "NiagaraSpriteRendererProperties.h"
#include "NiagaraSystem.h"

static bool IsValidMinecraftIdSegment(const FString& Value, bool bAllowSlash)
{
    if (Value.IsEmpty()) return false;
    for (TCHAR Ch : Value)
    {
        bool bOk = (Ch >= 'a' && Ch <= 'z') || (Ch >= '0' && Ch <= '9') || Ch == '_' || Ch == '-' || Ch == '.';
        if (bAllowSlash && Ch == '/') bOk = true;
        if (!bOk) return false;
    }
    return true;
}

FString FLumenFxNiagaraValidator::SanitizeId(const FString& Raw)
{
    FString Lower = Raw.ToLower();
    FString Out;
    Out.Reserve(Lower.Len());
    for (TCHAR Ch : Lower)
    {
        if ((Ch >= 'a' && Ch <= 'z') || (Ch >= '0' && Ch <= '9') || Ch == '_' || Ch == '-' || Ch == '.')
        {
            Out.AppendChar(Ch);
        }
        else
        {
            Out.AppendChar('_');
        }
    }
    Out.TrimCharInline('_', nullptr);
    return Out.IsEmpty() ? TEXT("effect") : Out;
}

FLumenFxValidationSummary FLumenFxNiagaraValidator::Validate(
    UNiagaraSystem* System,
    const FString& Namespace,
    const FString& EffectName,
    const FString& OutputDirectory,
    const ULumenFxExporterSettings* Settings)
{
    FLumenFxValidationSummary Summary;

    if (!System)
    {
        Summary.Items.Add({ ELumenFxIssueSeverity::Error, TEXT("Asset"), TEXT("No UNiagaraSystem asset selected.") });
        return Summary;
    }

    if (!IsValidMinecraftIdSegment(Namespace, false))
    {
        Summary.Items.Add({ ELumenFxIssueSeverity::Error, TEXT("Namespace"),
            FString::Printf(TEXT("Invalid Minecraft namespace '%s' (use lowercase a-z, 0-9, _, -)."), *Namespace) });
    }
    if (!IsValidMinecraftIdSegment(EffectName, true))
    {
        Summary.Items.Add({ ELumenFxIssueSeverity::Error, TEXT("EffectName"),
            FString::Printf(TEXT("Invalid effect path '%s' (use lowercase a-z, 0-9, _, -, /)."), *EffectName) });
    }
    if (OutputDirectory.IsEmpty())
    {
        Summary.Items.Add({ ELumenFxIssueSeverity::Error, TEXT("Output"), TEXT("Output directory must be set.") });
    }

    if (!Settings)
    {
        Summary.Items.Add({ ELumenFxIssueSeverity::Error, TEXT("Settings"), TEXT("LumenFX Exporter settings unavailable.") });
    }
    else
    {
        const FString ExportScript = Settings->ResolveExportScriptPath();
        const FString CompilerDll = Settings->ResolveCompilerDllPath();
        if (ExportScript.IsEmpty() || !FPaths::FileExists(ExportScript))
        {
            Summary.Items.Add({ ELumenFxIssueSeverity::Error, TEXT("Toolchain"),
                TEXT("LumenFX repository root is not configured or tools/unreal/Export-UnrealAssets.ps1 was not found. Configure in Project Settings -> Plugins -> LumenFX Exporter.") });
        }
        if (CompilerDll.IsEmpty() || !FPaths::FileExists(CompilerDll))
        {
            Summary.Items.Add({ ELumenFxIssueSeverity::Error, TEXT("Toolchain"),
                TEXT("LumenFx.Compiler.dll not found. Build tools/compiler/LumenFx.Compiler.csproj first or set its path in Project Settings.") });
        }
        if (Settings->UAssetExportDllPath.FilePath.IsEmpty() || !FPaths::FileExists(Settings->UAssetExportDllPath.FilePath))
        {
            Summary.Items.Add({ ELumenFxIssueSeverity::Warning, TEXT("Toolchain"),
                TEXT("UAssetExport.dll path is empty or missing. Set it in Project Settings if exporting directly from .uasset packages.") });
        }
    }

    for (const FNiagaraEmitterHandle& Handle : System->GetEmitterHandles())
    {
        if (!Handle.GetIsEnabled()) continue;
        Summary.EnabledEmitterCount++;
        const FVersionedNiagaraEmitterData* Data = Handle.GetEmitterData();
        if (!Data) continue;

        if (Data->SimTarget == ENiagaraSimTarget::GPUComputeSim)
        {
            Summary.GpuEmitterCount++;
        }
        else
        {
            Summary.CpuEmitterCount++;
        }

        for (UNiagaraRendererProperties* Renderer : Data->GetRenderers())
        {
            if (!Renderer || !Renderer->GetIsEnabled()) continue;
            if (Renderer->IsA<UNiagaraSpriteRendererProperties>()) Summary.SpriteRendererCount++;
            else if (Renderer->IsA<UNiagaraRibbonRendererProperties>()) Summary.RibbonRendererCount++;
            else if (Renderer->IsA<UNiagaraMeshRendererProperties>()) Summary.MeshRendererCount++;
            else Summary.OtherRendererCount++;
        }
    }

    if (Summary.EnabledEmitterCount == 0)
    {
        Summary.Items.Add({ ELumenFxIssueSeverity::Error, System->GetName(), TEXT("Niagara System has zero enabled emitters.") });
    }
    else
    {
        Summary.Items.Add({ ELumenFxIssueSeverity::Info, System->GetName(),
            FString::Printf(TEXT("Detected %d enabled emitter(s): %d CPU, %d GPU | Renderers: %d Sprite, %d Ribbon, %d Mesh, %d Other."),
                Summary.EnabledEmitterCount, Summary.CpuEmitterCount, Summary.GpuEmitterCount,
                Summary.SpriteRendererCount, Summary.RibbonRendererCount, Summary.MeshRendererCount, Summary.OtherRendererCount) });
    }

    if (Summary.GpuEmitterCount > 0 && Settings && Settings->UnrealSourceRoot.Path.IsEmpty())
    {
        Summary.Items.Add({ ELumenFxIssueSeverity::Warning, TEXT("GPU Emitters"),
            TEXT("System contains GPU compute emitters. Set UnrealSourceRoot in Project Settings to enable shader baking.") });
    }

    Summary.Items.Add({ ELumenFxIssueSeverity::Info, TEXT("Scale"),
        TEXT("Minecraft scale reminder: 100 Unreal cm = 1 Minecraft block. LumenFX handles UE->MC axis & unit conversion automatically.") });

    return Summary;
}
