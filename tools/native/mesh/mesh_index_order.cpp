#include <algorithm>
#include <new>
#include <stdint.h>
#include "forsythtriangleorderoptimizer.h"

extern "C" __declspec(dllexport) int LumenFxOptimizeMeshIndices(int vertexCount, int indexCount,
    const int* indices, int* output) {
    if (vertexCount <= 0 || indexCount <= 0 || indexCount % 3 != 0 || !indices || !output) return 1;
    uint32_t maximum = 0;
    for (int i = 0; i < indexCount; i++) {
        if (indices[i] < 0 || indices[i] >= vertexCount) return 1;
        maximum = std::max(maximum, static_cast<uint32_t>(indices[i]));
    }
    try {
        Forsyth::OptimizeFaces(reinterpret_cast<const uint32_t*>(indices), static_cast<uint32_t>(indexCount),
            maximum + 1, reinterpret_cast<uint32_t*>(output), 32);
        return 0;
    } catch (const std::bad_alloc&) {
        return 2;
    }
}
