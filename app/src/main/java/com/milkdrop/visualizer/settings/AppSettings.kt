package com.milkdrop.visualizer.settings

import android.content.Context
import android.app.ActivityManager
import com.milkdrop.visualizer.audio.AudioInput

/** Persists the overlay's toggle states (everything except momentary actions like Media) and the last preset across launches. */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val defaultQualityIndex = if (context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true) 2 else 0

    var swipeGesturesEnabled: Boolean
        get() = prefs.getBoolean("swipe_gestures_enabled", true)
        set(value) = prefs.edit().putBoolean("swipe_gestures_enabled", value).apply()

    var shuffleEnabled: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE, value).apply()

    var autoAdvanceEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADVANCE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ADVANCE, value).apply()

    var presetDurationSeconds: Int
        get() {
            val legacy = PresetTiming.legacyDurations.getOrElse(prefs.getInt(KEY_PRESET_DURATION_INDEX, 1)) { 15 }
            return prefs.getInt(KEY_PRESET_DURATION, legacy).takeIf { it in PresetTiming.durations } ?: 15
        }
        set(value) = prefs.edit().putInt(KEY_PRESET_DURATION, value.takeIf { it in PresetTiming.durations } ?: 15)
            .remove(KEY_PRESET_DURATION_INDEX).apply()

    var additionalRandomSeconds: Int
        get() = prefs.getInt(KEY_RANDOM_TIME, 0).takeIf { it in PresetTiming.additionalRandom } ?: 0
        set(value) = prefs.edit().putInt(KEY_RANDOM_TIME, value.takeIf { it in PresetTiming.additionalRandom } ?: 0).apply()

    // Preserve the old Instant toggle until a duration is saved in the unified control.
    var transitionSeconds: Float
        get() = if (prefs.getBoolean(KEY_HARD_CUT, false)) 0f else prefs.getFloat(KEY_TRANSITION_TIME, 1.7f).takeIf { seconds -> PresetTiming.transitions.any { it == seconds } } ?: 1.7f
        set(value) = prefs.edit().putFloat(KEY_TRANSITION_TIME,
            value.takeIf { seconds -> PresetTiming.transitions.any { it == seconds } } ?: 1.7f).remove(KEY_HARD_CUT).apply()

    var beatCutsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEAT_CUTS, false)
        set(value) = prefs.edit().putBoolean(KEY_BEAT_CUTS, value).apply()

    var showPresetNameDuringTransitions: Boolean
        get() = prefs.getBoolean(KEY_PRESET_NAME_TRANSITIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_PRESET_NAME_TRANSITIONS, value).apply()

    var fpsIndex: Int
        get() {
            val legacy = FrameRates.legacy.getOrElse(prefs.getInt(KEY_FPS_INDEX, 0)) { 30 }
            return FrameRates.indexFor(prefs.getInt(KEY_FPS_VALUE, legacy))
        }
        set(value) = prefs.edit().putInt(KEY_FPS_VALUE, FrameRates.options.getOrElse(value) { 30 })
            .remove(KEY_FPS_INDEX).apply()

    var screenDependentRendering: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_DEPENDENT_RENDERING, false)
        set(value) = prefs.edit().putBoolean(KEY_SCREEN_DEPENDENT_RENDERING, value).apply()

    var qualityIndex: Int
        get() = prefs.getInt(KEY_QUALITY_INDEX, defaultQualityIndex)
        set(value) = prefs.edit().putInt(KEY_QUALITY_INDEX, value).apply()

    var audioSensitivity: Float
        get() = AudioTuning.bounded(prefs.getFloat(KEY_AUDIO_SENSITIVITY, AudioTuning.SENSITIVITY_DEFAULT),
            AudioTuning.SENSITIVITY_MAX, AudioTuning.SENSITIVITY_DEFAULT)
        set(value) = prefs.edit().putFloat(KEY_AUDIO_SENSITIVITY,
            AudioTuning.bounded(value, AudioTuning.SENSITIVITY_MAX, AudioTuning.SENSITIVITY_DEFAULT)).apply()

    var fftShaderScaling: Float
        get() = AudioTuning.bounded(prefs.getFloat(KEY_FFT_SCALING, AudioTuning.FFT_SCALING_DEFAULT),
            AudioTuning.FFT_SCALING_MAX, AudioTuning.FFT_SCALING_DEFAULT)
        set(value) = prefs.edit().putFloat(KEY_FFT_SCALING,
            AudioTuning.bounded(value, AudioTuning.FFT_SCALING_MAX, AudioTuning.FFT_SCALING_DEFAULT)).apply()

    var fftShaderNoiseFloor: Float
        get() = AudioTuning.bounded(prefs.getFloat(KEY_FFT_NOISE_FLOOR, AudioTuning.FFT_NOISE_FLOOR_DEFAULT),
            AudioTuning.FFT_NOISE_FLOOR_MAX, AudioTuning.FFT_NOISE_FLOOR_DEFAULT)
        set(value) = prefs.edit().putFloat(KEY_FFT_NOISE_FLOOR,
            AudioTuning.bounded(value, AudioTuning.FFT_NOISE_FLOOR_MAX, AudioTuning.FFT_NOISE_FLOOR_DEFAULT)).apply()

    /** Stereo playback capture is explicitly opt-in; legacy Internal never prompts. */
    var stereoInternalAudio: Boolean
        get() = prefs.getBoolean("stereo_internal_audio", false)
        set(value) = prefs.edit().putBoolean("stereo_internal_audio", value).apply()

    /** Reads previous audio choices and maps both playback modes to the retained Internal source. */
    var audioInput: AudioInput
        get() = prefs.getString(KEY_AUDIO_INPUT, null)
            ?.let { saved ->
                when (saved) {
                    "PHONE_AUDIO", "OUTPUT_MIX" -> AudioInput.INTERNAL
                    else -> AudioInput.entries.firstOrNull { it.name == saved }
                }
            }
            ?: if (prefs.getBoolean(KEY_AUDIO_SOURCE_INTERNAL, false)) AudioInput.INTERNAL else AudioInput.MIC
        set(value) = prefs.edit().putString(KEY_AUDIO_INPUT, value.name).remove(KEY_AUDIO_SOURCE_INTERNAL).apply()

    /** Full path of the preset showing when the app last ran, to resume on it at launch. */
    var lastPresetPath: String?
        get() = prefs.getString(KEY_LAST_PRESET_PATH, null)
        set(value) = prefs.edit().putString(KEY_LAST_PRESET_PATH, value).apply()

    private companion object {
        const val PREFS_NAME = "milkdrop_settings"
        const val KEY_SHUFFLE = "shuffle_enabled"
        const val KEY_AUTO_ADVANCE = "auto_advance_enabled"
        const val KEY_HARD_CUT = "hard_cut_enabled"
        const val KEY_PRESET_DURATION_INDEX = "preset_duration_index"
        const val KEY_PRESET_DURATION = "preset_duration_seconds"
        const val KEY_RANDOM_TIME = "additional_random_seconds"
        const val KEY_TRANSITION_TIME = "transition_seconds"
        const val KEY_BEAT_CUTS = "beat_cuts_enabled"
        const val KEY_PRESET_NAME_TRANSITIONS = "show_preset_name_during_transitions"
        const val KEY_FPS_INDEX = "fps_index"
        const val KEY_FPS_VALUE = "frames_per_second"
        const val KEY_QUALITY_INDEX = "quality_index"
        const val KEY_AUDIO_SENSITIVITY = "audio_sensitivity"
        const val KEY_FFT_SCALING = "fft_shader_scaling"
        const val KEY_FFT_NOISE_FLOOR = "fft_shader_noise_floor"
        const val KEY_SCREEN_DEPENDENT_RENDERING = "screen_dependent_rendering"
        const val KEY_AUDIO_SOURCE_INTERNAL = "audio_source_internal"
        const val KEY_AUDIO_INPUT = "audio_input"
        const val KEY_LAST_PRESET_PATH = "last_preset_path"
    }
}
