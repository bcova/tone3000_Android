#pragma once

// EngineCore: the platform-independent signal chain.
//
//   guitar in -> input gain -> NAM amp model -> cabinet IR -> output gain -> out
//
// It knows nothing about Android or Oboe, so it can be compiled and tested on a
// desktop machine (see native-tests/). The Android audio layer (AudioEngine)
// feeds it blocks of samples from the real-time audio callback.
//
// Threading rules:
//   * process() and the peak getters are called from the real-time audio thread.
//     They never allocate, lock, or block.
//   * loadModel()/loadIr()/clear*() are called from a background thread. They do
//     the slow work (file parsing, allocation, prewarm) and then swap the new
//     model in with a single atomic pointer exchange.
//   * Setters (gains, toggles) can be called from any thread.

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace nam
{
class DSP;
}

namespace ampengine
{

class EngineCore
{
public:
  // Largest block handed to NAM at once. Bigger callbacks are split into chunks.
  static constexpr int kMaxBlock = 1024;

  explicit EngineCore(double sampleRate);
  ~EngineCore();

  EngineCore(const EngineCore&) = delete;
  EngineCore& operator=(const EngineCore&) = delete;

  // ---- Loading (background thread). Return "" on success, else an error message.
  std::string loadModel(const std::string& path); // .nam amp/pedal capture
  std::string loadIr(const std::string& path); // .wav cabinet impulse response
  void clearModel();
  void clearIr();

  bool hasModel() const { return mModel.load() != nullptr; }
  bool hasIr() const { return mIr.load() != nullptr; }

  // ---- Controls (any thread)
  void setInputGainDb(float db);
  void setOutputGainDb(float db);
  void setModelEnabled(bool enabled) { mModelEnabled.store(enabled); }
  void setIrEnabled(bool enabled) { mIrEnabled.store(enabled); }
  // Match loudness between captures (uses the loudness stored in the .nam file).
  void setNormalize(bool enabled) { mNormalize.store(enabled); }

  // ---- Real-time (audio thread)
  // Mono in, mono out. `in` and `out` may point to the same buffer.
  void process(const float* in, float* out, int numFrames);

  // Peak levels (linear, 0..1+) since the last call. Used for the UI meters.
  float takeInputPeak() { return mInputPeak.exchange(0.0f); }
  float takeOutputPeak() { return mOutputPeak.exchange(0.0f); }

  double sampleRate() const { return mSampleRate; }

private:
  // A loaded DSP plus everything the audio thread needs to run it, so the whole
  // thing can be swapped in one atomic step.
  struct Loaded
  {
    std::unique_ptr<nam::DSP> dsp;
    float normalizeGain = 1.0f; // only used for amp models
    std::vector<std::vector<float>> outBuffers; // one per DSP output channel
    std::vector<float*> outPtrs;
  };

  std::string load(std::atomic<Loaded*>& slot, const std::string& path, bool isIr);
  void replace(std::atomic<Loaded*>& slot, Loaded* next);
  void waitForAudioThread();
  static void runDsp(Loaded& loaded, float* buffer, int numFrames);

  const double mSampleRate;

  std::atomic<Loaded*> mModel{nullptr};
  std::atomic<Loaded*> mIr{nullptr};

  std::atomic<bool> mModelEnabled{true};
  std::atomic<bool> mIrEnabled{true};
  std::atomic<bool> mNormalize{true};

  std::atomic<float> mInputGainTarget{1.0f};
  std::atomic<float> mOutputGainTarget{1.0f};
  float mInputGain = 1.0f; // audio-thread only (smoothed)
  float mOutputGain = 1.0f; // audio-thread only (smoothed)

  std::atomic<float> mInputPeak{0.0f};
  std::atomic<float> mOutputPeak{0.0f};

  // Odd while the audio thread is inside process(). Lets the loader know when
  // it is safe to delete a model that was just swapped out.
  std::atomic<uint64_t> mProcessSeq{0};

  std::mutex mLoadMutex; // one load at a time
  std::vector<float> mScratch; // audio-thread work buffer
};

} // namespace ampengine
