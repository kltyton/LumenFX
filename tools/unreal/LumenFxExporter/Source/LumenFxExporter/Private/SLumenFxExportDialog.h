#pragma once

#include "CoreMinimal.h"
#include "LumenFxNiagaraValidator.h"
#include "Widgets/SCompoundWidget.h"

class UNiagaraSystem;
class SMultiLineEditableTextBox;

class SLumenFxExportDialog : public SCompoundWidget
{
public:
    SLATE_BEGIN_ARGS(SLumenFxExportDialog) : _InitialSystem(nullptr) {}
        SLATE_ARGUMENT(UNiagaraSystem*, InitialSystem)
    SLATE_END_ARGS()

    void Construct(const FArguments& InArgs);

private:
    TWeakObjectPtr<UNiagaraSystem> TargetSystem;
    FString NamespaceText;
    FString EffectNameText;
    FString OutputDirText;
    bool bGeneratePackMcmeta = true;
    int32 PackFormat = 55;
    bool bIsRunning = false;

    FLumenFxValidationSummary LastValidation;
    FString LogBuffer;
    TSharedPtr<SMultiLineEditableTextBox> LogTextBox;
    TSharedPtr<SVerticalBox> ValidationListBox;

    void RefreshValidation();
    void AppendLog(const FString& Line);
    FReply OnBrowseOutputClicked();
    FReply OnValidateClicked();
    FReply OnExportClicked();
    FReply OnOpenOutputFolderClicked();
    FReply OnOpenSettingsClicked();
};
