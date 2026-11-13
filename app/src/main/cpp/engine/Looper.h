#pragma once

// A one-button looper, like a looper pedal:
//   press: empty -> RECORDING -> (press) PLAYING -> (press) OVERDUBBING -> (press) PLAYING ...
//   stop / play, undo (last layer), clear.
//
// It records the rig's sound (your processed guitar), not the drums or backing track.
// Memory is allocated by prepare() on a normal thread; the audio thread never allocates.
// Undo restores a little at a time on the audio thread, so there's no glitch.

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <vector>

namespace ampengine
{

class Looper
{
public:
  enum class State : int
  {
    Empty = 0,
    Recording,
    Playing,
    Overdubbing,
    Stopped
  };

  static constexpr double kMaxSeconds = 60.0;

  explicit Looper(double sampleRate)
  : mSampleRate(sampleRate)
  {
  }

  /** Allocates loop memory (~50 MB for 60 s stereo + undo). Call once, off the audio thread. */
  void prepare()
  {
    if (mPrepared.load())
      return;
    const size_t n = static_cast<size_t>(kMaxSeconds * mSampleRate) * 2;
    mLoop.assign(n, 0.0f);
    mUndo.assign(n, 0.0f);
    mStamp.assign(n / 2, 0);
    mPrepared.store(true);
  }
  bool prepared() const { return mPrepared.load(); }

  // ---- Commands (any thread). Applied by the audio thread at the next block.
  enum class Command : int
  {
    None = 0,
    Press,
    StopPlay,
    Undo,
    Clear
  };
  void command(Command c) { mPending.store(static_cast<int>(c)); }

  /** If > 0, a new loop's length snaps to whole bars of this many samples (metronome sync). */
  void setBarSamples(double samples) { mBarSamples.store(samples); }
  void setVolume(float v) { mVolume.store(std::clamp(v, 0.0f, 2.0f)); }

  // ---- State for the UI
  State state() const { return static_cast<State>(mState.load()); }
  double lengthSeconds() const { return mLength.load() / mSampleRate; }
  double positionSeconds() const { return mPos.load() / mSampleRate; }
  bool canUndo() const { return mUndoAvailable.load(); }
  int layers() const { return mLayers.load(); }

  /**
   * Audio thread. `left`/`right` hold the rig's sound: it's recorded as needed, and the
   * loop playback is added into `mixL`/`mixR`.
   */
  void process(const float* left, const float* right, float* mixL, float* mixR, int numFrames)
  {
    if (!mPrepared.load(std::memory_order_acquire))
      return;
    handleCommand();
    continueUndo();

    State st = static_cast<State>(mState.load(std::memory_order_relaxed));
    const int64_t capacity = static_cast<int64_t>(mLoop.size() / 2);
    int64_t len = mLength.load(std::memory_order_relaxed);
    int64_t pos = mPos.load(std::memory_order_relaxed);
    const float vol = mVolume.load(std::memory_order_relaxed);

    for (int i = 0; i < numFrames; ++i)
    {
      if (st == State::Recording)
      {
        if (pos >= capacity) // hit the time limit: start looping what we have
        {
          len = finishRecording(pos);
          st = State::Playing;
          pos = 0;
        }
        else
        {
          mLoop[pos * 2] = left[i];
          mLoop[pos * 2 + 1] = right[i];
          ++pos;
          continue;
        }
      }
      if ((st == State::Playing || st == State::Overdubbing) && len > 0)
      {
        float& l = mLoop[pos * 2];
        float& r = mLoop[pos * 2 + 1];
        mixL[i] += vol * l;
        mixR[i] += vol * r;
        if (st == State::Overdubbing)
        {
          if (mStamp[pos] != mLayerId) // first time this layer touches this spot: save for undo
          {
            mStamp[pos] = mLayerId;
            mUndo[pos * 2] = l;
            mUndo[pos * 2 + 1] = r;
          }
          l += left[i];
          r += right[i];
        }
        if (++pos >= len)
          pos = 0;
      }
    }
    mState.store(static_cast<int>(st), std::memory_order_relaxed);
    mLength.store(len, std::memory_order_relaxed);
    mPos.store(pos, std::memory_order_relaxed);
  }

private:
  int64_t finishRecording(int64_t recorded)
  {
    int64_t len = recorded;
    const double bar = mBarSamples.load(std::memory_order_relaxed);
    if (bar > 0)
    {
      const double bars = std::max(1.0, std::round(recorded / bar));
      len = std::min<int64_t>(static_cast<int64_t>(bars * bar), static_cast<int64_t>(mLoop.size() / 2));
      for (int64_t p = recorded; p < len; ++p) // padded with silence
        mLoop[p * 2] = mLoop[p * 2 + 1] = 0.0f;
    }
    mLayers.store(1, std::memory_order_relaxed);
    return std::max<int64_t>(len, 1);
  }

