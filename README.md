# tone3000_android

A free Android app that plays **NAM (Neural Amp Modeler)** amp captures and **cabinet IRs**
— the same kinds of tones you find on [TONE3000](https://www.tone3000.com).

> **Status:** steps 1–3 done (audio engine + loading files from your phone).
> TONE3000 sign-in and in-app browsing come next (see [Roadmap](#roadmap)).

```
guitar ─► USB interface ─► input gain ─► NAM amp model (.nam) ─► cabinet IR (.wav) ─► output volume ─► headphones
```

---

## What you need

1. **Android Studio** (a recent version) on your computer.
2. **An Android phone**, Android 8.0 or newer. Newer phones have lower latency.
3. **A USB audio interface** that works with phones (USB-C, "class compliant").
   A guitar can't plug straight into a phone.
4. **Headphones** plugged into the interface. Using the phone speaker will cause feedback.

## Get the code (important: include submodules)

The amp engine ([NeuralAmpModelerCore](https://github.com/sdatkinson/NeuralAmpModelerCore), MIT license)
is a *git submodule*: a link to another repository. You have to download it too:

```bash
git clone --recurse-submodules https://github.com/bcova/tone3000_android.git
```

If you already cloned without it:

```bash
git submodule update --init --recursive
```

If you skip this, the build stops with a message telling you to run that command.

## Run it

1. Open the folder in Android Studio and let it sync. The first time, it downloads the
   Android NDK and CMake; accept any prompts to install them.
2. Plug in your phone (with USB debugging on) and press **Run ▶**.
3. In the app: **Start audio** → allow the microphone permission → **Load .nam** → pick a file.

## Getting tones

For now, download files from [tone3000.com](https://www.tone3000.com) to your phone, then load them in the app:

- **Amp model:** a `.nam` file.
- **Cabinet IR:** a `.wav` file. Skip this if the capture is "Amp + Cab" or "Full Rig", since the cabinet is already included.

**Phone tip:** neural models are heavy. Prefer **Lite** or **Feather** captures, or newer **A2** captures.
In desktop tests, a "standard" WaveNet model used about 25% of one fast CPU core. That may be too much
for older phones. If you hear crackles, try a lighter capture.

## How the code is organized

| Path | What it does |
|---|---|
| `app/src/main/cpp/engine/EngineCore.*` | The signal chain: gains, NAM model, IR, meters. Plain C++, so it can be tested on a computer. |
| `app/src/main/cpp/AudioEngine.*` | Connects the phone's audio hardware ([Oboe](https://github.com/google/oboe)) to the engine. Restarts automatically when you plug or unplug a device. |
| `app/src/main/cpp/native-lib.cpp` | Bridge between Kotlin and C++ (JNI). |
| `app/src/main/cpp/nam.cmake` | Builds NeuralAmpModelerCore. |
| `app/src/main/java/.../audio/NativeAudio.kt` | Kotlin view of the C++ engine. |
| `app/src/main/java/.../ui/` | The screen (Jetpack Compose) and its logic (ViewModel). |
| `native-tests/` | Desktop tests for the engine. |
| `third_party/NeuralAmpModelerCore` | The NAM engine (submodule). |

### Design notes

- **48 kHz:** NAM captures are trained at 48 kHz. The app asks Android for 48 kHz and lets Oboe
  resample if the hardware runs at another rate.
- **Real-time safety:** loading a file happens on a background thread. The new model is then swapped in
  with a single atomic pointer swap, so the audio never waits on a file.
- **No phone voice processing:** the input uses Android's `Unprocessed` mode, so auto-gain and noise
  suppression don't mangle the guitar.
- **Loudness normalization** (on by default) uses the loudness value stored in each `.nam` file, so
  different captures play at a similar volume. This matches the desktop NAM plugin.

## Engine tests (no phone needed)

On Linux or macOS with CMake and a C++20 compiler:

```bash
cmake -S native-tests -B build/native-tests -DCMAKE_BUILD_TYPE=Release
```

```bash
cmake --build build/native-tests -j
```

```bash
./build/native-tests/engine_test
```

These load NAM's example models and push a guitar-like signal through. They check the output is
clean, measure speed, and swap models while audio is running.

## Roadmap

- [x] 1. Android project skeleton
- [x] 2. Low-latency audio (Oboe) + NAM engine
- [x] 3. Load `.nam` and IR `.wav` files from the phone
- [ ] 4. TONE3000 sign-in + "Select" flow (OAuth with PKCE via Chrome Custom Tabs)
- [ ] 5. Screens that follow TONE3000's [design requirements](https://www.tone3000.com/api#design-requirements)

Ideas after that: noise gate, choosing the input/output device, remembering the last tone, tuner.

### TONE3000 API notes (for steps 4–5)

- This is a **free, non-commercial** app, so it uses TONE3000's **free tier**: no paid features or upsells.
  The free tier may only use the OAuth prompt flows (`select_tone`, `load_tone`) and the
  bounded lists (favorited, downloaded, created, trending, latest). No sign-off from TONE3000 is needed,
  but access can be revoked if the guidelines aren't followed.
- Only the **publishable key** (`client_id`) goes in the app. **Never** put the secret key (`t3k_cs_…`) in the app.
- Rate limit: 100 requests per minute.

## License

The NAM engine is MIT licensed (see `third_party/NeuralAmpModelerCore/LICENSE`).
Choose a license for this app's own code before publishing it.
