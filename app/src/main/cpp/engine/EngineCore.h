#pragma once

// EngineCore: the platform-independent signal chain.
//
//   guitar in -> input gain -> [gate listens] -> block 1 -> block 2 -> ...
//            -> [gate turns down] -> EQ (bass/mid/treble) -> output gain -> out (L/R)
//
// The signal is mono until a stereo impulse response, which makes it stereo. A mono
// block after that gets the (L+R)/2 mix. Optionally the EQ runs just before the
// first impulse response (like the NAM plugin, where EQ comes before the cab).
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
#include <cmath>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "Effects.h"
#include "Recorder.h"
#include "Tuner.h"

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
  // Per-block volume trim in dB (e.g. how hard a pedal hits the amp). 0 = unchanged.
  void setBlockLevelDb(int64_t id, float db);

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

  // Put the EQ right before the first impulse response (cab) instead of at the end.
  void setEqBeforeCab(bool before) { mEqBeforeCab.store(before); }

  // Input calibration (like the NAM plugin): captures that store their recording level
  // ("input_level_dbu") are fed as if your guitar hit the real amp. `interfaceDbu` is the
  // level in dBu that reads 0 dBFS on your audio interface (see its spec sheet).
  void setInputCalibration(bool enabled, float interfaceDbu)
  {
    mInterfaceDbu.store(interfaceDbu);
    mCalibrate.store(enabled);
  }

  Recorder& recorder() { return mRecorder; }

  // ---- Tuner
  // While active, the output is muted (silent tuning) and recent input is kept for analysis.
  void setTunerActive(bool active) { mTunerActive.store(active); }
  bool tunerActive() const { return mTunerActive.load(); }
  // Background thread: analyze the most recent input. hz = 0 when there's no clear note.
  PitchResult readTunerPitch();

  // ---- Real-time (audio thread)
  // Mono in, stereo out.
  void process(const float* in, float* outLeft, float* outRight, int numFrames);
  // Convenience for tests: mono in, (L+R)/2 out. May allocate; not for the audio thread.
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
    bool isIr = false; // .wav impulse response
    float inputLevelDbu = NAN; // calibration level stored in the .nam file, if any
    std::atomic<bool> enabled{true};
    std::atomic<float> levelGain{1.0f};
    float appliedLevel = 1.0f; // audio-thread only (smoothed)
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
  void runBlock(Block& block, float* left, float* right, bool& stereo, int numFrames, bool normalize, bool calibrate,
                float interfaceDbu);
  void runEq(float* left, float* right, bool stereo, int numFrames);

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

  std::vector<float> mScratch; // audio-thread work buffer (left / mono)
  std::vector<float> mScratchR; // audio-thread work buffer (right)
  std::vector<float> mTestR; // used by the mono convenience process()

  // Gate + EQ: settings from any thread, state owned by the audio thread.
  std::atomic<bool> mGateEnabled{false};
  std::atomic<float> mGateThresholdDb{-60.0f};
  std::atomic<bool> mEqEnabled{false};
  std::atomic<float> mBass{5.0f};
  std::atomic<float> mMiddle{5.0f};
  std::atomic<float> mTreble{5.0f};
  NoiseGate mGate;
  ToneStack mToneStack;
  ToneStack mToneStackR;
  std::atomic<bool> mEqBeforeCab{false};
  std::atomic<bool> mCalibrate{false};
  std::atomic<float> mInterfaceDbu{12.0f};
  Recorder mRecorder;
  std::vector<float> mGateGains;
  bool mGateWasOn = false;
  bool mEqWasOn = false;
  float mAppliedBass = -1.0f, mAppliedMiddle = -1.0f, mAppliedTreble = -1.0f;

  // Tuner: the audio thread writes raw input into a ring; readTunerPitch() copies from it.
  static constexpr int kTunerRing = 8192; // power of two
  static constexpr int kTunerWindow = 4096; // ~85 ms at 48 kHz
  std::atomic<bool> mTunerActive{false};
  std::unique_ptr<std::atomic<float>[]> mTunerRing; // relaxed atomics: no torn floats
  std::atomic<uint32_t> mTunerWrite{0};
};

} // namespace ampengine
