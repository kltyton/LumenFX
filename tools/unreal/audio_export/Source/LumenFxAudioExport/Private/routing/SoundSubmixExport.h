#pragma once

#include "CoreMinimal.h"

class FJsonObject;
class USoundSubmixBase;

namespace LumenFxAudioExport
{
    bool DescribeSubmix(FJsonObject& Owner, const TCHAR* Field, const USoundSubmixBase* Submix);
}