  void handleCommand()
  {
    const Command c = static_cast<Command>(mPending.exchange(0));
    if (c == Command::None)
      return;
    State st = static_cast<State>(mState.load(std::memory_order_relaxed));
    switch (c)
    {
      case Command::Press:
        if (st == State::Empty)
        {
          mPos.store(0);
          mLength.store(0);
          mUndoAvailable.store(false);
          st = State::Recording;
        }
        else if (st == State::Recording)
        {
          mLength.store(finishRecording(mPos.load()));
          mPos.store(0);
          st = State::Playing;
        }
        else if (st == State::Playing)
        {
          if (mUndoing)
            break; // finishing an undo (a fraction of a second); press again
          ++mLayerId; // a new layer: its first touches get saved for undo
          if (mLayerId == 0)
            mLayerId = 1; // (0 means "never")
          mUndoAvailable.store(true);
          mLayers.fetch_add(1);
          st = State::Overdubbing;
        }
        else if (st == State::Overdubbing)
          st = State::Playing;
        else if (st == State::Stopped)
          st = State::Playing;
        break;
      case Command::StopPlay:
        if (st == State::Playing || st == State::Overdubbing)
          st = State::Stopped;
        else if (st == State::Stopped)
        {
          mPos.store(0);
          st = State::Playing;
        }
        else if (st == State::Recording)
        {
          mLength.store(finishRecording(mPos.load()));
          mPos.store(0);
          st = State::Stopped;
        }
        break;
      case Command::Undo:
        if (mUndoAvailable.load() && st != State::Recording)
        {
          if (st == State::Overdubbing)
            st = State::Playing;
          mUndoCursor = 0; // restore gradually in continueUndo()
          mUndoing = true;
          mUndoAvailable.store(false);
          mLayers.fetch_sub(1);
        }
        break;
      case Command::Clear:
        st = State::Empty;
        mLength.store(0);
        mPos.store(0);
        mUndoAvailable.store(false);
        mUndoing = false;
        mLayers.store(0);
        break;
      default: break;
    }
    mState.store(static_cast<int>(st));
  }

  void continueUndo()
  {
    if (!mUndoing)
      return;
    const int64_t len = mLength.load(std::memory_order_relaxed);
    const int64_t end = std::min<int64_t>(len, mUndoCursor + 24000); // ~0.5 s of loop per callback
    for (int64_t p = mUndoCursor; p < end; ++p)
    {
      if (mStamp[p] == mLayerId)
      {
        mLoop[p * 2] = mUndo[p * 2];
        mLoop[p * 2 + 1] = mUndo[p * 2 + 1];
        mStamp[p] = 0;
      }
    }
    mUndoCursor = end;
    if (mUndoCursor >= len)
      mUndoing = false;
  }

  const double mSampleRate;
  std::atomic<bool> mPrepared{false};
  std::vector<float> mLoop, mUndo; // interleaved stereo
  std::vector<uint16_t> mStamp; // which layer last saved each position for undo
  uint16_t mLayerId = 0;
  std::atomic<int> mPending{0};
  std::atomic<int> mState{0};
  std::atomic<int64_t> mLength{0};
  std::atomic<int64_t> mPos{0};
  std::atomic<double> mBarSamples{0.0};
  std::atomic<float> mVolume{1.0f};
  std::atomic<bool> mUndoAvailable{false};
  std::atomic<int> mLayers{0};
  bool mUndoing = false;
  int64_t mUndoCursor = 0;
};

} // namespace ampengine
