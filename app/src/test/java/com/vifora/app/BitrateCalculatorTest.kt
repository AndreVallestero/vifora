package com.vifora.app

import com.vifora.app.engine.BitrateCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateCalculatorTest {
    @Test
    fun shortVideoIs90_10() {
        assertEquals(0.90, BitrateCalculator.videoRatio(30.0, true), 1e-9)
        assertEquals(0.10, BitrateCalculator.audioRatio(30.0, true), 1e-9)
    }

    @Test
    fun longVideoIs50_50() {
        assertEquals(0.50, BitrateCalculator.videoRatio(1800.0, true), 1e-9)
        assertEquals(0.50, BitrateCalculator.videoRatio(3600.0, true), 1e-9)
    }

    @Test
    fun midpointInterpolates() {
        // (60+1800)/2 = 930s -> 0.90 - 0.40*0.5 = 0.70
        assertEquals(0.70, BitrateCalculator.videoRatio(930.0, true), 1e-9)
    }

    @Test
    fun noAudioIs100() {
        assertEquals(1.0, BitrateCalculator.videoRatio(30.0, false), 1e-9)
        assertEquals(0.0, BitrateCalculator.audioRatio(30.0, false), 1e-9)
    }

    @Test
    fun retryFactors() {
        assertEquals(900L, BitrateCalculator.reduceVideoBitrate(1000L))
        assertEquals(950L, BitrateCalculator.reduceAudioBitrate(1000L))
        assertEquals(4, BitrateCalculator.MAX_RETRIES)
        assertEquals(5, BitrateCalculator.MAX_ATTEMPTS)
    }

    @Test
    fun guards() {
        assertTrue(BitrateCalculator.shouldBlockUpfront(10_000_000L, 10_000_000L))
        assertTrue(BitrateCalculator.shouldBlockUpfront(5_000_000L, 10_000_000L))
        assertTrue(BitrateCalculator.shouldDiscardResult(11_000_000L, 10_000_000L, 12_000_000L))
    }
}
