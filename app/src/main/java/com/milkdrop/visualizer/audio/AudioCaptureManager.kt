package com.milkdrop.visualizer.audio

import android.content.Context
import android.util.Log

/** Owns the active local audio source and swaps it when the user changes input. */
class AudioCaptureManager(
    private val context: Context,
    private val pcmSink: PcmSink,
) {
    /** Both retained audio sources run in process. */
    private var localSource: AudioSource? = null

    fun startMic() {
        startLocal(MicAudioSource(context, pcmSink))
    }

    /** Returns false if this phone won't give access to the output mix. */
    fun startInternal(): Boolean =
        try {
            startLocal(InternalAudioSource(pcmSink))
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Output mix capture unavailable", e)
            false
        }

    fun stopAll() {
        stopLocal()
    }

    private fun startLocal(source: AudioSource) {
        stopAll()
        source.start()
        localSource = source
    }

    private fun stopLocal() {
        localSource?.stop()
        localSource = null
    }

    private companion object {
        const val TAG = "AudioCaptureManager"
    }
}
