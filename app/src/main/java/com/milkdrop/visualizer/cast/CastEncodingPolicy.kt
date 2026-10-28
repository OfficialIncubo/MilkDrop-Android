package com.milkdrop.visualizer.cast

/** Prefer fluid HD output, then fall back to smaller frames before lowering frame rate. */
internal object CastEncodingPolicy {
    data class Mode(val width: Int, val height: Int, val fps: Int)

    fun modes(requestedFps: Int): List<Mode> =
        listOf(requestedFps.coerceAtLeast(1), minOf(requestedFps.coerceAtLeast(1), 60),
            minOf(requestedFps.coerceAtLeast(1), 30)).distinct().flatMap { fps ->
            listOf(1280 to 720, 960 to 544, 640 to 368).map { (w, h) -> Mode(w, h, fps) }
        }

    // Visualizer noise/lines need more detail than a low-motion camera feed.
    // Clamp to the encoder's reported range at configuration time.
    fun bitRate(mode: Mode): Int =
        (mode.width.toLong() * mode.height * mode.fps * 14 / 100).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
}
