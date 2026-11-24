#include <jni.h>
#include <emmintrin.h>
#include <cmath>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kltyton_lumenfx_client_simulation_niagara_parameter_store_NativeParameterStoreMath_useMerge(
    JNIEnv*, jclass, jint sourceCount, jint destinationCount)
{
    const int minimum = sourceCount < destinationCount ? sourceCount : destinationCount;
    const int maximum = sourceCount < destinationCount ? destinationCount : sourceCount;
    const float logarithm = logf(static_cast<float>(maximum)) * 1.4426950f;
    const int rounded = _mm_cvtss_si32(_mm_set_ss(logarithm + logarithm + .5f)) >> 1;
    return minimum * rounded >= sourceCount + destinationCount;
}
