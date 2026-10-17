package com.milkdrop.visualizer.audio

/** Where the visuals' audio comes from, as offered in Settings. */
enum class AudioInput {
    /** The microphone: works with anything, picks up the room. */
    MIC,

    /** Captures the playing device's output mix through Android's Visualizer API. */
    INTERNAL,

    /** Prefer captured playback, using the microphone while Internal is silent or unavailable. */
    AUTOMATIC,
}
