#include "SLumenFxExportDialog.h"
#include "LumenFxExporterSettings.h"
#include "LumenFxExportRunner.h"
#include "DesktopPlatformModule.h"
#include "Framework/Application/SlateApplication.h"
#include "HAL/PlatformProcess.h"
#include "ISettingsModule.h"
#include "Misc/Paths.h"
#include "Modules/ModuleManager.h"
#include "NiagaraSystem.h"
#include "Widgets/Input/SButton.h"
#include "Widgets/Input/SCheckBox.h"
#include "Widgets/Input/SEditableTextBox.h"
#include "Widgets/Input/SMultiLineEditableTextBox.h"
#include "Widgets/Layout/SBorder.h"
#include "Widgets/Layout/SScrollBox.h"
#include "Widgets/Text/STextBlock.h"

#define LOCTEXT_NAMESPACE "SLumenFxExportDialog"

void SLumenFxExportDialog::Construct(const FArguments& InArgs)
{
    TargetSystem = InArgs._InitialSystem;
    const ULumenFxExporterSettings* Settings = GetDefault<ULumenFxExporterSettings>();

    NamespaceText = (Settings && !Settings->DefaultNamespace.IsEmpty()) ? Settings->DefaultNamespace : TEXT("lumenfx");
    OutputDirText = Settings ? Settings->DefaultOutputDirectory.Path : FString();
    bGeneratePackMcmeta = Settings ? Settings->bGeneratePackMcmeta : true;
    PackFormat = Settings ? Settings->DefaultPackFormat : 55;
    EffectNameText = TargetSystem.IsValid()
        ? FLumenFxNiagaraValidator::SanitizeId(TargetSystem->GetName())
        : TEXT("my_effect");

    ChildSlot
    [
        SNew(SBorder)
        .Padding(12.0f)
        [
            SNew(SVerticalBox)

            // Header
            + SVerticalBox::Slot().AutoHeight().Padding(0, 0, 0, 8)
            [
                SNew(SHorizontalBox)
                + SHorizontalBox::Slot().FillWidth(1.0f).VAlign(VAlign_Center)
                [
                    SNew(STextBlock)
                    .Text(FText::FromString(TargetSystem.IsValid()
                        ? FString::Printf(TEXT("Niagara System: %s"), *TargetSystem->GetPathName())
                        : TEXT("No Niagara System selected (right-click a NiagaraSystem in Content Browser)")))
                    .Font(FCoreStyle::GetDefaultFontStyle("Bold", 11))
                ]
                + SHorizontalBox::Slot().AutoWidth()
                [
                    SNew(SButton)
                    .Text(LOCTEXT("PluginSettings", "Toolchain Settings..."))
                    .OnClicked(this, &SLumenFxExportDialog::OnOpenSettingsClicked)
                ]
            ]

            // Namespace
            + SVerticalBox::Slot().AutoHeight().Padding(0, 3)
            [
                SNew(SHorizontalBox)
                + SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center).Padding(0, 0, 8, 0)
                [
                    SNew(STextBlock).Text(LOCTEXT("Namespace", "Minecraft Namespace:")).MinDesiredWidth(150.0f)
                ]
                + SHorizontalBox::Slot().FillWidth(1.0f)
                [
                    SNew(SEditableTextBox)
                    .Text(FText::FromString(NamespaceText))
                    .OnTextChanged_Lambda([this](const FText& NewText) { NamespaceText = NewText.ToString().TrimStartAndEnd().ToLower(); })
                ]
            ]

            // Effect Name
            + SVerticalBox::Slot().AutoHeight().Padding(0, 3)
            [
                SNew(SHorizontalBox)
                + SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center).Padding(0, 0, 8, 0)
                [
                    SNew(STextBlock).Text(LOCTEXT("EffectName", "Effect Path / Name:")).MinDesiredWidth(150.0f)
                ]
                + SHorizontalBox::Slot().FillWidth(1.0f)
                [
                    SNew(SEditableTextBox)
                    .Text(FText::FromString(EffectNameText))
                    .OnTextChanged_Lambda([this](const FText& NewText) { EffectNameText = NewText.ToString().TrimStartAndEnd().ToLower(); })
                ]
            ]

            // Output Directory
            + SVerticalBox::Slot().AutoHeight().Padding(0, 3)
            [
                SNew(SHorizontalBox)
                + SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center).Padding(0, 0, 8, 0)
                [
                    SNew(STextBlock).Text(LOCTEXT("OutputDir", "Resource Pack Output:")).MinDesiredWidth(150.0f)
                ]
                + SHorizontalBox::Slot().FillWidth(1.0f).Padding(0, 0, 6, 0)
                [
                    SNew(SEditableTextBox)
                    .Text_Lambda([this]() { return FText::FromString(OutputDirText); })
                    .OnTextChanged_Lambda([this](const FText& NewText) { OutputDirText = NewText.ToString().TrimStartAndEnd(); })
                ]
                + SHorizontalBox::Slot().AutoWidth()
                [
                    SNew(SButton)
                    .Text(LOCTEXT("Browse", "Browse..."))
                    .OnClicked(this, &SLumenFxExportDialog::OnBrowseOutputClicked)
                ]
            ]

            // pack.mcmeta option
            + SVerticalBox::Slot().AutoHeight().Padding(0, 6)
            [
                SNew(SHorizontalBox)
                + SHorizontalBox::Slot().AutoWidth().VAlign(VAlign_Center)
                [
                    SNew(SCheckBox)
                    .IsChecked_Lambda([this]() { return bGeneratePackMcmeta ? ECheckBoxState::Checked : ECheckBoxState::Unchecked; })
                    .OnCheckStateChanged_Lambda([this](ECheckBoxState State) { bGeneratePackMcmeta = (State == ECheckBoxState::Checked); })
                ]
                + SHorizontalBox::Slot().FillWidth(1.0f).VAlign(VAlign_Center).Padding(6, 0, 0, 0)
                [
                    SNew(STextBlock).Text(LOCTEXT("GenPackMeta", "Generate pack.mcmeta (output folder works directly as a Minecraft Resource Pack)"))
                ]
            ]

            // Validation Summary
            + SVerticalBox::Slot().AutoHeight().Padding(0, 8, 0, 4)
            [
                SNew(STextBlock).Text(LOCTEXT("ValidationHeader", "Preflight Validation")).Font(FCoreStyle::GetDefaultFontStyle("Bold", 10))
            ]
            + SVerticalBox::Slot().MaxHeight(160.0f).Padding(0, 0, 0, 8)
            [
                SNew(SBorder).Padding(6.0f)
                [
                    SNew(SScrollBox)
                    + SScrollBox::Slot()
                    [
                        SAssignNew(ValidationListBox, SVerticalBox)
                    ]
                ]
            ]

            // Action Buttons
            + SVerticalBox::Slot().AutoHeight().Padding(0, 4)
            [
                SNew(SHorizontalBox)
                + SHorizontalBox::Slot().AutoWidth().Padding(0, 0, 8, 0)
                [
                    SNew(SButton)
                    .Text(LOCTEXT("ValidateBtn", "Validate"))
                    .IsEnabled_Lambda([this]() { return !bIsRunning; })
                    .OnClicked(this, &SLumenFxExportDialog::OnValidateClicked)
                ]
                + SHorizontalBox::Slot().AutoWidth().Padding(0, 0, 8, 0)
                [
                    SNew(SButton)
                    .Text(LOCTEXT("ExportBtn", "Export to LumenFX"))
                    .IsEnabled_Lambda([this]() { return !bIsRunning && TargetSystem.IsValid(); })
                    .OnClicked(this, &SLumenFxExportDialog::OnExportClicked)
                ]
                + SHorizontalBox::Slot().AutoWidth()
                [
                    SNew(SButton)
                    .Text(LOCTEXT("OpenFolderBtn", "Open Output Folder"))
                    .OnClicked(this, &SLumenFxExportDialog::OnOpenOutputFolderClicked)
                ]
            ]

            // Export Console Log
            + SVerticalBox::Slot().AutoHeight().Padding(0, 8, 0, 4)
            [
                SNew(STextBlock).Text(LOCTEXT("LogHeader", "Export Log")).Font(FCoreStyle::GetDefaultFontStyle("Bold", 10))
            ]
            + SVerticalBox::Slot().FillHeight(1.0f)
            [
                SAssignNew(LogTextBox, SMultiLineEditableTextBox)
                .IsReadOnly(true)
                .AutoWrapText(true)
            ]
        ]
    ];

    RefreshValidation();
}

