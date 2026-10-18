# MilkDrop Visualizer (personal build)

A personal, sideloaded Android app that renders MilkDrop/projectM-style visuals reacting to
whatever audio is playing on the phone (YouTube, browser audio, games), using presets and
textures loaded from external storage. Not intended for Play Store distribution.

## Building

Requires Android SDK with NDK `27.0.12077973` and CMake `3.22.1` installed, and a JDK 17+.
`local.properties` must point `sdk.dir` at your SDK.

```bash
git submodule update --init --recursive
./gradlew installDebug
```

The native build compiles `libprojectM` and `libprojectM_playlist` from source (vendored as a
git submodule at `native/projectm`, tracking upstream `master`) rather than using a
prebuilt binary, so presets/API stay in sync with that exact version.

Normal submodule initialization uses the commit recorded in this repository. To update to the
latest upstream `master` locally, run:

```bash
git submodule update --init --remote native/projectm
git -C native/projectm submodule update --init --recursive
```

For the optimized, installable release APK, see [Docs/RELEASE_BUILD.md](Docs/RELEASE_BUILD.md).

GitHub Actions builds the release APK on pushes, pull requests, and manual runs of **Build
Android APK**, fetching the latest projectM `master` on each run and logging the commit used.
After a successful run, download **MilkDrop-build** from the run's **Artifacts**
section and extract `MilkDrop-build.apk`. CI uses the project's debug signing configuration;
each fresh runner creates its own key, so installing over a local build or an earlier CI build
may require uninstalling the existing app first.

## Audio reaction

The native projectM build uses adaptive band analysis: bass **20–250 Hz**, mid
**250–4,000 Hz**, and treble **4,000–20,000 Hz**. Each band learns its recent loudness
and spectral-change statistics. Sudden attacks receive extra emphasis above the learned
variation, while steady audio stays near the preset convention of `1.0`. Silent input
produces zero immediate band values; the `bass_att`, `mid_att`, and `treb_att` values
decay smoothly. Timing follows elapsed seconds rather than frame counts.

This is applied to build-directory copies of projectM's `Loudness` implementation; the
submodule and waveform/spectrum data used by presets are unchanged.
Frequency boundaries and response times are CMake cache options in
`app/src/main/cpp/projectm/BeatAnalysis.cmake`; sensitivity is learned automatically rather
than requiring a fixed input-volume threshold. For example, pass
`-DMILKDROP_BASS_HIGH_HZ=200` as a native CMake argument to use a 200 Hz crossover.

The beat-analysis frequency reference is 44,100 Hz, matching Mic audio capture. Internal
uses that reference for beat detection too; its physical band boundaries may differ if
Android's Visualizer uses another sample rate. The separate shader FFT described below
uses the actual capture sample rate. Treble is limited to the analysis Nyquist frequency. The existing short FFT
window also limits separation of very low bass frequencies; these bands are approximate,
not sharp audio filters. This analysis cannot recover audio blocked or omitted by capture.

Preset shaders can use `get_fft()` / `get_fft_hz()`, `get_fft_peak()` / `get_fft_peak_hz()`,
and `get_wave()` / `get_wave_left()` / `get_wave_right()`. Shader FFT follows BeatDrop's
clean FFT, RMS adaptation, smoothing and peak hold. Local date/time and touch/mouse inputs
are also available to shaders and preset equations, including while using the overlay UI.
Shader feature checks support `#if`, `#ifdef` and `#ifndef`, including nested branches,
so optional BeatDrop features can select their supported or fallback code paths.
Shader translation also supports comma-separated texture sampler declarations and
whole matrix-row assignments, and renames HLSL identifiers that conflict with GLSL's
`smooth` keyword. Presets using external textures still need those files in
`MilkDropApp/textures`, including `Martin_Skin_Emergency_Power_Supply.jpg` for
martin - nivush - emergency power supply only.
Random textures accept both `tex2D(sampler_rand00, uv)` and `tex2D(rand00, uv)`.
A name such as `rand00_Stu` selects a texture whose filename starts with `Stu`
(ignoring case); place the supplied `Stu*.jpg` images in `MilkDropApp/textures`.
Random slots keep the same selected image across the preset's warp and composite shaders.
See [shader inputs and audio controls](Docs/SHADER_INPUTS.md) for names, coordinate
conventions, examples and capture limitations. The BeatDrop notice is included in the APK
at `assets/licenses/BeatDrop.txt`.

