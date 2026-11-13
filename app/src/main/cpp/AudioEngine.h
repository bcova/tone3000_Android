#pragma once

// AudioEngine: connects the phone's audio hardware (via Oboe) to EngineCore.
// Opens a low-latency input stream (guitar/USB interface) and output stream
// (headphones), and runs EngineCore inside the output callback.

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <cstring> // Oboe's FullDuplexStream.h uses memset without including this

#include <oboe/Oboe.h>

#include "engine/EngineCore.h"

class AudioEngine
{
public:
  // NAM captures are trained at 48 kHz. We ask Oboe for 48 kHz and let it
  // resample if the hardware runs at another rate.
  static constexpr int kSampleRate = 48000;

  AudioEngine();
  ~AudioEngine();

  bool start(); // true if audio is running
  void stop();
  bool isRunning() const;

  // Human-readable stream info for the UI, e.g. "48000 Hz · 96-frame bursts · exclusive".
  std::string statusText() const;

  ampengine::EngineCore& core() { return *mCore; }

private:
  class Duplex;
  class ErrorHandler;
  friend class Duplex;
  friend class ErrorHandler;

  oboe::Result openStreams();
  void closeStreams();
  void onStreamError(oboe::Result error);
  oboe::DataCallbackResult render(const float* input, int numInputFrames, float* output, int numOutputFrames);

  std::unique_ptr<ampengine::EngineCore> mCore;
  std::shared_ptr<Duplex> mDuplex;
  std::shared_ptr<ErrorHandler> mErrorHandler;
  std::shared_ptr<oboe::AudioStream> mInput;
  std::shared_ptr<oboe::AudioStream> mOutput;
  std::vector<float> mMono; // audio-thread scratch
  int mOutChannels = 2; // set before streams start; read by the audio thread
  mutable std::mutex mLock;
  bool mWantRunning = false;
  std::string mLastError;
};
