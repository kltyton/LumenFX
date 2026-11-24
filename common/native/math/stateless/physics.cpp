#include "float_math.h"
#include "jni_arrays.h"
#include "noise_table.h"
#include <algorithm>

namespace lumenfx::stateless {
namespace {
enum Parameter {
    MassBias = 0, MassScale = 1, DragBias = 2, DragScale = 3,
    VelocityBias = 4, VelocityScale = 7, WindBias = 10, WindScale = 13,
    AccelerationBias = 16, AccelerationScale = 19, GravityBias = 22, GravityScale = 25,
    ConeEnabled = 28, ConeQuaternion = 29, ConeAngleBias = 33, ConeAngleScale = 34,
    ConeVelocityBias = 35, ConeVelocityScale = 36, ConeFalloff = 37,
    PointEnabled = 38, PointOrigin = 39, PointVelocityBias = 42, PointVelocityScale = 43,
    NoiseEnabled = 44, NoiseStrength = 45, NoiseFrequency = 46, NoiseNormalize = 47,
    NoiseOffsetBias = 48, NoiseOffsetScale = 51, OwnerVelocityScale = 54, OwnerAccelerationScale = 57,
    ParameterCount = 60, LinearMultiplier = 60, ConeMultiplier = 61, PointMultiplier = 62,
    ForceAmounts = 63, VelocityAmounts = 66, InputCount = 69
};
Quat quaternion(const float* p) { return {p[0], p[1], p[2], p[3]}; }
void quaternion(float* p, Quat value) { p[0] = value.x; p[1] = value.y; p[2] = value.z; p[3] = value.w; }
Vec3 integrate(float age, float drag, Vec3 velocity, Vec3 wind, Vec3 acceleration) {
    if (drag > .0001f) {
        const Vec3 terminal = acceleration / drag + wind, initial = velocity - terminal;
        const float lambda = (1.f - expf(-(age * drag))) / drag;
        return initial * lambda + terminal * age;
    }
    return (velocity + wind) * age + ((acceleration * .5f) * age) * age;
}
Vec3 noise(const NoiseTable& table, const float* p, float lifetime, float age, Vec3 offset, Vec3 position) {
    const Vec3 sample = (position + offset) * p[NoiseFrequency];
    const float row = Vec3{fabsf(sample.x), fabsf(sample.y), fabsf(sample.z)}.dot({1.f / 8.f, 2.f / 8.f, 5.f / 8.f});
    const uint32_t a = uint32_t(floorInt(fabsf(row))) & 31u, b = (a + 1) & 31u;
    const float distance = lifetime * p[NoiseStrength], end = clamp(distance * p[NoiseNormalize], 1.f, 63.f);
    const float column = age * end;
    const uint32_t ca = std::min(uint32_t(floorInt(column)), 63u), cb = std::min(ca + 1, 63u);
    const Vec3 valueA = lerp(table[a * 64 + ca], table[b * 64 + ca], fractional(row));
    const Vec3 valueB = lerp(table[a * 64 + cb], table[b * 64 + cb], fractional(row));
    return position + (lerp(valueA, valueB, fractional(column)) * distance) / end;
}
}
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_preparePhysics(
    JNIEnv* env, jclass, jintArray controlArray, jfloatArray inputArray, jfloatArray poseArray, jfloatArray outputArray)
{
    using namespace lumenfx::stateless;
    if (!controlArray || env->GetArrayLength(controlArray) != 9 || !inputArray || env->GetArrayLength(inputArray) != InputCount
            || !poseArray || env->GetArrayLength(poseArray) != 10 || !outputArray || env->GetArrayLength(outputArray) != ParameterCount) {
        invalid(env, "Invalid stateless physics preparation layout"); return;
    }
    jint controls[9]; jfloat input[InputCount], pose[10], output[ParameterCount];
    env->GetIntArrayRegion(controlArray, 0, 9, controls);
    env->GetFloatArrayRegion(inputArray, 0, InputCount, input);
    env->GetFloatArrayRegion(poseArray, 0, 10, pose);
    if (env->ExceptionCheck()) return;
    std::copy_n(input, ParameterCount, output);
    const FrameTransform transforms(pose);
    const Vec3 componentScale = transforms.componentScale;
    const auto rotatePair = [&](int bias, int scale, int space) {
        write3(output + bias, transforms.vectorNoScale(space, read3(input + bias)));
        write3(output + scale, transforms.vectorNoScale(space, read3(input + scale)));
    };
    rotatePair(VelocityBias, VelocityScale, controls[0]);
    write3(output + VelocityBias, read3(output + VelocityBias) * input[LinearMultiplier]);
    write3(output + VelocityScale, read3(output + VelocityScale) * input[LinearMultiplier]);
    rotatePair(WindBias, WindScale, controls[1]);
    rotatePair(AccelerationBias, AccelerationScale, controls[2]);
    rotatePair(GravityBias, GravityScale, 1);
    const Quat localCone = controls[5] ? rotatorDegrees(read3(input + ConeQuaternion)) : directionQuat(read3(input + ConeQuaternion));
    quaternion(output + ConeQuaternion, transforms.rotation(controls[3], localCone));
    output[ConeAngleBias] = input[ConeAngleBias] * (Pi / 360.f);
    output[ConeAngleScale] = (input[ConeAngleScale] - input[ConeAngleBias]) * (Pi / 360.f);
    output[ConeVelocityBias] *= input[ConeMultiplier]; output[ConeVelocityScale] *= input[ConeMultiplier];
    write3(output + PointOrigin, transforms.position(controls[4], read3(input + PointOrigin)));
    output[PointVelocityBias] *= input[PointMultiplier]; output[PointVelocityScale] *= input[PointMultiplier];
    output[NoiseFrequency] = input[NoiseFrequency] / 50.f;
    output[NoiseNormalize] = output[NoiseFrequency] * .1f;
    Vec3 accelerationScale{1, 1, 1}, velocityScale{1, 1, 1};
    if (controls[6]) {
        Vec3 amount = read3(input + ForceAmounts);
        if (controls[7]) amount = {
            amount.x > 1.e-8f ? 1.f / sqrtf(amount.x) : 0.f,
            amount.y > 1.e-8f ? 1.f / sqrtf(amount.y) : 0.f,
            amount.z > 1.e-8f ? 1.f / sqrtf(amount.z) : 0.f};
        accelerationScale = Vec3{1, 1, 1} + (componentScale - Vec3{1, 1, 1}) * amount;
    }
    if (controls[8]) velocityScale = Vec3{1, 1, 1} + (componentScale - Vec3{1, 1, 1}) * read3(input + VelocityAmounts);
    write3(output + OwnerAccelerationScale, accelerationScale); write3(output + OwnerVelocityScale, velocityScale);
    env->SetFloatArrayRegion(outputArray, 0, ParameterCount, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_stateless_math_NativeStatelessMath_solvePhysics(
    JNIEnv* env, jclass, jint count, jint emitterSeed, jint moduleSeed, jfloat delta,
    jobjectArray requiredArray, jobjectArray componentArray, jfloatArray parameterArray)
{
    using namespace lumenfx::stateless;
    if (count <= 0) return;
    if (!parameterArray || env->GetArrayLength(parameterArray) != ParameterCount) { invalid(env, "Invalid stateless physics parameters"); return; }
    float p[ParameterCount]; env->GetFloatArrayRegion(parameterArray, 0, ParameterCount, p);
    if (env->ExceptionCheck()) return;
    WordColumns<6> required(env, JNI_ABORT);
    WordColumns<12> output(env, 0);
    if (!required.acquire(requiredArray, count, false) || !output.acquire(componentArray, count, true)) return;
    const float inverseDelta = delta > 0.f ? 1.f / delta : 0.f;
    const NoiseTable* table = p[NoiseEnabled] != 0.f ? &noiseTable() : nullptr;
    for (int row = 0; row < count; ++row) {
        const uint32_t id = uint32_t(required[0][row]);
        const auto scalar = [&](int call, int bias, int scale) { return randomFloat(uint32_t(emitterSeed), uint32_t(moduleSeed), id, call) * p[scale] + p[bias]; };
        const auto vector = [&](int call, int bias, int scale) { return randomVector(uint32_t(emitterSeed), uint32_t(moduleSeed), id, call) * read3(p + scale) + read3(p + bias); };
        const float mass = scalar(0, MassBias, MassScale), drag = scalar(1, DragBias, DragScale);
        Vec3 velocity = vector(2, VelocityBias, VelocityScale), wind = vector(3, WindBias, WindScale);
        const float massFactor = 1.f / maximum(mass, 1.e-4f);
        Vec3 acceleration = vector(4, AccelerationBias, AccelerationScale) * massFactor;
        acceleration = acceleration + vector(41, GravityBias, GravityScale);
        Vec3 position = output.readVector(0, row), previous = output.readVector(3, row, position);
        if (p[ConeEnabled] != 0.f) {
            const float angle = scalar(5, ConeAngleBias, ConeAngleScale), rotation = randomFloat(uint32_t(emitterSeed), uint32_t(moduleSeed), id, 6) * (2.f * Pi);
            const float sineAngle = sinf(angle), cosineAngle = cosf(angle), sineRotation = sinf(rotation), cosineRotation = cosf(rotation);
            const Vec3 direction{sineRotation * sineAngle, cosineRotation * sineAngle, cosineAngle};
            float scale = scalar(7, ConeVelocityBias, ConeVelocityScale);
            if (p[ConeFalloff] > 0.f) {
                const float falloff = powf(clamp(cosineAngle, 0.f, 1.f), p[ConeFalloff] * 10.f);
                scale *= 1.f + p[ConeFalloff] * (falloff - 1.f);
            }
            velocity = velocity + quaternion(p + ConeQuaternion).rotate(direction) * scale;
        }
        if (p[PointEnabled] != 0.f) {
            const Vec3 fallback = (randomVector(uint32_t(emitterSeed), uint32_t(moduleSeed), id, 8) - Vec3{.5f, .5f, .5f}).normalized(1.e-4f, {1, 0, 0});
            const Vec3 direction = (position - read3(p + PointOrigin)).normalized(1.e-4f, fallback);
            velocity = velocity + direction * scalar(9, PointVelocityBias, PointVelocityScale);
        }
        if (table) {
            const Vec3 offset = vector(10, NoiseOffsetBias, NoiseOffsetScale);
            const float lifetime = required.read(1, row);
            position = noise(*table, p, lifetime, required.read(4, row), offset, position);
            previous = noise(*table, p, lifetime, required.read(5, row), offset, previous);
        }
        velocity = velocity * read3(p + OwnerVelocityScale);
        wind = wind * read3(p + OwnerAccelerationScale); acceleration = acceleration * read3(p + OwnerAccelerationScale);
        position = position + integrate(maximum(required.read(2, row), 1.e-4f), drag, velocity, wind, acceleration);
        previous = previous + integrate(required.read(3, row), drag, velocity, wind, acceleration);
        output.writeVector(0, row, position); output.writeVector(3, row, previous);
        const Vec3 finalVelocity = (position - previous) * inverseDelta;
        output.writeVector(6, row, finalVelocity); output.writeVector(9, row, finalVelocity);
    }
}