## Setting up presets

On startup, after storage access is granted, the app creates any missing folders on external
storage and reuses existing folders and their contents:

- `/storage/emulated/0/MilkDropApp/presets/` — drop preset pack folders here (e.g. "Cream of
  the Crop", "Milkdrop2", "Classic projectM"). Subfolders are scanned recursively.
- `/storage/emulated/0/MilkDropApp/textures/` — drop the Milkdrop base texture pack here, since
  many presets reference shared textures by name.

Grant the "All files access" permission when prompted so the app can read these folders.

Existing presets load automatically, including presets inside pack subfolders. On the first
scan, the first discovered preset can start while the rest of the library is scanned. Missing
`presets/` and `textures/` folders are created independently; an empty or missing texture folder
does not prevent preset loading. Existing textures are available to presets that reference them.
If there are no `.milk` presets, the app asks you to add them to `MilkDropApp/presets/`, even if
textures are already present.

Favourite and hidden presets are kept next to them, in `MilkDropApp/favourites.txt` and
`MilkDropApp/hidden.txt`: one preset per line, relative to `MilkDropApp/presets`. They survive
reinstalling the app, can be copied to another phone with the presets, and can be edited by hand
(lines starting with `#` are ignored).

## Controls

Tap the visuals to show the controls; tap again to hide them. They stay up until you tap again.
Swipe on the visuals to use shortcuts: right selects the previous preset,
left selects the next, up opens the preset list, and down toggles shuffle with brief status feedback. Swipes
work with the control bar shown or hidden, and are disabled over Settings and the preset list. Sliders and the preset list keep their
normal touch behavior. Short drags, diagonal drags and multi-touch gestures do not trigger
shortcuts; touch input still reaches the preset shader pointer variables.
Taps and swipes on the visualization are silent. Buttons, Settings and the preset list keep
their normal interaction sounds, subject to the device's sound settings.
Closing the preset list depends on how it was opened: swipe up or remote Up
returns to hidden controls, while the sidebar's List button returns to the sidebar. Selecting
a preset follows the same rule.
The preset's name shows at the top while they're up. With **Preset name during transitions**
enabled, it also appears for a couple of seconds on each preset change.

- **Prev / Next** — step through presets. Prev goes back through the presets you actually saw,
  even with shuffle on.
- **Shuffle** — random order instead of folder order.
- **Favourite** — mark the preset that's showing as a favourite.
- **List** — browse and search all presets; tap one to jump to it. Each row has a favourite and a
  hide button, and **Favourites** shows only favourites. Hidden presets stay in the list, dimmed,
  but Next, Shuffle and Auto-advance skip them. Back closes the list.
  Search ignores case and extra whitespace and matches all words in any order, including
  folder names. For example, `remix zylot` finds `Zylot - To Deep 13 (Wanders Remix)`.
  The list shows a result count or an explanation when no presets match the query or filter.
- **Settings** — see below.
- **⏮ ⏯ ⏭** — previous track, play/pause and next track in whatever audio app is playing.

The app follows the phone's rotation; in landscape the controls are a single row.

### Casting visuals

A rounded **Cast** button appears at the upper right when a compatible display is discovered.
Choose a TV or projector to send only the visuals. The phone shows the receiver's name and
keeps preset controls, swipe gestures, the list and Settings available. **Stop cast** restores
the visuals to the phone without restarting the preset.
The button hides while Settings or the preset list is open and shows a spinner while connecting.

- **MilkDrop Live (recommended for live visuals):** install this version of MilkDrop on an
  Android TV/projector and keep the TV app open. Connect both devices to the same local
  network, then choose **TV name (MilkDrop Live)** on the phone. No Google Play services are
  required for this path. Direct H.264 decoding, decoder feedback and a bounded frame queue
  avoid the video player's multi-second playback buffer. Slow connections drop stale frames
  and recover at a fresh key frame; actual latency still depends on Wi-Fi and the TV decoder.
  The TV pauses its local renderer and audio capture while receiving; press **Back** on the
  TV remote or **Stop cast** on the phone to return to local visuals. Receiving is advertised
  only while the TV app is in the foreground. Android 9 remains supported.
- **Google Cast / Chromecast / Google TV:** requires Google Play services and a receiver on
  the same reachable Wi-Fi or Ethernet network. The phone serves a session-only H.264 live
  stream; receiver buffering adds several seconds of delay. Guest networks that isolate
  devices can prevent playback.
- **Miracast / Wireless Display and external displays:** supported when Android exposes the
  display as a video route or connected presentation display. Use **Wireless display / Miracast…**
  in the Cast picker or MilkDrop Settings to open the phone's system picker (or connection
  settings if a dedicated picker is unavailable). Connect there and return to MilkDrop;
  it automatically uses a supported connected display for visuals. Samsung TVs seen in
  Smart View may appear only in that system picker until connected. This requires no
  MilkDrop installation on the TV. The phone's firmware and TV must support Miracast;
  the app cannot add that protocol to a phone that lacks it. If the system only offers
  whole-screen mirroring without a presentation display, it mirrors the phone's UI too.
  Stop cast ends MilkDrop's presentation; use system connection settings to disconnect
  mirroring if the phone keeps that connection active.

