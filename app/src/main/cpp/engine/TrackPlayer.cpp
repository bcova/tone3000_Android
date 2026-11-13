#include "TrackPlayer.h"

#include <algorithm>
#include <chrono>

namespace ampengine
{

TrackPlayer::TrackPlayer(double sampleRate)
: mSampleRate(sampleRate)
, mChunks(kChunks)
, mPcm(kChunkFrames * 2)
{
}

TrackPlayer::~TrackPlayer()
{
  close();
}

std::string TrackPlayer::open(const std::string& pcmPath)
{
  close();
  mFile = std::fopen(pcmPath.c_str(), "rb");
  if (!mFile)
    return "Could not open the backing track.";
  std::fseek(mFile, 0, SEEK_END);
  const long bytes = std::ftell(mFile);
  if (bytes < 4)
  {
    std::fclose(mFile);
    mFile = nullptr;
    return "The backing track is empty.";
  }
  mFrames.store(bytes / 4);
  mPosition.store(0);
  mLoopA.store(0);
  mLoopB.store(0);
  mSeekTo.store(0);
  mGeneration.fetch_add(1);
  mStop.store(false);
  mReader = std::thread([this] { readerLoop(); });
  return "";
}

void TrackPlayer::close()
{
  mPlaying.store(false);
  if (mReader.joinable())
  {
    mStop.store(true);
    mReader.join();
  }
  if (mFile)
  {
    std::fclose(mFile);
    mFile = nullptr;
  }
  mFrames.store(0);
  mPosition.store(0);
  mGeneration.fetch_add(1); // anything still queued is now stale
}

void TrackPlayer::seek(double seconds)
{
  const int64_t frames = mFrames.load();
  if (frames <= 0)
    return;
  const int64_t target = std::clamp<int64_t>(static_cast<int64_t>(seconds * mSampleRate), 0, frames - 1);
  mPosition.store(target);
  mSeekTo.store(target);
}

void TrackPlayer::setLoop(double a, double b)
{
  const int64_t fa = static_cast<int64_t>(std::max(0.0, a) * mSampleRate);
  const int64_t fb = static_cast<int64_t>(std::max(0.0, b) * mSampleRate);
  mLoopA.store(fa);
  mLoopB.store(fb > fa ? std::min(fb, mFrames.load()) : 0);
  // Re-read from the current spot so audio already queued past the new end isn't played.
  if (fb > fa && mPosition.load() >= fb)
    seek(a);
  else
    seek(positionSeconds());
}

bool TrackPlayer::fillOne(int64_t& readFrame, uint32_t generation)
{
  const uint64_t head = mHead.load(std::memory_order_relaxed);
  const uint64_t tail = mTail.load(std::memory_order_acquire);
  if (head - tail >= static_cast<uint64_t>(kChunks))
    return false; // queue full

  const int64_t total = mFrames.load();
  const int64_t loopA = mLoopA.load(), loopB = mLoopB.load();
  const int64_t end = loopB > 0 ? loopB : total;
  if (readFrame >= end)
  {
    if (loopB > 0)
      readFrame = loopA; // A-B loop: wrap around
    else
      return false; // end of track
  }
  const int n = static_cast<int>(std::min<int64_t>(kChunkFrames, end - readFrame));
  std::fseek(mFile, static_cast<long>(readFrame * 4), SEEK_SET);
  const size_t got = std::fread(mPcm.data(), sizeof(int16_t), static_cast<size_t>(n) * 2, mFile) / 2;

  Chunk& c = mChunks[head % kChunks];
  c.generation = generation;
  c.startFrame = readFrame;
  c.frames = static_cast<int>(got);
  for (size_t i = 0; i < got * 2; ++i)
    c.data[i] = mPcm[i] / 32768.0f;
  mHead.store(head + 1, std::memory_order_release);
  readFrame += static_cast<int64_t>(got > 0 ? got : n);
  return got > 0;
}

void TrackPlayer::readerLoop()
{
  int64_t readFrame = 0;
  uint32_t generation = mGeneration.load();
  while (!mStop.load())
  {
    const int64_t seekTo = mSeekTo.exchange(-1);
    if (seekTo >= 0)
    {
      generation = mGeneration.fetch_add(1) + 1; // queued chunks become stale
      readFrame = seekTo;
    }
    bool wrote = false;
    for (int i = 0; i < 8 && fillOne(readFrame, generation); ++i)
      wrote = true;
    if (!wrote)
      std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
}

void TrackPlayer::render(float* left, float* right, int numFrames)
{
  if (!mPlaying.load(std::memory_order_relaxed))
    return;
  const uint32_t generation = mGeneration.load(std::memory_order_acquire);
  const float vol = mVolume.load(std::memory_order_relaxed);
  int i = 0;
  while (i < numFrames)
  {
    const uint64_t tail = mTail.load(std::memory_order_relaxed);
    if (tail == mHead.load(std::memory_order_acquire))
    {
      // Nothing queued: end of the track (or the reader is catching up after a seek).
      if (mLoopB.load(std::memory_order_relaxed) == 0 && mPosition.load(std::memory_order_relaxed) >= mFrames.load() - kChunkFrames)
        mPlaying.store(false, std::memory_order_relaxed);
      return;
    }
    Chunk& c = mChunks[tail % kChunks];
    if (c.generation != generation)
    {
      mChunkOffset = 0;
      mTail.store(tail + 1, std::memory_order_release); // stale: from before a seek
      continue;
    }
    const int take = std::min(numFrames - i, c.frames - mChunkOffset);
    for (int k = 0; k < take; ++k)
    {
      left[i + k] += vol * c.data[(mChunkOffset + k) * 2];
      right[i + k] += vol * c.data[(mChunkOffset + k) * 2 + 1];
    }
    i += take;
    mChunkOffset += take;
    mPosition.store(c.startFrame + mChunkOffset, std::memory_order_relaxed);
    if (mChunkOffset >= c.frames)
    {
      mChunkOffset = 0;
      mTail.store(tail + 1, std::memory_order_release);
    }
  }
}

} // namespace ampengine
