package dev.nittala.voicegateway.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicedGateTest {
    private fun stats(voiced: Int, span: Int) = UtteranceStats(16, span, voiced, 16, 32.0)

    @Test
    fun atLeast800msVoiced() {
        assertFalse(VoicedGate.passes(stats(24, 24)))
        assertTrue(VoicedGate.passes(stats(25, 25)))
        assertEquals(768L, stats(24, 24).voicedMs)
    }

    @Test
    fun atLeastFortyPercentOfTheSpanVoicedInclusive() {
        assertTrue(VoicedGate.passes(stats(40, 100)))
        assertFalse(VoicedGate.passes(stats(40, 101)))
        assertTrue(VoicedGate.passes(stats(25, 62)))
        assertFalse(VoicedGate.passes(stats(25, 63)))
    }

    @Test
    fun propertyBothRulesForEverySmallPair() {
        for (span in 1..300) for (voiced in 0..span) {
            val expected = voiced >= 25 && voiced * 10 >= span * 4
            assertEquals("$voiced/$span", expected, VoicedGate.passes(stats(voiced, span)))
        }
    }

    @Test
    fun statsDurations() {
        val s = UtteranceStats(preRollFrames = 10, spanFrames = 50, voicedFrames = 30, tailFrames = 5, frameMs = 32.0)
        assertEquals(65, s.totalFrames)
        assertEquals(960L, s.voicedMs)
        assertEquals(1600L, s.spanMs)
    }
}
