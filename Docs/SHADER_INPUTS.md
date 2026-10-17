# Shader audio, clock and pointer inputs

These additions follow [BeatDrop Music Visualizer](https://github.com/OfficialIncubo/BeatDrop-Music-Visualizer).
They are applied to build-directory copies of projectM master. The projectM submodule remains unchanged.

## Audio controls

Settings includes three sliders, applied while the visualization runs and saved across launches:

| Control | Range | Default | Effect |
| --- | --- | --- | --- |
| Audio sensitivity | 0–10×, in 0.1× steps | 1.0× | Scales captured samples for the visualization. Playback volume is unaffected. Zero supplies silence. |
| FFT Shader Scaling | 0–2, in 0.01 steps | 0.45 | Scales the RMS-normalized shader FFT before smoothing. |
| FFT Shader Noise Floor | 0–1, in 0.01 steps | 0.10 | Subtracts a floor from the normalized shader FFT, reducing quiet background detail. |

Shader FFT processing uses a clean 2,048-sample Hann window and 1,024 magnitude bins,
BeatDrop's RMS adaptation, low-bin compensation, attack/decay smoothing, and square-root
output. Peak history belongs to the projectM instance and continues across preset changes.
Preset `FFTAttack` and `FFTDecay` values are supported, with defaults of 0.5 and 0.7.

The current Mic and Internal sources are mono. Both stereo waveform functions therefore
return the same channel with these sources; the implementation preserves separate channels
when fed stereo PCM. Internal uses Android Visualizer snapshots, so its waveform detail is
limited by the device's capture size. Overlapping snapshots are analyzed as complete windows.

## Audio shader functions

| Function | Result |
| --- | --- |
| `get_fft(position)` | Smoothed spectrum magnitude at a normalized position. |
| `get_fft_hz(frequency)` | Smoothed magnitude at a frequency in Hz. |
| `get_fft_peak(position)` | Peak-held spectrum magnitude. |
| `get_fft_peak_hz(frequency)` | Peak-held magnitude at a frequency in Hz. |
| `get_wave(position)` | Average of the left and right waveform samples. |
| `get_wave_left(position)` | Left waveform sample. |
| `get_wave_right(position)` | Right waveform sample. |

Positions clamp to 0–1. FFT positions span DC to the capture sample rate's Nyquist frequency
(22,050 Hz for 44,100 Hz input). Hz lookups use the actual sample rate, including the rate
reported by Android's Visualizer. Waveforms use 576 samples normalized around zero; audio
sensitivity above 1× can increase their amplitude beyond −1 to +1.

FFT values are relative magnitudes and can exceed 1. Peak hold duration follows `FFTDecay`,
then the peaks fall smoothly. A change to scaling or floor affects new FFT values immediately;
existing levels and peak trails continue their normal decay.

```hlsl
shader_body {
    float bar = get_fft(frac(uv.x));
    float peak = get_fft_peak(frac(uv.x));
    float wave = get_wave(uv.x);
    ret = float3(bar, peak, abs(wave));
}
```

## Local system clock

All values use the device's local date and time. Weekdays run from Monday = 1 to Sunday = 7.

| Shader variable | Value |
| --- | --- |
| `sysTime` | `float4(hour, minute, second, totalSeconds)` |
| `sysDate` | `float4(year, month, day, weekday)` |
| `sysTimeHour`, `sysTimeMinute`, `sysTimeSecond`, `sysTimeTotalSeconds` | Components of `sysTime`. |
| `sysDateYear`, `sysDateMonth`, `sysDateDay`, `sysDateWeekday` | Components of `sysDate`. |

Preset equations also expose `year`, `month`, `day`, `weekday`, `hour`, `minute`, `second`,
`milliseconds` and `totalseconds`. Total seconds is time since local midnight, including
the fractional milliseconds. These inputs are available in initialization, per-frame,
per-vertex, custom-wave and custom-shape equation contexts.

## Touch and mouse

One primary touch controls the pointer. It continues to update over Settings, sliders,
buttons and the preset list. A connected mouse also provides position and button input.
Pointer events are observed by the Activity and then delivered normally to the UI.

| Shader variable | Value |
| --- | --- |
| `mouse`, `mouse_pos`, `mouse_x`, `mouse_y` | `mouse.xy` is normalized position, with (0, 0) at the top left. `mouse_pos` aliases `.xy`. |
| `mouse.z`, `mouse_clicked` | Last press X coordinate, positive while held and negative otherwise. |
| `mouse.w`, `mouse_released` | Last press Y coordinate, positive for one rendered frame after release and negative otherwise. |

The signed `.z` and `.w` values are coordinates, not Boolean flags; a coordinate of exactly
zero retains that value with either sign. Use equation variables passed through `q` variables
when an unambiguous down/release Boolean is needed. Presets may assign `mouse` components
locally in their shader, as in BeatDrop.

Equation inputs are `mousex`, `mousey`, `mousedown` and `mouseclick`. To match BeatDrop,
equation `mousey` increases upward: it is `1 - mouse.y` while the pointer is inside.
`mousedown` is 1 while held; `mouseclick` is 1 for one rendered frame after release.
Outside the visualization, both position coordinates are −1. A canceled gesture or app
pause clears the held state without creating a click.
