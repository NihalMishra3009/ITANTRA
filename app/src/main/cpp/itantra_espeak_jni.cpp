// JNI bridge for the eSpeak NG synthesis core (see itantra_espeak_core.c).
// Bound to com.itantra.tts.EspeakNative (static methods).
#include <jni.h>

#include <vector>

#include "itantra_espeak_core.h"

namespace {
std::vector<char> toUtf8Cstr(JNIEnv *env, jbyteArray bytes) {
    std::vector<char> out;
    if (!bytes) return out;
    const jsize n = env->GetArrayLength(bytes);
    out.resize(static_cast<size_t>(n) + 1, 0);
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}
}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_itantra_tts_EspeakNative_nInit(JNIEnv *env, jclass, jbyteArray dataParent) {
    std::vector<char> dir = toUtf8Cstr(env, dataParent);
    if (dir.empty()) return -1;
    return itx_init(dir.data());
}

JNIEXPORT jint JNICALL
Java_com_itantra_tts_EspeakNative_nSetVoice(JNIEnv *env, jclass, jbyteArray voice) {
    std::vector<char> v = toUtf8Cstr(env, voice);
    if (v.empty()) return -1;
    return itx_set_voice(v.data());
}

// Returns 16-bit mono PCM, or null on failure.
JNIEXPORT jshortArray JNICALL
Java_com_itantra_tts_EspeakNative_nSynth(JNIEnv *env, jclass, jbyteArray text, jint rateWpm) {
    std::vector<char> t = toUtf8Cstr(env, text);
    if (t.empty()) return nullptr;
    short *pcm = nullptr;
    int n = 0;
    if (itx_synth(t.data(), rateWpm, &pcm, &n) != 0) return nullptr;
    jshortArray arr = env->NewShortArray(n);
    if (arr != nullptr && n > 0) env->SetShortArrayRegion(arr, 0, n, pcm);
    itx_free(pcm);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_itantra_tts_EspeakNative_nTerminate(JNIEnv *, jclass) {
    itx_terminate();
}

}  // extern "C"
