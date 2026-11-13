#pragma once

// EngineCore: the platform-independent signal chain.
//
//   guitar in -> input gain -> [gate listens] -> block 1 -> block 2 -> ...
//            -> [gate turns down] -> EQ (bass/mid/treble) -> output gain -> out
//
// Each block is a NAM capture (.nam: pedal, amp, outboard...) or an impulse
// response (.wav: cabinet, room...). Blocks are identified by an id chosen by the
// app, and their order is set with setOrder().
//
// It knows nothing about Android or Oboe, so it can be compiled and tested on a
// desktop machine (see native-tests/). The Android audio layer (AudioEngine)
// feeds it blocks of samples from the real-time audio callback.
//
// Threading rules:
//   * process() and the meter getters are called from the real-time audio thread.
//     They never allocate, lock, or block.
//   * loadBlock()/removeBlock()/setOrder() are called from a background thread.
//     They do the slow work (file parsing, allocation, prewarm), build a new
//     chain, and swap it in with a single atomic pointer exchange.
//   * Setters (gains, toggles) can be called from any thread.

#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Effects.h"

namespace nam
{
class DSP;
}

namespace ampengine
{

class EngineCore
{
public:
  // Largest block of samples handed to NAM at once. Bigger callbacks are split.
  static constexpr int kMaxFrames = 1024;
  // Most blocks allowed in the chain (phones run out of CPU well before this).
  static constexpr int kMaxBlocks = 8;

  explicit EngineCore(double sampleRate);
  ~EngineCore();

  EngineCore(const EngineCore&) = delete;
  EngineCore& operator=(const EngineCore&) = delete;

  // ---- Chain editing (background thread). Return "" on success, else an error message.

  // Loads a .nam or .wav file into block `id`, creating the block if needed.
  // A new block goes at the end of the chain until setOrder() says otherwise.
  std::string loadBlock(int64_t id, const std::string& path);
  void removeBlock(int64_t id);
  // Chain order, first to last. Unknown ids are ignored; loaded blocks missing
  // from the list keep their relative order after the listed ones.
  void setOrder(const std::vector<int64_t>& ids);
  void setBlockEnabled(int64_t id, bool enabled);

  bool hasBlock(int64_t id) const;
  int blockCount() const;

  // ---- Controls (any thread)
  void setInputGainDb(float db);
  void setOutputGainDb(float db);
  // Match loudness between captures (uses the loudness stored in each .nam file).
  void setNormalize(bool enabled) { mNormalize.store(enabled); }
  // Noise gate, like the NAM plugin. Threshold in dB (e.g. -60; lower = gentler).
  void setGate(bool enabled, float thresholdDb)
  {
    mGateThresholdDb.store(thresholdDb);
    mGateEnabled.store(enabled);
  }
  // EQ knobs 0..10 (5 = flat), like the NAM plugin's tone stack.
  void setEq(bool enabled, float bass, float middle, float treble)
  {
    mBass.store(bass);
    mMiddle.store(middle);
    mTreble.store(treble);
    mEqEnabled.store(enabled);
  }

  // ---- Real-time (audio thread)
  // Mono in, mono out. `in` and `out` may point to the same buffer.
  void process(const float* in, float* out, int numFrames);

  // Peak levels (linear, 0..1+) since the last call. Used for the UI meters.
  float takeInputPeak() { return mInputPeak.exchange(0.0f); }
  float takeOutputPeak() { return mOutputPeak.exchange(0.0f); }
  // Highest share of the available time spent processing since the last call
  // (0.5 = half the time budget). Near or above 1.0 means audio will crackle.
  float takeCpuLoad() { return mCpuLoad.exchange(0.0f); }

  double sampleRate() const { return mSampleRate; }

private:
  // A loaded DSP plus everything the audio thread needs to run it.
  struct Block
  {
    std::unique_ptr<nam::DSP> dsp;
    float normalizeGain = 1.0f; // only for NAM captures
    std::atomic<bool> enabled{true};
    std::vector<std::vector<float>> outBuffers; // one per DSP output channel
    std::vector<float*> outPtrs;
  };

  // An immutable snapshot of the chain order, read by the audio thread.
  struct Chain
  {
    std::vector<Block*> blocks;
  };

  void rebuildChainLocked();
  void waitForAudioThread();
  static void runBlock(Block& block, float* buffer, int numFrames, bool normalize);

  const double mSampleRate;

  // Owned blocks by id, and the requested order. Guarded by mEditMutex.
  mutable std::mutex mEditMutex;
  std::map<int64_t, std::shared_ptr<Block>> mBlocks;
  std::vector<int64_t> mOrder;
  // Blocks replaced or removed, kept alive until the audio thread can't see them.
  std::vector<std::shared_ptr<Block>> mRetired;

  std::atomic<Chain*> mChain;

  std::atomic<bool> mNormalize{true};

  std::atomic<float> mInputGainTarget{1.0f};
  std::atomic<float> mOutputGainTarget{1.0f};
  float mInputGain = 1.0f; // audio-thread only (smoothed)
  float mOutputGain = 1.0f; // audio-thread only (smoothed)

  std::atomic<float> mInputPeak{0.0f};
  std::atomic<float> mOutputPeak{0.0f};
  std::atomic<float> mCpuLoad{0.0f};

  // Odd while the audio thread is inside process(). Lets editors know when it
  // is safe to free a chain or block that was just swapped out.
  std::atomic<uint64_t> mProcessSeq{0};

  std::vector<float> mScratch; // audio-thread work buffer

  // Gate + EQ: settings from any thread, state owned by the audio thread.
  std::atomic<bool> mGateEnabled{false};
  std::atomic<float> mGateThresholdDb{-60.0f};
  std::atomic<bool> mEqEnabled{false};
  std::atomic<float> mBass{5.0f};
  std::atomic<float> mMiddle{5.0f};
  std::atomic<float> mTreble{5.0f};
  NoiseGate mGate;
  ToneStack mToneStack;
  std::vector<float> mGateGains;
  bool mGateWasOn = false;
  bool mEqWasOn = false;
  float mAppliedBass = -1.0f, mAppliedMiddle = -1.0f, mAppliedTreble = -1.0f;
};

} // namespace ampengine