void SLumenFxExportDialog::RefreshValidation()
{
    LastValidation = FLumenFxNiagaraValidator::Validate(
        TargetSystem.Get(), NamespaceText, EffectNameText, OutputDirText, GetDefault<ULumenFxExporterSettings>());

    if (!ValidationListBox.IsValid()) return;
    ValidationListBox->ClearChildren();

    for (const FLumenFxValidationItem& Item : LastValidation.Items)
    {
        FLinearColor Color = FLinearColor(0.75f, 0.88f, 1.0f);
        FString Prefix = TEXT("[INFO]");
        if (Item.Severity == ELumenFxIssueSeverity::Warning)
        {
            Color = FLinearColor(1.0f, 0.82f, 0.25f);
            Prefix = TEXT("[WARN]");
        }
        else if (Item.Severity == ELumenFxIssueSeverity::Error)
        {
            Color = FLinearColor(1.0f, 0.38f, 0.38f);
            Prefix = TEXT("[ERROR]");
        }

        ValidationListBox->AddSlot().AutoHeight().Padding(0, 2)
        [
            SNew(STextBlock)
            .AutoWrapText(true)
            .ColorAndOpacity(Color)
            .Text(FText::FromString(FString::Printf(TEXT("%s %s: %s"), *Prefix, *Item.Context, *Item.Message)))
        ];
    }
}

