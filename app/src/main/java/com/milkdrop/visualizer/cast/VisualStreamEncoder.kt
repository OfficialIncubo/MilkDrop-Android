package com.milkdrop.visualizer.cast

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Surface-input AVC encoding; never reads the screen or captures the app's controls/audio. */
internal class VisualStreamEncoder(
    private val server: LiveStreamServer,
    private val onReady: () -> Unit,
    private val onError: (Exception) -> Unit,
) : Closeable {
    val width: Int
    val height: Int
    private val codec: MediaCodec
    val surface: Surface
    private val running = AtomicBoolean(true)
    private val drainThread: Thread

    init {
        var selected: MediaCodec? = null
        var selectedSurface: Surface? = null
        var size = 960 to 544
        var failure: Exception? = null
        for ((w, h) in listOf(960 to 544, 640 to 368)) {
            val candidate = try { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
                catch (e: Exception) { failure = e; continue }
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (w > 640) 2_000_000 else 1_200_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                }
                candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                selectedSurface = candidate.createInputSurface()
                candidate.start()
                selected = candidate; size = w to h
                break
            } catch (e: Exception) {
                failure = e
                selectedSurface?.release(); selectedSurface = null
                runCatching { candidate.release() }
            }
        }
        codec = selected ?: throw IllegalStateException("No compatible AVC surface encoder", failure)
        surface = checkNotNull(selectedSurface)
        width = size.first; height = size.second
        drainThread = Thread(::drain, "MilkDrop-cast-encoder").apply { isDaemon = true; start() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        val muxer = TransportStream()
        var headers = ByteArray(0)
        var segmentStart = -1L
        var firstTimestamp = -1L
        var previousTime = -1L
        var discontinuity = false
        var nextDiscontinuity = false
        var ready = false
        fun publish(duration: Double) {
            val available = server.publish(muxer.bytes(), duration.coerceAtLeast(0.001), discontinuity)
            if (available && !ready) { ready = true; onReady() }
        }
        fun requestKeyFrame() {
            codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
        try {
            while (running.get()) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    headers = listOf("csd-0", "csd-1").mapNotNull { codec.outputFormat.getByteBuffer(it) }
                        .fold(ByteArray(0)) { bytes, buffer -> bytes + annexB(buffer.duplicate()) }
                } else if (index >= 0) {
                    try {
                        val buffer = codec.getOutputBuffer(index) ?: continue
                        if (info.size <= 0) continue
                        check(info.size <= MAX_FRAME_BYTES) { "Invalid encoder output size" }
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val bytes = annexB(buffer.slice())
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            headers = bytes; continue
                        }
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (firstTimestamp < 0) firstTimestamp = info.presentationTimeUs
                        val timeUs = (info.presentationTimeUs - firstTimestamp).coerceAtLeast(0)
                        // A slow preset load can leave a gap. End the old segment before the
                        // gap and restart at a key frame with an explicit HLS discontinuity.
                        if (segmentStart >= 0 && previousTime >= 0 && timeUs - previousTime > 500_000) {
                            publish((previousTime - segmentStart + 33_333) / 1_000_000.0)
                            segmentStart = -1; nextDiscontinuity = true
                        }
                        if (segmentStart < 0 && !key) { requestKeyFrame(); continue }
                        if (segmentStart >= 0 && timeUs - segmentStart > 1_500_000 && !key) {
                            publish((previousTime - segmentStart + 33_333).coerceAtMost(2_000_000) / 1_000_000.0)
                            segmentStart = -1; nextDiscontinuity = true
                            requestKeyFrame(); continue
                        }
                        if (key) {
                            if (segmentStart >= 0) {
                                publish((timeUs - segmentStart) / 1_000_000.0)
                            }
                            muxer.begin(); segmentStart = timeUs
                            discontinuity = nextDiscontinuity; nextDiscontinuity = false
                        }
                        muxer.video(byteArrayOf(0, 0, 0, 1, 9, 0xf0.toByte()) +
                            (if (key) headers else ByteArray(0)) + bytes, timeUs)
                        check(muxer.byteCount <= 8 * 1024 * 1024) { "Encoder did not produce key frames" }
                        previousTime = timeUs
                    } finally { codec.releaseOutputBuffer(index, false) }
                }
            }
        } catch (e: Exception) {
            if (running.get()) onError(e)
        }
    }

    /** Called after the GL output is detached, so no producer can write into a released surface. */
    override fun close() {
        if (!running.getAndSet(false)) return
        if (Thread.currentThread() !== drainThread) drainThread.join(1000)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        surface.release()
    }

    private fun annexB(buffer: ByteBuffer): ByteArray {
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        if (bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            (bytes[2] == 1.toByte() || (bytes.size >= 4 && bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))) return bytes
        val output = java.io.ByteArrayOutputStream()
        val data = ByteBuffer.wrap(bytes)
        while (data.remaining() >= 4) {
            val length = data.int
            require(length > 0 && length <= data.remaining()) { "Invalid AVC packet" }
            output.write(byteArrayOf(0, 0, 0, 1))
            output.write(ByteArray(length).also { data.get(it) })
        }
        require(!data.hasRemaining()) { "Truncated AVC packet" }
        return output.toByteArray()
    }

    private companion object { const val MAX_FRAME_BYTES = 4 * 1024 * 1024 }
}
