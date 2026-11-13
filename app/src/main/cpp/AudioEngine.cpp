#include "AudioEngine.h"

#include <algorithm>

#include <android/log.h>

#define LOG_TAG "AmpEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Oboe's helper that reads the input stream inside the output stream's callback,
// so input and output stay in lock-step.
class AudioEngine::Duplex : public oboe::FullDuplexStream
{
public:
  explicit Duplex(AudioEngine& engine)
  : mEngine(engine)
  {
  }

  oboe::DataCallbackResult onBothStreamsReady(const void* inputData, int numInputFrames, void* outputData,
                                              int numOutputFrames) override
  {
    return mEngine.render(static_cast<const float*>(inputData), numInputFrames, static_cast<float*>(outputData),
                          numOutputFrames);
  }

private:
  AudioEngine& mEngine;
};

class AudioEngine::ErrorHandler : public oboe::AudioStreamErrorCallback
{
public:
  explicit ErrorHandler(AudioEngine& engine)
  : mEngine(engine)
  {
  }

  // Called on a separate thread after Oboe has closed the failed stream,
  // e.g. when a USB interface or headphones are unplugged.
  void onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) override { mEngine.onStreamError(error); }

private:
  AudioEngine& mEngine;
};

AudioEngine::AudioEngine()
: mCore(std::make_unique<ampengine::EngineCore>(kSampleRate))
{
  mDuplex = std::make_shared<Duplex>(*this);
  mErrorHandler = std::make_shared<ErrorHandler>(*this);
}

AudioEngine::~AudioEngine()
{
  stop();
}

bool AudioEngine::start()
{
  std::lock_guard<std::mutex> lock(mLock);
  if (mWantRunning)
    return true;

  mWantRunning = true;
  oboe::Result result = openStreams();
  if (result == oboe::Result::OK)
    result = mDuplex->start();
  if (result != oboe::Result::OK)
  {
    mLastError = std::string("Audio failed to start: ") + oboe::convertToText(result);
    LOGE("%s", mLastError.c_str());
    closeStreams();
    mWantRunning = false;
    return false;
  }
  mLastError.clear();
  return true;
}

void AudioEngine::stop()
{
  std::lock_guard<std::mutex> lock(mLock);
  mWantRunning = false;
  closeStreams();
}

bool AudioEngine::isRunning() const
{
  // "Wanted and not failed": stays true during an automatic device-change
  // restart, so the UI doesn't flicker to "stopped".
  std::lock_guard<std::mutex> lock(mLock);
  return mWantRunning;
}

std::string AudioEngine::statusText() const
{
  std::lock_guard<std::mutex> lock(mLock);
  if (!mOutput || !mInput)
    return mLastError.empty() ? "Stopped" : mLastError;

  const bool exclusive = mOutput->getSharingMode() == oboe::SharingMode::Exclusive
                         && mInput->getSharingMode() == oboe::SharingMode::Exclusive;
  const bool lowLatency = mOutput->getPerformanceMode() == oboe::PerformanceMode::LowLatency
                          && mInput->getPerformanceMode() == oboe::PerformanceMode::LowLatency;
  std::string text = std::to_string(mOutput->getSampleRate()) + " Hz · "
                     + std::to_string(mOutput->getFramesPerBurst()) + "-frame bursts · "
                     + (exclusive ? "exclusive" : "shared") + (lowLatency ? "" : " · NOT low-latency");
  auto latency = mOutput->calculateLatencyMillis();
  if (latency)
    text += " · out " + std::to_string(static_cast<int>(latency.value())) + " ms";
  return text;
}

