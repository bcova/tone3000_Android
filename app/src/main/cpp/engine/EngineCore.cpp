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

void updatePeak(std::atomic<float>& peak, float value)
{
  if (value > peak.load(std::memory_order_relaxed))
    peak.store(value, std::memory_order_relaxed);
}

} // namespace

EngineCore::EngineCore(double sampleRate)
: mSampleRate(sampleRate)
, mScratch(kMaxBlock, 0.0f)
{
}

EngineCore::~EngineCore()
{
  // No audio thread may be running by now (the owner stops streams first).
  delete mModel.exchange(nullptr);
  delete mIr.exchange(nullptr);
}

void EngineCore::setInputGainDb(float db)
{
  mInputGainTarget.store(dbToGain(db));
}

void EngineCore::setOutputGainDb(float db)
{
  mOutputGainTarget.store(dbToGain(db));
}

std::string EngineCore::loadModel(const std::string& path)
{
  return load(mModel, path, false);
}

std::string EngineCore::loadIr(const std::string& path)
{
  return load(mIr, path, true);
}

void EngineCore::clearModel()
{
  std::lock_guard<std::mutex> lock(mLoadMutex);
  replace(mModel, nullptr);
}

void EngineCore::clearIr()
{
  std::lock_guard<std::mutex> lock(mLoadMutex);
  replace(mIr, nullptr);
}

std::string EngineCore::load(std::atomic<Loaded*>& slot, const std::string& path, bool isIr)
{
  std::lock_guard<std::mutex> lock(mLoadMutex);

  const std::filesystem::path fsPath(path);
  std::string ext = fsPath.extension().string();
  std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
  if (isIr && ext != ".wav")
    return "Cabinet IRs must be .wav files.";
  if (!isIr && ext != ".nam")
    return "Amp models must be .nam files.";

  auto next = std::make_unique<Loaded>();
  try
  {
    next->dsp = nam::get_dsp(fsPath);
  }
  catch (const std::exception& e)
  {
    return std::string("Could not load file: ") + e.what();
  }
  if (!next->dsp)
    return "Could not load file.";

  nam::DSP& dsp = *next->dsp;
  if (dsp.NumInputChannels() != 1 || dsp.NumOutputChannels() < 1)
    return "Unsupported file: expected a mono-input model.";

  // Allocate and warm up now, so the audio thread never has to.
  dsp.ResetAndPrewarm(mSampleRate, kMaxBlock);

  next->outBuffers.assign(dsp.NumOutputChannels(), std::vector<float>(kMaxBlock, 0.0f));
  for (auto& b : next->outBuffers)
    next->outPtrs.push_back(b.data());

  if (!isIr && dsp.HasLoudness())
    next->normalizeGain = static_cast<float>(std::pow(10.0, (kTargetLoudnessDb - dsp.GetLoudness()) / 20.0));

  replace(slot, next.release());
  return "";
}

void EngineCore::replace(std::atomic<Loaded*>& slot, Loaded* next)
{
  Loaded* old = slot.exchange(next);
  if (old != nullptr)
  {
    waitForAudioThread();
    delete old;
  }
}

void EngineCore::waitForAudioThread()
{
  // If the audio thread is mid-callback it may still hold the old pointer.
  // Wait until that callback finishes; any later callback sees the new one.
  const uint64_t seq = mProcessSeq.load();
  if ((seq & 1u) == 0)
    return;
  while (mProcessSeq.load() == seq)
    std::this_thread::sleep_for(std::chrono::microseconds(200));
}

void EngineCore::runDsp(Loaded& loaded, float* buffer, int numFrames)
{
  float* inPtr = buffer;
  loaded.dsp->process(&inPtr, loaded.outPtrs.data(), numFrames);
  // Use the first output channel (stereo IRs: left).
  std::copy_n(loaded.outPtrs[0], numFrames, buffer);
}

void EngineCore::process(const float* in, float* out, int numFrames)
{
  mProcessSeq.fetch_add(1); // now odd: "audio thread busy"
  ScopedFlushDenormals noDenormals;

  Loaded* model = mModel.load();
  Loaded* ir = mIr.load();
  const bool useModel = model != nullptr && mModelEnabled.load(std::memory_order_relaxed);
  const bool useIr = ir != nullptr && mIrEnabled.load(std::memory_order_relaxed);
  const float normGain = (useModel && mNormalize.load(std::memory_order_relaxed)) ? model->normalizeGain : 1.0f;

  const float inTarget = mInputGainTarget.load(std::memory_order_relaxed);
  const float outTarget = mOutputGainTarget.load(std::memory_order_relaxed) * normGain;

  float inPeak = 0.0f;
  float outPeak = 0.0f;
  float* buf = mScratch.data();

  for (int offset = 0; offset < numFrames; offset += kMaxBlock)
  {
    const int n = std::min(kMaxBlock, numFrames - offset);

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

    if (useModel)
      runDsp(*model, buf, n);
    if (useIr)
      runDsp(*ir, buf, n);

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

  updatePeak(mInputPeak, inPeak);
  updatePeak(mOutputPeak, outPeak);

  mProcessSeq.fetch_add(1); // even again: "audio thread idle"
}

} // namespace ampengine
