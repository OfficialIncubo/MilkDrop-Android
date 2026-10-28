package com.milkdrop.visualizer.cast

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class FragmentedMp4Test {
    private fun annex(vararg bytes: Int) = byteArrayOf(0, 0, 0, 1) + bytes.map { it.toByte() }.toByteArray()
    private fun muxer() = FragmentedMp4(640, 368, annex(0x67, 0x42, 0xc0, 0x20), annex(0x68, 0))

    private fun position(bytes: ByteArray, type: String): Int {
        val pattern = type.toByteArray(Charsets.US_ASCII)
        return (0..bytes.size - pattern.size).first { offset -> pattern.indices.all { bytes[offset + it] == pattern[it] } }
    }

    @Test fun emitsAvcInitializationAndSelfContainedFragmentTiming() {
        val mux = muxer()
        assertEquals("video/mp4; codecs=\"avc1.42c020\"", mux.mime)
        for (type in listOf("ftyp", "moov", "avcC", "mvex", "trex")) assertTrue(position(mux.initialization, type) >= 0)
        val fragment = mux.fragment(7, annex(0x65, 1, 2, 3), 1_000_000, 16_667, true)
        val tfdt = position(fragment, "tfdt")
        assertEquals(90_000L, ByteBuffer.wrap(fragment, tfdt + 8, 8).long)
        val trun = position(fragment, "trun")
        assertEquals(position(fragment, "mdat") + 4, ByteBuffer.wrap(fragment, trun + 12, 4).int)
        assertEquals(1500, ByteBuffer.wrap(fragment, trun + 16, 4).int)
        assertEquals(0x02000000, ByteBuffer.wrap(fragment, trun + 24, 4).int)
    }

    @Test fun marksDeltaFramesAndRejectsInvalidInput() {
        val mux = muxer()
        val delta = mux.fragment(2, annex(0x41, 3), 16_667, 16_667, false)
        val trun = position(delta, "trun")
        assertEquals(0x01010000, ByteBuffer.wrap(delta, trun + 24, 4).int)
        assertTrue(runCatching { mux.fragment(1, byteArrayOf(1, 2), 0, 1, true) }.isFailure)
        assertTrue(runCatching { mux.fragment(1, annex(0x65, 1), -1, 1, true) }.isFailure)
    }
}