oboe::Result AudioEngine::openStreams()
{
  // Output first: it drives the timing.
  oboe::AudioStreamBuilder outBuilder;
  outBuilder.setDirection(oboe::Direction::Output)
    ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
    ->setSharingMode(oboe::SharingMode::Exclusive)
    ->setFormat(oboe::AudioFormat::Float)
    ->setChannelCount(oboe::ChannelCount::Stereo)
    ->setSampleRate(kSampleRate)
    ->setDataCallback(mDuplex)
    ->setErrorCallback(mErrorHandler);
  oboe::Result result = outBuilder.openStream(mOutput);
  if (result != oboe::Result::OK)
    return result;
  // Smallest safe buffer: two bursts.
  mOutput->setBufferSizeInFrames(mOutput->getFramesPerBurst() * 2);

  // Input: "Unprocessed" turns off the phone's voice processing (auto-gain,
  // noise suppression), which would wreck a guitar signal.
  oboe::AudioStreamBuilder inBuilder;
  inBuilder.setDirection(oboe::Direction::Input)
    ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
    ->setSharingMode(oboe::SharingMode::Exclusive)
    ->setFormat(oboe::AudioFormat::Float)
    ->setChannelCount(oboe::ChannelCount::Mono)
    ->setSampleRate(mOutput->getSampleRate())
    ->setBufferCapacityInFrames(mOutput->getBufferCapacityInFrames() * 2)
    ->setInputPreset(oboe::InputPreset::Unprocessed);
  result = inBuilder.openStream(mInput);
  if (result != oboe::Result::OK)
  {
    // Some devices don't support Unprocessed; fall back.
    LOGI("Unprocessed input preset failed (%s); retrying with VoiceRecognition", oboe::convertToText(result));
    inBuilder.setInputPreset(oboe::InputPreset::VoiceRecognition);
    result = inBuilder.openStream(mInput);
  }
  if (result != oboe::Result::OK)
    return result;

  mMono.assign(static_cast<size_t>(mOutput->getBufferCapacityInFrames()), 0.0f);
  mOutChannels = mOutput->getChannelCount();
  mDuplex->setSharedInputStream(mInput);
  mDuplex->setSharedOutputStream(mOutput);

  LOGI("Streams open: %d Hz, burst %d, in-ch %d, out-ch %d", mOutput->getSampleRate(), mOutput->getFramesPerBurst(),
       mInput->getChannelCount(), mOutput->getChannelCount());
  return oboe::Result::OK;
}

void AudioEngine::closeStreams()
{
  if (mOutput)
  {
    mOutput->stop();
    mOutput->close();
    mOutput.reset();
  }
  if (mInput)
  {
    mInput->stop();
    mInput->close();
    mInput.reset();
  }
}

void AudioEngine::onStreamError(oboe::Result error)
{
  std::lock_guard<std::mutex> lock(mLock);
  LOGE("Stream error: %s", oboe::convertToText(error));
  closeStreams();
  if (!mWantRunning)
    return;
  // Device changed (e.g. USB interface plugged/unplugged): reopen on the new device.
  if (error == oboe::Result::ErrorDisconnected && openStreams() == oboe::Result::OK
      && mDuplex->start() == oboe::Result::OK)
  {
    LOGI("Audio restarted on new device");
    return;
  }
  closeStreams();
  mWantRunning = false;
  mLastError = std::string("Audio stopped: ") + oboe::convertToText(error);
}

oboe::DataCallbackResult AudioEngine::render(const float* input, int numInputFrames, float* output,
                                             int numOutputFrames)
{
  const int frames = std::min(numOutputFrames, static_cast<int>(mMono.size()));
  const int fromInput = std::min(numInputFrames, frames);

  // Mono guitar in. Missing input frames (stream warming up) become silence.
  std::copy_n(input, fromInput, mMono.data());
  std::fill(mMono.begin() + fromInput, mMono.begin() + frames, 0.0f);

  mCore->process(mMono.data(), mMono.data(), frames);

  // Same signal to both ears.
  const int outChannels = mOutChannels;
  for (int i = 0; i < frames; ++i)
    for (int c = 0; c < outChannels; ++c)
      output[i * outChannels + c] = mMono[i];
  for (int i = frames * outChannels; i < numOutputFrames * outChannels; ++i)
    output[i] = 0.0f;

  return oboe::DataCallbackResult::Continue;
}
