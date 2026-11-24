#pragma once
#include <cstdint>

namespace lumenfx::scalar {
inline void sinCos(float value, float& sine, float& cosine) {
    float quotient = (0.31830988618f * .5f) * value;
    quotient = static_cast<float>(static_cast<int64_t>(value >= 0.f ? quotient + .5f : quotient - .5f));
    float y = value - 6.28318530717f * quotient;
    float sign;
    if (y > 1.57079632679f) { y = 3.1415926535897932f - y; sign = -1.f; }
    else if (y < -1.57079632679f) { y = -3.1415926535897932f - y; sign = -1.f; }
    else sign = 1.f;
    const float y2 = y * y;
    sine = (((((-2.3889859e-08f * y2 + 2.7525562e-06f) * y2 - .00019840874f) * y2 + .0083333310f) * y2 - .16666667f) * y2 + 1.f) * y;
    const float p = ((((-2.6051615e-07f * y2 + 2.4760495e-05f) * y2 - .0013888378f) * y2 + .041666638f) * y2 - .5f) * y2 + 1.f;
    cosine = sign * p;
}
}