MilkDrop streams its visual framebuffer; its network video streams carry no audio or app UI.
Android manages audio routing and whole-screen mirroring for system wireless displays.
Existing audio modes keep feeding projectM. Casting runs only while MilkDrop is in the foreground and stops on
app pause, disconnection or failure; the phone then resumes local visuals. Network video
encoding is capped at 960×544 at 30 fps, with a 640×368 fallback, to limit work on older
devices. Android 9 remains the minimum supported version. Device discovery and playback
still need verification on physical receivers; a compatible receiver must advertise a service or route
before the Cast button can appear.

Preset names, favourites, and the resume preset follow the filename confirmed by a successful
projectM load. If projectM skips and removes a failed preset from its internal playlist, later
names and list selections still refer to the correct files. Tap a skipped preset in the list
to explicitly retry it.

### Shader aspect variables

`aspect` keeps projectM's physical screen aspect in both render modes. `aspect_sd` is a
new `float4` available in warp and composite shaders: `.xy` contains the effective aspect
multipliers and `.zw` their inverses. With Screen-Dependent Render Mode on, all four
components are `1.0`; with it off, `aspect_sd` equals `aspect`.

For example, use `float2 p = (uv - 0.5) * aspect_sd.xy;` for shader coordinates that
follow the toggle. Existing shaders using `aspect` retain their original correction.
Preset equation variables `aspectx` and `aspecty` also become `1.0` in screen-dependent mode.

### Settings

- **Auto-advance** — switch preset on a timer. Turning it off resets and holds the switching
  countdown at zero; turning it on starts a fresh countdown. Preset animation keeps running.
- **Change on big beats** — also switch on a sudden jump in loudness.
- **Preset name during transitions** — show the brief name popup on preset changes. On by
  default and saved across launches; turning it off keeps the name available with the sidebar open.
- **Time per preset** — 10, 15, 20, 25, 30, 45 or 60 seconds. Changing it restarts the countdown.
- **Additional random time** — Off, 5, 10, 15, 20, 25 or 30 seconds. Adds a fresh random amount
  from zero up to the selected limit per preset. For example, 15 s plus up to 5 s means 15–20 s.
  Off is the default. Changing the limit also restarts the countdown.
- **Transition time** — 0.5, 1, 1.5, 1.7 (default), 2 or 5 seconds for smooth transitions.
  Changes apply to the next blend, preserving any blend already in progress.
- **Transition** — Smooth blends using Transition time; Instant cuts straight to the next preset.
- **Frame rate** — 30, 45, 60 or Max. MilkDrop's motion advances per frame, so presets move
  faster at higher frame rates.
- **Quality** — render resolution (High / Medium / Low). Low also uses a coarser warp mesh.
- **Screen-Dependent Render Mode** — fit custom waves, shapes, built-in waves, and per-vertex
  motion to full-screen coordinates, following BeatDrop's screen-dependent convention. Off by
  default for standard projectM rendering. Applies immediately and is saved across launches.
