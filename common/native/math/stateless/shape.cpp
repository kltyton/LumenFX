#include "jni_arrays.h"

namespace lumenfx::stateless {
namespace {
Vec3 location(uint32_t seed, uint32_t module, uint32_t id, const jint* mode, const float* p) {
    if (mode[0] == 0) {
        const Vec3 size = read3(p), random = randomVector(seed, module, id, 0) - Vec3{.5f, .5f, .5f};
        const Quat rotation{p[8], p[9], p[10], p[11]};
        if (mode[1] != 1) return rotation.rotate(random * size);
        static constexpr Vec3 a[6]{{0,1,0},{0,1,0},{0,0,1},{0,0,1},{1,0,0},{1,0,0}};
        static constexpr Vec3 b[6]{{0,0,1},{0,0,1},{1,0,0},{1,0,0},{0,1,0},{0,1,0}};
        static constexpr Vec3 c[6]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        const Vec3 localSize = size + read3(p + 4) * random.z;
        const uint32_t axis = random4(seed, module, id, 1)[0] % uint32_t(mode[2]);
        Vec3 value = (localSize * a[axis]) * random.x;
        value = value + (localSize * b[axis]) * random.y;
        value = value + (localSize * c[axis]) * .5f;
        return rotation.rotate(value);
    }
    if (mode[0] == 1) {
        const auto bits = random4(seed, module, id, 0);
        const float x = float(bits[0] >> 8) / 16777216.f, y = float(bits[1] >> 8) / 16777216.f,
            z = float(bits[2] >> 8) / 16777216.f, w = float(bits[3] >> 8) / 16777216.f;
        float xyOffset = p[11] * z, zOffset = w * p[3] + p[7];
        if ((mode[1] & 1) != 0) {
            const bool caps = (mode[1] & 2) != 0;
            const float thicknessScale = std::bit_cast<float>(mode[2]), thicknessBias = std::bit_cast<float>(mode[3]);
            const uint32_t edge = (random4(seed, module, id, 1)[0] & 3u) + (caps ? 0u : 2u);
            zOffset = edge == 0 ? p[7] - thicknessBias - thicknessScale * w : zOffset;
            zOffset = edge == 1 ? p[7] + p[3] + thicknessBias + thicknessScale * w : zOffset;
            xyOffset = edge >= 2 ? p[11] + thicknessBias + thicknessScale * z : (p[11] + thicknessBias + thicknessScale) * z;
        }
        const float dx = x - .5f, dy = y - .5f, square = dx * dx + dy * dy;
        const float inverse = square < 1.e-4f ? 0.f : 1.f / sqrtf(square);
        const float px = (square < 1.e-4f ? 1.f : dx * inverse) * xyOffset, py = (square < 1.e-4f ? 0.f : dy * inverse) * xyOffset;
        Vec3 value = read3(p) * px; value = value + read3(p + 4) * py; value = value + read3(p + 8) * zOffset;
        return value;
    }
    if (mode[0] == 2) {
        const float radius = randomFloat(seed, module, id, 0) * p[0] + p[1], u = randomFloat(seed, module, id, 1) * p[2] + p[3];
        return (read3(p + 4) * cosf(u)) * radius + (read3(p + 8) * sinf(u)) * radius;
    }
    const Vec3 direction = (randomVector(seed, module, id, 0) - Vec3{.5f, .5f, .5f}).normalized(1.e-4f, {1, 0, 0});
    const Vec3 value = direction * (randomFloat(seed, module, id, 1) * p[3] + p[7]);
    return read3(p) * value.x + read3(p + 4) * value.y + read3(p + 8) * value.z;
}
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_prepareShape(
    JNIEnv* env, jclass, jintArray controlArray, jfloatArray inputArray, jfloatArray poseArray, jintArray modeArray, jfloatArray parameterArray)
{
    using namespace lumenfx::stateless;
    if (!controlArray || env->GetArrayLength(controlArray) != 6 || !inputArray || env->GetArrayLength(inputArray) != 14
            || !poseArray || env->GetArrayLength(poseArray) != 10 || !modeArray || env->GetArrayLength(modeArray) != 4
            || !parameterArray || env->GetArrayLength(parameterArray) != 15) { invalid(env, "Invalid stateless shape preparation"); return; }
    jint control[6], mode[4]{}; float input[14], pose[10], p[15]{};
    env->GetIntArrayRegion(controlArray, 0, 6, control); env->GetFloatArrayRegion(inputArray, 0, 14, input); env->GetFloatArrayRegion(poseArray, 0, 10, pose);
    if (env->ExceptionCheck()) return;
    if (control[0] < 0 || control[0] > 4 || control[1] < 0 || control[1] > 2 || control[4] < 0 || control[4] > 2) {
        invalid(env, "Invalid stateless shape mode"); return;
    }
    const FrameTransform transform(pose);
    const Quat local = control[5] ? Quat{input[0], input[1], input[2], input[3]} : rotatorDegrees(read3(input));
    const Quat rotation = transform.rotation(control[1], local);
    const Vec3 scale = read3(input + 4), axisX = rotation.rotate({1,0,0}) * scale.x,
        axisY = rotation.rotate({0,1,0}) * scale.y, axisZ = rotation.rotate({0,0,1}) * scale.z;
    write3(p + 12, read3(input + 7));
    const float thickness = input[13], half = thickness * .5f;
    if (control[0] == 0 || control[0] == 2) {
        const float bias = control[4] == 0 ? -half : control[4] == 2 ? half : 0.f;
        mode[0] = 0; mode[1] = control[2] ? 1 : 0; mode[2] = control[0] == 0 ? 6 : 4;
        const Vec3 size = control[0] == 0 ? read3(input + 10) * scale : Vec3{input[10] * scale.x, input[11] * scale.y, 0.f};
        write3(p, size + Vec3{bias, bias, control[0] == 0 ? bias : 0.f});
        write3(p + 4, {thickness, thickness, control[0] == 0 ? thickness : 0.f});
        p[8] = rotation.x; p[9] = rotation.y; p[10] = rotation.z; p[11] = rotation.w;
    } else if (control[0] == 1) {
        mode[0] = 1; mode[1] = (control[2] ? 1 : 0) | (control[3] ? 2 : 0);
        mode[2] = std::bit_cast<jint>(thickness); mode[3] = std::bit_cast<jint>(control[4] == 0 ? -thickness : control[4] == 1 ? -half : 0.f);
        write3(p, axisX); p[3] = input[10]; write3(p + 4, axisY); p[7] = input[10] * -input[12]; write3(p + 8, axisZ); p[11] = input[11];
    } else if (control[0] == 3) {
        const float coverage = clamp(1.f - input[11], 0.f, 1.f), root = coverage > 0.f ? sqrtf(coverage) : 0.f;
        mode[0] = 2; p[0] = input[10] * (1.f - root); p[1] = input[10] * root; p[2] = -(2.f * Pi) * (1.f - input[12]);
        write3(p + 4, axisX); write3(p + 8, axisY);
    } else {
        mode[0] = 3; write3(p, axisX); p[3] = input[11]; write3(p + 4, axisY); p[7] = input[10]; write3(p + 8, axisZ);
    }
    env->SetIntArrayRegion(modeArray, 0, 4, mode); env->SetFloatArrayRegion(parameterArray, 0, 15, p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_shape(
    JNIEnv* env, jclass, jint count, jint emitterSeed, jint moduleSeed, jintArray modeArray, jfloatArray parameterArray,
    jobjectArray requiredArray, jobjectArray offsetArray, jobjectArray outputArray)
{
    using namespace lumenfx::stateless;
    if (count <= 0) return;
    if (!modeArray || env->GetArrayLength(modeArray) != 4 || !parameterArray || env->GetArrayLength(parameterArray) != 15) {
        invalid(env, "Invalid stateless shape parameters"); return;
    }
    jint mode[4]; float p[15]; env->GetIntArrayRegion(modeArray, 0, 4, mode); env->GetFloatArrayRegion(parameterArray, 0, 15, p);
    if (env->ExceptionCheck()) return;
    if (mode[0] < 0 || mode[0] > 3 || (mode[0] == 0 && mode[1] == 1 && mode[2] != 4 && mode[2] != 6)) {
        invalid(env, "Invalid stateless shape face selection"); return;
    }
    WordColumns<1> required(env, JNI_ABORT); WordColumns<6> offsets(env, JNI_ABORT), output(env, 0);
    if (!required.acquire(requiredArray, count, false) || !offsets.acquire(offsetArray, count, false) || !output.acquire(outputArray, count, true)) return;
    for (int row = 0; row < count; ++row) {
        const Vec3 shape = location(uint32_t(emitterSeed), uint32_t(moduleSeed), uint32_t(required[0][row]), mode, p);
        const Vec3 current = output.readVector(0, row), previous = output.readVector(3, row), factor = read3(p + 12);
        output.writeVector(0, row, current + offsets.readVector(0, row) * factor + shape);
        output.writeVector(3, row, previous + offsets.readVector(3, row) * factor + shape);
    }
}
