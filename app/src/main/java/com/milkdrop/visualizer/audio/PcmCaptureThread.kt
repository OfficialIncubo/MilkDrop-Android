package com.milkdrop.visualizer.audio

import android.annotation.SuppressLint
import android.media.AudioRecord
import android.os.Process
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads PCM16 frames from an already-built, unstarted [AudioRecord] on a dedicated thread.
 *
 * Reads are small (~5 ms of audio): each read blocks until it's full, so the read size is how
 * stale the newest audio can be and how often the visuals get any. Sizing it from
 * AudioRecord.getMinBufferSize (bytes, mistaken for samples) made it ~80 ms.
 */
internal class PcmCaptureThread(
    private val audioRecord: AudioRecord,
    private val channels: Int,
    private val sink: PcmSink,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            audioRecord.startRecording()
        } catch (e: RuntimeException) {
            running.set(false)
            audioRecord.release()
            throw e
        }
        val readThread = Thread({
            val buffer = ShortArray(audioRecord.sampleRate / READS_PER_SECOND * channels)
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                while (running.get()) {
                    val read = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (read < 0) {
                        if (running.get()) Log.w(TAG, "PCM capture failed: $read")
                        break
                    }
                    if (read > 0 && running.get()) {
                        val frameCount = read / channels
                        sink(buffer, frameCount, channels, audioRecord.sampleRate, false)
                    }
                }
            } catch (e: RuntimeException) {
                if (running.get()) Log.w(TAG, "PCM capture stopped", e)
            } finally {
                running.set(false)
                audioRecord.release()
            }
        }, "milkdrop-pcm-capture")
        thread = readThread
        readThread.start()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Stop first to unblock an in-flight read, then wait for the worker to release the recorder.
        try {
            audioRecord.stop()
        } catch (_: IllegalStateException) {
            // The worker may already have exited and released the recorder.
        }
        thread?.join(500)
        thread = null
    }

    private companion object {
        const val READS_PER_SECOND = 200
        const val TAG = "PcmCaptureThread"
    }
}
