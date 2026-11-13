# Ampfield

A free Android app that plays **NAM (Neural Amp Modeler)** amp captures and **cabinet IRs**
— the same kinds of tones you find on [TONE3000](https://www.tone3000.com).

> **Status:** steps 1–5 done: audio engine, loading files from your phone, and
> TONE3000 sign-in + browsing with a vertical, phone-first interface.

```
guitar ─► USB interface ─► input gain ─► block ─► block ─► … ─► output volume ─► headphones
                                          (pedal)   (amp)   (cab, space, outboard…)
```

The chain holds up to 8 blocks. Each block is a NAM capture (`.nam`) or an impulse response (`.wav`).
Below the chain are a **noise gate** (threshold) and **EQ** (bass / middle / treble, 0–10 with 5 = flat),
using the same curves as the NAM plugin. The gate listens to your guitar before the chain and turns the
sound down after it. The EQ comes after the whole chain.

**Presets** (button at the top) save the whole chain plus gate, EQ and volume. Your current setup is also
**remembered automatically** and restored the next time you open the app. Tones you load are copied into
the app's private storage, so presets work offline. Copies that nothing uses are deleted when the app starts.

**Tuner** (button at the top): a chromatic tuner, A4 = 440 Hz. The sound is muted while it's open.

**Record** saves what you hear as a stereo WAV in **Music/Ampfield**, with a Share button.
**Live mode** has big buttons: previous/next preset, a preset grid, block on/off tiles, tuner, record.

**Practice** (button on the player):
- **Metronome & drums:** BPM slider, ± buttons, tap tempo, click with 1–12 beats per bar, or drum grooves
  (rock, funk, shuffle, half-time, ballad, metal). All drum sounds are synthesized by the app.
- **Looper:** one big button: Record → Play → Overdub → Play…, plus Stop, Undo (last layer) and Clear. Up to 60 s.
  With the metronome on, loops snap to whole bars.
- **Backing tracks:** add MP3/M4A/WAV/FLAC/OGG songs from your phone (converted once and kept in the app).
  Play/pause, seek, ±10 s, volume, and an A–B loop to repeat a section.
- Looper, backing track and drums are included in recordings; the plain metronome click isn't.
- **Jamming with YouTube:** turn on Settings → *Play along with other apps*, then use YouTube in split screen
  or pop-up view. Ampfield doesn't pull audio out of YouTube; that's against YouTube's terms.

**Background playing:** while audio is on, a "now playing" notification keeps Ampfield running when you
switch apps or lock the screen.

**Each block has a Level slider** (−24 to +12 dB), e.g. to set how hard a boost pedal hits the amp.
Stereo IRs (cabs, rooms) play in stereo.

**Settings** (gear icon):
- **Audio devices:** pick the input and output, or leave it on Automatic. Bluetooth audio is flagged as high-delay.
- **Latency:** Lowest / Normal / Safe buffer, with the measured delay in ms.
- **Input calibration:** like the NAM plugin. Set your interface's max input level (dBu), and calibrated captures react like the real gear.
- **EQ position:** before the cab (like the NAM plugin) or at the end.
- **Footswitch & MIDI:** USB/Bluetooth MIDI pedals and page-turner pedals. Program Change N picks preset N.
  Tap **Learn** next to an action (next/previous preset, tuner, record, block 1–6, gate), then press a pedal.

Tap **Add block** to add a Pedal, Amp, Cab, Outboard, Space or Any block. Use a block's **⋮** menu
to move it up or down, empty it, or remove it. Watch the **CPU** meter: near 100%, the audio crackles.

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
1. The first time, a short "powered by TONE3000" welcome screen appears. Tap **Continue** and sign in
   with your TONE3000 email (they send a 6-digit code).
2. Pick a tone on TONE3000's page, or close it to see **Your Tones**:
   Favorites · Downloaded · Created · Trending · Latest.
3. On the tone's page, choose a model (e.g. Clean / Crunch / Lead) and tap **Load**.
   The app downloads it and loads it into the right block automatically (NAM → amp, IR → cabinet).

**Search the whole catalog:** in Your Tones, tap the search box. Type words (e.g. "plexi") and narrow
results with filters: sort, format (NAM/IR), gear type, model size, tags, makes (brands and models),
A2 only, calibrated, and verified creators.

**From your phone:** tap **Load from phone** on a block and pick a file:

- **Amp model:** a `.nam` file.
- **Cabinet IR:** a `.wav` file. Skip this if the capture is "Amp + Cab" or "Full Rig", since the cabinet is already included.

**Phone tip:** neural models are heavy. Prefer **Lite** or **Feather** captures, or newer **A2** captures.
In desktop tests, a "standard" WaveNet model used about 25% of one fast CPU core. That may be too much
for older phones. If you hear crackles, try a lighter capture.

## Share the app (signed APK)

Android apps must be **signed** with your own key. Updates must use the **same key**, or phones refuse them.

1. In Android Studio: **Build → Generate Signed App Bundle / APK…** → choose **APK** → **Next**.
2. Under *Key store path*, click **Create new…**
   - Save it **outside** this project folder, e.g. `Documents/ampfield-release.jks`.
   - Pick strong passwords and an alias (e.g. `ampfield`). Fill in at least your name.
   - **Back up the .jks file and passwords** (e.g. a password manager plus a USB stick). If they're lost,
     you can never ship an update to people who installed the app.
3. Choose the **release** build variant → **Create**.
4. The APK appears in `app/release/app-release.apk`. Send that file to friends (Drive, email…). On their phone:
   tap it → allow *Install unknown apps* for that app once → **Install**.
5. For each new version you share, raise `versionCode` (and `versionName`) in `app/build.gradle.kts`.

Never commit the key: `*.jks`, `*.keystore` and `keystore.properties` are already in `.gitignore`.

## How the code is organized

| Path | What it does |
|---|---|
| `app/src/main/cpp/engine/Recorder.*` | Lock-free recording to WAV (audio thread to writer thread). |
| `app/src/main/cpp/engine/Rhythm.h` | Metronome and synthesized drum grooves. |
| `app/src/main/cpp/engine/Looper.h` | Looper with overdub and glitch-free undo. |
| `app/src/main/cpp/engine/TrackPlayer.*` | Streams backing tracks from storage (seek, A-B loop). |
| `app/src/main/cpp/engine/Tuner.h` | YIN pitch detection for the tuner. |
| `app/src/main/cpp/engine/Effects.h` | Noise gate and bass/mid/treble EQ (NAM-plugin curves). |
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

### ✅ Full catalog search: approved by TONE3000

In-app search uses TONE3000 endpoints (`/tones/search`, `/tags`, `/makes`) that their
[API terms](https://www.tone3000.com/api/terms) normally reserve for approved (commercial) apps.
**TONE3000 approved this app's use of those endpoints by email in November 2026.** Keep that email as the record.

Conditions from that approval:
- Branding must say **"Powered by TONE3000"**. Do **not** say "partnered with TONE3000" unless a formal
  partnership agreement is made.
- TONE3000 invited a test build or screenshots for them to look at. Send them before a public release.

The switch `t3kFullCatalogSearch` in `gradle.properties` stays `true`. Setting it to `false` removes search
from the app (only needed if the approval is ever withdrawn).

### ⚠️ In-app favorites: personal use only (for now)

The ♥ button on tones uses TONE3000's favorite/unfavorite endpoints (`PUT`/`DELETE /tones/{id}/favorite`).
Their email approval covered search, tags and makes, **not** these. Personal/development use is allowed by their
terms. **Before sharing the app**, either get TONE3000's OK or set `t3kInAppFavorites=false` in `gradle.properties`.

### TONE3000 integration notes

- Tones are **Powered by [TONE3000](https://www.tone3000.com)**. The API terms require that label and this link.

- This is a **free, non-commercial** app: no paid features or upsells. Keep it that way. Charging money
  would need a commercial agreement with TONE3000.
- It uses the OAuth prompt flows (`select_tone`, `load_tone`), the bounded lists (favorited, downloaded,
  created, trending, latest), and full-catalog search. The search part is covered by TONE3000's email approval
  (see above). Access can be revoked if the guidelines aren't followed.
- Only the **publishable key** (`client_id`) goes in the app: it's `t3kClientId` in `gradle.properties`.
  **Never** put the secret key (`t3k_cs_…`) in the app or the repo.
- The redirect URI `com.bcova.namplayer://callback` must be registered in your TONE3000 API settings.
- Logos in `res/drawable-nodpi/` are TONE3000's official assets from their API page. The full logo is shown at
  entry points, and the compact T3K mark only after it (design rule 7).
- The app asks for newer **A2** models too (TONE3000 hides them by default). They're lighter on phones.
- Rate limit: 100 requests per minute.

## License

Ampfield is MIT licensed. See [LICENSE](LICENSE).

It bundles: [NeuralAmpModelerCore](https://github.com/sdatkinson/NeuralAmpModelerCore) (MIT),
[Oboe](https://github.com/google/oboe) (Apache 2.0), Eigen (MPL 2.0), nlohmann/json (MIT), and AndroidX /
Jetpack Compose / Kotlin / Coil (Apache 2.0). The credits are also shown in the app under **About**.
TONE3000 logos (`res/drawable-nodpi/t3k_*`) are TONE3000's trademarks, used per their API guidelines, and are
not covered by this project's license.
