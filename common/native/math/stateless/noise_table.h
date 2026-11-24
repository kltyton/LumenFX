#pragma once
#include <array>
#include "float_math.h"

namespace lumenfx::stateless {
using NoiseTable = std::array<Vec3, 32 * 64>;
const NoiseTable& noiseTable();
}
