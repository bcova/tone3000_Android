#pragma once

// Metronome and simple drum loops. All sounds are synthesized (no samples):
//   kick  = falling sine "thump", snare = noise + tone, hat = short bright noise,
//   click = short beep (accented on beat 1).
// Real-time safe: no allocation after construction.

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

namespace ampengine
{

enum class RhythmStyle : int
{
  Click = 0, // plain metronome
  Rock,
  Funk,
  Shuffle,
  HalfTime,
  Ballad,
  Metal,
  Count
};

class Rhythm
{
public:
  explicit Rhythm(double sampleRate)
  : mSampleRate(sampleRate)
  {
  }

  // ---- Controls (any thread)
  void setPlaying(bool playing) { mPlaying.store(playing); }
  bool playing() const { return mPlaying.load(); }
  void setBpm(float bpm) { mBpm.store(std::clamp(bpm, 30.0f, 300.0f)); }
  float bpm() const { return mBpm.load(); }
  void setStyle(RhythmStyle s) { mStyle.store(static_cast<int>(s)); }
  RhythmStyle style() const { return static_cast<RhythmStyle>(mStyle.load()); }
  void setBeatsPerBar(int beats) { mBeatsPerBar.store(std::clamp(beats, 1, 12)); }
  int beatsPerBar() const { return mBeatsPerBar.load(); }
  void setVolume(float v) { mVolume.store(std::clamp(v, 0.0f, 2.0f)); }
  // Current beat (0-based) for the UI's flashing light; -1 when stopped.
  int currentBeat() const { return mPlaying.load() ? mBeatShown.load() : -1; }

  // Length of one bar in samples at the current tempo (used to snap looper length).
  double barSamples() const
  {
    const int beats = style() == RhythmStyle::Click ? beatsPerBar() : 4;
    return beats * 60.0 / bpm() * mSampleRate;
  }

  /** Adds the rhythm to left/right. Returns true if anything sounded. */
  bool render(float* left, float* right, int numFrames)
  {
    const bool playing = mPlaying.load(std::memory_order_relaxed);
    if (!playing)
    {
      mWasPlaying = false;
      // Let ringing sounds finish naturally.
      if (!voicesActive())
        return false;
    }
    else if (!mWasPlaying)
    {
      mWasPlaying = true;
      mStepPos = 0.0;
      mStep = 0;
      trigger(0); // start right on the downbeat
    }

    const RhythmStyle style = static_cast<RhythmStyle>(mStyle.load(std::memory_order_relaxed));
    const double samplesPerStep = 60.0 / mBpm.load(std::memory_order_relaxed) * mSampleRate / 4.0; // 16th notes
    const float vol = mVolume.load(std::memory_order_relaxed);

    for (int i = 0; i < numFrames; ++i)
    {
      if (playing)
      {
        // Shuffle: delay every off-beat 16th to make a swing feel.
        const double swing = (style == RhythmStyle::Shuffle && (mStep & 1)) ? samplesPerStep * 0.33 : 0.0;
        // Step k starts exactly k * samplesPerStep samples after the downbeat.
        if (mStepPos >= samplesPerStep + swing)
        {
          mStepPos -= samplesPerStep + swing;
          mStep = (mStep + 1) % stepsPerBar(style);
          trigger(mStep);
        }
        mStepPos += 1.0;
      }
      const float s = vol * nextSample();
      left[i] += s;
      right[i] += s;
    }
    return true;
  }

private:
  int stepsPerBar(RhythmStyle s) const { return s == RhythmStyle::Click ? mBeatsPerBar.load() * 4 : 16; }

  // Patterns: 16 steps (16th notes) of one 4/4 bar. 'x' = hit, 'o' = soft hit.
  struct Pattern
  {
    const char* kick;
    const char* snare;
    const char* hat;
  };
  static Pattern pattern(RhythmStyle s)
  {
    switch (s)
    {
      case RhythmStyle::Rock: return {"x.......x.x.....", "....x.......x...", "x.x.x.x.x.x.x.x."};
      case RhythmStyle::Funk: return {"x..x..x...x..x..", "....x..o.o..x..o", "xxxxxxxxxxxxxxxx"};
      case RhythmStyle::Shuffle: return {"x.......x.......", "....x.......x...", "x.xox.xox.xox.xo"};
      case RhythmStyle::HalfTime: return {"x.........x.....", "........x.......", "x.x.x.x.x.x.x.x."};
      case RhythmStyle::Ballad: return {"x.......x..x....", "....x.......x...", "x...x...x...x..."};
      case RhythmStyle::Metal: return {"xxxxxxxxxxxxxxxx", "....x.......x...", "x...x...x...x..."};
      default: return {"", "", ""};
    }
  }

