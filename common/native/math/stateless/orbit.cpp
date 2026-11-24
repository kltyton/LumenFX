#include "jni_arrays.h"

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_prepareOrbit(
    JNIEnv* env, jclass, jint centerSpace, jint rotationSpace, jfloatArray inputArray, jfloatArray poseArray, jfloatArray outputArray)
{
    using namespace lumenfx::stateless;
    if (centerSpace < 0 || centerSpace > 2 || rotationSpace < 0 || rotationSpace > 2
            || !inputArray || env->GetArrayLength(inputArray) != 18 || !poseArray || env->GetArrayLength(poseArray) != 10
            || !outputArray || env->GetArrayLength(outputArray) != 18) { invalid(env, "Invalid stateless orbit preparation"); return; }
    float input[18], pose[10], output[18]; env->GetFloatArrayRegion(inputArray, 0, 18, input); env->GetFloatArrayRegion(poseArray, 0, 10, pose);
    if (env->ExceptionCheck()) return;
    for (int index = 0; index < 6; ++index) output[index] = input[index];
    output[0] *= Pi / 180.f; output[1] *= Pi / 180.f;
    const FrameTransform transform(pose);
    write3(output + 6, transform.position(centerSpace, read3(input + 6)));
    write3(output + 9, transform.vector(centerSpace, read3(input + 9)));
    const Vec3 rotation = read3(input + 12);
    const Quat quaternion = transform.rotation(rotationSpace, rotatorDegrees({rotation.y, rotation.z, rotation.x}));
    write3(output + 12, euler(quaternion) / 360.f); write3(output + 15, read3(input + 15) / 360.f);
    env->SetFloatArrayRegion(outputArray, 0, 18, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_orbit(
    JNIEnv* env, jclass, jint count, jint emitterSeed, jint moduleSeed,
    jobjectArray requiredArray, jobjectArray outputArray, jfloatArray parameterArray)
{
    using namespace lumenfx::stateless;
    if (count <= 0) return;
    if (!parameterArray || env->GetArrayLength(parameterArray) != 18) { invalid(env, "Invalid stateless orbit parameters"); return; }
    float p[18]; env->GetFloatArrayRegion(parameterArray, 0, 18, p);
    if (env->ExceptionCheck()) return;
    WordColumns<3> required(env, JNI_ABORT); WordColumns<6> output(env, 0);
    if (!required.acquire(requiredArray, count, false) || !output.acquire(outputArray, count, true)) return;
    for (int row = 0; row < count; ++row) {
        const uint32_t id = uint32_t(required[0][row]);
        const float rate = randomFloat(uint32_t(emitterSeed), uint32_t(moduleSeed), id, 0) * p[1] + p[0];
        const float radius = randomFloat(uint32_t(emitterSeed), uint32_t(moduleSeed), id, 1) * p[3] + p[2];
        const float phase = randomFloat(uint32_t(emitterSeed), uint32_t(moduleSeed), id, 2) * p[5] + p[4];
        const Vec3 center = randomVector(uint32_t(emitterSeed), uint32_t(moduleSeed), id, 3) * read3(p + 9) + read3(p + 6);
        const Quat rotation = rotatorTurns(randomVector(uint32_t(emitterSeed), uint32_t(moduleSeed), id, 4) * read3(p + 15) + read3(p + 12));
        const float time = phase + required.read(1, row) * rate, previousTime = phase + required.read(2, row) * rate;
        const Vec3 current = output.readVector(0, row), previous = output.readVector(3, row);
        output.writeVector(0, row, current + (center + rotation.rotate(Vec3{cosf(time), sinf(time), 0.f} * radius)));
        output.writeVector(3, row, previous + (center + rotation.rotate(Vec3{cosf(previousTime), sinf(previousTime), 0.f} * radius)));
    }
}
