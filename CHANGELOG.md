# Changelog

All notable changes to this app. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- FPS choices of 24, 30, 45, 50, 60, 120 and Max (Unlimited), preserving saved frame rates.
- Transition times of 2.5 and 3 seconds, plus Random: sample a duration from 0.5–5 seconds
  per preset transition and use it consistently for the blend and its timer.
- Wireless display / Miracast shortcut in the Cast picker and Settings. Detect connected
  presentation displays, including those paired in the phone's system picker, and restore
  visual-only output on returning to MilkDrop without a TV app. Keep manual Stop respected
  and unregister display listeners on pause. Firmware Miracast support is still required.
- Direct MilkDrop Live receiving in the foreground TV app, discovered on the local network.
  Send H.264 frames with decoder feedback, bounded queues and key-frame recovery instead
  of a buffered video-player timeline. Restore local TV rendering/audio on disconnect.
- Foreground visual casting to Google Cast video receivers and Android-exposed external
  display/Miracast routes. Show a rounded Cast/Stop cast button as receivers appear, keep
  controls on the phone, and return visuals locally when casting stops. Use bounded stream
  buffers, encoder fallback and lifecycle cleanup while retaining Android 9 support.
- Saved Preset name during transitions toggle after Change on big beats, enabled by default.
  Disable the preset-change name popup while keeping the name available in the sidebar.
- Match search words in any order, ignoring case and extra whitespace, and show result
  counts and clear messages for empty search results, empty libraries or no favourites.
- Time per preset choices of 10, 15, 20, 25, 30, 45 and 60 seconds; Additional random time
  options of Off or up to 5, 10, 15, 20, 25 and 30 seconds; and Transition time choices of
  0.5, 1, 1.5, 1.7 (default), 2 and 5 seconds. Preserve existing saved preset durations.

### Fixed

- Show Cast/Stop cast only alongside the sidebar controls, retaining the Settings/preset-list
  visibility rules. Replace the connecting spinner with an animated cast signal icon and
  stop its animation whenever the button is hidden or the app is paused.
- Resolve Google Cast names from the active session and try the connected Wi-Fi Direct
  peer when a wireless display exposes only a generic label. Optional Nearby devices /
  Location access is requested only for that fallback, with bounded queries and cleanup.
- Remove the fixed 30 FPS casting cap. Follow the selected frame rate within negotiated
  encoder limits, support up to 60 FPS for Google Cast video, and keep older encoder fallbacks.
- Shorten HLS segments to a one-second target and startup/live windows to about three seconds.
  Request fresh key frames and recover from encoder/preset stalls without oversized segments.
  The default Google receiver's startup screen and playback buffer remain receiver-controlled.
- Exclude the phone's localized local route label (including Telefon) when resolving a
  wireless receiver name. Use the connected TV/projector name when Android exposes it.
- Limit the advertised Google Cast live playlist to about three seconds while retaining
  older segments for pending requests. Seek back to live playback when reported lag
  exceeds eight seconds, with a cooldown to prevent repeated seeks on slow receivers.
- Prefer connected route names over generic Cast Receiver display labels and autosize long
  receiver names. Explain Google Cast's buffered delay in the casting status.
- Request wireless-display disconnection through Android routing APIs on Stop cast. Verify
  that the display disappeared and open system settings if firmware requires manual disconnect,
  retaining the connection status until it actually ends.
- Use an RGB casting texture compatible with alpha-less render surfaces, fixing invalid
  framebuffer copies that could leave the receiver gray. Synchronize shared casting
  textures in both directions, match shader varying precision,
  preserve AVC parameter sets reported separately, and avoid skipping frames at 30 fps.
- Hide Cast/Stop cast while Settings or the preset list is open; animate the cast icon while connecting.
- Accept bare `randXX` texture calls alongside `sampler_randXX` names. Bind short
  random-texture aliases to the selected texture unit, retain each shader's sampler
  name and filter/wrap settings when reusing a random slot, and deduplicate shared
  uniform declarations. Respect filename prefixes such as `rand00_Stu` with sampler qualifiers.
- Handle comma-separated texture sampler declarations, the HLSL variable name `smooth`,
  and whole matrix-row assignments when translating preset shaders to OpenGL ES.
  Fix shader compilation failures in martin - klingonian server bank and
  martin - nivush - emergency power supply only, including binding the latter's skin texture.
- Recognize shader `#ifdef` and `#ifndef` directives, including nested branches and comments.
  Fix preprocessing failures in presets such as IkeC - Equalizer that use optional visual modes.
- Restart the switching countdown when duration or random time changes. Auto-advance off
  resets and holds the countdown at zero; enabling it starts again. Preserve preset animation
  and blends in progress, and discard stale search results after editing or closing the list.

### Changed

- Allow visualization swipes with the control bar shown or hidden, preserving overlay interactions.
- Use the sidebar's translucent background for Settings and the preset list.
- Restore the sidebar after closing or selecting from the preset list when opened with its
  List button. Swipe up and remote Up return to hidden controls.
