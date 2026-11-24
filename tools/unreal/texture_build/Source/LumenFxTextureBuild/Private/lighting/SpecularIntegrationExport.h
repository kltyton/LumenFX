#pragma once

#include "RHICommandList.h"
#include "Serialization/BufferArchive.h"

bool AppendPreintegratedSpecularGF(FRHICommandListImmediate& Commands, FBufferArchive& Archive);
