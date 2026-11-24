#include "jni_arrays.h"

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_prepareInitialOrientation(
    JNIEnv* env, jclass, jint mode, jfloatArray inputArray, jfloatArray outputArray)
{
    using namespace lumenfx::stateless;
    if (!inputArray || env->GetArrayLength(inputArray) != 12 || !outputArray || env->GetArrayLength(outputArray) != 6) {
        invalid(env, "Invalid stateless mesh orientation preparation"); return;
    }
    float input[12], output[6]; env->GetFloatArrayRegion(inputArray, 0, 12, input);
    if (env->ExceptionCheck()) return;
    const Vec3 scale = read3(input), bias = read3(input + 3);
    Vec3 adjustment{};
    if (mode == 2) {
        const Vec3 from = read3(input + 6).normalized(1.e-4f, {1, 0, 0}), to = read3(input + 9).normalized(1.e-4f, {1, 0, 0});
        adjustment = euler(betweenVectors(from, to));
    }
    write3(output, scale / 360.f); write3(output + 3, (bias + adjustment) / 360.f);
    env->SetFloatArrayRegion(outputArray, 0, 6, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_initialOrientation(
    JNIEnv* env, jclass, jint count, jint emitterSeed, jint moduleSeed,
    jobjectArray requiredArray, jobjectArray outputArray, jfloatArray parameterArray)
{
    using namespace lumenfx::stateless;
    if (count <= 0) return;
    if (!parameterArray || env->GetArrayLength(parameterArray) != 6) { invalid(env, "Invalid stateless mesh orientation parameters"); return; }
    float p[6]; env->GetFloatArrayRegion(parameterArray, 0, 6, p);
    if (env->ExceptionCheck()) return;
    WordColumns<1> required(env, JNI_ABORT); WordColumns<8> output(env, 0);
    if (!required.acquire(requiredArray, count, false) || !output.acquire(outputArray, count, true)) return;
    for (int row = 0; row < count; ++row) {
        const Vec3 rotation = randomVector(uint32_t(emitterSeed), uint32_t(moduleSeed), uint32_t(required[0][row]), 0) * read3(p) + read3(p + 3);
        const Quat q = rotatorTurns(rotation);
        for (int first : {0, 4}) { output.write(first, row, q.x); output.write(first + 1, row, q.y); output.write(first + 2, row, q.z); output.write(first + 3, row, q.w); }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_applyMeshRotation(
    JNIEnv* env, jclass, jint count, jobjectArray angleArray, jobjectArray outputArray)
{
    using namespace lumenfx::stateless;
    if (count <= 0) return;
    WordColumns<6> angles(env, JNI_ABORT); WordColumns<8> output(env, 0);
    if (!angles.acquire(angleArray, count, false) || !output.acquire(outputArray, count, true)) return;
    for (int row = 0; row < count; ++row) {
        const Quat current{output.read(0, row), output.read(1, row), output.read(2, row), output.read(3, row, 1.f)};
        const Quat previous{output.read(4, row), output.read(5, row), output.read(6, row), output.read(7, row, 1.f)};
        const Quat value = current * rotatorTurns(angles.readVector(0, row)), old = previous * rotatorTurns(angles.readVector(3, row));
        output.write(0, row, value.x); output.write(1, row, value.y); output.write(2, row, value.z); output.write(3, row, value.w);
        output.write(4, row, old.x); output.write(5, row, old.y); output.write(6, row, old.z); output.write(7, row, old.w);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_toSimulationRotation(
    JNIEnv* env, jclass, jint space, jfloatArray poseArray, jfloatArray outputArray)
{
    using namespace lumenfx::stateless;
    if (space < 0 || space > 2 || !poseArray || env->GetArrayLength(poseArray) != 10 || !outputArray || env->GetArrayLength(outputArray) != 4) {
        invalid(env, "Invalid stateless rotation space"); return;
    }
    float pose[10]; env->GetFloatArrayRegion(poseArray, 0, 10, pose);
    if (env->ExceptionCheck()) return;
    const Quat rotation = space == 1 ? worldToSimulationRotation(pose) : Quat{};
    const float output[4]{rotation.x, rotation.y, rotation.z, rotation.w};
    env->SetFloatArrayRegion(outputArray, 0, 4, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_decalOrientation(
    JNIEnv* env, jclass, jint count, jobjectArray angleArray, jobjectArray outputArray, jfloatArray rotationArray)
{
    using namespace lumenfx::stateless;
    if (count <= 0) return;
    if (!rotationArray || env->GetArrayLength(rotationArray) != 4) { invalid(env, "Invalid stateless decal rotation"); return; }
    float p[4]; env->GetFloatArrayRegion(rotationArray, 0, 4, p);
    if (env->ExceptionCheck()) return;
    const Quat rotation{p[0], p[1], p[2], p[3]};
    WordColumns<3> angles(env, JNI_ABORT); WordColumns<4> output(env, 0);
    if (!angles.acquire(angleArray, count, false) || !output.acquire(outputArray, count, false)) return;
    for (int row = 0; row < count; ++row) {
        const Quat value = rotation * rotatorDegrees(angles.readVector(0, row));
        output.write(0, row, value.x); output.write(1, row, value.y); output.write(2, row, value.z); output.write(3, row, value.w);
    }
}
