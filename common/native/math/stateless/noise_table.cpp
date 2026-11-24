#include "noise_table.h"
#include <jni.h>

namespace lumenfx::stateless {
namespace {
std::array<uint32_t, 3> random16(int32_t a, int32_t b, int32_t c) {
    uint32_t x = uint32_t(a) * 1664525u + 1013904223u, y = uint32_t(b) * 1664525u + 1013904223u, z = uint32_t(c) * 1664525u + 1013904223u;
    x += y * z; y += z * x; z += x * y; x += y * z; y += z * x; z += x * y;
    return {x >> 16, y >> 16, z >> 16};
}
Vec3 gradient(uint32_t value) {
    return {float(value & 0x8000) * (1.f / 0x4000) - 1.f, float(value & 0x4000) * (1.f / 0x2000) - 1.f, float(value & 0x2000) * (1.f / 0x1000) - 1.f};
}
Vec3 curl(Vec3 v) {
    const float skew = (v.x + v.y + v.z) * (1.f / 3.f);
    const Vec3 tet{floorf(v.x + skew), floorf(v.y + skew), floorf(v.z + skew)};
    const float unskew = (tet.x + tet.y + tet.z) * (1.f / 6.f);
    const Vec3 base = tet - Vec3{unskew, unskew, unskew}, f = v - base;
    const Vec3 g{f.x >= f.y ? 1.f : 0.f, f.y >= f.z ? 1.f : 0.f, f.z >= f.x ? 1.f : 0.f};
    const Vec3 h{1.f - g.z, 1.f - g.x, 1.f - g.y};
    const std::array<Vec3, 4> corners{base,
        base + Vec3{minimum(g.x, h.x) - 1.f / 6.f, minimum(g.y, h.y) - 1.f / 6.f, minimum(g.z, h.z) - 1.f / 6.f},
        base + Vec3{maximum(g.x, h.x) - 1.f / 3.f, maximum(g.y, h.y) - 1.f / 3.f, maximum(g.z, h.z) - 1.f / 3.f},
        base + Vec3{.5f, .5f, .5f}};
    Vec3 jacobian[3]{};
    for (Vec3 corner : corners) {
        const Vec3 relative = v - corner;
        const auto random = random16(int32_t(floorf(6.f * corner.x + .5f)), int32_t(floorf(6.f * corner.y + .5f)), int32_t(floorf(6.f * corner.z + .5f)));
        const float square = relative.dot(relative), s = clamp(square + square, 0.f, 1.f);
        constexpr float scale = 1024.f / 375.f;
        const float value = scale * (1.f + s * (-3.f + s * (3.f - s)));
        const float derivative = scale * (-12.f + s * (24.f - s * 12.f));
        for (int channel = 0; channel < 3; ++channel) {
            const Vec3 grad = gradient(random[channel]);
            jacobian[channel] = jacobian[channel] + (grad * value + relative * (derivative * grad.dot(relative)));
        }
    }
    return {jacobian[1].z - jacobian[2].y, jacobian[2].x - jacobian[0].z, jacobian[0].y - jacobian[1].x};
}
}
const NoiseTable& noiseTable() {
    static const NoiseTable table = [] {
        NoiseTable values{};
        for (uint32_t row = 0; row < 32; ++row) {
            const float fRow = float(row) - 16.f;
            Vec3 position{fRow, fRow * 3.f, fRow * 9.f}, offset{};
            for (uint32_t column = 1; column < 64; ++column) {
                const Vec3 direction = curl(position).normalized(1.e-8f, {});
                position = position + direction * .1f; offset = offset + direction;
                values[row * 64 + column] = offset;
            }
        }
        return values;
    }();
    return table;
}
}
extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_initializeNoise(JNIEnv*, jclass) {
    lumenfx::stateless::noiseTable();
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_noiseLookupData(JNIEnv* env, jclass) {
    const auto& table = lumenfx::stateless::noiseTable();
    constexpr jsize count = 32 * 64 * 3;
    jfloatArray output = env->NewFloatArray(count);
    if (!output) return nullptr;
    std::array<jfloat, count> values;
    for (size_t index = 0; index < table.size(); ++index) {
        values[index * 3] = table[index].x;
        values[index * 3 + 1] = table[index].y;
        values[index * 3 + 2] = table[index].z;
    }
    env->SetFloatArrayRegion(output, 0, count, values.data());
    return output;
}
