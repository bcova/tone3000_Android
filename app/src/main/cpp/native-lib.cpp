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

JNIEXPORT void JNICALL JNI_FN(setSharedMode)(JNIEnv*, jobject, jboolean shared)
{
  engine().setSharedMode(shared == JNI_TRUE);
}

// ---- Metronome / drums
JNIEXPORT void JNICALL JNI_FN(setRhythm)(JNIEnv*, jobject, jboolean playing, jfloat bpm, jint style, jint beatsPerBar,
                                         jfloat volume)
{
  auto& r = engine().core().rhythm();
  r.setBpm(bpm);
  r.setStyle(static_cast<ampengine::RhythmStyle>(style));
  r.setBeatsPerBar(beatsPerBar);
  r.setVolume(volume);
  r.setPlaying(playing == JNI_TRUE);
}

JNIEXPORT jint JNICALL JNI_FN(rhythmBeat)(JNIEnv*, jobject)
{
  return engine().core().rhythm().currentBeat();
}

// ---- Looper
JNIEXPORT void JNICALL JNI_FN(looperPrepare)(JNIEnv*, jobject)
{
  engine().core().looper().prepare();
}

JNIEXPORT void JNICALL JNI_FN(looperCommand)(JNIEnv*, jobject, jint command)
{
  engine().core().looper().command(static_cast<ampengine::Looper::Command>(command));
}

JNIEXPORT void JNICALL JNI_FN(looperVolume)(JNIEnv*, jobject, jfloat volume)
{
  engine().core().looper().setVolume(volume);
}

// Returns [state, lengthSeconds, positionSeconds, layers, canUndo(0/1)].
JNIEXPORT jdoubleArray JNICALL JNI_FN(looperStatus)(JNIEnv* env, jobject)
{
  auto& l = engine().core().looper();
  const jdouble v[5] = {static_cast<double>(l.state()), l.lengthSeconds(), l.positionSeconds(),
                        static_cast<double>(l.layers()), l.canUndo() ? 1.0 : 0.0};
  jdoubleArray out = env->NewDoubleArray(5);
  env->SetDoubleArrayRegion(out, 0, 5, v);
  return out;
}

// ---- Backing track
JNIEXPORT jstring JNICALL JNI_FN(trackOpen)(JNIEnv* env, jobject, jstring path)
{
  return errorOrNull(env, engine().core().track().open(toStdString(env, path)));
}

JNIEXPORT void JNICALL JNI_FN(trackClose)(JNIEnv*, jobject)
{
  engine().core().track().close();
}

JNIEXPORT void JNICALL JNI_FN(trackPlay)(JNIEnv*, jobject, jboolean playing)
{
  engine().core().track().setPlaying(playing == JNI_TRUE);
}

JNIEXPORT void JNICALL JNI_FN(trackSeek)(JNIEnv*, jobject, jdouble seconds)
{
  engine().core().track().seek(seconds);
}

JNIEXPORT void JNICALL JNI_FN(trackLoop)(JNIEnv*, jobject, jdouble a, jdouble b)
{
  engine().core().track().setLoop(a, b);
}

JNIEXPORT void JNICALL JNI_FN(trackVolume)(JNIEnv*, jobject, jfloat volume)
{
  engine().core().track().setVolume(volume);
}

// Returns [playing(0/1), positionSeconds, durationSeconds].
JNIEXPORT jdoubleArray JNICALL JNI_FN(trackStatus)(JNIEnv* env, jobject)
{
  auto& t = engine().core().track();
  const jdouble v[3] = {t.playing() ? 1.0 : 0.0, t.positionSeconds(), t.durationSeconds()};
  jdoubleArray out = env->NewDoubleArray(3);
  env->SetDoubleArrayRegion(out, 0, 3, v);
  return out;
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
