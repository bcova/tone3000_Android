// JNI bridge: the functions Kotlin's `NativeAudio` object calls.

#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

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

JNIEXPORT jstring JNICALL JNI_FN(loadModel)(JNIEnv* env, jobject, jstring path)
{
  return errorOrNull(env, engine().core().loadModel(toStdString(env, path)));
}

JNIEXPORT jstring JNICALL JNI_FN(loadIr)(JNIEnv* env, jobject, jstring path)
{
  return errorOrNull(env, engine().core().loadIr(toStdString(env, path)));
}

JNIEXPORT void JNICALL JNI_FN(clearModel)(JNIEnv*, jobject)
{
  engine().core().clearModel();
}

JNIEXPORT void JNICALL JNI_FN(clearIr)(JNIEnv*, jobject)
{
  engine().core().clearIr();
}

JNIEXPORT void JNICALL JNI_FN(setInputGainDb)(JNIEnv*, jobject, jfloat db)
{
  engine().core().setInputGainDb(db);
}

JNIEXPORT void JNICALL JNI_FN(setOutputGainDb)(JNIEnv*, jobject, jfloat db)
{
  engine().core().setOutputGainDb(db);
}

JNIEXPORT void JNICALL JNI_FN(setModelEnabled)(JNIEnv*, jobject, jboolean enabled)
{
  engine().core().setModelEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(setIrEnabled)(JNIEnv*, jobject, jboolean enabled)
{
  engine().core().setIrEnabled(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(setNormalize)(JNIEnv*, jobject, jboolean enabled)
{
  engine().core().setNormalize(enabled == JNI_TRUE);
}

JNIEXPORT jfloat JNICALL JNI_FN(takeInputPeak)(JNIEnv*, jobject)
{
  return engine().core().takeInputPeak();
}

JNIEXPORT jfloat JNICALL JNI_FN(takeOutputPeak)(JNIEnv*, jobject)
{
  return engine().core().takeOutputPeak();
}

} // extern "C"
