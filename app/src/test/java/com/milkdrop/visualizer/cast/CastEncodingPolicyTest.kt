package com.milkdrop.visualizer.cast

import org.junit.Assert.*
import org.junit.Test

class CastEncodingPolicyTest {
    @Test fun prefersFullHdOnlyAfterReceiverConfirmation() {
        val modes = CastEncodingPolicy.modes(60, fullHdSupported = true)
        assertEquals(CastEncodingPolicy.Mode(1920, 1080, 60), modes.first())
        assertEquals(CastEncodingPolicy.Mode(1280, 720, 60), modes[1])
        assertTrue(CastEncodingPolicy.modes(60).none { it.width == 1920 })
        assertTrue(modes.indexOf(CastEncodingPolicy.Mode(640, 368, 60)) <
            modes.indexOf(CastEncodingPolicy.Mode(1920, 1080, 30)))
    }

    @Test fun prefersSmallerHighFrameRateBeforeSlowerHd() {
        val modes = CastEncodingPolicy.modes(60)
        assertEquals(CastEncodingPolicy.Mode(1280, 720, 60), modes.first())
        assertTrue(modes.indexOf(CastEncodingPolicy.Mode(640, 368, 60)) <
            modes.indexOf(CastEncodingPolicy.Mode(1280, 720, 30)))
        assertEquals(modes.distinct(), modes)
    }

    @Test fun preservesRatesAndScalesBitrateWithFrameRate() {
        assertTrue(CastEncodingPolicy.modes(24).all { it.fps == 24 })
        assertTrue(CastEncodingPolicy.modes(120).any { it.fps == 120 })
        assertTrue(CastEncodingPolicy.modes(0).all { it.fps > 0 })
        val hd = CastEncodingPolicy.Mode(1280, 720, 60)
        assertEquals(CastEncodingPolicy.bitRate(hd), 2 * CastEncodingPolicy.bitRate(hd.copy(fps = 30)))
    }
}
