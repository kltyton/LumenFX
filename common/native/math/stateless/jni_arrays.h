#pragma once
#include <jni.h>
#include <array>
#include <bit>
#include "float_math.h"

namespace lumenfx::stateless {
inline void invalid(JNIEnv* env, const char* message) {
    jclass error = env->FindClass("java/lang/IllegalArgumentException");
    if (error) { env->ThrowNew(error, message); env->DeleteLocalRef(error); }
}
template<size_t N> class WordColumns {
    JNIEnv* env;
    jint releaseMode;
    std::array<jintArray, N> arrays{};
    std::array<jint*, N> data{};
public:
    WordColumns(JNIEnv* env, jint releaseMode) : env(env), releaseMode(releaseMode) {}
    ~WordColumns() {
        for (size_t i = 0; i < N; ++i) {
            if (data[i]) env->ReleaseIntArrayElements(arrays[i], data[i], releaseMode);
            if (arrays[i]) env->DeleteLocalRef(arrays[i]);
        }
    }
    bool acquire(jobjectArray source, int count, bool optional) {
        if (!source || env->GetArrayLength(source) != N) { invalid(env, "Invalid stateless native component layout"); return false; }
        for (size_t i = 0; i < N; ++i) {
            arrays[i] = static_cast<jintArray>(env->GetObjectArrayElement(source, jsize(i)));
            if (env->ExceptionCheck()) return false;
            if (!arrays[i] && optional) continue;
            if (!arrays[i] || env->GetArrayLength(arrays[i]) < count) { invalid(env, "Stateless native component has insufficient rows"); return false; }
            data[i] = env->GetIntArrayElements(arrays[i], nullptr);
            if (!data[i]) return false;
        }
        return true;
    }
    jint* operator[](size_t column) const { return data[column]; }
    float read(size_t column, int row, float fallback = 0.f) const { return data[column] ? std::bit_cast<float>(data[column][row]) : fallback; }
    void write(size_t column, int row, float value) { if (data[column]) data[column][row] = std::bit_cast<jint>(value); }
    Vec3 readVector(size_t first, int row, Vec3 fallback = {}) const {
        return {read(first, row, fallback.x), read(first + 1, row, fallback.y), read(first + 2, row, fallback.z)};
    }
    void writeVector(size_t first, int row, Vec3 value) { write(first, row, value.x); write(first + 1, row, value.y); write(first + 2, row, value.z); }
};
}
