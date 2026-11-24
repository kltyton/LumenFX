#include <jni.h>
#include <immintrin.h>
#include <bit>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include "../scalar/trigonometry.h"

namespace {
constexpr float OneThird = 1.f / 3.f;
constexpr double SmallNumber = 1.e-8;
constexpr double DoublePi = 3.141592653589793238462643383279502884197169399;

struct Curve {
    const float* keys;
    const jint* modes;
    int count;
    float time(int index) const { return keys[index * 6]; }
    float value(int index) const { return keys[index * 6 + 1]; }
};

using lumenfx::scalar::sinCos;
float lerp(float a, float b, float alpha) { return a + alpha * (b - a); }

float bezier(float p0, float p1, float p2, float p3, float alpha) {
    const float p01 = lerp(p0, p1, alpha), p12 = lerp(p1, p2, alpha), p23 = lerp(p2, p3, alpha);
    const float p012 = lerp(p01, p12, alpha), p123 = lerp(p12, p23, alpha);
    return lerp(p012, p123, alpha);
}

double cubeRoot(double value) {
    return value > 0.0 ? pow(value, 1.0 / 3.0) : value < 0.0 ? -pow(-value, 1.0 / 3.0) : 0.0;
}

int solveCubic(const double coefficients[4], double solutions[3]) {
    const double denominator = coefficients[3] != 0.0 ? coefficients[3] : SmallNumber * SmallNumber;
    const double a = coefficients[2] / denominator, b = coefficients[1] / denominator, c = coefficients[0] / denominator;
    const double squareOfA = a * a;
    const double p = 1.0 / 3 * (-1.0 / 3 * squareOfA + b);
    const double q = 1.0 / 2 * (2.0 / 27 * a * squareOfA - 1.0 / 3 * a * b + c);
    const double cubeOfP = p * p * p, d = q * q + cubeOfP;
    int count;
    if (std::abs(d) <= SmallNumber) {
        if (std::abs(q) <= SmallNumber) { solutions[0] = 0; count = 1; }
        else { const double u = cubeRoot(-q); solutions[0] = 2 * u; solutions[1] = -u; count = 2; }
    } else if (d < 0) {
        const double phi = 1.0 / 3 * acos(-q / sqrt(-cubeOfP)), t = 2 * sqrt(-p);
        solutions[0] = t * cos(phi);
        solutions[1] = -t * cos(phi + DoublePi / 3);
        solutions[2] = -t * cos(phi - DoublePi / 3);
        count = 3;
    } else {
        const double sqrtD = sqrt(d), u = cubeRoot(sqrtD - q), v = -cubeRoot(sqrtD + q);
        solutions[0] = u + v; count = 1;
    }
    const double subtraction = 1.0 / 3 * a;
    for (int index = 0; index < count; index++) solutions[index] -= subtraction;
    return count;
}

float weighted(const float* a, int weightA, const float* b, int weightB, float time) {
    const float difference = b[0] - a[0], alpha = (time - a[0]) / difference;
    const double time1 = a[0], time2 = b[0];
    const float x = static_cast<float>(time2 - time1);
    float sine, cosine;
    sinCos(atanf(a[3]), sine, cosine);
    float leaveWeight;
    if (weightA == 0 || weightA == 1) { const float y = a[3] * x; leaveWeight = sqrtf(x * x + y * y) * OneThird; }
    else leaveWeight = a[5];
    const float tangentX1 = static_cast<float>(cosine * leaveWeight + time1);
    const float tangentY1 = sine * leaveWeight + a[1];
    sinCos(atanf(b[2]), sine, cosine);
    float arriveWeight;
    if (weightB == 0 || weightB == 2) { const float y = b[2] * x; arriveWeight = sqrtf(x * x + y * y) * OneThird; }
    else arriveWeight = b[4];
    const float tangentX2 = static_cast<float>(-cosine * arriveWeight + time2);
    const float tangentY2 = -sine * arriveWeight + b[1];
    const float range = static_cast<float>(time2 - time1);
    const float dx1 = static_cast<float>(tangentX1 - time1), dx2 = static_cast<float>(tangentX2 - time1);
    const float normalizedX1 = dx1 / range, normalizedX2 = dx2 / range;
    const double edge0 = normalizedX1, edge1 = static_cast<double>(normalizedX2) - normalizedX1, edge2 = 1.0 - normalizedX2;
    const double delta = edge1 - edge0;
    const double coefficients[4] = {-static_cast<double>(alpha), 3.0 * edge0, 3.0 * delta, edge2 - edge1 - delta};
    double solutions[3];
    const int count = solveCubic(coefficients, solutions);
    double interpolation = alpha;
    if (count == 1) interpolation = solutions[0];
    else {
        interpolation = std::numeric_limits<double>::lowest();
        for (int index = 0; index < count; index++) {
            const double result = solutions[index];
            if ((result > 0.0 || std::abs(result) <= SmallNumber) && (result < 1.0 || std::abs(result - 1.0) <= SmallNumber))
                if (interpolation < 0.0 || result > interpolation) interpolation = result;
        }
        if (interpolation == std::numeric_limits<double>::lowest()) interpolation = 0.0;
    }
    return bezier(a[1], tangentY1, tangentY2, b[1], static_cast<float>(interpolation));
}

float between(const Curve& curve, int left, int right, float time) {
    const float* a = curve.keys + left * 6;
    const float* b = curve.keys + right * 6;
    const float difference = b[0] - a[0];
    if (!(difference > 0.f)) return a[1];
    const int interpolation = curve.modes[left] & 3;
    if (interpolation == 0) return time < b[0] ? a[1] : b[1];
    const float alpha = (time - a[0]) / difference;
    if (interpolation == 1) return lerp(a[1], b[1], alpha);
    const int weightA = (curve.modes[left] >> 2) & 3, weightB = (curve.modes[right] >> 2) & 3;
    if ((weightA == 0 || weightA == 1) && (weightB == 0 || weightB == 2))
        return bezier(a[1], a[1] + (a[3] * difference * OneThird), b[1] - (b[2] * difference * OneThird), b[1], alpha);
    return weighted(a, weightA, b, weightB, time);
}

int floorToInt(float value) { return _mm_cvt_ss2si(_mm_set_ss(value + value - .5f)) >> 1; }

void cycle(float minimum, float maximum, float& time, int& cycles) {
    const float initial = time, duration = maximum - minimum;
    if (time > maximum) { cycles = floorToInt((maximum - time) / duration); time = time + duration * cycles; }
    else if (time < minimum) { cycles = floorToInt((time - minimum) / duration); time = time - duration * cycles; }
    if (time == maximum && initial < minimum) time = minimum;
    if (time == minimum && initial > maximum) time = maximum;
    cycles = std::abs(cycles);
}

float evaluate(const Curve& curve, float time, int pre, int post) {
    float offset = 0;
    const int last = curve.count - 1;
    if (curve.count > 1 && (time <= curve.time(0) || time >= curve.time(last))) {
        const bool before = time <= curve.time(0);
        const int mode = before ? pre : post;
        if (mode != 0 && mode != 1) {
            int cycles = 0;
            cycle(curve.time(0), curve.time(last), time, cycles);
            if (mode == 3) offset = (before ? curve.value(0) - curve.value(last) : curve.value(last) - curve.value(0)) * cycles;
            else if (mode == 4 && cycles % 2 == 1) time = curve.time(0) + (curve.time(last) - time);
        }
    }
    float value;
    if (curve.count < 2 || time < curve.time(0)) {
        if (pre == 1 && curve.count > 1) {
            const float dt = curve.time(1) - curve.time(0);
            value = std::abs(dt) <= 1.e-8f ? curve.value(0)
                : (curve.value(1) - curve.value(0)) / dt * (time - curve.time(0)) + curve.value(0);
        } else value = curve.value(0);
    } else if (time <= curve.time(last)) {
        int first = 1, count = last - first;
        while (count > 0) {
            const int step = count / 2, middle = first + step;
            if (time >= curve.time(middle)) { first = middle + 1; count -= step + 1; }
            else count = step;
        }
        value = between(curve, first - 1, first, time);
    } else if (post == 1) {
        const float dt = curve.time(last - 1) - curve.time(last);
        value = std::abs(dt) <= 1.e-8f ? curve.value(last)
            : (curve.value(last - 1) - curve.value(last)) / dt * (time - curve.time(last)) + curve.value(last);
    } else value = curve.value(last);
    return value + offset;
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_curve_NativeCurveSampler_evaluateNative(
    JNIEnv* env, jclass, jint count, jintArray input, jintArray output, jfloatArray keyData, jintArray modes, jint pre, jint post)
{
    if (!input || !output || !keyData || !modes || count < 0 || count > env->GetArrayLength(input)
        || count > env->GetArrayLength(output) || pre < 0 || pre > 5 || post < 0 || post > 5) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid rich-curve batch"); return;
    }
    const jsize keyCount = env->GetArrayLength(modes);
    if (keyCount < 1 || static_cast<int64_t>(keyCount) * 6 != env->GetArrayLength(keyData)) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid rich-curve key layout"); return;
    }
    auto* values = static_cast<jint*>(env->GetPrimitiveArrayCritical(input, nullptr));
    if (!values) return;
    auto* destination = static_cast<jint*>(env->GetPrimitiveArrayCritical(output, nullptr));
    if (!destination) { env->ReleasePrimitiveArrayCritical(input, values, JNI_ABORT); return; }
    auto* keys = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(keyData, nullptr));
    if (!keys) { env->ReleasePrimitiveArrayCritical(output, destination, JNI_ABORT); env->ReleasePrimitiveArrayCritical(input, values, JNI_ABORT); return; }
    auto* flags = static_cast<jint*>(env->GetPrimitiveArrayCritical(modes, nullptr));
    if (!flags) {
        env->ReleasePrimitiveArrayCritical(keyData, keys, JNI_ABORT); env->ReleasePrimitiveArrayCritical(output, destination, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(input, values, JNI_ABORT); return;
    }
    const Curve curve{keys, flags, keyCount};
    for (int index = 0; index < count; index++) destination[index] = std::bit_cast<jint>(evaluate(curve, std::bit_cast<float>(values[index]), pre, post));
    env->ReleasePrimitiveArrayCritical(modes, flags, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(keyData, keys, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(output, destination, 0);
    env->ReleasePrimitiveArrayCritical(input, values, JNI_ABORT);
}
