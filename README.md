# tone3000_android

A free Android app that plays **NAM (Neural Amp Modeler)** amp captures and **cabinet IRs**
— the same kinds of tones you find on [TONE3000](https://www.tone3000.com).

> **Status:** steps 1–5 done: audio engine, loading files from your phone, and
> TONE3000 sign-in + browsing with a vertical, phone-first interface.

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

**From TONE3000 (easiest):** tap **Browse TONE3000** on the Amp or Cabinet block.
1. The first time, a short "partnered with TONE3000" screen appears. Tap **Continue** and sign in
   with your TONE3000 email (they send a 6-digit code).
2. Pick a tone on TONE3000's page, or close it to see **Your Tones**:
   Favorites · Downloaded · Created · Trending · Latest.
3. On the tone's page, choose a model (e.g. Clean / Crunch / Lead) and tap **Load**.
   The app downloads it and loads it into the right block automatically (NAM → amp, IR → cabinet).

**From your phone:** tap **Load from phone** on a block and pick a file:

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
| `app/src/main/java/.../t3k/` | TONE3000 API: sign-in (OAuth + PKCE), tone lists, model downloads. |
| `app/src/main/java/.../ui/` | Screens (Jetpack Compose) and their logic (ViewModels). |
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
- [x] 4. TONE3000 sign-in + "Select" flow (OAuth with PKCE via Chrome Custom Tabs)
- [x] 5. Screens that follow TONE3000's [design requirements](https://www.tone3000.com/api#design-requirements)

Ideas next: remember the last tone between launches, noise gate, choose input/output device, tuner.

### TONE3000 integration notes

- This is a **free, non-commercial** app, so it uses TONE3000's **free tier**: no paid features or upsells.
  The free tier may only use the OAuth prompt flows (`select_tone`, `load_tone`) and the
  bounded lists (favorited, downloaded, created, trending, latest). No sign-off from TONE3000 is needed,
  but access can be revoked if the guidelines aren't followed.
- Only the **publishable key** (`client_id`) goes in the app: it's `t3kClientId` in `gradle.properties`.
  **Never** put the secret key (`t3k_cs_…`) in the app or the repo.
- The redirect URI `com.bcova.namplayer://callback` must be registered in your TONE3000 API settings.
- Logos in `res/drawable-nodpi/` are TONE3000's official assets from their API page. The full logo is shown at
  entry points, and the compact T3K mark only after it (design rule 7).
- The app asks for newer **A2** models too (TONE3000 hides them by default). They're lighter on phones.
- Rate limit: 100 requests per minute.

## License

The NAM engine is MIT licensed (see `third_party/NeuralAmpModelerCore/LICENSE`).
Choose a license for this app's own code before publishing it.