  void trigger(int step)
  {
    const RhythmStyle s = static_cast<RhythmStyle>(mStyle.load(std::memory_order_relaxed));
    if (s == RhythmStyle::Click)
    {
      if (step % 4 == 0)
      {
        const bool accent = step == 0;
        mClickFreq = accent ? 1600.0f : 1000.0f;
        mClickAmp = accent ? 0.6f : 0.4f;
        mClickPhase = 0.0f;
        mBeatShown.store(step / 4, std::memory_order_relaxed);
      }
      return;
    }
    if (step % 4 == 0)
      mBeatShown.store(step / 4, std::memory_order_relaxed);
    const Pattern p = pattern(s);
    auto hit = [&](const char* row) -> float {
      const char c = row[step];
      return c == 'x' ? 1.0f : (c == 'o' ? 0.45f : 0.0f);
    };
    if (float v = hit(p.kick); v > 0)
    {
      mKickAmp = 0.9f * v;
      mKickPhase = 0.0f;
      mKickT = 0;
    }
    if (float v = hit(p.snare); v > 0)
    {
      mSnareAmp = 0.5f * v;
      mSnareT = 0;
    }
    if (float v = hit(p.hat); v > 0)
      mHatAmp = 0.18f * v * ((step % 4 == 0) ? 1.0f : 0.75f);
  }

  bool voicesActive() const { return mKickAmp > 1e-4f || mSnareAmp > 1e-4f || mHatAmp > 1e-4f || mClickAmp > 1e-4f; }

  float noise()
  {
    mSeed = mSeed * 1664525u + 1013904223u;
    return static_cast<float>(static_cast<int32_t>(mSeed)) / 2147483648.0f;
  }

  float nextSample()
  {
    const float sr = static_cast<float>(mSampleRate);
    float out = 0.0f;
    if (mKickAmp > 1e-4f)
    {
      const float t = mKickT++ / sr;
      const float freq = 50.0f + 90.0f * std::exp(-t * 30.0f); // pitch drops 140 -> 50 Hz
      mKickPhase += 2.0f * static_cast<float>(M_PI) * freq / sr;
      out += mKickAmp * std::sin(mKickPhase);
      mKickAmp *= std::exp(-1.0f / (0.12f * sr));
    }
    if (mSnareAmp > 1e-4f)
    {
      const float t = mSnareT++ / sr;
      const float body = std::sin(2.0f * static_cast<float>(M_PI) * 185.0f * t) * std::exp(-t * 30.0f);
      // High-passed noise (difference of samples) for the snare wires.
      const float n = noise();
      const float wires = n - mLastNoise;
      mLastNoise = n;
      out += mSnareAmp * (0.5f * body + 0.6f * wires);
      mSnareAmp *= std::exp(-1.0f / (0.07f * sr));
    }
    if (mHatAmp > 1e-4f)
    {
      const float n = noise();
      const float bright = n - mLastHat; // crude high-pass
      mLastHat = n;
      out += mHatAmp * bright;
      mHatAmp *= std::exp(-1.0f / (0.025f * sr));
    }
    if (mClickAmp > 1e-4f)
    {
      mClickPhase += 2.0f * static_cast<float>(M_PI) * mClickFreq / sr;
      out += mClickAmp * std::sin(mClickPhase);
      mClickAmp *= std::exp(-1.0f / (0.015f * sr));
    }
    return out;
  }

  const double mSampleRate;
  std::atomic<bool> mPlaying{false};
  std::atomic<float> mBpm{100.0f};
  std::atomic<int> mStyle{0};
  std::atomic<int> mBeatsPerBar{4};
  std::atomic<float> mVolume{0.7f};
  std::atomic<int> mBeatShown{0};

  // Audio-thread state
  bool mWasPlaying = false;
  double mStepPos = 0.0;
  int mStep = 0;
  uint32_t mSeed = 12345u;
  float mKickAmp = 0, mKickPhase = 0;
  uint32_t mKickT = 0;
  float mSnareAmp = 0, mLastNoise = 0;
  uint32_t mSnareT = 0;
  float mHatAmp = 0, mLastHat = 0;
  float mClickAmp = 0, mClickPhase = 0, mClickFreq = 1000;
};

} // namespace ampengine
