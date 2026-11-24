#include "jni_arrays.h"

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_subImage(
    JNIEnv* env, jclass, jint count, jint emitterSeed, jint moduleSeed, jint mode,
    jobjectArray requiredArray, jobjectArray outputArray, jfloatArray parameterArray)
{
    using namespace lumenfx::stateless;
    if (count <= 0) return;
    if (!parameterArray || env->GetArrayLength(parameterArray) != 7) { invalid(env, "Invalid stateless subimage parameters"); return; }
    float p[7]; env->GetFloatArrayRegion(parameterArray, 0, 7, p);
    if (env->ExceptionCheck()) return;
    WordColumns<3> required(env, JNI_ABORT); WordColumns<1> output(env, 0);
    if (!required.acquire(requiredArray, count, false) || !output.acquire(outputArray, count, false)) return;
    for (int row = 0; row < count; ++row) {
        const float age = required.read(1, row), normalizedAge = required.read(2, row);
        const uint32_t call = uint32_t(age * p[3]);
        float frame = randomFloat(uint32_t(emitterSeed), uint32_t(moduleSeed), uint32_t(required[0][row]), call) * p[1] + p[2];
        if (mode == 1) { const float interpolation = age * p[6]; frame = fractional(frame + p[4] + interpolation * p[5]); }
        else if (mode == 2) { const float interpolation = normalizedAge * p[6]; frame = clamp(frame + p[4] + interpolation * p[5], 0.f, 1.f); }
        output.write(0, row, frame * p[0]);
    }
}
