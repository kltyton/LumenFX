#pragma once
#include <array>
#include <bit>
#include <cmath>
#include <cstdint>
#include <immintrin.h>
#include "../scalar/trigonometry.h"

namespace lumenfx::stateless {
constexpr float Pi = 3.14159265358979323846264338327950288f;
inline float minimum(float a, float b) { return a < b ? a : b; }
inline float maximum(float a, float b) { return b < a ? a : b; }
inline float clamp(float a, float lo, float hi) { return a < lo ? lo : a < hi ? a : hi; }
inline float fractional(float value) { return value - truncf(value); }
inline int floorInt(float value) { return _mm_cvt_ss2si(_mm_set_ss(value + value - .5f)) >> 1; }

struct Vec3 {
    float x = 0.f, y = 0.f, z = 0.f;
    Vec3 operator+(Vec3 b) const { return {x + b.x, y + b.y, z + b.z}; }
    Vec3 operator-(Vec3 b) const { return {x - b.x, y - b.y, z - b.z}; }
    Vec3 operator*(Vec3 b) const { return {x * b.x, y * b.y, z * b.z}; }
    Vec3 operator*(float b) const { return {x * b, y * b, z * b}; }
    Vec3 operator/(float b) const { const float reciprocal = 1.f / b; return {x * reciprocal, y * reciprocal, z * reciprocal}; }
    float dot(Vec3 b) const { return x * b.x + y * b.y + z * b.z; }
    Vec3 cross(Vec3 b) const { return {y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x}; }
    Vec3 normalized(float tolerance, Vec3 fallback) const {
        const float square = dot(*this);
        if (square == 1.f) return *this;
        return square < tolerance ? fallback : *this * (1.f / sqrtf(square));
    }
};
inline Vec3 read3(const float* p) { return {p[0], p[1], p[2]}; }
inline void write3(float* p, Vec3 value) { p[0] = value.x; p[1] = value.y; p[2] = value.z; }
inline Vec3 lerp(Vec3 a, Vec3 b, float alpha) { return a + (b - a) * alpha; }

struct Quat {
    float x = 0.f, y = 0.f, z = 0.f, w = 1.f;
    Quat inverse() const { return {-x, -y, -z, w}; }
    Quat operator*(Quat b) const {
        const __m128 av = _mm_setr_ps(x, y, z, w), bv = _mm_setr_ps(b.x, b.y, b.z, b.w);
        __m128 value = _mm_mul_ps(_mm_shuffle_ps(av, av, _MM_SHUFFLE(3, 3, 3, 3)), bv);
        value = _mm_add_ps(value, _mm_mul_ps(_mm_mul_ps(_mm_shuffle_ps(av, av, 0), _mm_shuffle_ps(bv, bv, _MM_SHUFFLE(0, 1, 2, 3))), _mm_setr_ps(1, -1, 1, -1)));
        value = _mm_add_ps(value, _mm_mul_ps(_mm_mul_ps(_mm_shuffle_ps(av, av, _MM_SHUFFLE(1, 1, 1, 1)), _mm_shuffle_ps(bv, bv, _MM_SHUFFLE(1, 0, 3, 2))), _mm_setr_ps(1, 1, -1, -1)));
        value = _mm_add_ps(value, _mm_mul_ps(_mm_mul_ps(_mm_shuffle_ps(av, av, _MM_SHUFFLE(2, 2, 2, 2)), _mm_shuffle_ps(bv, bv, _MM_SHUFFLE(2, 3, 0, 1))), _mm_setr_ps(-1, 1, 1, -1)));
        alignas(16) float result[4]; _mm_store_ps(result, value);
        return {result[0], result[1], result[2], result[3]};
    }
    Vec3 rotate(Vec3 value) const {
        const Vec3 q{x, y, z}, t = q.cross(value) * 2.f;
        return value + t * w + q.cross(t);
    }
    Quat normalized() const {
        const __m128 value = _mm_setr_ps(x, y, z, w);
        const __m128 squared = _mm_mul_ps(value, value);
        const __m128 pairs = _mm_add_ps(squared, _mm_shuffle_ps(squared, squared, _MM_SHUFFLE(2, 3, 0, 1)));
        const __m128 sum = _mm_add_ps(pairs, _mm_shuffle_ps(pairs, pairs, _MM_SHUFFLE(1, 0, 3, 2)));
        const __m128 factor = _mm_div_ps(_mm_set1_ps(1.f), _mm_sqrt_ps(sum));
        const __m128 result = _mm_and_ps(_mm_cmpge_ps(sum, _mm_set1_ps(1.e-8f)), _mm_mul_ps(value, factor));
        if (!(_mm_cvtss_f32(sum) >= 1.e-8f)) return {};
        alignas(16) float values[4]; _mm_store_ps(values, result);
        return {values[0], values[1], values[2], values[3]};
    }
};
inline Quat rotatorDegrees(Vec3 pitchYawRoll) {
    const __m128 angles = _mm_setr_ps(pitchYawRoll.x, pitchYawRoll.y, pitchYawRoll.z, 0.f), fullTurn = _mm_set1_ps(360.f);
    const __m128 winding = _mm_round_ps(_mm_div_ps(angles, fullTurn), _MM_FROUND_TO_ZERO | _MM_FROUND_NO_EXC);
    const __m128 radians = _mm_mul_ps(_mm_sub_ps(angles, _mm_mul_ps(fullTurn, winding)), _mm_set1_ps((Pi / 180.f) * .5f));
    __m128 c; const __m128 s = _mm_sincos_ps(&c, radians);
    const __m128 sr = _mm_shuffle_ps(s, s, _MM_SHUFFLE(2, 2, 2, 2)), cr = _mm_shuffle_ps(c, c, _MM_SHUFFLE(2, 2, 2, 2));
    const __m128 sycy = _mm_shuffle_ps(s, c, _MM_SHUFFLE(1, 1, 1, 1));
    const __m128 spcp = _mm_shuffle_ps(s, c, 0), cpsp = _mm_shuffle_ps(c, s, 0);
    const __m128 sycy2 = _mm_shuffle_ps(sycy, sycy, _MM_SHUFFLE(2, 0, 2, 0)), cysy2 = _mm_shuffle_ps(sycy, sycy, _MM_SHUFFLE(0, 2, 0, 2));
    const __m128 left = _mm_xor_ps(_mm_setr_ps(0.f, -0.f, 0.f, 0.f), _mm_mul_ps(cr, _mm_mul_ps(spcp, sycy2)));
    const __m128 right = _mm_xor_ps(_mm_setr_ps(-0.f, -0.f, -0.f, 0.f), _mm_mul_ps(sr, _mm_mul_ps(cpsp, cysy2)));
    alignas(16) float result[4]; _mm_store_ps(result, _mm_add_ps(left, right));
    return {result[0], result[1], result[2], result[3]};
}
inline Quat rotatorTurns(Vec3 rollPitchYaw) {
    rollPitchYaw = {fractional(rollPitchYaw.x) * Pi, fractional(rollPitchYaw.y) * Pi, fractional(rollPitchYaw.z) * Pi};
    float sr, cr, sp, cp, sy, cy;
    scalar::sinCos(rollPitchYaw.x, sr, cr); scalar::sinCos(rollPitchYaw.y, sp, cp); scalar::sinCos(rollPitchYaw.z, sy, cy);
    return {cr * sp * sy - sr * cp * cy, -cr * sp * cy - sr * cp * sy,
        cr * cp * sy - sr * sp * cy, cr * cp * cy + sr * sp * sy};
}
inline float atan2Source(float y, float x) {
    const float ax = fabsf(x), ay = fabsf(y); const bool yBigger = ay > ax;
    float maximum = yBigger ? ay : ax, minimum = yBigger ? ax : ay;
    if (maximum == 0.f) return 0.f;
    const float ratio = minimum / maximum, square = ratio * ratio;
    float result = +7.2128853633444123e-03f;
    result = result * square - 3.5059680836411644e-02f;
    result = result * square + 8.1675882859940430e-02f;
    result = result * square - 1.3374657325451267e-01f;
    result = result * square + 1.9856563505717162e-01f;
    result = result * square - 3.3324998579202170e-01f;
    result = (result * square + 1.f) * ratio;
    result = yBigger ? .5f * Pi - result : result;
    result = x < 0.f ? Pi - result : result;
    return y < 0.f ? -result : result;
}
inline float fastAsin(float value) {
    const bool nonnegative = value >= 0.f; const float x = fabsf(value);
    float remaining = 1.f - x; if (remaining < 0.f) remaining = 0.f;
    constexpr float halfPi = 1.5707963050f;
    float result = ((((((-.0012624911f * x + .0066700901f) * x - .0170881256f) * x + .0308918810f) * x - .0501743046f) * x + .0889789874f) * x - .2145988016f) * x + halfPi;
    result *= sqrtf(remaining);
    return nonnegative ? halfPi - result : result - halfPi;
}
inline float normalizeAxis(float angle) {
    angle = fmodf(angle, 360.f); if (angle < 0.f) angle += 360.f;
    return angle > 180.f ? angle - 360.f : angle;
}
inline Vec3 euler(Quat q) {
    const float singularity = q.z * q.x - q.w * q.y, yawY = 2.f * (q.w * q.z + q.x * q.y), yawX = 1.f - 2.f * (q.y * q.y + q.z * q.z);
    constexpr float toDegrees = 180.f / Pi;
    if (singularity < -.4999995f) return {0.f, -90.f, normalizeAxis(-2.f * atan2Source(q.x, q.w) * toDegrees)};
    if (singularity > .4999995f) return {0.f, 90.f, normalizeAxis(2.f * atan2Source(q.x, q.w) * toDegrees)};
    return {atan2Source(-2.f * (q.w * q.x + q.y * q.z), 1.f - 2.f * (q.x * q.x + q.y * q.y)) * toDegrees,
        fastAsin(2.f * singularity) * toDegrees, atan2Source(yawY, yawX) * toDegrees};
}
inline Quat betweenVectors(Vec3 a, Vec3 b) {
    const float norm = sqrtf(a.dot(a) * b.dot(b)), w = norm + a.dot(b);
    Vec3 axis;
    if (w >= 1.e-6f * norm) axis = a.cross(b);
    else { const Vec3 basis = fabsf(a.x) > fabsf(a.y) && fabsf(a.x) > fabsf(a.z) ? Vec3{0, 1, 0} : Vec3{-1, 0, 0}; axis = a.cross(basis); }
    return Quat{axis.x, axis.y, axis.z, w >= 1.e-6f * norm ? w : 0.f}.normalized();
}
inline Quat worldToSimulationRotation(const float* pose) {
    return fabsf(pose[7]) > 1.e-8f || fabsf(pose[8]) > 1.e-8f || fabsf(pose[9]) > 1.e-8f
        ? Quat{pose[0], pose[1], pose[2], pose[3]}.inverse() : Quat{};
}
struct FrameTransform {
    Vec3 componentScale, inverseScale, inverseTranslation;
    Quat inverseRotation;
    explicit FrameTransform(const float* pose) : componentScale(read3(pose + 7)), inverseRotation(worldToSimulationRotation(pose)) {
        const bool invertible = fabsf(componentScale.x) > 1.e-8f || fabsf(componentScale.y) > 1.e-8f || fabsf(componentScale.z) > 1.e-8f;
        inverseScale = invertible ? Vec3{
            fabsf(componentScale.x) <= 1.e-8f ? 0.f : 1.f / componentScale.x,
            fabsf(componentScale.y) <= 1.e-8f ? 0.f : 1.f / componentScale.y,
            fabsf(componentScale.z) <= 1.e-8f ? 0.f : 1.f / componentScale.z} : Vec3{1, 1, 1};
        inverseTranslation = invertible ? inverseRotation.rotate(inverseScale * read3(pose + 4)) * -1.f : Vec3{};
    }
    Vec3 vectorNoScale(int space, Vec3 value) const { return space == 1 ? inverseRotation.rotate(value) : value; }
    Vec3 vector(int space, Vec3 value) const { return space == 1 ? inverseRotation.rotate(value * inverseScale) : value; }
    Vec3 position(int space, Vec3 value) const { return space == 1 ? vector(space, value) + inverseTranslation : value; }
    Quat rotation(int space, Quat value) const { return space == 1 ? inverseRotation * value : value; }
};
inline Quat directionQuat(Vec3 direction) {
    direction = direction.normalized(1.e-4f, {0, 0, 1});
    const Vec3 forward{0, 0, 1};
    const float dot = forward.dot(direction);
    const Vec3 axis = dot < -.99999f ? Vec3{0, 1, 0} : forward.cross(direction);
    const float angle = dot < -.99999f ? Pi : acosf(clamp(dot, -1.f, 1.f));
    float sine, cosine; scalar::sinCos(angle * .5f, sine, cosine);
    const Quat result{axis.x * sine, axis.y * sine, axis.z * sine, cosine};
    return dot < -.99999f ? result : result.normalized();
}
inline std::array<uint32_t, 4> random4(uint32_t emitterSeed, uint32_t moduleSeed, uint32_t id, uint32_t call) {
    uint32_t x = (7123u + call) * 1664525u + 1013904223u;
    uint32_t y = emitterSeed * 1664525u + 1013904223u;
    uint32_t z = (3581u + id) * 1664525u + 1013904223u;
    uint32_t w = (6405u + moduleSeed) * 1664525u + 1013904223u;
    x += y * w; y += z * x; z += x * y; w += y * z;
    x ^= x >> 16; y ^= y >> 16; z ^= z >> 16; w ^= w >> 16;
    x += y * w; y += z * x; z += x * y; w += y * z;
    return {x, y, z, w};
}
inline float randomFloat(uint32_t emitterSeed, uint32_t moduleSeed, uint32_t id, uint32_t call) {
    return float(random4(emitterSeed, moduleSeed, id, call)[0] >> 8) / 16777216.f;
}
inline Vec3 randomVector(uint32_t emitterSeed, uint32_t moduleSeed, uint32_t id, uint32_t call) {
    const auto value = random4(emitterSeed, moduleSeed, id, call);
    return {float(value[0] >> 8) / 16777216.f, float(value[1] >> 8) / 16777216.f, float(value[2] >> 8) / 16777216.f};
}
}
