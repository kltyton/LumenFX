#pragma once

#include "CoreMinimal.h"
#include "Modules/ModuleManager.h"

class UNiagaraSystem;

class FLumenFxExporterModule : public IModuleInterface
{
public:
    virtual void StartupModule() override;
    virtual void ShutdownModule() override;

    static void OpenExportDialog(UNiagaraSystem* InitialSystem = nullptr);

private:
    void RegisterMenus();
};
