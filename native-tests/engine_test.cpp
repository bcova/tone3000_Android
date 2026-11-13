// Desktop tests for EngineCore: load NAM example models and a generated IR,
// push a guitar-like signal through, and check the output is sane and fast.

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <random>
#include <string>
#include <thread>
#include <vector>

#include "EngineCore.h"

namespace fs = std::filesystem;
using ampengine::EngineCore;

static int gFailures = 0;

#define CHECK(cond, msg)                                                                                               \
  do                                                                                                                   \
  {                                                                                                                    \
    if (!(cond))                                                                                                       \
    {                                                                                                                  \
      std::printf("  FAIL: %s (%s:%d)\n", msg, __FILE__, __LINE__);                                                    \
      ++gFailures;                                                                                                     \
    }                                                                                                                  \
  } while (0)

constexpr double kRate = 48000.0;

// Plucked-string-ish test signal: decaying 110 Hz tone with harmonics.
static std::vector<float> makeGuitarish(int frames)
{
  std::vector<float> v(frames);
  for (int i = 0; i < frames; ++i)
  {
    const double t = i / kRate;
    const double env = std::exp(-3.0 * std::fmod(t, 1.0));
    v[i] = static_cast<float>(0.3 * env
                              * (std::sin(2 * M_PI * 110 * t) + 0.5 * std::sin(2 * M_PI * 220 * t)
                                 + 0.25 * std::sin(2 * M_PI * 330 * t)));
  }
  return v;
}

// Writes a mono 16-bit WAV with a decaying-noise "cabinet" impulse response.
static void writeTestIr(const fs::path& path, int length, int sampleRate)
{
  std::mt19937 rng(42);
  std::normal_distribution<float> noise(0.0f, 1.0f);
  std::vector<int16_t> samples(length);
  for (int i = 0; i < length; ++i)
  {
    const float v = (i == 0 ? 1.0f : 0.3f * noise(rng)) * std::exp(-8.0f * i / length);
    samples[i] = static_cast<int16_t>(std::clamp(v, -1.0f, 1.0f) * 32767.0f);
  }
  auto put32 = [](std::ofstream& f, uint32_t v) { f.write(reinterpret_cast<const char*>(&v), 4); };
  auto put16 = [](std::ofstream& f, uint16_t v) { f.write(reinterpret_cast<const char*>(&v), 2); };
  std::ofstream f(path, std::ios::binary);
  const uint32_t dataBytes = static_cast<uint32_t>(length * 2);
  f.write("RIFF", 4);
  put32(f, 36 + dataBytes);
  f.write("WAVEfmt ", 8);
  put32(f, 16);
  put16(f, 1); // PCM
  put16(f, 1); // mono
  put32(f, static_cast<uint32_t>(sampleRate));
  put32(f, static_cast<uint32_t>(sampleRate * 2));
  put16(f, 2);
  put16(f, 16);
  f.write("data", 4);
  put32(f, dataBytes);
  f.write(reinterpret_cast<const char*>(samples.data()), dataBytes);
}

struct RunStats
{
  bool allFinite = true;
  float peak = 0.0f;
  double realtimeFactor = 0.0; // >1 means faster than real time
};

static RunStats runSignal(EngineCore& core, const std::vector<float>& input, int block)
{
  RunStats stats;
  std::vector<float> out(block);
  const auto start = std::chrono::steady_clock::now();
  for (size_t pos = 0; pos + block <= input.size(); pos += block)
  {
    core.process(input.data() + pos, out.data(), block);
    for (float s : out)
    {
      stats.allFinite = stats.allFinite && std::isfinite(s);
      stats.peak = std::max(stats.peak, std::fabs(s));
    }
  }
  const double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
  stats.realtimeFactor = (input.size() / kRate) / secs;
  return stats;
}

