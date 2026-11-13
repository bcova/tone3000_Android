#pragma once

// Backing-track player. Streams a raw PCM file (48 kHz, stereo, 16-bit little-endian,
// made by the app from your MP3/M4A/WAV) from storage:
//   a reader thread fills a queue of small chunks; the audio thread plays them.
// Seeking bumps a "generation" number so the audio thread skips stale chunks.
// Supports play/pause, seek, A-B loop, and volume.

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

namespace ampengine
{

class TrackPlayer
{
public:
  explicit TrackPlayer(double sampleRate);
  ~TrackPlayer();

  TrackPlayer(const TrackPlayer&) = delete;
  TrackPlayer& operator=(const TrackPlayer&) = delete;

  // ---- Control (any normal thread)
  std::string open(const std::string& pcmPath); // "" on success
  void close();
  void setPlaying(bool playing) { mPlaying.store(playing && mFrames.load() > 0); }
  bool playing() const { return mPlaying.load(); }
  void seek(double seconds);
  // Loop between a and b seconds; b <= a turns looping off.
  void setLoop(double a, double b);
  void setVolume(float v) { mVolume.store(v); }

  double durationSeconds() const { return mFrames.load() / mSampleRate; }
  double positionSeconds() const { return mPosition.load() / mSampleRate; }
  bool loaded() const { return mFrames.load() > 0; }

  // ---- Audio thread
  void render(float* left, float* right, int numFrames);

private:
  static constexpr int kChunkFrames = 1024;
  static constexpr int kChunks = 64; // ~1.4 s of audio queued

  struct Chunk
  {
    uint32_t generation = 0;
    int64_t startFrame = 0;
    int frames = 0;
    float data[kChunkFrames * 2];
  };

  void readerLoop();
  bool fillOne(int64_t& readFrame, uint32_t generation);

  const double mSampleRate;
  std::vector<Chunk> mChunks;
  std::atomic<uint64_t> mHead{0}; // written by reader
  std::atomic<uint64_t> mTail{0}; // advanced by audio thread
  std::atomic<uint32_t> mGeneration{1};
  std::atomic<int64_t> mSeekTo{-1};

  std::atomic<int64_t> mFrames{0};
  std::atomic<int64_t> mPosition{0};
  std::atomic<int64_t> mLoopA{0}, mLoopB{0};
  std::atomic<bool> mPlaying{false};
  std::atomic<float> mVolume{0.8f};

  // Audio thread: partly-played chunk
  int mChunkOffset = 0;

  FILE* mFile = nullptr;
  std::thread mReader;
  std::atomic<bool> mStop{false};
  std::vector<int16_t> mPcm; // reader scratch
};

} // namespace ampengine
