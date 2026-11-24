#include "CoreMinimal.h"
#include "RequiredProgramMainCPPInclude.h"
#include "DialogueCultureGroups.h"
#include "DialogueResolution.h"
#include "TextEquivalence.h"
#include "Misc/CommandLine.h"
#include "Misc/Parse.h"
#include "Misc/ScopeExit.h"
#include "Modules/ModuleManager.h"

IMPLEMENT_APPLICATION(LumenFxTextResolve, "LumenFxTextResolve");

INT32_MAIN_INT32_ARGC_TCHAR_ARGV()
{
    FTaskTagScope TaskScope(ETaskTag::EGameThread);
    ON_SCOPE_EXIT
    {
        RequestEngineExit(TEXT("Text comparison completed"));
        FEngineLoop::AppPreExit();
        FModuleManager::Get().UnloadModulesAtShutdown();
        FEngineLoop::AppExit();
    };
    if (int32 Status = GEngineLoop.PreInit(ArgC, ArgV)) return Status;
    FString Input, Output;
    if (!FParse::Value(FCommandLine::Get(), TEXT("input="), Input)
        || !FParse::Value(FCommandLine::Get(), TEXT("output="), Output)) return 2;
    FString Operation;
    FParse::Value(FCommandLine::Get(), TEXT("operation="), Operation);
    if (Operation == TEXT("dialogue"))
        return ResolveDialogue(Input, Output) ? 0 : 3;
    if (Operation == TEXT("dialogue_cultures"))
        return ResolveDialogueCultureGroups(Input, Output) ? 0 : 3;
    return ResolveTextEquivalence(Input, Output) ? 0 : 3;
}
