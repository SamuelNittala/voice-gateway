package dev.nittala.voicegateway.audio

import android.media.ToneGenerator
import dev.nittala.voicegateway.core.loop.EarconKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The earcons' tones. The working cue is the softest and shortest: it repeats every 15 s while the agent works. */
class EarconsTest {
    @Test
    fun theWorkingCueIsAShortSoftBeep() {
        assertEquals(ToneGenerator.TONE_PROP_BEEP to 80, toneOf(EarconKind.Working))
        assertEquals(25, volumeOf(EarconKind.Working))
    }

    @Test
    fun theOtherEarconsAreUnchanged() {
        assertEquals(ToneGenerator.TONE_PROP_BEEP to 150, toneOf(EarconKind.Listen))
        assertEquals(ToneGenerator.TONE_PROP_ACK to 150, toneOf(EarconKind.Sent))
        for (kind in EarconKind.entries.filter { it != EarconKind.Working }) {
            assertEquals("$kind", 70, volumeOf(kind))
            assertTrue("$kind", toneOf(kind).second > toneOf(EarconKind.Working).second)
        }
    }
}
