#pragma once

#include "CoreMinimal.h"

class FJsonObject;

namespace LumenFxAudioExport
{
    bool ExportRouting(const FJsonObject& Request, const FString& Output, bool bClassOnly);
}
