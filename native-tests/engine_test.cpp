// Desktop tests for EngineCore: load NAM example models and a generated IR,
// push a guitar-like signal through, and check the output is sane and fast.

#include <array>
#include <atomic>
#include <algorithm>
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

// RMS level (dB) of the second half of `signal` after running it through `core`.
static double renderDb(EngineCore& core, const std::vector<float>& signal)
{
  std::vector<float> out(signal.size());
  for (size_t pos = 0; pos + 256 <= signal.size(); pos += 256)
    core.process(signal.data() + pos, out.data() + pos, 256);
  double sum = 0;
  const size_t start = out.size() / 2;
  for (size_t i = start; i < out.size(); ++i)
    sum += double(out[i]) * out[i];
  return 10.0 * std::log10(sum / (out.size() - start) + 1e-20);
}

static std::vector<float> sine(double freq, double amp, int frames)
{
  std::vector<float> v(frames);
  for (int i = 0; i < frames; ++i)
    v[i] = static_cast<float>(amp * std::sin(2 * M_PI * freq * i / kRate));
  return v;
}


// Writes a stereo 16-bit WAV IR whose left and right channels differ.
static void writeStereoIr(const fs::path& path, int length, int sampleRate)
{
  std::mt19937 rng(9);
  std::normal_distribution<float> noise(0.0f, 1.0f);
  std::vector<int16_t> samples(length * 2);
  for (int i = 0; i < length; ++i)
  {
    const float env = std::exp(-8.0f * i / length);
    samples[i * 2] = static_cast<int16_t>(std::clamp((i == 0 ? 0.9f : 0.3f * noise(rng)) * env, -1.0f, 1.0f) * 32767);
    samples[i * 2 + 1] = static_cast<int16_t>(std::clamp((i == 3 ? 0.6f : 0.2f * noise(rng)) * env, -1.0f, 1.0f) * 32767);
  }
  auto put32 = [](std::ofstream& f, uint32_t v) { f.write(reinterpret_cast<const char*>(&v), 4); };
  auto put16 = [](std::ofstream& f, uint16_t v) { f.write(reinterpret_cast<const char*>(&v), 2); };
  std::ofstream f(path, std::ios::binary);
  const uint32_t dataBytes = static_cast<uint32_t>(samples.size() * 2);
  f.write("RIFF", 4);
  put32(f, 36 + dataBytes);
  f.write("WAVEfmt ", 8);
  put32(f, 16);
  put16(f, 1);
  put16(f, 2);
  put32(f, static_cast<uint32_t>(sampleRate));
  put32(f, static_cast<uint32_t>(sampleRate * 4));
  put16(f, 4);
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
    CHECK(!core.loadBlock(1, (tmp / "missing.nam").string()).empty(), "missing .nam reports an error");
    CHECK(!core.loadBlock(1, (tmp / "x.txt").string()).empty(), "unsupported extension reports an error");
    std::ofstream(tmp / "garbage.nam") << "not json";
    CHECK(!core.loadBlock(1, (tmp / "garbage.nam").string()).empty(), "corrupt .nam reports an error");
    CHECK(core.blockCount() == 0, "no blocks after failures");
  }

  std::printf("3) Each example model, 128-frame blocks\n");
  for (const char* name : {"lstm.nam", "wavenet.nam", "wavenet_a1_standard.nam", "A2.nam"})
  {
    EngineCore core(kRate);
    const std::string err = core.loadBlock(1, (models / name).string());
    CHECK(err.empty(), (std::string("load ") + name + ": " + err).c_str());
    if (!err.empty())
      continue;
    auto s = runSignal(core, input, 128);
    std::printf("   %-26s peak %.3f  speed %.1fx real time  cpu %.0f%%\n", name, s.peak, s.realtimeFactor,
                core.takeCpuLoad() * 100);
    CHECK(s.allFinite, "output finite");
    CHECK(s.peak > 1e-4f, "model produces sound");
  }

  writeTestIr(tmp / "cab.wav", 2048, 44100); // different rate: exercises resampling

  std::printf("4) Pedal -> amp -> cab chain, odd block size\n");
  {
    EngineCore core(kRate);
    CHECK(core.loadBlock(10, (models / "lstm.nam").string()).empty(), "load pedal");
    CHECK(core.loadBlock(20, (models / "A2.nam").string()).empty(), "load amp");
    const std::string err = core.loadBlock(30, (tmp / "cab.wav").string());
    CHECK(err.empty(), ("load IR: " + err).c_str());
    CHECK(core.blockCount() == 3, "three blocks");
    auto s = runSignal(core, input, 333); // not a power of two, on purpose
    std::printf("   pedal+amp+cab              peak %.3f  speed %.1fx real time\n", s.peak, s.realtimeFactor);
    CHECK(s.allFinite, "output finite");
    CHECK(s.peak > 1e-4f, "chain produces sound");

    std::vector<float> big(EngineCore::kMaxFrames * 3 + 17, 0.1f), out(big.size());
    core.process(big.data(), out.data(), static_cast<int>(big.size()));
    bool finite = true;
    for (float v : out)
      finite = finite && std::isfinite(v);
    CHECK(finite, "oversized callback handled");
    CHECK(core.takeCpuLoad() > 0.0f, "CPU meter reports load");
    CHECK(core.takeCpuLoad() == 0.0f, "CPU meter resets after reading");
  }

  std::printf("5) Order matters, remove, replace, limits\n");
  {
    // Two different chains of the same blocks should sound different (nonlinear models).
    auto render = [&](const std::vector<int64_t>& order) {
      EngineCore core(kRate);
      core.loadBlock(1, (models / "lstm.nam").string());
      core.loadBlock(2, (models / "wavenet.nam").string());
      core.setNormalize(false);
      core.setOrder(order);
      std::vector<float> out(input.size());
      for (size_t pos = 0; pos + 256 <= input.size(); pos += 256)
        core.process(input.data() + pos, out.data() + pos, 256);
      return out;
    };
    auto a = render({1, 2});
    auto b = render({2, 1});
    double diff = 0;
    for (size_t i = 0; i < a.size(); ++i)
      diff += std::fabs(a[i] - b[i]);
    CHECK(diff > 1.0, "reordering blocks changes the sound");

    EngineCore core(kRate);
    CHECK(core.loadBlock(1, (models / "lstm.nam").string()).empty(), "load");
    CHECK(core.loadBlock(1, (models / "wavenet.nam").string()).empty(), "replace same id");
    CHECK(core.blockCount() == 1, "replacing keeps one block");
    core.setBlockEnabled(1, false);
    runSignal(core, input, 256);
    auto s = runSignal(core, input, 256);
    CHECK(std::fabs(s.peak - 0.48f) < 0.1f, "disabled block passes dry signal");
    CHECK(core.loadBlock(1, (models / "lstm.nam").string()).empty(), "replace while disabled");
    s = runSignal(core, input, 256);
    CHECK(std::fabs(s.peak - 0.48f) < 0.1f, "replacement keeps the block disabled");
    core.removeBlock(1);
    CHECK(core.blockCount() == 0, "remove");
    core.setOrder({5, 6, 7}); // unknown ids are ignored
    for (int i = 0; i < EngineCore::kMaxBlocks; ++i)
      CHECK(core.loadBlock(100 + i, (models / "lstm.nam").string()).empty(), "fill chain");
    CHECK(!core.loadBlock(999, (models / "lstm.nam").string()).empty(), "chain limit enforced");
    CHECK(core.loadBlock(100, (models / "wavenet.nam").string()).empty(), "replace allowed when full");
  }

  std::printf("6) Gain controls\n");
  {
    EngineCore core(kRate);
    core.setOutputGainDb(-6.0f);
    runSignal(core, input, 256); // let gain ramp settle
    auto s = runSignal(core, input, 256);
    CHECK(std::fabs(s.peak - 0.48f * 0.501f) < 0.05f, "-6 dB output gain halves the level");
    core.takeInputPeak();
    std::vector<float> in(256, 0.25f), out(256);
    core.process(in.data(), out.data(), 256);
    CHECK(std::fabs(core.takeInputPeak() - 0.25f) < 1e-3f, "input meter reads the peak");
    CHECK(core.takeInputPeak() == 0.0f, "meter resets after reading");
  }

  std::printf("7) Edit the chain while audio is running (thread safety)\n");
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
    for (int i = 0; i < 12; ++i)
    {
      CHECK(core.loadBlock(i % 4, (models / names[i % 3]).string()).empty(), "load while running");
      core.setOrder({3, 2, 1, 0});
      core.setBlockEnabled(i % 4, i % 2 == 0);
      if (i % 5 == 4)
        core.removeBlock(i % 4);
    }
    CHECK(core.loadBlock(9, (tmp / "cab.wav").string()).empty(), "IR load while running");
    core.setOrder({9, 0, 1});
    core.removeBlock(9);
    running = false;
    audio.join();
    std::printf("   processed %ld blocks during chain edits\n", blocks.load());
    CHECK(finite.load(), "output stayed finite during edits");
  }

  std::printf("8) EQ (bass/middle/treble) and noise gate\n");
  {
    const int n = static_cast<int>(kRate); // 1 second
    const auto low = sine(60, 0.1, n), mid = sine(425, 0.1, n), high = sine(8000, 0.1, n);
    auto dbWith = [&](const std::vector<float>& sig, bool on, float b, float m, float t) {
      EngineCore core(kRate);
      core.setEq(on, b, m, t);
      return renderDb(core, sig);
    };
    const double ref = dbWith(low, false, 5, 5, 5);
    CHECK(std::fabs(dbWith(low, true, 5, 5, 5) - ref) < 0.1, "EQ at 5/5/5 is flat");
    const double bassBoost = dbWith(low, true, 10, 5, 5) - ref;
    const double midCut = dbWith(mid, true, 5, 0, 5) - dbWith(mid, false, 5, 5, 5);
    const double trebleCut = dbWith(high, true, 5, 5, 0) - dbWith(high, false, 5, 5, 5);
    std::printf("   bass 10: %+.1f dB @60Hz, middle 0: %+.1f dB @425Hz, treble 0: %+.1f dB @8kHz\n", bassBoost,
                midCut, trebleCut);
    CHECK(bassBoost > 17 && bassBoost < 21, "bass 10 boosts lows by ~20 dB");
    CHECK(midCut < -13 && midCut > -16, "middle 0 cuts mids by ~15 dB");
    CHECK(trebleCut < -8.5 && trebleCut > -10.5, "treble 0 cuts highs by ~10 dB");

    // Gate: quiet hiss (-70 dB) under a -50 dB threshold gets silenced; playing passes.
    std::mt19937 rng(7);
    std::normal_distribution<float> noise(0.0f, 0.0003f);
    std::vector<float> hiss(n);
    for (auto& s : hiss)
      s = noise(rng);
    auto gated = [&](const std::vector<float>& sig, bool on) {
      EngineCore core(kRate);
      core.setGate(on, -50.0f);
      return renderDb(core, sig);
    };
    const double hissOff = gated(hiss, false), hissOn = gated(hiss, true);
    const double playOff = gated(low, false), playOn = gated(low, true);
    std::printf("   gate: hiss %.1f -> %.1f dB, playing %.1f -> %.1f dB\n", hissOff, hissOn, playOff, playOn);
    CHECK(hissOn < hissOff - 40, "gate silences hiss below the threshold");
    CHECK(std::fabs(playOn - playOff) < 0.5, "gate leaves playing untouched");
  }

  std::printf("9) Tuner\n");
  {
    // Pure pitch detection on strings of a guitar (and a low bass/7-string note).
    struct Note { const char* name; double hz; };
    for (Note note : {Note{"B0 (7-str/bass)", 30.87}, Note{"E2", 82.41}, Note{"A2", 110.0}, Note{"D3", 146.83},
                      Note{"G3", 196.0}, Note{"B3", 246.94}, Note{"E4", 329.63}, Note{"E5 (12th fret)", 659.26}})
    {
      // Guitar-like: fundamental + harmonics.
      std::vector<float> x(4096);
      for (int i = 0; i < 4096; ++i)
      {
        const double t = i / kRate;
        x[i] = static_cast<float>(0.2 * std::sin(2 * M_PI * note.hz * t) + 0.15 * std::sin(2 * M_PI * 2 * note.hz * t)
                                  + 0.08 * std::sin(2 * M_PI * 3 * note.hz * t));
      }
      auto r = ampengine::detectPitch(x.data(), 4096, kRate);
      const double cents = 1200.0 * std::log2(r.hz / note.hz);
      std::printf("   %-16s %7.2f Hz -> %7.2f Hz (%+.2f cents)\n", note.name, note.hz, r.hz, cents);
      CHECK(std::fabs(cents) < 2.0, "pitch within 2 cents");
    }
    std::vector<float> quiet(4096, 0.0f);
    CHECK(ampengine::detectPitch(quiet.data(), 4096, kRate).hz == 0.0f, "silence gives no pitch");

    // Through the engine: tuner mode mutes the output and reads the input.
    EngineCore core(kRate);
    CHECK(core.loadBlock(1, (models / "lstm.nam").string()).empty(), "load");
    core.setTunerActive(true);
    const auto a2 = sine(110.0, 0.2, 9600);
    std::vector<float> out(256);
    float outPeak = 0.0f;
    for (size_t pos = 0; pos + 256 <= a2.size(); pos += 256)
    {
      core.process(a2.data() + pos, out.data(), 256);
      for (float v : out)
        outPeak = std::max(outPeak, std::fabs(v));
    }
    CHECK(outPeak == 0.0f, "output muted while tuning");
    auto r = core.readTunerPitch();
    CHECK(std::fabs(r.hz - 110.0f) < 0.5f, "engine tuner reads A2");
    core.setTunerActive(false);
    for (size_t pos = 0; pos + 256 <= a2.size(); pos += 256)
      core.process(a2.data() + pos, out.data(), 256);
    CHECK(std::fabs(out[100]) > 0.0f, "sound returns after tuning");
  }

  std::printf("10) Stereo, block level, calibration, EQ position, recording\n");
  {
    writeStereoIr(tmp / "stereo.wav", 1024, 48000);
    std::vector<float> L(256), R(256);
    auto render = [&](EngineCore& core, const std::vector<float>& sig) {
      double diff = 0, energyL = 0, energyR = 0;
      for (size_t pos = 0; pos + 256 <= sig.size(); pos += 256)
      {
        core.process(sig.data() + pos, L.data(), R.data(), 256);
        for (int i = 0; i < 256; ++i)
        {
          diff += std::fabs(L[i] - R[i]);
          energyL += L[i] * L[i];
          energyR += R[i] * R[i];
        }
      }
      return std::array<double, 3>{diff, energyL, energyR};
    };
    {
      EngineCore core(kRate);
      CHECK(core.loadBlock(1, (tmp / "stereo.wav").string()).empty(), "load stereo IR");
      auto r = render(core, input);
      CHECK(r[0] > 1.0 && r[1] > 0 && r[2] > 0, "stereo IR gives different left and right");
    }
    {
      EngineCore core(kRate);
      CHECK(core.loadBlock(1, (tmp / "cab.wav").string()).empty(), "load mono IR");
      CHECK(render(core, input)[0] == 0.0, "mono IR gives identical left and right");
      // A NAM block after a stereo IR gets the mix and makes it mono again.
      CHECK(core.loadBlock(2, (tmp / "stereo.wav").string()).empty(), "load");
      CHECK(core.loadBlock(3, (models / "lstm.nam").string()).empty(), "load");
      core.setOrder({2, 3});
      auto r = render(core, input);
      CHECK(r[0] == 0.0 && r[1] > 0, "mono block after stereo IR is mono and audible");
    }
    {
      EngineCore core(kRate);
      core.setNormalize(false);
      CHECK(core.loadBlock(1, (tmp / "cab.wav").string()).empty(), "load");
      const double base = renderDb(core, input);
      core.setBlockLevelDb(1, -6.0f);
      const double trimmed = renderDb(core, input);
      std::printf("   block level -6 dB: %.2f dB change\n", trimmed - base);
      CHECK(std::fabs((trimmed - base) + 6.0) < 0.2, "block level trims by 6 dB");
    }
    {
      // lstm.nam was captured at 18.3 dBu. With a 12 dBu interface, calibration
      // turns the signal into the capture down by 6.3 dB; the IR in front is unaffected.
      const auto quiet = sine(110.0, 0.05, static_cast<int>(kRate));
      EngineCore off(kRate), on(kRate);
      for (EngineCore* core : {&off, &on})
      {
        core->setNormalize(false);
        CHECK(core->loadBlock(1, (models / "lstm.nam").string()).empty(), "load");
      }
      on.setInputCalibration(true, 12.0f);
      const double dOff = renderDb(off, quiet), dOn = renderDb(on, quiet);
      std::printf("   calibration (12 dBu interface, 18.3 dBu capture): %.2f -> %.2f dB\n", dOff, dOn);
      CHECK(dOn < dOff - 1.0, "calibration lowers the drive into a hotter capture");
    }
    {
      EngineCore after(kRate), before(kRate);
      for (EngineCore* core : {&after, &before})
      {
        CHECK(core->loadBlock(1, (models / "lstm.nam").string()).empty(), "load amp");
        CHECK(core->loadBlock(2, (tmp / "cab.wav").string()).empty(), "load cab");
        core->setEq(true, 10, 5, 0);
      }
      before.setEqBeforeCab(true);
      std::vector<float> a(input.size()), b(input.size());
      for (size_t pos = 0; pos + 256 <= input.size(); pos += 256)
      {
        after.process(input.data() + pos, a.data() + pos, 256);
        before.process(input.data() + pos, b.data() + pos, 256);
      }
      double diff = 0;
      bool finite = true;
      for (size_t i = 0; i < a.size(); ++i)
      {
        diff += std::fabs(a[i] - b[i]);
        finite = finite && std::isfinite(a[i]) && std::isfinite(b[i]);
      }
      CHECK(finite, "EQ before/after cab stays finite");
      // Linear filters commute, so with a linear cab the two orders match closely.
      CHECK(diff / a.size() < 1e-3, "EQ before cab equals EQ after cab for linear blocks");
    }
    {
      EngineCore core(kRate);
      CHECK(core.loadBlock(1, (tmp / "stereo.wav").string()).empty(), "load");
      const fs::path wav = tmp / "take.wav";
      CHECK(core.recorder().start(wav.string()).empty(), "start recording");
      const int frames = static_cast<int>(kRate); // 1 second
      for (int pos = 0; pos + 480 <= frames; pos += 480)
        core.process(input.data() + pos, L.data(), R.data(), 240), core.process(input.data() + pos + 240, L.data(), R.data(), 240);
      std::this_thread::sleep_for(std::chrono::milliseconds(50));
      const double secs = core.recorder().stop();
      std::printf("   recorded %.3f s, %llu dropped\n", secs, (unsigned long long)core.recorder().droppedFrames());
      CHECK(std::fabs(secs - 1.0) < 0.01, "recording length matches");
      CHECK(core.recorder().droppedFrames() == 0, "no frames dropped");
      std::ifstream f(wav, std::ios::binary | std::ios::ate);
      const auto size = static_cast<long long>(f.tellg());
      CHECK(size == 44 + static_cast<long long>(secs * kRate + 0.5) * 4, "WAV file size matches header");
      f.seekg(0);
      char riff[4];
      f.read(riff, 4);
      CHECK(std::string(riff, 4) == "RIFF", "valid WAV header");
      CHECK(!core.recorder().recording(), "stopped");
    }
  }

  fs::remove_all(tmp);
  if (gFailures == 0)
    std::printf("\nALL TESTS PASSED\n");
  else
    std::printf("\n%d CHECK(S) FAILED\n", gFailures);
  return gFailures == 0 ? 0 : 1;
}
