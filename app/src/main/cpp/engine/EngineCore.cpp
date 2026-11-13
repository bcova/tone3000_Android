#include "EngineCore.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <exception>
#include <filesystem>
#include <thread>

#include "NAM/get_dsp.h"

#if defined(__x86_64__) || defined(__i386__)
  #include <xmmintrin.h>
#endif

namespace ampengine
{
namespace
{

// NAM captures are normalized to this loudness, matching the desktop NAM plugin.
constexpr double kTargetLoudnessDb = -18.0;

float dbToGain(float db)
{
  return std::pow(10.0f, db / 20.0f);
}

// Tiny numbers ("denormals") can make the CPU crawl inside a neural network.
// Flush them to zero for the duration of a process() call.
class ScopedFlushDenormals
{
public:
  ScopedFlushDenormals()
  {
#if defined(__aarch64__)
    asm volatile("mrs %0, fpcr" : "=r"(mSaved));
    asm volatile("msr fpcr, %0" : : "r"(mSaved | (1ULL << 24)));
#elif defined(__x86_64__) || defined(__i386__)
    mSaved = _mm_getcsr();
    _mm_setcsr(static_cast<unsigned int>(mSaved) | 0x8040); // FTZ | DAZ
#endif
  }
  ~ScopedFlushDenormals()
  {
#if defined(__aarch64__)
    asm volatile("msr fpcr, %0" : : "r"(mSaved));
#elif defined(__x86_64__) || defined(__i386__)
    _mm_setcsr(static_cast<unsigned int>(mSaved));
#endif
  }

private:
  uint64_t mSaved = 0;
};

void updateMax(std::atomic<float>& value, float candidate)
{
  if (candidate > value.load(std::memory_order_relaxed))
    value.store(candidate, std::memory_order_relaxed);
}

} // namespace

EngineCore::EngineCore(double sampleRate)
: mSampleRate(sampleRate)
, mChain(new Chain())
, mScratch(kMaxFrames, 0.0f)
, mGateGains(kMaxFrames, 1.0f)
, mTunerRing(new std::atomic<float>[kTunerRing]())
{
  mGate.prepare(sampleRate);
}

EngineCore::~EngineCore()
{
  // No audio thread may be running by now (the owner stops streams first).
  delete mChain.exchange(nullptr);
}

void EngineCore::setInputGainDb(float db)
{
  mInputGainTarget.store(dbToGain(db));
}

void EngineCore::setOutputGainDb(float db)
{
  mOutputGainTarget.store(dbToGain(db));
}

std::string EngineCore::loadBlock(int64_t id, const std::string& path)
{
  const std::filesystem::path fsPath(path);
  std::string ext = fsPath.extension().string();
  std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  if (ext != ".nam" && ext != ".wav")
    return "Unsupported file: use a .nam capture or a .wav impulse response.";

  {
    std::lock_guard<std::mutex> lock(mEditMutex);
    if (mBlocks.find(id) == mBlocks.end() && static_cast<int>(mBlocks.size()) >= kMaxBlocks)
      return "The chain is full (" + std::to_string(kMaxBlocks) + " blocks).";
  }

  // Slow part, done without holding the lock.
  auto block = std::make_shared<Block>();
  try
  {
    block->dsp = nam::get_dsp(fsPath);
  }
  catch (const std::exception& e)
  {
    return std::string("Could not load file: ") + e.what();
  }
  if (!block->dsp)
    return "Could not load file.";

  nam::DSP& dsp = *block->dsp;
  if (dsp.NumInputChannels() != 1 || dsp.NumOutputChannels() < 1)
    return "Unsupported file: expected a mono-input model.";

  // Allocate and warm up now, so the audio thread never has to.
  dsp.ResetAndPrewarm(mSampleRate, kMaxFrames);
  block->outBuffers.assign(dsp.NumOutputChannels(), std::vector<float>(kMaxFrames, 0.0f));
  for (auto& b : block->outBuffers)
    block->outPtrs.push_back(b.data());
  if (ext == ".nam" && dsp.HasLoudness())
    block->normalizeGain = static_cast<float>(std::pow(10.0, (kTargetLoudnessDb - dsp.GetLoudness()) / 20.0));

  std::lock_guard<std::mutex> lock(mEditMutex);
  auto it = mBlocks.find(id);
  if (it != mBlocks.end())
  {
    block->enabled.store(it->second->enabled.load()); // replacing keeps on/off state
    mRetired.push_back(it->second);
    it->second = block;
  }
  else
  {
    if (static_cast<int>(mBlocks.size()) >= kMaxBlocks) // filled up while we were loading
      return "The chain is full (" + std::to_string(kMaxBlocks) + " blocks).";
    mBlocks.emplace(id, block);
    if (std::find(mOrder.begin(), mOrder.end(), id) == mOrder.end())
      mOrder.push_back(id);
  }
  rebuildChainLocked();
  return "";
}

void EngineCore::removeBlock(int64_t id)
{
  std::lock_guard<std::mutex> lock(mEditMutex);
  auto it = mBlocks.find(id);
  if (it == mBlocks.end())
    return;
  mRetired.push_back(it->second);
  mBlocks.erase(it);
  rebuildChainLocked();
}

void EngineCore::setOrder(const std::vector<int64_t>& ids)
{
  std::lock_guard<std::mutex> lock(mEditMutex);
  std::vector<int64_t> order;
  for (int64_t id : ids)
    if (std::find(order.begin(), order.end(), id) == order.end())
      order.push_back(id);
  // Keep any loaded blocks the caller didn't mention, after the listed ones.
  for (int64_t id : mOrder)
    if (std::find(order.begin(), order.end(), id) == order.end() && mBlocks.count(id))
      order.push_back(id);
  mOrder = std::move(order);
  rebuildChainLocked();
}

void EngineCore::setBlockEnabled(int64_t id, bool enabled)
{
  std::lock_guard<std::mutex> lock(mEditMutex);
  auto it = mBlocks.find(id);
  if (it != mBlocks.end())
    it->second->enabled.store(enabled);
}

bool EngineCore::hasBlock(int64_t id) const
{
  std::lock_guard<std::mutex> lock(mEditMutex);
  return mBlocks.count(id) > 0;
}

int EngineCore::blockCount() const
{
  std::lock_guard<std::mutex> lock(mEditMutex);
  return static_cast<int>(mBlocks.size());
}

void EngineCore::rebuildChainLocked()
{
  auto* next = new Chain();
  next->blocks.reserve(mOrder.size());
  for (int64_t id : mOrder)
  {
    auto it = mBlocks.find(id);
    if (it != mBlocks.end())
      next->blocks.push_back(it->second.get());
  }
  Chain* old = mChain.exchange(next);
  // The audio thread may still be walking the old chain (and retired blocks).
  waitForAudioThread();
  delete old;
  mRetired.clear();
}

void EngineCore::waitForAudioThread()
{
  // If the audio thread is mid-callback it may still hold the old pointers.
  // Wait until that callback finishes; any later callback sees the new chain.
  const uint64_t seq = mProcessSeq.load();
  if ((seq & 1u) == 0)
    return;
  while (mProcessSeq.load() == seq)
    std::this_thread::sleep_for(std::chrono::microseconds(200));
}

PitchResult EngineCore::readTunerPitch()
{
  // Copy the latest window. The audio thread may overwrite the oldest samples meanwhile;
  // at worst one reading is a little off and the next one is fine.
  std::vector<float> window(kTunerWindow);
  const uint32_t end = mTunerWrite.load(std::memory_order_acquire);
  const uint32_t start = end - kTunerWindow;
  for (int i = 0; i < kTunerWindow; ++i)
    window[i] = mTunerRing[(start + i) & (kTunerRing - 1)].load(std::memory_order_relaxed);
  return detectPitch(window.data(), kTunerWindow, mSampleRate);
}

void EngineCore::runBlock(Block& block, float* buffer, int numFrames, bool normalize)
{
  float* inPtr = buffer;
  block.dsp->process(&inPtr, block.outPtrs.data(), numFrames);
  // Use the first output channel (stereo IRs: left).
  const float* out = block.outPtrs[0];
  const float gain = normalize ? block.normalizeGain : 1.0f;
  for (int i = 0; i < numFrames; ++i)
    buffer[i] = out[i] * gain;
}

void EngineCore::process(const float* in, float* out, int numFrames)
{
  mProcessSeq.fetch_add(1); // now odd: "audio thread busy"
  const auto start = std::chrono::steady_clock::now();
  ScopedFlushDenormals noDenormals;

  if (mTunerActive.load(std::memory_order_relaxed))
  {
    // Tuner mode: keep the raw guitar signal for pitch detection, and stay silent.
    uint32_t w = mTunerWrite.load(std::memory_order_relaxed);
    float peak = 0.0f;
    for (int i = 0; i < numFrames; ++i)
    {
      mTunerRing[w & (kTunerRing - 1)].store(in[i], std::memory_order_relaxed);
      ++w;
      peak = std::max(peak, std::fabs(in[i]));
    }
    mTunerWrite.store(w, std::memory_order_release);
    std::fill(out, out + numFrames, 0.0f);
    updateMax(mInputPeak, peak);
    mProcessSeq.fetch_add(1);
    return;
  }

  const Chain* chain = mChain.load();
  const bool normalize = mNormalize.load(std::memory_order_relaxed);
  const float inTarget = mInputGainTarget.load(std::memory_order_relaxed);
  const float outTarget = mOutputGainTarget.load(std::memory_order_relaxed);

  const bool gateOn = mGateEnabled.load(std::memory_order_relaxed);
  if (gateOn)
  {
    if (!mGateWasOn)
      mGate.reset();
    mGate.setThresholdDb(mGateThresholdDb.load(std::memory_order_relaxed));
  }
  mGateWasOn = gateOn;

  const bool eqOn = mEqEnabled.load(std::memory_order_relaxed);
  if (eqOn)
  {
    const float b = mBass.load(std::memory_order_relaxed);
    const float m = mMiddle.load(std::memory_order_relaxed);
    const float t = mTreble.load(std::memory_order_relaxed);
    if (b != mAppliedBass || m != mAppliedMiddle || t != mAppliedTreble)
    {
      mToneStack.setKnobs(mSampleRate, b, m, t); // a few cos/pow calls: fine on the audio thread
      mAppliedBass = b;
      mAppliedMiddle = m;
      mAppliedTreble = t;
    }
    if (!mEqWasOn)
      mToneStack.reset();
  }
  mEqWasOn = eqOn;

  float inPeak = 0.0f;
  float outPeak = 0.0f;
  float* buf = mScratch.data();

  for (int offset = 0; offset < numFrames; offset += kMaxFrames)
  {
    const int n = std::min(kMaxFrames, numFrames - offset);

    // Ramp gains across the chunk so slider moves don't "zipper".
    const float inStep = (inTarget - mInputGain) / static_cast<float>(n);
    for (int i = 0; i < n; ++i)
    {
      mInputGain += inStep;
      const float s = in[offset + i] * mInputGain;
      inPeak = std::max(inPeak, std::fabs(s));
      buf[i] = s;
    }
    mInputGain = inTarget;

    if (gateOn)
      mGate.detect(buf, mGateGains.data(), n); // listen to the guitar before the chain

    for (Block* block : chain->blocks)
      if (block->enabled.load(std::memory_order_relaxed))
        runBlock(*block, buf, n, normalize);

    if (gateOn)
      NoiseGate::apply(buf, mGateGains.data(), n);
    if (eqOn)
      mToneStack.process(buf, n);

    const float outStep = (outTarget - mOutputGain) / static_cast<float>(n);
    for (int i = 0; i < n; ++i)
    {
      mOutputGain += outStep;
      float s = buf[i] * mOutputGain;
      if (!std::isfinite(s))
        s = 0.0f;
      s = std::clamp(s, -1.0f, 1.0f); // protect ears and speakers
      outPeak = std::max(outPeak, std::fabs(s));
      out[offset + i] = s;
    }
    mOutputGain = outTarget;
  }

  updateMax(mInputPeak, inPeak);
  updateMax(mOutputPeak, outPeak);
  if (numFrames > 0)
  {
    const double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
    updateMax(mCpuLoad, static_cast<float>(elapsed / (numFrames / mSampleRate)));
  }

  mProcessSeq.fetch_add(1); // even again: "audio thread idle"
}

} // namespace ampengine
