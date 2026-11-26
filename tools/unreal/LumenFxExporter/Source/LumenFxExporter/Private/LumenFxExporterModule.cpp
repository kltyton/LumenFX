#include "LumenFxExporterModule.h"
#include "SLumenFxExportDialog.h"
#include "ContentBrowserMenuContexts.h"
#include "Framework/Application/SlateApplication.h"
#include "NiagaraSystem.h"
#include "ToolMenus.h"
#include "Widgets/SWindow.h"

#define LOCTEXT_NAMESPACE "FLumenFxExporterModule"

void FLumenFxExporterModule::StartupModule()
{
    UToolMenus::RegisterStartupCallback(
        FSimpleMulticastDelegate::FDelegate::CreateRaw(this, &FLumenFxExporterModule::RegisterMenus));
}

void FLumenFxExporterModule::ShutdownModule()
{
    UToolMenus::UnRegisterStartupCallback(this);
    UToolMenus::UnregisterOwner(this);
}

void FLumenFxExporterModule::RegisterMenus()
{
    FToolMenuOwnerScoped OwnerScoped(this);

    // Content Browser right-click context menu on UNiagaraSystem assets
    if (UToolMenu* AssetMenu = UToolMenus::Get()->ExtendMenu("ContentBrowser.AssetContextMenu.NiagaraSystem"))
    {
        FToolMenuSection& Section = AssetMenu->FindOrAddSection("GetAssetActions");
        Section.AddDynamicEntry("LumenFxExportAction", FNewToolMenuSectionDelegate::CreateLambda([](FToolMenuSection& InSection)
        {
            const UContentBrowserAssetContextMenuContext* Context = InSection.FindContext<UContentBrowserAssetContextMenuContext>();
            if (!Context || Context->SelectedAssets.IsEmpty())
            {
                return;
            }
            InSection.AddMenuEntry(
                "ExportToLumenFx",
                LOCTEXT("ExportToLumenFxLabel", "Export to LumenFX (Minecraft)..."),
                LOCTEXT("ExportToLumenFxTooltip", "Validate and export this Niagara System into a LumenFX Minecraft resource pack."),
                FSlateIcon(),
                FUIAction(FExecuteAction::CreateLambda([Selected = Context->SelectedAssets]()
                {
                    for (const FAssetData& Data : Selected)
                    {
                        if (UNiagaraSystem* System = Cast<UNiagaraSystem>(Data.GetAsset()))
                        {
                            OpenExportDialog(System);
                            break;
                        }
                    }
                }))
            );
        }));
    }

    // Level Editor Tools top menu entry
    if (UToolMenu* ToolsMenu = UToolMenus::Get()->ExtendMenu("LevelEditor.MainMenu.Tools"))
    {
        FToolMenuSection& Section = ToolsMenu->FindOrAddSection("Programming");
        Section.AddMenuEntry(
            "OpenLumenFxExporter",
            LOCTEXT("ToolsOpenLumenFxLabel", "LumenFX Exporter (Minecraft)..."),
            LOCTEXT("ToolsOpenLumenFxTooltip", "Open the LumenFX Niagara to Minecraft exporter window."),
            FSlateIcon(),
            FUIAction(FExecuteAction::CreateLambda([]()
            {
                OpenExportDialog(nullptr);
            }))
        );
    }
}

void FLumenFxExporterModule::OpenExportDialog(UNiagaraSystem* InitialSystem)
{
    TSharedRef<SWindow> Window = SNew(SWindow)
        .Title(LOCTEXT("WindowTitle", "LumenFX — Unreal Niagara to Minecraft Exporter"))
        .ClientSize(FVector2D(680.0f, 620.0f))
        .SupportsMaximize(true)
        .SupportsMinimize(true)
        [
            SNew(SLumenFxExportDialog)
            .InitialSystem(InitialSystem)
        ];

    FSlateApplication::Get().AddWindow(Window);
}

#undef LOCTEXT_NAMESPACE

IMPLEMENT_MODULE(FLumenFxExporterModule, LumenFxExporter)
