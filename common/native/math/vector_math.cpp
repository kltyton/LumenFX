#include <jni.h>
#include <immintrin.h>
#include <intrin.h>
#include <cstdint>
#include <cmath>

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kltyton_lumenfx_client_render_math_NativeSceneMath_power(JNIEnv*, jclass, jfloat base, jfloat exponent)
{
    return powf(base, exponent);
}

extern "C" JNIEXPORT jdouble JNICALL
Java_com_kltyton_lumenfx_client_render_math_NativeSceneMath_tangent(JNIEnv*, jclass, jdouble radians)
{
    return tan(radians);
}

static float evaluate_ies_brightness(jfloat intensity, jint unit, jboolean hasIes, jfloat iesBrightness,
    jfloat iesMultiplier, jboolean useIesBrightness, jfloat iesBrightnessScale)
{
    float brightness = intensity;
    if (hasIes) {
        if (unit == 4) brightness = powf(2.f, brightness);
        if (useIesBrightness) brightness = iesBrightness * iesBrightnessScale;
        brightness *= iesMultiplier;
        if (unit == 4) brightness = logf(brightness) * 1.4426950f;
    }
    return brightness;
}

static float evaluate_local_brightness(jfloat intensity, jint unit, jboolean inverseSquared, jfloat sourceRadius, jfloat sourceLength,
    jboolean hasIes, jfloat iesBrightness, jfloat iesMultiplier, jboolean useIesBrightness, jfloat iesBrightnessScale, jfloat cosineOuter)
{
    float brightness = evaluate_ies_brightness(intensity, unit, hasIes, iesBrightness, iesMultiplier, useIesBrightness, iesBrightnessScale);
    if (inverseSquared) {
        if (unit == 1) brightness *= 100.f * 100.f;
        else if (unit == 3) {
            const float area = (4.f * 3.14159265358979323846264338327950288f) * sourceRadius * (sourceRadius + .5f * sourceLength);
            brightness *= area;
        }
        else if (unit == 2) {
            constexpr float pi = 3.14159265358979323846264338327950288f;
            brightness *= cosineOuter < 0.f ? (100.f * 100.f / 4.f / pi)
                                          : (100.f * 100.f / 2.f / pi / (1.f - cosineOuter));
        }
        else if (unit == 4) brightness = powf(2.f, brightness) * (100.f * 100.f);
        else brightness *= 16;
    }
    return brightness;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kltyton_lumenfx_client_render_light_photometry_PointLightPhotometry_evaluateRectBrightness(
    JNIEnv*, jclass, jfloat intensity, jint unit, jfloat sourceWidth, jfloat sourceHeight, jboolean legacyEv,
    jboolean hasIes, jfloat iesBrightness, jfloat iesMultiplier, jboolean useIesBrightness, jfloat iesBrightnessScale)
{
    constexpr float pi = 3.14159265358979323846264338327950288f;
    float brightness = evaluate_ies_brightness(intensity, unit, hasIes, iesBrightness, iesMultiplier, useIesBrightness, iesBrightnessScale);
    switch (unit) {
        case 1: return brightness * (100.f * 100.f);
        case 2: return brightness * (100.f * 100.f / pi);
        case 3: return brightness * (sourceWidth * sourceHeight);
        case 4: {
            const float luminance = powf(2.f, brightness) * (100.f * 100.f);
            return legacyEv ? brightness * luminance : luminance;
        }
        default: return brightness * 16.f;
    }
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kltyton_lumenfx_client_render_light_photometry_PointLightPhotometry_evaluateBrightness(
    JNIEnv*, jclass, jfloat intensity, jint unit, jboolean inverseSquared, jfloat sourceRadius, jfloat sourceLength,
    jboolean hasIes, jfloat iesBrightness, jfloat iesMultiplier, jboolean useIesBrightness, jfloat iesBrightnessScale)
{
    return evaluate_local_brightness(intensity, unit, inverseSquared, sourceRadius, sourceLength, hasIes,
        iesBrightness, iesMultiplier, useIesBrightness, iesBrightnessScale, -1.f);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kltyton_lumenfx_client_render_light_photometry_PointLightPhotometry_evaluateLocalBrightness(
    JNIEnv*, jclass, jfloat intensity, jint unit, jboolean inverseSquared, jfloat sourceRadius, jfloat sourceLength,
    jboolean hasIes, jfloat iesBrightness, jfloat iesMultiplier, jboolean useIesBrightness, jfloat iesBrightnessScale, jfloat cosineOuter)
{
    return evaluate_local_brightness(intensity, unit, inverseSquared, sourceRadius, sourceLength, hasIes,
        iesBrightness, iesMultiplier, useIesBrightness, iesBrightnessScale, cosineOuter);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_render_math_NativeSceneMath_evaluateRotator(
    JNIEnv* env, jclass, jdouble pitch, jdouble yaw, jdouble roll, jdoubleArray output)
{
    const __m128d fullTurn = _mm_set1_pd(360.0);
    const __m128d halfRadians = _mm_set1_pd((3.14159265358979323846264338327950288 / 180.0) / 2.0);
    const __m128d anglesXY = _mm_set_pd(yaw, pitch);
    const __m128d anglesZW = _mm_set_pd(0.0, roll);
    const __m128d windingXY = _mm_round_pd(_mm_div_pd(anglesXY, fullTurn), _MM_FROUND_TO_ZERO | _MM_FROUND_NO_EXC);
    const __m128d windingZW = _mm_round_pd(_mm_div_pd(anglesZW, fullTurn), _MM_FROUND_TO_ZERO | _MM_FROUND_NO_EXC);
    const __m128d halfXY = _mm_mul_pd(_mm_sub_pd(anglesXY, _mm_mul_pd(fullTurn, windingXY)), halfRadians);
    const __m128d halfZW = _mm_mul_pd(_mm_sub_pd(anglesZW, _mm_mul_pd(fullTurn, windingZW)), halfRadians);
    __m128d cosineXY, cosineZW;
    const __m128d sineXY = _mm_sincos_pd(&cosineXY, halfXY);
    const __m128d sineZW = _mm_sincos_pd(&cosineZW, halfZW);
    const __m128d sp = _mm_unpacklo_pd(sineXY, sineXY);
    const __m128d cp = _mm_unpacklo_pd(cosineXY, cosineXY);
    const __m128d sr = _mm_unpacklo_pd(sineZW, sineZW);
    const __m128d cr = _mm_unpacklo_pd(cosineZW, cosineZW);
    const __m128d syCy = _mm_shuffle_pd(sineXY, cosineXY, 3);
    const __m128d cySy = _mm_shuffle_pd(cosineXY, sineXY, 3);
    const __m128d signBoth = _mm_castsi128_pd(_mm_set1_epi64x(INT64_MIN));
    const __m128d signUpper = _mm_castsi128_pd(_mm_set_epi64x(INT64_MIN, 0));
    const __m128d signLower = _mm_castsi128_pd(_mm_set_epi64x(0, INT64_MIN));
    const __m128d leftXY = _mm_xor_pd(_mm_mul_pd(cr, _mm_mul_pd(sp, syCy)), signUpper);
    const __m128d rightXY = _mm_xor_pd(_mm_mul_pd(sr, _mm_mul_pd(cp, cySy)), signBoth);
    const __m128d leftZW = _mm_mul_pd(cr, _mm_mul_pd(cp, syCy));
    const __m128d rightZW = _mm_xor_pd(_mm_mul_pd(sr, _mm_mul_pd(sp, cySy)), signLower);
    jdouble quaternion[4];
    _mm_storeu_pd(quaternion, _mm_add_pd(leftXY, rightXY));
    _mm_storeu_pd(quaternion + 2, _mm_add_pd(leftZW, rightZW));
    env->SetDoubleArrayRegion(output, 0, 4, quaternion);
}

// Four-lane MSVC math matches the Windows CPU script's intrinsic calling path.
extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_NativeVectorMath_evaluate(
    JNIEnv* env, jclass, jint operation, jint groups, jintArray left, jint leftOffset,
    jint leftStride, jintArray right, jint rightOffset, jint rightStride, jintArray destination)
{
    constexpr jint ChunkGroups = 128;
    constexpr jint ChunkValues = ChunkGroups * 4;
    jint leftScratch[ChunkValues];
    jint rightScratch[ChunkValues];
    jint outScratch[ChunkValues];

    if (groups <= 0) return;

    const bool leftConstant = leftStride == 0;
    const bool rightConstant = rightStride == 0;

    if (leftConstant) {
        env->GetIntArrayRegion(left, leftOffset, 4, leftScratch);
        if (env->ExceptionCheck()) return;
    }
    if (rightConstant) {
        env->GetIntArrayRegion(right, rightOffset, 4, rightScratch);
        if (env->ExceptionCheck()) return;
    }

    const __m128 epsilon = _mm_set1_ps(1.e-8f);
    const __m128 signless = _mm_castsi128_ps(_mm_set1_epi32(0x7fffffff));
    const __m128 one = _mm_set1_ps(1.0f);

    for (jint first = 0; first < groups; first += ChunkGroups) {
        jint count = groups - first;
        if (count > ChunkGroups) count = ChunkGroups;
        const jint values = count * 4;

        if (!leftConstant) {
            env->GetIntArrayRegion(left, leftOffset + first * 4, values, leftScratch);
            if (env->ExceptionCheck()) return;
        }
        if (!rightConstant) {
            env->GetIntArrayRegion(right, rightOffset + first * 4, values, rightScratch);
            if (env->ExceptionCheck()) return;
        }

        for (jint group = 0; group < count; ++group) {
            const jint* leftValues = leftConstant ? leftScratch : leftScratch + group * 4;
            const jint* rightValues = rightConstant ? rightScratch : rightScratch + group * 4;
            const __m128 x = _mm_loadu_ps(reinterpret_cast<const float*>(leftValues));
            __m128 result;
            switch (operation) {
                case 0:
                    result = _mm_and_ps(_mm_cmpgt_ps(_mm_and_ps(x, signless), epsilon), _mm_rcp_ps(x));
                    break;
                case 1: result = _mm_sin_ps(x); break;
                case 2: result = _mm_cos_ps(x); break;
                case 3: {
                    const __m128 square = _mm_mul_ps(_mm_sub_ps(one, x), _mm_add_ps(one, x));
                    const __m128 root = _mm_and_ps(_mm_cmpgt_ps(square, epsilon), _mm_sqrt_ps(square));
                    result = _mm_atan2_ps(root, x);
                    break;
                }
                case 4: {
                    const __m128 power = _mm_loadu_ps(reinterpret_cast<const float*>(rightValues));
                    result = _mm_and_ps(_mm_cmpgt_ps(x, epsilon), _mm_pow_ps(x, power));
                    break;
                }
                case 5:
                    result = _mm_and_ps(_mm_cmpgt_ps(x, epsilon), _mm_div_ps(one, _mm_sqrt_ps(x)));
                    break;
                case 6: {
                    const __m128 divisor = _mm_loadu_ps(reinterpret_cast<const float*>(rightValues));
                    const __m128 invalid = _mm_cmple_ps(_mm_and_ps(divisor, signless), epsilon);
                    result = _mm_andnot_ps(invalid, _mm_fmod_ps(x, divisor));
                    break;
                }
                case 7: result = _mm_exp_ps(x); break;
                case 8: result = _mm_exp2_ps(x); break;
                case 9: result = _mm_and_ps(_mm_cmpgt_ps(x, _mm_setzero_ps()), _mm_log_ps(x)); break;
                case 10: result = _mm_log2_ps(x); break;
                case 11: result = _mm_tan_ps(x); break;
                case 12: {
                    const __m128 square = _mm_mul_ps(_mm_sub_ps(one, x), _mm_add_ps(one, x));
                    const __m128 root = _mm_and_ps(_mm_cmpgt_ps(square, epsilon), _mm_sqrt_ps(square));
                    result = _mm_sub_ps(_mm_set1_ps(3.14159265359f * 0.25f), _mm_atan2_ps(root, x));
                    break;
                }
                case 13: result = _mm_atan_ps(x); break;
                case 14: result = _mm_atan2_ps(x, _mm_loadu_ps(reinterpret_cast<const float*>(rightValues))); break;
                default: __assume(0);
            }
            _mm_storeu_ps(reinterpret_cast<float*>(outScratch + group * 4), result);
        }

        env->SetIntArrayRegion(destination, first * 4, values, outScratch);
        if (env->ExceptionCheck()) return;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_NativeVectorMath_evaluateSinCos(
    JNIEnv* env, jclass, jint groups, jintArray input, jint inputOffset, jint inputStride,
    jintArray sine, jintArray cosine)
{
    constexpr jint ChunkGroups = 128;
    constexpr jint ChunkValues = ChunkGroups * 4;
    jint inputScratch[ChunkValues];
    jint sineScratch[ChunkValues];
    jint cosineScratch[ChunkValues];
    if (groups <= 0) return;

    const bool constant = inputStride == 0;
    if (constant) {
        env->GetIntArrayRegion(input, inputOffset, 4, inputScratch);
        if (env->ExceptionCheck()) return;
    }
    for (jint first = 0; first < groups; first += ChunkGroups) {
        jint count = groups - first;
        if (count > ChunkGroups) count = ChunkGroups;
        const jint values = count * 4;
        if (!constant) {
            env->GetIntArrayRegion(input, inputOffset + first * 4, values, inputScratch);
            if (env->ExceptionCheck()) return;
        }
        for (jint group = 0; group < count; ++group) {
            const jint* valuesIn = constant ? inputScratch : inputScratch + group * 4;
            const __m128 inputValue = _mm_loadu_ps(reinterpret_cast<const float*>(valuesIn));
            __m128 cosineValue;
            const __m128 sineValue = _mm_sincos_ps(&cosineValue, inputValue);
            _mm_storeu_ps(reinterpret_cast<float*>(sineScratch + group * 4), sineValue);
            _mm_storeu_ps(reinterpret_cast<float*>(cosineScratch + group * 4), cosineValue);
        }
        env->SetIntArrayRegion(sine, first * 4, values, sineScratch);
        if (env->ExceptionCheck()) return;
        env->SetIntArrayRegion(cosine, first * 4, values, cosineScratch);
        if (env->ExceptionCheck()) return;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_BatchRandom_initialize(JNIEnv* env, jclass, jintArray words)
{
    auto* output = static_cast<jint*>(env->GetPrimitiveArrayCritical(words, nullptr));
    if (output == nullptr) return;
    std::uint64_t state = __rdtsc();
    const std::uint64_t increment = ((reinterpret_cast<std::uintptr_t>(output) << 32) ^ 0xCAFEF00DD15EA5E5ULL) | 1ULL;
    state ^= __rdtsc() << 32;
    for (int index = 0; index < 20; ++index) {
        const std::uint64_t old = state;
        state = old * 6364136223846793005ULL + increment;
        const auto shifted = static_cast<std::uint32_t>(((old >> 18) ^ old) >> 27);
        const auto rotation = static_cast<unsigned>(old >> 59);
        output[index] = static_cast<jint>(_rotr(shifted, rotation));
    }
    output[20] = static_cast<jint>(increment);
    output[21] = static_cast<jint>(increment >> 32);
    output[22] = static_cast<jint>(state);
    output[23] = static_cast<jint>(state >> 32);
    env->ReleasePrimitiveArrayCritical(words, output, 0);
}
