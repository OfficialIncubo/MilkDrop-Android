# Changelog

All notable changes to this app. Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