- **Audio source**:
  - *Auto* — uses Internal when sound is available, falls back to the microphone after
    about 1.2 seconds of silence, and returns to Internal after about 100 ms of sustained
    sound. Brief pauses do not trigger a switch. The description shows the source in use.
    Internal monitoring continues while the microphone is active, and unavailable capture
    sources are retried. Blocked playback also triggers the microphone fallback.
  - *Microphone* — the microphone; works with any player and picks up the room.
  - *Internal* — what the phone plays through Android's `Visualizer` API, without a prompt.
    Apps that block capture come through silent.
- **Audio sensitivity** — 0–10× input gain for the visuals; default 1.0×.
- **FFT Shader Scaling** — 0–2; default 0.45.
- **FFT Shader Noise Floor** — 0–1; default 0.10.

The audio sliders apply immediately and are saved. Audio sensitivity affects the samples
used for visualization, while FFT scaling and noise floor adjust shader FFT output.
Auto requires microphone permission. It pauses capture while the app is in the background
and resumes automatically when you return. Your selected audio mode is saved across launches.

Settings and the preset list use the same translucent background as the sidebar.

The app requires Android 9 (API 28) or newer and a GPU with working OpenGL ES 3.0 support.
The unified APK includes 32-bit and 64-bit ARM and x86 builds. Devices that report low RAM
start with Low quality unless a quality setting has already been saved.

### TVs and projectors

MilkDrop appears in the Android TV launcher. While the UI is hidden, the remote provides
these shortcuts: OK shows controls, Left selects the previous preset, Right selects the next
preset, Down toggles shuffle, and Up opens the preset list. A held shortcut acts once per press.

With the UI visible, arrows move between controls and OK activates the focused control.
Hold OK on the control bar to hide it. Settings, preset rows, favourite
and hide buttons, and sliders are accessible with the D-pad. Left/right adjusts a focused
slider. Back closes the current panel, then hides controls; another Back exits. Menu is an
optional shortcut to Settings. Controls show a white outline when focused.

In the preset list, Left/Right moves across Search, Favourites and Close when the keyboard is
closed. Down enters the list, Up/Down browses presets, and OK selects a preset or activates
a button. Select Search with OK to type; while the keyboard is open, arrows keep their normal
editing behavior. In Settings, Up/Down moves between controls, Left/Right moves between
options or adjusts a focused slider, and OK activates buttons and toggles.

Android version alone does not determine graphics support. For example, an Android 9
projector with a Mali-450 GPU reporting OpenGL ES 2.0 cannot run the current projectM renderer.
Startup now checks the advertised graphics version and probes an ES 3 context before
starting projectM or requesting permissions. Unsupported devices show an explanation and
a remote-accessible Close app button. This does not add an OpenGL ES 2 renderer. A compatible
Android TV device connected over HDMI can provide the rendering for an older projector.

All settings, and the preset that was showing, are remembered across launches. The preset list
is cached, so the app resumes within a moment of opening; it rescans the presets folder in the
background and picks up added or removed presets automatically.

## Troubleshooting startup

After reinstalling, grant **All files access** again. Existing files in `MilkDropApp/` survive
uninstalling, but the storage permission does not. The app defers reading presets and ratings
until access is granted, then reloads them when you return from Settings. A denied file read
does not prevent startup, and ratings are not saved while storage access is denied.

If native library loading or projectM graphics initialization fails, the app shows **Unable to
start visuals on this device.** It stops rendering instead of using an invalid native instance.
projectM startup diagnostics are forwarded to Android Logcat under the `projectM` tag; library
loading and initialization failures are also logged under `MilkDropRenderer` and `MilkDropJNI`.

With the phone connected over adb, reproduce the issue and then save the log:

```bash
adb logcat -d > milkdrop-logcat.txt
```

Include the log, phone model, Android version, and selected audio source when reporting a crash.
Since CI fetches upstream `master` on every run, include the projectM commit shown in the CI log
as well. This helps distinguish a native startup failure from an audio or preset loading issue.

## Licensing note

`libprojectM` and `projectm-playlist` are LGPL-2.1; this app links them as dynamically-loaded
shared libraries (`.so`), which keeps LGPL compliance straightforward. `projectm-eval` (vendored
inside the projectM submodule) is MIT-licensed.
