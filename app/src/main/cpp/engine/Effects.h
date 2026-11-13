#pragma once

// Built-in effects that sit around the capture chain, modeled on the NAM plugin:
//   * ToneStack: bass / middle / treble on 0..10 knobs (5 = flat).
//   * NoiseGate: listens to the guitar before the chain, turns the volume down after it.
// All methods are real-time safe (no allocation, no locks).

#include <algorithm>
#include <cmath>

namespace ampengine
{

/** One second-order filter (RBJ "Audio EQ Cookbook"), transposed direct form II. */
class Biquad
{
public:
  enum class Type
  {
    LowShelf,
    Peaking,
    HighShelf
  };

  void set(Type type, double sampleRate, double freq, double q, double gainDb)
  {
    const double a = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI * freq / sampleRate;
    const double cw = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);
    double b0, b1, b2, a0, a1, a2;
    switch (type)
    {
      case Type::Peaking:
        b0 = 1 + alpha * a;
        b1 = -2 * cw;
        b2 = 1 - alpha * a;
        a0 = 1 + alpha / a;
        a1 = -2 * cw;
        a2 = 1 - alpha / a;
        break;
      case Type::LowShelf:
      {
        const double s = 2 * std::sqrt(a) * alpha;
        b0 = a * ((a + 1) - (a - 1) * cw + s);
        b1 = 2 * a * ((a - 1) - (a + 1) * cw);
        b2 = a * ((a + 1) - (a - 1) * cw - s);
        a0 = (a + 1) + (a - 1) * cw + s;
        a1 = -2 * ((a - 1) + (a + 1) * cw);
        a2 = (a + 1) + (a - 1) * cw - s;
        break;
      }
      case Type::HighShelf:
      default:
      {
        const double s = 2 * std::sqrt(a) * alpha;
        b0 = a * ((a + 1) + (a - 1) * cw + s);
        b1 = -2 * a * ((a - 1) + (a + 1) * cw);
        b2 = a * ((a + 1) + (a - 1) * cw - s);
        a0 = (a + 1) - (a - 1) * cw + s;
        a1 = 2 * ((a - 1) - (a + 1) * cw);
        a2 = (a + 1) - (a - 1) * cw - s;
        break;
      }
    }
    mB0 = static_cast<float>(b0 / a0);
    mB1 = static_cast<float>(b1 / a0);
    mB2 = static_cast<float>(b2 / a0);
    mA1 = static_cast<float>(a1 / a0);
    mA2 = static_cast<float>(a2 / a0);
  }

  float process(float x)
  {
    const float y = mB0 * x + mZ1;
    mZ1 = mB1 * x - mA1 * y + mZ2;
    mZ2 = mB2 * x - mA2 * y;
    return y;
  }

  void reset() { mZ1 = mZ2 = 0.0f; }

private:
  float mB0 = 1, mB1 = 0, mB2 = 0, mA1 = 0, mA2 = 0;
  float mZ1 = 0, mZ2 = 0;
};

/**
 * Bass / middle / treble, using the same curves as the NAM plugin's tone stack:
 *   bass:   low shelf  150 Hz, ±20 dB (4 dB per knob step)
 *   middle: peak       425 Hz, ±15 dB (3 dB per step), wider when cutting
 *   treble: high shelf 1800 Hz, ±10 dB (2 dB per step)
 * Knobs go 0..10 with 5 = flat.
 */
class ToneStack
{
public:
  void setKnobs(double sampleRate, float bass, float middle, float treble)
  {
    const double bassDb = 4.0 * (bass - 5.0);
    const double midDb = 3.0 * (middle - 5.0);
    const double trebleDb = 2.0 * (treble - 5.0);
    mBass.set(Biquad::Type::LowShelf, sampleRate, 150.0, 0.707, bassDb);
    mMid.set(Biquad::Type::Peaking, sampleRate, 425.0, midDb < 0.0 ? 1.5 : 0.7, midDb);
    mTreble.set(Biquad::Type::HighShelf, sampleRate, 1800.0, 0.707, trebleDb);
  }

  void process(float* buffer, int numFrames)
  {
    for (int i = 0; i < numFrames; ++i)
      buffer[i] = mTreble.process(mMid.process(mBass.process(buffer[i])));
  }

  void reset()
  {
    mBass.reset();
    mMid.reset();
    mTreble.reset();
  }

private:
  Biquad mBass, mMid, mTreble;
};

/**
 * Noise gate. detect() follows the level of the dry guitar; apply() turns the processed
 * signal down when you stop playing. Open 5 ms, hold 10 ms, close 50 ms (NAM plugin defaults).
 * Below the threshold, gain falls by 10 dB for every 1 dB under (ratio 0.1 → near silence).
 */
class NoiseGate
{
public:
  void prepare(double sampleRate)
  {
    mSampleRate = sampleRate;
    // RMS level over ~10 ms.
    mLevelCoeff = static_cast<float>(std::exp(-1.0 / (0.010 * sampleRate)));
    mOpenCoeff = static_cast<float>(std::exp(-1.0 / (0.005 * sampleRate)));
    mCloseCoeff = static_cast<float>(std::exp(-1.0 / (0.050 * sampleRate)));
    mHoldSamples = static_cast<int>(0.010 * sampleRate);
  }

  void setThresholdDb(float db) { mThresholdDb = db; }

  /** Call with the guitar signal (before the chain). Fills `gains` (one per frame). */
  void detect(const float* input, float* gains, int numFrames)
  {
    for (int i = 0; i < numFrames; ++i)
    {
      const float x = input[i];
      mMeanSquare = mLevelCoeff * mMeanSquare + (1.0f - mLevelCoeff) * x * x;
      const float levelDb = 10.0f * std::log10(mMeanSquare + 1e-12f);

      float target = 1.0f;
      if (levelDb >= mThresholdDb)
        mHoldLeft = mHoldSamples;
      else if (mHoldLeft > 0)
        --mHoldLeft;
      else
      {
        const float reductionDb = std::max((levelDb - mThresholdDb) * 10.0f, -120.0f);
        target = std::pow(10.0f, reductionDb / 20.0f);
      }
      const float coeff = target > mGain ? mOpenCoeff : mCloseCoeff;
      mGain = coeff * mGain + (1.0f - coeff) * target;
      gains[i] = mGain;
    }
  }

  static void apply(float* buffer, const float* gains, int numFrames)
  {
    for (int i = 0; i < numFrames; ++i)
      buffer[i] *= gains[i];
  }

  void reset()
  {
    mMeanSquare = 0.0f;
    mGain = 1.0f;
    mHoldLeft = 0;
  }

private:
  double mSampleRate = 48000.0;
  float mLevelCoeff = 0, mOpenCoeff = 0, mCloseCoeff = 0;
  int mHoldSamples = 0;
  float mThresholdDb = -60.0f;
  float mMeanSquare = 0.0f;
  float mGain = 1.0f;
  int mHoldLeft = 0;
};

} // namespace ampengine
