#pragma once

// Pitch detection for the tuner (YIN algorithm, de Cheveigné & Kawahara 2002).
// Not real-time code: runs on a background thread on a copy of recent input.

#include <cmath>
#include <vector>

namespace ampengine
{

struct PitchResult
{
  float hz = 0.0f; // 0 = no clear pitch (silence or noise)
  float clarity = 0.0f; // 0..1, higher = more confident
};

/**
 * Finds the fundamental frequency of `x` (mono, `n` samples at `sampleRate`) between
 * minHz and maxHz. Covers 7-string guitars, drop tunings and bass by default.
 */
inline PitchResult detectPitch(const float* x, int n, double sampleRate, float minHz = 28.0f, float maxHz = 1400.0f)
{
  PitchResult result;
  // Ignore very quiet input (about -55 dBFS RMS).
  double energy = 0.0;
  for (int i = 0; i < n; ++i)
    energy += double(x[i]) * x[i];
  if (n <= 0 || energy / n < 3e-6)
    return result;

  const int tauMin = std::max(2, static_cast<int>(sampleRate / maxHz));
  const int tauMax = std::min(n / 2, static_cast<int>(sampleRate / minHz));
  if (tauMax <= tauMin + 2)
    return result;
  const int w = n - tauMax; // window length

  // Step 2+3: difference function and cumulative mean normalized difference.
  std::vector<float> d(tauMax + 1, 0.0f);
  d[0] = 1.0f;
  double runningSum = 0.0;
  for (int tau = 1; tau <= tauMax; ++tau)
  {
    double sum = 0.0;
    for (int i = 0; i < w; ++i)
    {
      const double diff = double(x[i]) - x[i + tau];
      sum += diff * diff;
    }
    runningSum += sum;
    d[tau] = runningSum > 0.0 ? static_cast<float>(sum * tau / runningSum) : 1.0f;
  }

  // Step 4: first dip below the threshold (then walk to its bottom).
  constexpr float kThreshold = 0.12f;
  int best = -1;
  for (int tau = tauMin; tau <= tauMax; ++tau)
  {
    if (d[tau] < kThreshold)
    {
      while (tau + 1 <= tauMax && d[tau + 1] < d[tau])
        ++tau;
      best = tau;
      break;
    }
  }
  if (best < 0)
    return result;

  // Step 5: parabolic interpolation for sub-sample accuracy.
  double refined = best;
  if (best > 1 && best < tauMax)
  {
    const double a = d[best - 1], b = d[best], c = d[best + 1];
    const double denom = a - 2 * b + c;
    if (std::fabs(denom) > 1e-12)
      refined = best + 0.5 * (a - c) / denom;
  }
  result.hz = static_cast<float>(sampleRate / refined);
  result.clarity = std::max(0.0f, 1.0f - d[best]);
  return result;
}

} // namespace ampengine