int main()
{
  const fs::path models = NAM_EXAMPLE_MODELS;
  const fs::path tmp = fs::temp_directory_path() / "engine_test";
  fs::create_directories(tmp);
  const auto input = makeGuitarish(static_cast<int>(kRate * 3));

  std::printf("1) Pass-through with nothing loaded\n");
  {
    EngineCore core(kRate);
    auto s = runSignal(core, input, 192);
    CHECK(s.allFinite, "output finite");
    CHECK(std::fabs(s.peak - 0.3f * 1.6f) < 0.2f, "dry signal passes through at unity gain");
  }

  std::printf("2) Rejects bad files\n");
  {
    EngineCore core(kRate);
    CHECK(!core.loadModel((tmp / "missing.nam").string()).empty(), "missing .nam reports an error");
    CHECK(!core.loadModel((tmp / "x.wav").string()).empty(), "wrong extension for model reports an error");
    CHECK(!core.loadIr((models / "lstm.nam").string()).empty(), "wrong extension for IR reports an error");
    std::ofstream(tmp / "garbage.nam") << "not json";
    CHECK(!core.loadModel((tmp / "garbage.nam").string()).empty(), "corrupt .nam reports an error");
    CHECK(!core.hasModel(), "no model loaded after failures");
  }

  std::printf("3) Each example model, 128-frame blocks\n");
  for (const char* name : {"lstm.nam", "wavenet.nam", "wavenet_a1_standard.nam", "A2.nam"})
  {
    EngineCore core(kRate);
    const std::string err = core.loadModel((models / name).string());
    CHECK(err.empty(), (std::string("load ") + name + ": " + err).c_str());
    if (!err.empty())
      continue;
    auto s = runSignal(core, input, 128);
    std::printf("   %-26s peak %.3f  speed %.1fx real time\n", name, s.peak, s.realtimeFactor);
    CHECK(s.allFinite, "output finite");
    CHECK(s.peak > 1e-4f, "model produces sound");
  }

  std::printf("4) Model + cabinet IR, odd block size\n");
  {
    writeTestIr(tmp / "cab.wav", 2048, 44100); // different rate: exercises resampling
    EngineCore core(kRate);
    CHECK(core.loadModel((models / "wavenet_a1_standard.nam").string()).empty(), "load model");
    const std::string err = core.loadIr((tmp / "cab.wav").string());
    CHECK(err.empty(), ("load IR: " + err).c_str());
    auto s = runSignal(core, input, 333); // not a power of two, on purpose
    std::printf("   model+IR                   peak %.3f  speed %.1fx real time\n", s.peak, s.realtimeFactor);
    CHECK(s.allFinite, "output finite");
    CHECK(s.peak > 1e-4f, "chain produces sound");

    // Oversized callback gets split into chunks.
    std::vector<float> big(EngineCore::kMaxBlock * 3 + 17, 0.1f), out(big.size());
    core.process(big.data(), out.data(), static_cast<int>(big.size()));
    bool finite = true;
    for (float v : out)
      finite = finite && std::isfinite(v);
    CHECK(finite, "oversized block handled");
  }

  std::printf("5) Gain controls and bypass\n");
  {
    EngineCore core(kRate);
    core.setOutputGainDb(-6.0f);
    runSignal(core, input, 256); // let gain ramp settle
    auto s = runSignal(core, input, 256);
    CHECK(std::fabs(s.peak - 0.48f * 0.501f) < 0.05f, "-6 dB output gain halves the level");
    CHECK(core.loadModel((models / "lstm.nam").string()).empty(), "load model");
    core.setModelEnabled(false);
    core.setOutputGainDb(0.0f);
    runSignal(core, input, 256);
    s = runSignal(core, input, 256);
    CHECK(std::fabs(s.peak - 0.48f) < 0.1f, "bypassed model passes dry signal");
    core.takeInputPeak();
    std::vector<float> in(256, 0.25f), out(256);
    core.process(in.data(), out.data(), 256);
    CHECK(std::fabs(core.takeInputPeak() - 0.25f) < 1e-3f, "input meter reads the peak");
    CHECK(core.takeInputPeak() == 0.0f, "meter resets after reading");
  }

  std::printf("6) Swap models while audio is running (thread safety)\n");
  {
    EngineCore core(kRate);
    std::atomic<bool> running{true};
    std::atomic<bool> finite{true};
    std::atomic<long> blocks{0};
    std::thread audio([&] {
      std::vector<float> out(128);
      size_t pos = 0;
      while (running.load())
      {
        core.process(input.data() + pos, out.data(), 128);
        for (float v : out)
          if (!std::isfinite(v))
            finite = false;
        pos = (pos + 128) % (input.size() - 128);
        ++blocks;
      }
    });
    const char* names[] = {"lstm.nam", "wavenet.nam", "wavenet_a1_standard.nam"};
    for (int i = 0; i < 9; ++i)
    {
      CHECK(core.loadModel((models / names[i % 3]).string()).empty(), "swap load");
      if (i % 3 == 2)
        core.clearModel();
    }
    CHECK(core.loadIr((tmp / "cab.wav").string()).empty(), "IR load while running");
    core.clearIr();
    running = false;
    audio.join();
    std::printf("   processed %ld blocks during 10 swaps\n", blocks.load());
    CHECK(finite.load(), "output stayed finite during swaps");
  }

  fs::remove_all(tmp);
  if (gFailures == 0)
    std::printf("\nALL TESTS PASSED\n");
  else
    std::printf("\n%d CHECK(S) FAILED\n", gFailures);
  return gFailures == 0 ? 0 : 1;
}
