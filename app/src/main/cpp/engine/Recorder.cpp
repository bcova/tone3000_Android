#include "Recorder.h"

#include <algorithm>
#include <chrono>
#include <cmath>

namespace ampengine
{
namespace
{
void put32(FILE* f, uint32_t v)
{
  const unsigned char b[4] = {static_cast<unsigned char>(v), static_cast<unsigned char>(v >> 8),
                              static_cast<unsigned char>(v >> 16), static_cast<unsigned char>(v >> 24)};
  fwrite(b, 1, 4, f);
}
void put16(FILE* f, uint16_t v)
{
  const unsigned char b[2] = {static_cast<unsigned char>(v), static_cast<unsigned char>(v >> 8)};
  fwrite(b, 1, 2, f);
}
} // namespace

Recorder::Recorder(double sampleRate)
: mSampleRate(sampleRate)
, mRing(kRingFrames * 2, 0.0f)
{
}

Recorder::~Recorder()
{
  stop();
}

void Recorder::writeHeader(FILE* f, double sampleRate, uint32_t dataBytes)
{
  const uint32_t rate = static_cast<uint32_t>(sampleRate);
  fwrite("RIFF", 1, 4, f);
  put32(f, 36 + dataBytes);
  fwrite("WAVEfmt ", 1, 8, f);
  put32(f, 16);
  put16(f, 1); // PCM
  put16(f, 2); // stereo
  put32(f, rate);
  put32(f, rate * 4); // bytes per second
  put16(f, 4); // bytes per frame
  put16(f, 16); // bits
  fwrite("data", 1, 4, f);
  put32(f, dataBytes);
}

std::string Recorder::start(const std::string& path)
{
  if (mActive.load())
    return "Already recording.";
  if (mThread.joinable())
    mThread.join();
  mFile = std::fopen(path.c_str(), "wb");
  if (!mFile)
    return "Could not create the recording file.";
  writeHeader(mFile, mSampleRate, 0); // sizes are fixed up in stop()
  mRead.store(mWrite.load());
  mDropped.store(0);
  mFramesWritten.store(0);
  mStopWriter.store(false);
  mActive.store(true);
  mThread = std::thread([this] { writerLoop(); });
  return "";
}

double Recorder::stop()
{
  if (!mThread.joinable())
    return 0.0;
  mActive.store(false);
  mStopWriter.store(true);
  mThread.join();
  drain(); // anything pushed just before stopping
  const uint64_t frames = mFramesWritten.load();
  const uint32_t dataBytes = static_cast<uint32_t>(std::min<uint64_t>(frames * 4, 0xFFFFFFF0u));
  std::fseek(mFile, 0, SEEK_SET);
  writeHeader(mFile, mSampleRate, dataBytes);
  std::fclose(mFile);
  mFile = nullptr;
  return static_cast<double>(frames) / mSampleRate;
}

void Recorder::push(const float* left, const float* right, int numFrames)
{
  if (!mActive.load(std::memory_order_relaxed))
    return;
  const uint64_t w = mWrite.load(std::memory_order_relaxed);
  const uint64_t r = mRead.load(std::memory_order_acquire);
  const uint64_t free = kRingFrames - (w - r);
  const int n = static_cast<int>(std::min<uint64_t>(free, static_cast<uint64_t>(numFrames)));
  for (int i = 0; i < n; ++i)
  {
    const uint64_t slot = ((w + i) & (kRingFrames - 1)) * 2;
    mRing[slot] = left[i];
    mRing[slot + 1] = right[i];
  }
  if (n < numFrames)
    mDropped.fetch_add(numFrames - n, std::memory_order_relaxed);
  mWrite.store(w + n, std::memory_order_release);
}

void Recorder::drain()
{
  const uint64_t r = mRead.load(std::memory_order_relaxed);
  const uint64_t w = mWrite.load(std::memory_order_acquire);
  const uint64_t n = w - r;
  if (n == 0 || !mFile)
    return;
  mPcm.resize(n * 2);
  for (uint64_t i = 0; i < n; ++i)
  {
    const uint64_t slot = ((r + i) & (kRingFrames - 1)) * 2;
    for (int c = 0; c < 2; ++c)
    {
      const float s = std::clamp(mRing[slot + c], -1.0f, 1.0f);
      mPcm[i * 2 + c] = static_cast<int16_t>(std::lround(s * 32767.0f));
    }
  }
  std::fwrite(mPcm.data(), sizeof(int16_t), mPcm.size(), mFile);
  mRead.store(w, std::memory_order_release);
  mFramesWritten.fetch_add(n);
}

void Recorder::writerLoop()
{
  while (!mStopWriter.load())
  {
    drain();
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
  }
}

} // namespace ampengine