void SLumenFxExportDialog::AppendLog(const FString& Line)
{
    if (!LogBuffer.IsEmpty()) LogBuffer += TEXT("\n");
    LogBuffer += Line;
    if (LogTextBox.IsValid())
    {
        LogTextBox->SetText(FText::FromString(LogBuffer));
        LogTextBox->GoTo(FTextLocation(LogBuffer.Len()));
    }
}

FReply SLumenFxExportDialog::OnBrowseOutputClicked()
{
    if (IDesktopPlatform* DesktopPlatform = FDesktopPlatformModule::Get())
    {
        FString SelectedDir;
        const void* ParentHandle = FSlateApplication::Get().FindBestParentWindowHandleForDialogs(nullptr);
        if (DesktopPlatform->OpenDirectoryDialog(ParentHandle, TEXT("Select Minecraft Resource Pack Output Folder"), OutputDirText, SelectedDir))
        {
            OutputDirText = SelectedDir;
            RefreshValidation();
        }
    }
    return FReply::Handled();
}

FReply SLumenFxExportDialog::OnValidateClicked()
{
    RefreshValidation();
    return FReply::Handled();
}

FReply SLumenFxExportDialog::OnExportClicked()
{
    RefreshValidation();
    if (LastValidation.HasErrors() || !TargetSystem.IsValid())
    {
        AppendLog(TEXT("[LumenFX] Export aborted: resolve validation errors above first."));
        return FReply::Handled();
    }

    bIsRunning = true;
    LogBuffer.Empty();
    AppendLog(TEXT("[LumenFX] Starting export..."));

    FLumenFxExportRequest Request;
    Request.PackageObjectPath = TargetSystem->GetPathName();
    Request.ProjectContentDir = FPaths::ConvertRelativePathToFull(FPaths::ProjectContentDir());
    Request.Namespace = NamespaceText;
    Request.EffectName = EffectNameText;
    Request.OutputDirectory = OutputDirText;
    Request.bGeneratePackMcmeta = bGeneratePackMcmeta;
    Request.PackFormat = PackFormat;

    TWeakPtr<SLumenFxExportDialog> WeakSelf = SharedThis(this);
    FLumenFxExportRunner::RunAsync(
        Request,
        GetDefault<ULumenFxExporterSettings>(),
        FLumenFxExportRunner::FOnLogLine::CreateLambda([WeakSelf](const FString& Line)
        {
            if (TSharedPtr<SLumenFxExportDialog> Pinned = WeakSelf.Pin())
            {
                Pinned->AppendLog(Line);
            }
        }),
        FLumenFxExportRunner::FOnCompleted::CreateLambda([WeakSelf](bool bSuccess, const FString& Summary)
        {
            if (TSharedPtr<SLumenFxExportDialog> Pinned = WeakSelf.Pin())
            {
                Pinned->bIsRunning = false;
                Pinned->AppendLog(FString::Printf(TEXT("[LumenFX] %s: %s"), bSuccess ? TEXT("DONE") : TEXT("FAILED"), *Summary));
            }
        }));

    return FReply::Handled();
}

FReply SLumenFxExportDialog::OnOpenOutputFolderClicked()
{
    if (!OutputDirText.IsEmpty() && FPaths::DirectoryExists(OutputDirText))
    {
        FPlatformProcess::ExploreFolder(*OutputDirText);
    }
    return FReply::Handled();
}

FReply SLumenFxExportDialog::OnOpenSettingsClicked()
{
    if (ISettingsModule* SettingsModule = FModuleManager::GetModulePtr<ISettingsModule>("Settings"))
    {
        SettingsModule->ShowViewer("Project", "Plugins", "LumenFX Exporter");
    }
    return FReply::Handled();
}

#undef LOCTEXT_NAMESPACE
