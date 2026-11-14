package com.vifora.app

import com.vifora.app.engine.AdvancedOptions
import com.vifora.app.engine.FFmpegCommandBuilder
import com.vifora.app.engine.Preset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FFmpegCommandBuilderTest {
    private val base = AdvancedOptions(frameRateFps = 30)

    @Test
    fun fpsAppendedWhenSourceFaster() {
        assertEquals("fps=30", FFmpegCommandBuilder.videoFilter(base, 60.0))
    }

    @Test
    fun fpsOmittedWhenSourceMatchesCap() {
        assertNull(FFmpegCommandBuilder.videoFilter(base, 30.0))
    }

    @Test
    fun fpsOmittedWhenSourceSlower() {
        // Cap-only: never invent frames.
        assertNull(FFmpegCommandBuilder.videoFilter(base, 24.0))
        assertNull(FFmpegCommandBuilder.videoFilter(base, 29.97))
    }

    @Test
    fun fpsOmittedWhenSourceUnknown() {
        assertNull(FFmpegCommandBuilder.videoFilter(base, null))
    }

    @Test
    fun fpsIsLastInChain() {
        val opts = base.copy(denoise = true, grayscale = true)
        assertEquals("hqdn3d,format=gray,fps=30", FFmpegCommandBuilder.videoFilter(opts, 60.0))
    }

    @Test
    fun pass2CarriesFpsFilter() {
        val cmd = FFmpegCommandBuilder.pass2(
            "in.mp4", "out.mp4", Preset.COMPATIBILITY,
            1_000_000L, 128_000L, base, sourceFps = 60.0,
        )
        val vfIndex = cmd.indexOf("-vf")
        assertTrue(vfIndex >= 0)
        assertTrue(cmd[vfIndex + 1].contains("fps=30"))
    }

    @Test
    fun pass2OmitsVfWhenNothingToFilter() {
        val cmd = FFmpegCommandBuilder.pass2(
            "in.mp4", "out.mp4", Preset.COMPATIBILITY,
            1_000_000L, 128_000L, base, sourceFps = 24.0,
        )
        assertTrue("-vf" !in cmd)
    }
}