- Silence taps and swipe shortcuts on the visualization, while preserving normal UI sounds.
- Make preset-list header focus move between Search, Favourites and Close with a remote;
  retain search cursor movement while the on-screen keyboard is open.
- Swipe right for the previous preset and left for the next while the UI is hidden.
- Order audio source choices as Auto, Microphone, then Internal.
- Keep Shuffle white when on and dimmed white when off, with a transparent background
  and a white outline for remote focus.

## [2.0.0] - 2026-10-17

### Fixed

- Check graphics support before creating the renderer. OpenGL ES 2-only projectors show a
  compatibility message instead of attempting to create an unsupported ES 3 surface.
- Preset shader feature checks such as `#if HAS_FFT_PEAK` and
  `#if defined(HAS_FFT_PEAK)` now evaluate correctly, including nested inactive branches.

### Added

- Touchscreen shortcuts on the visualization while the UI is hidden: swipe left/right for previous/next preset,
  up for the preset list, and down to toggle shuffle. Preserve tap controls, overlay touch
  handling and shader pointer input; ignore short drags and multi-touch gestures.
- Remote shortcuts with the UI hidden: OK shows controls, Left/Right change preset,
  Down toggles shuffle, and Up opens the preset list. Hold OK on the control bar to hide it;
  normal D-pad and OK navigation remains available while the UI is visible.
- Android TV launcher entry and banner, visible remote focus, D-pad access to preset rows
  and settings, and focus restoration when closing panels. Back hides controls before exit.
- Auto audio source: prefer Internal playback, use the microphone during silence or when
  Internal capture is unavailable, and switch back when sound resumes. Includes a live
  source description, capture retries, and pause/resume handling when leaving the app.
- Shader functions `get_fft_peak()`, `get_fft_peak_hz()`, `get_wave()`, `get_wave_left()` and
  `get_wave_right()`, alongside a BeatDrop-style clean FFT for `get_fft()` and `get_fft_hz()`.
- Local system date/time and pointer inputs for shaders and preset equations. Touch input
  remains available while interacting with controls, settings and the preset list.
- Live, saved sliders for audio sensitivity (0–10×), FFT Shader Scaling (0–2, default 0.45),
  and FFT Shader Noise Floor (0–1, default 0.10).

### Changed

- Default to Low render quality on devices reporting low RAM, preserving saved choices.
- Shader Hz lookups follow the capture sample rate; overlapping Internal audio snapshots
  are analyzed without concatenating them into a false continuous waveform.
- Launcher icon now uses the supplied MilkDrop spiral artwork.
- Denied recording permission and unavailable microphone input display an explanation
  instead of failing during audio startup.

## [1.1.0] - 2026-10-03

### Added

- New controls: Prev, Next, Shuffle, Favourite, List and Settings as icons with labels, plus
  media keys (previous track, play/pause, next track) for whatever audio app is playing.
- Settings sheet with every option visible at once: Auto-advance, Change on big beats, Time per
  preset (10/15/30/60 s), Transition, Frame rate, Quality and Audio source.
- Landscape: the app follows the phone's rotation; the controls become a single row.
- Preset name at the top while the controls are up, and for a couple of seconds on each change.
- Favourite and hidden presets. Favourite from the control bar or the List; the List can show
  favourites only. Hidden presets are skipped by Next, Shuffle and Auto-advance and stay in the
  List, dimmed. Both are kept in `MilkDropApp/favourites.txt` and `MilkDropApp/hidden.txt`.
- Internal audio source: phone audio without Android's screen-capture consent dialog, at
  lower detail. Audio source is now Mic or Internal.
- MilkDrop 3 presets:
  - 16 custom shapes and waves per preset (was 4).
  - `get_fft()` / `get_fft_hz()` in preset shaders, used by MilkDrop 3's equalizers.
  - Texture samplers declared by the texture's plain name.

### Changed

- The screen stays on while the visualizer is showing.
- Audio reaches the visuals in ~10 ms steps instead of ~80 ms, so they react sooner.
- The microphone uses the unprocessed input (no voice auto-gain or noise suppression) on phones
  that support it.
- Minimum Android version lowered to Android 9 (API 28); 32-bit ARM devices are supported.
- Presets get the real frame rate. It was always reported as 35, so presets that scale motion
  by it ran too fast at higher frame rates.

### Fixed

- Many MilkDrop 3 presets whose shaders failed to load now work: failures among the 390 MilkDrop
  3 presets went from 60 to 9. The fixes are in the shader translation (`tanh`/`sinh`/`cosh`,
  macro handling, declaration scoping, `sincos`, integer multiply, variables named like GLSL
  built-ins) and apply to all presets.
- Shader failures are logged with their cause (logcat tag `MilkDropShader`).

## [1.0.0]

First build: projectM 4.1.7 visualizer with internal-audio and microphone capture, preset list
with search, and tuned frame pacing for the Galaxy F15.

[1.1.0]: https://github.com/samir-dahal/MilkDrop/compare/5c195a4...v1.1.0
