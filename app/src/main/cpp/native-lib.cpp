// JNI bridge: the functions Kotlin's `NativeAudio` object calls.

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "AudioEngine.h"

namespace
{
std::mutex gEngineLock;
std::unique_ptr<AudioEngine> gEngine;

AudioEngine& engine()
{
  std::lock_guard<std::mutex> lock(gEngineLock);
  if (!gEngine)
    gEngine = std::make_unique<AudioEngine>();
  return *gEngine;
}

std::string toStdString(JNIEnv* env, jstring s)
{
  const char* chars = env->GetStringUTFChars(s, nullptr);
  std::string out(chars);
  env->ReleaseStringUTFChars(s, chars);
  return out;
}

// "" (success) -> null; otherwise the error message.
jstring errorOrNull(JNIEnv* env, const std::string& error)
{
  return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}
} // namespace

#define JNI_FN(name) Java_com_bcova_namplayer_audio_NativeAudio_##name

extern "C" {

JNIEXPORT jboolean JNICALL JNI_FN(start)(JNIEnv*, jobject)
{
  return engine().start() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL JNI_FN(stop)(JNIEnv*, jobject)
{
  engine().stop();
}

JNIEXPORT jboolean JNICALL JNI_FN(isRunning)(JNIEnv*, jobject)
{
  return engine().isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JNI_FN(statusText)(JNIEnv* env, jobject)
{
  return env->NewStringUTF(engine().statusText().c_str());
}

JNIEXPORT jstring JNICALL JNI_FN(loadBlock)(JNIEnv* env, jobject, jlong id, jstring path)
{
  return errorOrNull(env, engine().core().loadBlock(id, toStdString(env, path)));
}

JNIEXPORT void JNICALL JNI_FN(removeBlock)(JNIEnv*, jobject, jlong id)
{
  engine().core().removeBlock(id);
}

JNIEXPORT void JNICALL JNI_FN(setOrder)(JNIEnv* env, jobject, jlongArray ids)
{
  const jsize n = env->GetArrayLength(ids);
  std::vector<int64_t> order(static_cast<size_t>(n));
  env->GetLongArrayRegion(ids, 0, n, reinterpret_cast<jlong*>(order.data()));
  engine().core().setOrder(order);
}

JNIEXPORT void JNICALL JNI_FN(setBlockEnabled)(JNIEnv*, jobject, jlong id, jboolean enabled)
{
  engine().core().setBlockEnabled(id, enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(setInputGainDb)(JNIEnv*, jobject, jfloat db)
{
  engine().core().setInputGainDb(db);
}

JNIEXPORT void JNICALL JNI_FN(setOutputGainDb)(JNIEnv*, jobject, jfloat db)
{
  engine().core().setOutputGainDb(db);
}

JNIEXPORT void JNICALL JNI_FN(setNormalize)(JNIEnv*, jobject, jboolean enabled)
{
  engine().core().setNormalize(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(setGate)(JNIEnv*, jobject, jboolean enabled, jfloat thresholdDb)
{
  engine().core().setGate(enabled == JNI_TRUE, thresholdDb);
}

JNIEXPORT void JNICALL JNI_FN(setEq)(JNIEnv*, jobject, jboolean enabled, jfloat bass, jfloat middle, jfloat treble)
{
  engine().core().setEq(enabled == JNI_TRUE, bass, middle, treble);
}

JNIEXPORT void JNICALL JNI_FN(setDevices)(JNIEnv*, jobject, jint inputId, jint outputId)
{
  engine().setDevices(inputId, outputId);
}

JNIEXPORT void JNICALL JNI_FN(setBufferBursts)(JNIEnv*, jobject, jint bursts)
{
  engine().setBufferBursts(bursts);
}

JNIEXPORT jint JNICALL JNI_FN(roundTripLatencyMs)(JNIEnv*, jobject)
{
  return engine().roundTripLatencyMs();
}

JNIEXPORT void JNICALL JNI_FN(setBlockLevelDb)(JNIEnv*, jobject, jlong id, jfloat db)
{
  engine().core().setBlockLevelDb(id, db);
}

JNIEXPORT void JNICALL JNI_FN(setEqBeforeCab)(JNIEnv*, jobject, jboolean before)
{
  engine().core().setEqBeforeCab(before == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(setInputCalibration)(JNIEnv*, jobject, jboolean enabled, jfloat interfaceDbu)
{
  engine().core().setInputCalibration(enabled == JNI_TRUE, interfaceDbu);
}

JNIEXPORT jstring JNICALL JNI_FN(startRecording)(JNIEnv* env, jobject, jstring path)
{
  return errorOrNull(env, engine().core().recorder().start(toStdString(env, path)));
}

JNIEXPORT jdouble JNICALL JNI_FN(stopRecording)(JNIEnv*, jobject)
{
  return engine().core().recorder().stop();
}

JNIEXPORT jdouble JNICALL JNI_FN(recordingSeconds)(JNIEnv*, jobject)
{
  return engine().core().recorder().recording() ? engine().core().recorder().seconds() : -1.0;
}

JNIEXPORT void JNICALL JNI_FN(setTunerActive)(JNIEnv*, jobject, jboolean active)
{
  engine().core().setTunerActive(active == JNI_TRUE);
}

// Returns [hz, clarity]; hz = 0 when there's no clear note.
JNIEXPORT jfloatArray JNICALL JNI_FN(readTunerPitch)(JNIEnv* env, jobject)
{
  const ampengine::PitchResult r = engine().core().readTunerPitch();
  jfloatArray out = env->NewFloatArray(2);
  const jfloat values[2] = {r.hz, r.clarity};
  env->SetFloatArrayRegion(out, 0, 2, values);
  return out;
}

JNIEXPORT jfloat JNICALL JNI_FN(takeInputPeak)(JNIEnv*, jobject)
{
  return engine().core().takeInputPeak();
}

JNIEXPORT jfloat JNICALL JNI_FN(takeOutputPeak)(JNIEnv*, jobject)
{
  return engine().core().takeOutputPeak();
}

JNIEXPORT jfloat JNICALL JNI_FN(takeCpuLoad)(JNIEnv*, jobject)
{
  return engine().core().takeCpuLoad();
}

} // extern "C"
