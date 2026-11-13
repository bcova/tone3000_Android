#pragma once

// Records the stereo output to a 16-bit WAV file.
//
// The audio thread only copies samples into a lock-free ring buffer (push()).
// A background thread drains the ring and writes the file, so the audio never
// waits on the disk. Analogy: the audio thread drops letters in a mailbox; the
// writer thread empties the mailbox every few milliseconds.

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

namespace ampengine
{

class Recorder
{
public:
  explicit Recorder(double sampleRate);
  ~Recorder();

  Recorder(const Recorder&) = delete;
  Recorder& operator=(const Recorder&) = delete;

  // Background thread. Returns "" on success, else an error message.
  std::string start(const std::string& path);
  // Stops and finishes the file. Returns the recorded length in seconds.
  double stop();
  bool recording() const { return mActive.load(); }
  double seconds() const { return static_cast<double>(mFramesWritten.load()) / mSampleRate; }
  // Frames lost because the disk couldn't keep up (should stay 0).
  uint64_t droppedFrames() const { return mDropped.load(); }

  // Audio thread: real-time safe. Does nothing unless recording.
  void push(const float* left, const float* right, int numFrames);

private:
  void writerLoop();
  void drain();
  static void writeHeader(FILE* f, double sampleRate, uint32_t dataBytes);

  const double mSampleRate;
  static constexpr uint64_t kRingFrames = 1u << 18; // ~5.5 s at 48 kHz
  std::vector<float> mRing; // interleaved L/R
  std::atomic<uint64_t> mWrite{0};
  std::atomic<uint64_t> mRead{0};
  std::atomic<bool> mActive{false};
  std::atomic<bool> mStopWriter{false};
  std::atomic<uint64_t> mDropped{0};
  std::atomic<uint64_t> mFramesWritten{0};
  std::thread mThread;
  FILE* mFile = nullptr;
  std::vector<int16_t> mPcm; // writer-thread scratch
};

} // namespace ampengine
