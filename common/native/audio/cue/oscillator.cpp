#include <jni.h>
#include <cmath>

extern "C" JNIEXPORT jfloat JNICALL
Java_com_kltyton_lumenfx_client_audio_cue_NativeCueMath_oscillator(JNIEnv*, jclass,
        jfloat center, jfloat amplitude, jfloat frequency, jfloat offset, jfloat playback) {
    const float phase = offset + (frequency * playback) * 3.1415926535897932f;
    return center + amplitude * std::sin(phase);
}
